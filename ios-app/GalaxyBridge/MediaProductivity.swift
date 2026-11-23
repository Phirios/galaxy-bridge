import ActivityKit
import AVFoundation
import Combine
import CoreImage
import CoreImage.CIFilterBuiltins
import EventKit
import Foundation
import MediaPlayer
import NaturalLanguage
import SwiftUI
import UIKit
import UserNotifications
import Vision
import WearLink
import WidgetKit
#if canImport(Translation)
import Translation
#endif

/// The iPhone side of the media, customisation and productivity features (Wave D): music and
/// podcasts pushed to the watch, seeking the Apple Music player, face scheduling, QR passes,
/// home shortcuts, world clock, translate, the two-way checklist, tile status, and the Home/Lock
/// Screen widget plus Live Activity. Everything from the watch is untrusted and checked here.
@MainActor
final class MediaProductivityHub: ObservableObject {
    // MARK: Types

    struct WatchTrack: Identifiable, Equatable { let id: String; let title: String; let artist: String; let bytes: Int64; let ms: Int64 }
    struct Slot: Identifiable, Codable, Equatable {
        var id = UUID()
        var hour: Int
        var minute: Int
        var pkg: String
        var name: String
    }
    struct FacePlan: Codable, Equatable {
        var on = false
        var slots: [Slot] = []
        var workoutPkg: String?
        var workoutName: String?
    }
    struct Pass: Identifiable, Equatable { let id: String; let title: String; let sub: String }
    struct Scene: Identifiable, Codable, Equatable {
        var id = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12).lowercased()
        var name: String
        /// Name of the Apple Shortcut to run.
        var shortcut: String
    }
    struct Clock: Identifiable, Codable, Equatable, Hashable {
        var id: String { tz }
        var tz: String
        var label: String
    }
    struct TileInfo: Identifiable, Equatable { var id: String { cls }; let cls: String; let label: String; let active: Bool? }

    enum Symbology: String, CaseIterable, Identifiable {
        case qr = "QR", aztec = "Aztec", pdf417 = "PDF417", code128 = "Code 128"
        var id: String { rawValue }
    }

    // MARK: State

    @Published var toast: String?
    @Published private(set) var tracks: [WatchTrack] = []
    @Published private(set) var watchFreeMB: Int?
    /// Name and 0…1 progress of the file being sent to the watch.
    @Published private(set) var pushing: (name: String, progress: Double)?
    @Published var plan: FacePlan = MediaProductivityHub.loadJSON("wd.facePlan") ?? FacePlan()
    @Published private(set) var passes: [Pass] = []
    @Published var scenes: [Scene] = MediaProductivityHub.loadJSON("wd.scenes") ?? [] { didSet { saveJSON(scenes, "wd.scenes") } }
    @Published var clocks: [Clock] = MediaProductivityHub.loadJSON("wd.clocks") ?? [] { didSet { saveJSON(clocks, "wd.clocks") } }
    @Published var langA = UserDefaults.standard.string(forKey: "wd.langA") ?? "en" { didSet { UserDefaults.standard.set(langA, forKey: "wd.langA") } }
    @Published var langB = UserDefaults.standard.string(forKey: "wd.langB") ?? "es" { didSet { UserDefaults.standard.set(langB, forKey: "wd.langB") } }
    @Published private(set) var lastTranslation: (source: String, target: String)?
    @Published var checklistId = UserDefaults.standard.string(forKey: "wd.checklist") ?? "" {
        didSet { UserDefaults.standard.set(checklistId, forKey: "wd.checklist"); Task { await sendChecklist() } }
    }
    @Published private(set) var reminderLists: [(id: String, title: String)] = []
    @Published private(set) var tiles: [TileInfo] = []
    @Published private(set) var tilesKnown = false
    @Published private(set) var liveActivityOn = false
    @Published private(set) var snapshot = WatchSnapshot.load()

    var send: ((GBMessage) -> Void)?
    var isConnected: () -> Bool = { false }

    private let events = EKEventStore()
    private var cancellables: Set<AnyCancellable> = []
    private var lastWidgetReload = Date.distantPast
    private var lastActivityUpdate = Date.distantPast
    private var checklistDebounce: Task<Void, Never>?

    // Music transfer
    private var pushData = Data()
    private var pushId = ""
    private var pushParts = 0
    private var pushSent = 0
    private var pushMeta: [String: Any] = [:]
    private static let partSize = 32 * 1024
    private static let window = 16

    static let messageTypes: Set<String> = [
        "musicAck", "musicLib", "faceScheduleState", "qrPasses", "homeScene", "translate",
        "checklistTick", "reminderAdd", "tileState", "mediaSeek",
    ]

    init() {
        NotificationCenter.default.addObserver(forName: .EKEventStoreChanged, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.checklistChanged() }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.runPendingScene() }
        }
        liveActivityOn = !Activity<WatchActivityAttributes>.activities.isEmpty
    }

    /// Hooks this hub into the link next to FeatureHub. Wraps the existing callbacks rather than
    /// replacing them, so the other features keep working; call after they are set.
    func install(link: WatchLink, health: HealthSync) {
        send = { [link] in link.send($0) }
        isConnected = { [link] in link.status == .connected }
        let previousFeature = link.onFeature
        link.onFeature = { [weak self] type, body in
            if Self.messageTypes.contains(type) { self?.handle(type, body) } else { previousFeature?(type, body) }
        }
        let previousConnected = link.onConnected
        link.onConnected = { [weak self] in previousConnected?(); self?.onConnected() }

        health.$summary.combineLatest(link.$watchBattery, link.$status, link.$watchStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] summary, battery, status, ws in
                self?.updateSnapshot(hr: summary.heartRate, steps: summary.steps, battery: battery,
                                     charging: ws?.charging ?? false, connected: status == .connected)
            }
            .store(in: &cancellables)
    }

    private func post(_ type: String, _ body: [String: Any] = [:]) {
        guard let m = GBFeature.message(type, body) else { return }
        send?(m)
    }

    func onConnected() {
        post("musicList")
        post("qrPassList")
        sendTools()
        sendScenes()
        Task { await sendChecklist() }
    }

    // MARK: Incoming

    func handle(_ type: String, _ b: [String: Any]) {
        switch type {
        case "musicAck":
            guard let id = b["id"] as? String, id == pushId, let part = (b["part"] as? NSNumber)?.intValue else { return }
            if part < 0 { endPush(); return }
            pumpMusic(acked: part)
        case "musicLib":
            tracks = (b["items"] as? [[String: Any]] ?? []).prefix(500).compactMap {
                guard let id = $0["id"] as? String, id.count <= 64 else { return nil }
                return WatchTrack(id: id, title: String(($0["title"] as? String ?? "Track").prefix(80)),
                                  artist: String(($0["artist"] as? String ?? "").prefix(60)),
                                  bytes: ($0["bytes"] as? NSNumber)?.int64Value ?? 0, ms: ($0["ms"] as? NSNumber)?.int64Value ?? 0)
            }
            watchFreeMB = (b["freeMB"] as? NSNumber)?.intValue
        case "faceScheduleState":
            var p = FacePlan(on: b["on"] as? Bool ?? false)
            p.slots = (b["slots"] as? [[String: Any]] ?? []).prefix(8).compactMap {
                guard let h = $0["h"] as? Int, let m = $0["m"] as? Int, (0...23).contains(h), (0...59).contains(m),
                      let pkg = $0["pkg"] as? String, pkg.count <= 200 else { return nil }
                return Slot(hour: h, minute: m, pkg: pkg, name: String(($0["name"] as? String ?? pkg).prefix(50)))
            }
            if let w = b["workout"] as? [String: Any], let pkg = w["pkg"] as? String, pkg.count <= 200 {
                p.workoutPkg = pkg; p.workoutName = String((w["name"] as? String ?? pkg).prefix(50))
            }
            plan = p
            saveJSON(p, "wd.facePlan")
        case "qrPasses":
            passes = (b["items"] as? [[String: Any]] ?? []).prefix(20).compactMap {
                guard let id = $0["id"] as? String, id.count <= 64 else { return nil }
                return Pass(id: id, title: String(($0["title"] as? String ?? "Pass").prefix(40)), sub: String(($0["sub"] as? String ?? "").prefix(60)))
            }
        case "homeScene":
            guard let id = b["id"] as? String, let scene = scenes.first(where: { $0.id == id }) else { return }
            runScene(scene)
        case "translate":
            guard let id = b["id"] as? String, id.count <= 64, let text = b["text"] as? String, !text.isEmpty, text.count <= 500 else { return }
            let from = (b["from"] as? String).flatMap(Self.cleanLang) ?? langA
            let to = (b["to"] as? String).flatMap(Self.cleanLang) ?? langB
            Task { await translate(id: id, text: text, from: from, to: to) }
        case "checklistTick":
            guard let id = b["id"] as? String, id.count <= 200, let done = b["done"] as? Bool else { return }
            tick(id, done: done)
        case "reminderAdd":
            guard let title = (b["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty else { return }
            addReminder(String(title.prefix(80)))
        case "tileState":
            tilesKnown = b["known"] as? Bool ?? false
            tiles = (b["items"] as? [[String: Any]] ?? []).prefix(30).compactMap {
                guard let cls = $0["cls"] as? String, cls.count <= 60 else { return nil }
                return TileInfo(cls: cls, label: String(($0["label"] as? String ?? cls).prefix(40)), active: $0["active"] as? Bool)
            }
        case "mediaSeek":
            guard let sec = (b["sec"] as? NSNumber)?.doubleValue, sec.isFinite, sec >= 0, sec < 24 * 3600 else { return }
            seekAppleMusic(to: sec)
        default: break
        }
    }

    // MARK: Music on the watch

    func requestMusicList() { post("musicList") }
    func deleteTrack(_ t: WatchTrack) { post("musicDelete", ["id": t.id]) }

    /// Reads an audio file the user picked in Files and streams it to the watch.
    func pushAudio(_ url: URL) async {
        guard isConnected() else { toast = "Connect your watch first"; return }
        guard pushing == nil else { toast = "Wait for the current file to finish"; return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { toast = "Couldn't read that file"; return }
        guard data.count > 0, data.count <= 80 * 1024 * 1024 else { toast = "Files up to 80 MB can go to the watch"; return }
        var title = url.deletingPathExtension().lastPathComponent
        var artist = ""
        let asset = AVURLAsset(url: url)
        if let items = try? await asset.load(.commonMetadata) {
            for item in items {
                if item.commonKey == .commonKeyTitle, let v = try? await item.load(.stringValue), !v.isEmpty { title = v }
                if item.commonKey == .commonKeyArtist, let v = try? await item.load(.stringValue) { artist = v }
            }
        }
        pushData = data
        pushId = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        pushParts = (data.count + Self.partSize - 1) / Self.partSize
        pushSent = 0
        pushMeta = ["name": String(url.lastPathComponent.prefix(80)), "title": String(title.prefix(80)),
                    "artist": String(artist.prefix(60)), "bytes": data.count]
        pushing = (String(title.prefix(40)), 0)
        pumpMusic(acked: -1)
    }

    /// Keeps up to [window] parts in flight beyond the last one the watch acknowledged.
    private func pumpMusic(acked: Int) {
        guard pushParts > 0 else { return }
        if acked >= pushParts - 1 { endPush(); return }
        pushing = (pushing?.name ?? "", Double(acked + 1) / Double(pushParts))
        while pushSent < pushParts, pushSent <= acked + Self.window {
            let i = pushSent
            let chunk = pushData.subdata(in: i * Self.partSize..<min(pushData.count, (i + 1) * Self.partSize))
            var body = pushMeta
            body["id"] = pushId; body["part"] = i; body["parts"] = pushParts; body["data"] = chunk.base64EncodedString()
            if i > 0 { body.removeValue(forKey: "artist") }
            post("musicPush", body)
            pushSent += 1
        }
    }

    private func endPush() {
        pushing = nil
        pushData = Data()
        pushParts = 0
        pushSent = 0
        pushId = ""
    }

    func cancelPush() { endPush(); toast = "Stopped sending" }

    // MARK: Apple Music seek and output

    @Published private(set) var musicAuthorized = MPMediaLibrary.authorizationStatus() == .authorized

    func requestMusicAccess() async {
        let status = await withCheckedContinuation { c in MPMediaLibrary.requestAuthorization { c.resume(returning: $0) } }
        musicAuthorized = status == .authorized
    }

    /// iOS only lets apps scrub the Apple Music player (the system music player); other apps'
    /// playback can't be seeked from outside.
    func seekAppleMusic(to sec: Double) {
        let player = MPMusicPlayerController.systemMusicPlayer
        guard player.playbackState == .playing || player.playbackState == .paused else {
            toast = "Seeking from the watch works for Apple Music only"
            return
        }
        player.currentPlaybackTime = sec
    }

    // MARK: Face schedule

    func saveFacePlan() {
        let slots = plan.slots.sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }
        var body: [String: Any] = ["on": plan.on, "slots": slots.map { ["h": $0.hour, "m": $0.minute, "pkg": $0.pkg, "name": $0.name] as [String: Any] }]
        if let pkg = plan.workoutPkg { body["workout"] = ["pkg": pkg, "name": plan.workoutName ?? pkg] }
        saveJSON(plan, "wd.facePlan")
        guard isConnected() else { toast = "Saved here; connect the watch to send it"; return }
        post("faceSchedule", body)
    }

    func requestFacePlan() { post("getFaceSchedule") }

    // MARK: QR passes

    func requestPasses() { post("qrPassList") }
    func deletePass(_ p: Pass) { post("qrPassDelete", ["id": p.id]) }

    /// Renders [code] as a crisp black-on-white barcode and sends it.
    func sendCode(_ code: String, symbology: Symbology, title: String, sub: String) {
        guard let png = Self.barcode(code, symbology)?.pngData() else { toast = "Couldn't make a code from that text"; return }
        sendPassImage(png, title: title, sub: sub)
    }

    /// For a screenshot or photo of a ticket: if it contains a barcode, re-draws it crisply;
    /// otherwise sends the picture itself, shrunk to fit.
    func sendPassPhoto(_ image: UIImage, title: String, sub: String) async {
        if let (payload, sym) = await Self.detectBarcode(in: image), let png = Self.barcode(payload, sym)?.pngData() {
            sendPassImage(png, title: title, sub: sub)
            toast = "Found a \(sym.rawValue) code and sent it"
            return
        }
        var side: CGFloat = 420
        var data: Data?
        while side >= 200 {
            data = Self.resized(image, side: side).jpegData(compressionQuality: 0.75)
            if let d = data, d.count <= 46_000 { break }
            side -= 60
        }
        guard let d = data, d.count <= 46_000 else { toast = "That picture is too detailed to send"; return }
        sendPassImage(d, title: title, sub: sub)
    }

    private func sendPassImage(_ data: Data, title: String, sub: String) {
        guard isConnected() else { toast = "Connect your watch first"; return }
        guard data.count <= 46_000 else { toast = "That code is too large for the watch"; return }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16).lowercased()
        post("qrPass", ["id": String(id), "title": String(title.prefix(40)), "sub": String(sub.prefix(60)), "img": data.base64EncodedString()])
    }

    static func barcode(_ text: String, _ sym: Symbology) -> UIImage? {
        guard let message = text.data(using: sym == .code128 ? .ascii : .utf8), !message.isEmpty, message.count <= 2000 else { return nil }
        let output: CIImage?
        switch sym {
        case .qr:
            let f = CIFilter.qrCodeGenerator(); f.message = message; f.correctionLevel = "M"; output = f.outputImage
        case .aztec:
            let f = CIFilter.aztecCodeGenerator(); f.message = message; output = f.outputImage
        case .pdf417:
            let f = CIFilter.pdf417BarcodeGenerator(); f.message = message; output = f.outputImage
        case .code128:
            let f = CIFilter.code128BarcodeGenerator(); f.message = message; f.quietSpace = 10; output = f.outputImage
        }
        guard let img = output, img.extent.width > 0 else { return nil }
        let scale = max(1, (360 / max(img.extent.width, img.extent.height)).rounded(.down))
        let scaled = img.samplingNearest().transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    private static func detectBarcode(in image: UIImage) async -> (String, Symbology)? {
        guard let cg = image.cgImage else { return nil }
        return await Task.detached {
            let request = VNDetectBarcodesRequest()
            try? VNImageRequestHandler(cgImage: cg).perform([request])
            for o in request.results ?? [] {
                guard let payload = o.payloadStringValue, !payload.isEmpty else { continue }
                switch o.symbology {
                case .qr: return (payload, .qr)
                case .aztec: return (payload, .aztec)
                case .pdf417: return (payload, .pdf417)
                case .code128: return (payload, .code128)
                default: continue
                }
            }
            return nil
        }.value
    }

    private static func resized(_ image: UIImage, side: CGFloat) -> UIImage {
        let scale = min(1, side / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    // MARK: Home shortcuts

    func sendScenes() {
        post("homeScenes", ["items": scenes.prefix(8).map { ["id": $0.id, "name": String($0.name.prefix(30))] }])
    }

    private var pendingScene: (scene: Scene, at: Date)?

    /// Runs the scene's Apple Shortcut. iOS lets an app open Shortcuts only while it's on screen
    /// (and HomeKit only works in the foreground too), so when the iPhone is locked or the app is
    /// in the background this posts a notification; tapping it opens the app, which runs it.
    func runScene(_ scene: Scene) {
        guard let url = Self.shortcutURL(scene.shortcut) else { return }
        if UIApplication.shared.applicationState == .active {
            UIApplication.shared.open(url)
            post("homeSceneResult", ["detail": "Running \(scene.name) on iPhone"])
            return
        }
        pendingScene = (scene, Date())
        let content = UNMutableNotificationContent()
        content.title = "Run \(scene.name)?"
        content.body = "Tap to run the “\(scene.shortcut)” shortcut you asked for from your watch."
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "homeScene", content: content, trigger: nil))
        post("homeSceneResult", ["detail": "Tap the notification on your iPhone"])
    }

    private func runPendingScene() {
        guard let p = pendingScene, Date().timeIntervalSince(p.at) < 120 else { pendingScene = nil; return }
        pendingScene = nil
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["homeScene"])
        if let url = Self.shortcutURL(p.scene.shortcut) { UIApplication.shared.open(url) }
    }

    static func shortcutURL(_ name: String) -> URL? {
        var c = URLComponents(string: "shortcuts://run-shortcut")
        c?.queryItems = [URLQueryItem(name: "name", value: name)]
        return name.isEmpty ? nil : c?.url
    }

    func requestNotificationAccess() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }

    // MARK: World clock and translate languages

    func sendTools() {
        post("toolsConfig", [
            "clocks": clocks.prefix(6).map { ["tz": $0.tz, "label": String($0.label.prefix(20))] },
            "langA": langA, "langAName": Self.languageName(langA),
            "langB": langB, "langBName": Self.languageName(langB),
        ])
    }

    static func languageName(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    static func cleanLang(_ s: String) -> String? {
        s.range(of: "^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$", options: .regularExpression) != nil ? s : nil
    }

    static let languageChoices = ["en", "es", "fr", "de", "it", "pt", "nl", "ja", "ko", "zh-Hans", "zh-Hant", "ar", "hi", "ru", "tr", "pl", "uk", "th", "vi", "id"]

    /// Translates on the iPhone with Apple's Translation framework and answers the watch.
    /// The headless session (no view on screen) needs iOS 26 and the two languages downloaded.
    private func translate(id: String, text: String, from: String, to: String) async {
        func reply(_ ok: Bool, _ s: String) {
            post("translateResult", ["id": id, "ok": ok, (ok ? "text" : "detail"): String(s.prefix(1000))])
        }
        #if canImport(Translation)
        if #available(iOS 26.0, *) {
            // Use the pair's other language if the speech was actually in the target language.
            var source = from
            let recognizer = NLLanguageRecognizer()
            recognizer.languageConstraints = [NLLanguage(rawValue: from), NLLanguage(rawValue: to)]
            recognizer.processString(text)
            if let lang = recognizer.dominantLanguage?.rawValue, lang == to, text.count > 12 { source = to }
            let target = source == from ? to : from
            do {
                let session = TranslationSession(installedSource: Locale.Language(identifier: source), target: Locale.Language(identifier: target))
                let response = try await session.translate(text)
                lastTranslation = (text, response.targetText)
                reply(true, response.targetText)
            } catch {
                reply(false, "Download \(Self.languageName(source)) and \(Self.languageName(target)) in the iPhone app: Watch → Translate")
            }
            return
        }
        #endif
        reply(false, "Watch translate needs iOS 26 on the iPhone")
    }

    // MARK: Checklist (two-way Reminders list)

    func loadReminderLists() {
        guard EKEventStore.authorizationStatus(for: .reminder) == .fullAccess else { reminderLists = []; return }
        reminderLists = events.calendars(for: .reminder).map { ($0.calendarIdentifier, $0.title) }
    }

    func requestRemindersAccess() async {
        _ = try? await events.requestFullAccessToReminders()
        loadReminderLists()
    }

    private func checklistChanged() {
        events.refreshSourcesIfNecessary()
        checklistDebounce?.cancel()
        checklistDebounce = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled else { return }
            await self?.sendChecklist()
        }
    }

    func sendChecklist() async {
        guard !checklistId.isEmpty, EKEventStore.authorizationStatus(for: .reminder) == .fullAccess,
              let cal = events.calendar(withIdentifier: checklistId) else { return }
        let predicate = events.predicateForReminders(in: [cal])
        let recent = Date().addingTimeInterval(-24 * 3600)
        let items: [[String: Any]] = await withCheckedContinuation { done in
            events.fetchReminders(matching: predicate) { reminders in
                let kept = (reminders ?? []).filter { !$0.isCompleted || ($0.completionDate ?? .distantPast) > recent }
                    .sorted { ($0.creationDate ?? .distantPast) < ($1.creationDate ?? .distantPast) }
                done.resume(returning: kept.prefix(40).map {
                    ["id": $0.calendarItemIdentifier, "title": String(($0.title ?? "").prefix(80)), "done": $0.isCompleted] as [String: Any]
                })
            }
        }
        post("checklist", ["list": String(cal.title.prefix(30)), "items": items])
    }

    private func tick(_ id: String, done: Bool) {
        guard EKEventStore.authorizationStatus(for: .reminder) == .fullAccess,
              let r = events.calendarItem(withIdentifier: id) as? EKReminder else { return }
        r.isCompleted = done
        try? events.save(r, commit: true)
    }

    private func addReminder(_ title: String) {
        guard EKEventStore.authorizationStatus(for: .reminder) == .fullAccess else { return }
        let r = EKReminder(eventStore: events)
        r.title = title
        r.calendar = events.calendar(withIdentifier: checklistId) ?? events.defaultCalendarForNewReminders()
        guard r.calendar != nil else { return }
        try? events.save(r, commit: true)
    }

    // MARK: Tiles

    func requestTiles() { post("getTileState") }

    // MARK: Widget and Live Activity

    private func updateSnapshot(hr: Double?, steps: Double, battery: Int?, charging: Bool, connected: Bool) {
        var s = snapshot
        s.heartRate = hr.map { Int($0.rounded()) }
        s.steps = Int(steps)
        s.battery = battery ?? s.battery
        s.charging = charging
        s.connected = connected
        guard s != snapshot else { return }
        s.updated = Date()
        snapshot = s
        s.save()
        // WidgetKit budgets reloads; a few a minute at most is plenty for a glanceable widget.
        if Date().timeIntervalSince(lastWidgetReload) > 60 {
            lastWidgetReload = Date()
            WidgetCenter.shared.reloadAllTimelines()
        }
        if liveActivityOn, Date().timeIntervalSince(lastActivityUpdate) > 15 {
            lastActivityUpdate = Date()
            let state = activityState()
            Task { for a in Activity<WatchActivityAttributes>.activities { await a.update(ActivityContent(state: state, staleDate: nil)) } }
        }
    }

    private func activityState() -> WatchActivityAttributes.ContentState {
        .init(heartRate: snapshot.heartRate, steps: snapshot.steps, battery: snapshot.battery,
              connected: snapshot.connected, updated: snapshot.updated)
    }

    var activitiesAllowed: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }

    func startLiveActivity() {
        guard activitiesAllowed else { toast = "Turn on Live Activities for Wear OS Bridge in Settings"; return }
        do {
            _ = try Activity.request(attributes: WatchActivityAttributes(watchName: "Galaxy Watch"),
                                     content: ActivityContent(state: activityState(), staleDate: nil))
            liveActivityOn = true
        } catch {
            toast = "Couldn't start the Live Activity"
        }
    }

    func stopLiveActivity() {
        let state = activityState()
        Task {
            for a in Activity<WatchActivityAttributes>.activities {
                await a.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: .immediate)
            }
        }
        liveActivityOn = false
    }

    // MARK: Persistence

    private static func loadJSON<T: Decodable>(_ key: String) -> T? {
        UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }

    private func saveJSON<T: Encodable>(_ value: T, _ key: String) {
        UserDefaults.standard.set(try? JSONEncoder().encode(value), forKey: key)
    }
}
