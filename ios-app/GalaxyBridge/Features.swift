import Charts
import Contacts
import EventKit
import Foundation
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import WearLink

/// The iPhone side of the watch features added on top of the core link: contacts, notification
/// filters, reminders, alarms and timers, apps, sounds, photos, recordings, workouts and the
/// camera preview. Everything the watch sends is untrusted and checked before use.
/// One ECG recording from the watch: a microvolt series at a known sample rate. Display only.
struct EcgStrip {
    let hz: Int
    let microvolts: [Int16]
    let at: Date
    /// Millivolts for plotting.
    var millivolts: [Double] { microvolts.map { Double($0) / 1000.0 } }
    var durationSec: Double { hz > 0 ? Double(microvolts.count) / Double(hz) : 0 }
}

/// A body-composition (BIA) reading. Any field may be absent depending on the watch.
struct BodyComposition {
    let fatPct: Double?      // body fat, %
    let leanKg: Double?      // fat-free mass, kg
    let waterPct: Double?    // total body water, %
    let muscleKg: Double?    // skeletal muscle mass, kg
    let bmr: Double?         // basal metabolic rate, kcal/day
    let at: Date
}

@MainActor
final class FeatureHub: ObservableObject {
    struct NotifApp: Identifiable, Equatable { let id: String; var name: String; var muted: Bool }
    struct WatchApp: Identifiable, Equatable { let id: String; var name: String; var version: String; var system: Bool; var mb: Double }
    struct Sound: Identifiable, Hashable { var id: String { uri }; let title: String; let uri: String }
    struct Recording: Identifiable, Equatable { let id: Int64; let name: String; let ms: Int64; let bytes: Int64; let at: Date }

    @Published var toast: String?
    /// Preview and name of the face currently on the watch.
    @Published private(set) var faceImage: UIImage? = FeatureHub.loadFace()
    @Published private(set) var faceName: String? = UserDefaults.standard.string(forKey: "faceName")
    @Published private(set) var faces: [WatchFace] = []
    @Published private(set) var facePreviews: [String: UIImage] = [:]
    /// The design on the Wear OS Bridge face, and whether that face is the active one.
    @Published private(set) var design: FaceDesign? = (UserDefaults.standard.data(forKey: "design")).flatMap { try? JSONDecoder().decode(FaceDesign.self, from: $0) }
    @Published private(set) var designActive = UserDefaults.standard.bool(forKey: "designActive")

    /// Samsung-only body sensors (SpO₂, skin temperature), reachable once Samsung Health's
    /// developer mode is on. See SensorBridge.kt on the watch.
    @Published private(set) var sensorsAvailable = false
    @Published private(set) var measuringSensors = false
    @Published private(set) var measuringEcg = false
    @Published private(set) var measuringBia = false
    /// Latest ECG strip (display only — HealthKit bars third-party ECG writes).
    @Published private(set) var ecg: EcgStrip?
    /// Latest body-composition reading; writable parts go to Apple Health.
    @Published private(set) var bodyComposition: BodyComposition?
    struct WatchFace: Identifiable, Equatable { let id: String; let name: String; let active: Bool }
    @Published private(set) var notifApps: [NotifApp] = []
    @Published private(set) var categories: [Int: Bool] = [:]
    @Published private(set) var apps: [WatchApp] = []
    @Published private(set) var loadingApps = false
    @Published private(set) var ringtones: [Sound] = []
    @Published private(set) var notifSounds: [Sound] = []
    @Published private(set) var ringNow = ""
    @Published private(set) var notifNow = ""
    @Published private(set) var recordings: [Recording] = []
    @Published private(set) var downloading: Int64?
    @Published private(set) var downloadProgress = 0.0
    @Published private(set) var contactsBusy = false
    @Published private(set) var photoBusy = false
    /// An APK is being streamed to the watch for a push-install.
    @Published private(set) var installing = false
    @Published var shareReminders = UserDefaults.standard.object(forKey: "shareReminders") as? Bool ?? true {
        didSet { UserDefaults.standard.set(shareReminders, forKey: "shareReminders"); Task { await sendReminders() } }
    }
    @Published private(set) var remindersAllowed = EKEventStore.authorizationStatus(for: .reminder) == .fullAccess

    var send: ((GBMessage) -> Void)?
    var isConnected: () -> Bool = { false }
    weak var health: HealthSync?
    weak var camera: Camera?

    private let events = EKEventStore()
    private var recordingBuffer = Data()
    private var recordingParts = 0

    static let categoryNames: [(Int, String)] = [
        (0, "Other"), (2, "Missed calls"), (3, "Voicemail"), (4, "Social & messages"), (5, "Calendar"),
        (6, "Email"), (7, "News"), (8, "Health & fitness"), (9, "Business & finance"), (10, "Location"),
        (11, "Entertainment"),
    ]

    init() {
        NotificationCenter.default.addObserver(forName: .EKEventStoreChanged, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { Task { await self?.sendReminders() } }
        }
    }

    private func post(_ type: String, _ body: [String: Any] = [:]) {
        guard let m = GBFeature.message(type, body) else { return }
        send?(m)
    }

    /// Called on every (re)connection.
    func onConnected() {
        Task { await sendReminders() }
        post("getNotifApps")
        post("getFace")
        CallsSafetyStore.shared.onConnected(self)
        HealthDepthStore.shared.onConnected(self)
    }

    // MARK: Incoming

    func handle(_ type: String, _ b: [String: Any]) {
        switch type {
        case "notifApps":
            notifApps = (b["items"] as? [[String: Any]] ?? []).prefix(300).compactMap {
                guard let id = $0["id"] as? String, id.count <= 200 else { return nil }
                return NotifApp(id: id, name: String(($0["name"] as? String ?? id).prefix(40)), muted: $0["muted"] as? Bool ?? false)
            }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            var cats: [Int: Bool] = [:]
            for (k, v) in b["cats"] as? [String: Any] ?? [:] { if let i = Int(k), (0...11).contains(i) { cats[i] = v as? Bool ?? true } }
            categories = cats
        case "apps":
            loadingApps = false
            apps = (b["items"] as? [[String: Any]] ?? []).prefix(500).compactMap {
                guard let pkg = $0["pkg"] as? String, pkg.count <= 200 else { return nil }
                return WatchApp(id: pkg, name: String(($0["name"] as? String ?? pkg).prefix(40)),
                                version: String(($0["ver"] as? String ?? "").prefix(24)),
                                system: $0["system"] as? Bool ?? false, mb: ($0["mb"] as? NSNumber)?.doubleValue ?? -1)
            }
        case "sounds":
            func list(_ key: String) -> [Sound] {
                (b[key] as? [[String: Any]] ?? []).prefix(80).compactMap {
                    guard let t = $0["title"] as? String, let u = $0["uri"] as? String, u.count <= 300 else { return nil }
                    return Sound(title: String(t.prefix(40)), uri: u)
                }
            }
            ringtones = list("ring"); notifSounds = list("notif")
            ringNow = b["ringNow"] as? String ?? ""; notifNow = b["notifNow"] as? String ?? ""
        case "recordings":
            recordings = (b["items"] as? [[String: Any]] ?? []).prefix(100).compactMap {
                guard let id = ($0["id"] as? NSNumber)?.int64Value, let name = $0["name"] as? String else { return nil }
                return Recording(id: id, name: String(name.prefix(60)), ms: ($0["ms"] as? NSNumber)?.int64Value ?? 0,
                                 bytes: ($0["bytes"] as? NSNumber)?.int64Value ?? 0,
                                 at: Date(timeIntervalSince1970: (($0["at"] as? NSNumber)?.doubleValue ?? 0) / 1000))
            }
        case "recPart": receiveRecordingPart(b)
        case "workout":
            Task {
                guard let health else { return }
                if await health.saveWorkout(b), let id = b["id"] as? String {
                    post("workoutAck", ["id": id])
                    toast = "Workout saved to Apple Health"
                }
            }
        case "reminderDone":
            if let id = b["id"] as? String { completeReminder(id) }
        case "camView":
            camera?.streamToWatch = b["on"] as? Bool ?? false
            camera?.updateStreaming()
            sendCameraState()
        case "camCmd":
            switch b["cmd"] as? String {
            case "shoot": camera?.capture()
            case "flip": camera?.flip()
            case "timer": camera?.cycleTimer()
            default: break
            }
            sendCameraState()
        case "face":
            let name = (b["name"] as? String).map { String($0.prefix(60)) }
            faceName = name
            UserDefaults.standard.set(name, forKey: "faceName")
            if let data = (b["png"] as? String).flatMap({ Data(base64Encoded: $0) }), data.count < 60_000,
               let img = UIImage(data: data), img.size.width <= 600 {
                faceImage = img
                try? data.write(to: Self.faceURL)
            }
        case "faces":
            faces = (b["items"] as? [[String: Any]] ?? []).prefix(200).compactMap {
                guard let id = $0["pkg"] as? String, id.count <= 200 else { return nil }
                return WatchFace(id: id, name: String(($0["name"] as? String ?? id).prefix(50)), active: $0["active"] as? Bool ?? false)
            }
        case "facePreview":
            if let pkg = b["pkg"] as? String, pkg.count <= 200, facePreviews.count < 300,
               let data = (b["img"] as? String).flatMap({ Data(base64Encoded: $0) }), data.count < 30_000,
               let img = UIImage(data: data) { facePreviews[pkg] = img }
        case "design":
            designActive = b["active"] as? Bool ?? false
            UserDefaults.standard.set(designActive, forKey: "designActive")
            if let d = (b["design"] as? [String: Any]).flatMap(FaceDesign.init(json:)) {
                design = d
                UserDefaults.standard.set(try? JSONEncoder().encode(d), forKey: "design")
            }
        case "install":
            installing = false
            let status = b["status"] as? String ?? ""
            let detail = String((b["detail"] as? String ?? "").prefix(120))
            toast = detail.isEmpty ? (status == "ok" ? "Installed on watch" : "Install failed") : detail
        case "sensors":
            sensorsAvailable = b["available"] as? Bool ?? false
        case "ecg":
            measuringEcg = false
            guard let b64 = b["uv"] as? String, let data = Data(base64Encoded: b64),
                  data.count <= 64_000, !data.isEmpty else { break }
            let hz = (b["hz"] as? NSNumber)?.intValue ?? 250
            let uv: [Int16] = data.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
            ecg = EcgStrip(hz: max(1, hz), microvolts: uv, at: Date())
        case "bia":
            measuringBia = false
            func num(_ k: String) -> Double? { (b[k] as? NSNumber)?.doubleValue }
            let bc = BodyComposition(fatPct: num("fatPct"), leanKg: num("leanKg"),
                                     waterPct: num("waterPct"), muscleKg: num("muscleKg"),
                                     bmr: num("bmr"), at: Date())
            bodyComposition = bc
            Task { await health?.saveBodyComposition(bc) }
        case "result":
            let what = b["what"] as? String ?? ""
            let ok = b["ok"] as? Bool ?? false
            let detail = String((b["detail"] as? String ?? "").prefix(120))
            if what == "contacts" { contactsBusy = false }
            if what == "photo" { photoBusy = false }
            if what == "sensors" { measuringSensors = false }
            if what == "ecg" { measuringEcg = false }
            if what == "bia" { measuringBia = false }
            toast = detail.isEmpty ? (ok ? "Done on watch" : "The watch couldn't do that") : detail
        case "callLog", "safetyState", "sosAlert", "hrAlert", "findLoud", "quickReply":
            CallsSafetyStore.shared.handle(type, b, hub: self)
        case "hrvResult", "hdState":
            HealthDepthStore.shared.handle(type, b)
        default: break
        }
    }

    private static var faceURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("face.png")
    }

    private static func loadFace() -> UIImage? {
        try? FileManager.default.createDirectory(at: faceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        return (try? Data(contentsOf: faceURL)).flatMap(UIImage.init(data:))
    }

    func requestSensors() { post("getSensors") }
    func openSensorSettings() { post("openSensorSettings") }
    func measureSensors(_ kinds: [String]) {
        measuringSensors = true
        post("measureSensors", ["sensors": kinds])
    }
    func measureEcg() { measuringEcg = true; post("measureEcg") }
    func measureBia() { measuringBia = true; post("measureBia") }

    func requestFaces() { post("getFaces") }
    func requestFace() { post("getFace") }
    func openFacePicker() { post("openFacePicker") }
    func requestDesign() { post("getDesign") }
    func setDesign(_ d: FaceDesign) {
        design = d
        post("setDesign", d.json)
    }

    /// Applies any installed face from the phone. The watch switches classic faces directly (via
    /// SysUI's EDIT_WATCH_FACE) and deep-links to the picker for WFF faces (one tap). The phone
    /// doesn't know a third-party face's service class, so it sends an empty `cls`.
    func applyFace(_ f: WatchFace) {
        guard isConnected() else { toast = "Connect your watch first"; return }
        post("applyFace", ["pkg": f.id, "cls": ""])
    }

    // MARK: Install / body profile / calls

    /// Streams an APK chosen on the phone to the watch for a one-tap-confirm install.
    func installApk(_ url: URL) {
        guard isConnected() else { toast = "Connect your watch first"; return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { toast = "Couldn't read that file"; return }
        guard data.count <= 200 * 1024 * 1024 else { toast = "That APK is too large"; return }
        installing = true
        let id = UUID().uuidString
        let name = url.deletingPathExtension().lastPathComponent
        let part = 32 * 1024
        let parts = max(1, (data.count + part - 1) / part)
        for i in 0..<parts {
            let chunk = data.subdata(in: i * part..<min(data.count, (i + 1) * part))
            post("install", ["id": id, "part": i, "parts": parts, "name": String(name.prefix(60)), "data": chunk.base64EncodedString()])
        }
        toast = "Sending \(data.count / 1024) KB to your watch…"
    }

    /// Sends the BIA body profile (height cm, weight kg, gender 1=male/0=female, age) to the watch.
    func setBiaProfile(heightCm: Double, weightKg: Double, gender: Int, age: Int) {
        post("setBiaProfile", ["height": heightCm, "weight": weightKg, "gender": gender, "age": age])
    }

    /// Reads the profile from Apple Health and, if complete, sends it; returns what was read so the
    /// UI can prefill any missing field for manual entry.
    func sendBiaProfileFromHealth() async -> HealthSync.BiaProfileInput? {
        guard let health else { return nil }
        let p = await health.readBiaProfile()
        if let h = p.heightCm, let w = p.weightKg, let g = p.gender, let a = p.age {
            setBiaProfile(heightCm: h, weightKg: w, gender: g, age: a)
            toast = "Body profile sent to your watch"
        }
        return p
    }

    /// Call control over HFP (place/answer/reject/hang up). Needs a classic-BT bond; see CallAudio.
    func call(_ action: String, number: String = "") {
        guard isConnected() else { toast = "Connect your watch first"; return }
        post("call", ["action": action, "number": number])
    }

    // MARK: Contacts

    func syncContacts() async {
        let store = CNContactStore()
        if CNContactStore.authorizationStatus(for: .contacts) != .authorized {
            guard (try? await store.requestAccess(for: .contacts)) == true else {
                toast = "Allow Contacts for Wear OS Bridge in iPhone Settings"
                return
            }
        }
        contactsBusy = true
        let keys: [CNKeyDescriptor] = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                                       CNContactPhoneNumbersKey as CNKeyDescriptor, CNContactEmailAddressesKey as CNKeyDescriptor,
                                       CNContactOrganizationNameKey as CNKeyDescriptor]
        let items: [[String: Any]] = await Task.detached {
            var out: [[String: Any]] = []
            try? store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { c, _ in
                let name = CNContactFormatter.string(from: c, style: .fullName) ?? c.organizationName
                let phones = c.phoneNumbers.map(\.value.stringValue).prefix(8)
                let emails = c.emailAddresses.map { String($0.value) }.prefix(4)
                guard !name.isEmpty, !(phones.isEmpty && emails.isEmpty) else { return }
                out.append(["id": c.identifier, "n": String(name.prefix(80)), "p": Array(phones), "e": Array(emails)])
            }
            return out
        }.value
        let pageSize = 150
        let pages = max(1, (items.count + pageSize - 1) / pageSize)
        for p in 0..<pages {
            let slice = Array(items[min(items.count, p * pageSize)..<min(items.count, (p + 1) * pageSize)])
            post("contacts", ["page": p, "pages": pages, "items": slice])
        }
        toast = "Sending \(items.count) contacts…"
    }

    // MARK: Reminders

    func requestRemindersAccess() async {
        remindersAllowed = (try? await events.requestFullAccessToReminders()) ?? false
        await sendReminders()
    }

    func sendReminders() async {
        remindersAllowed = EKEventStore.authorizationStatus(for: .reminder) == .fullAccess
        guard shareReminders, remindersAllowed else { return }
        let predicate = events.predicateForIncompleteReminders(withDueDateStarting: nil, ending: nil, calendars: nil)
        let found: [[String: Any]] = await withCheckedContinuation { done in
            events.fetchReminders(matching: predicate) { reminders in
                let sorted = (reminders ?? []).sorted {
                    ($0.dueDateComponents?.date ?? .distantFuture) < ($1.dueDateComponents?.date ?? .distantFuture)
                }
                done.resume(returning: sorted.prefix(20).map {
                    ["id": $0.calendarItemIdentifier, "title": String(($0.title ?? "").prefix(80)),
                     "due": Int64(($0.dueDateComponents?.date?.timeIntervalSince1970 ?? 0) * 1000),
                     "list": String(($0.calendar?.title ?? "").prefix(30))]
                })
            }
        }
        post("reminders", ["items": found])
    }

    private func completeReminder(_ id: String) {
        guard remindersAllowed, let r = events.calendarItem(withIdentifier: id) as? EKReminder else { return }
        r.isCompleted = true
        try? events.save(r, commit: true)
    }

    // MARK: Alarms, timers

    func setAlarm(_ date: Date, label: String) {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        post("alarm", ["h": c.hour ?? 7, "m": c.minute ?? 0, "label": String(label.prefix(40))])
    }

    func setTimer(seconds: Int, label: String) {
        post("timer", ["secs": max(1, min(seconds, 86_400)), "label": String(label.prefix(40))])
    }

    // MARK: Apps, store, sounds

    func requestApps() { loadingApps = true; post("getApps") }
    func uninstall(_ pkg: String) { post("uninstall", ["pkg": pkg]) }
    func launch(_ pkg: String) { post("launch", ["pkg": pkg]) }
    func openStore(_ pkg: String) {
        guard isConnected() else { toast = "Connect your watch first"; return }
        post("openStore", ["pkg": pkg])
    }
    func requestSounds() { post("getSounds") }
    func setSound(_ kind: String, _ uri: String) { post("setSound", ["kind": kind, "uri": uri]) }

    // MARK: Notifications

    func requestNotifApps() { post("getNotifApps") }

    func setMuted(_ id: String, _ muted: Bool) {
        if let i = notifApps.firstIndex(where: { $0.id == id }) { notifApps[i].muted = muted }
        post("mute", ["apps": [id: muted]])
    }

    func setCategory(_ i: Int, _ on: Bool) {
        categories[i] = on
        post("mute", ["cats": [String(i): on]])
    }

    // MARK: Photos

    /// Shrinks the photo to the watch's screen size and sends it in parts.
    func sendPhoto(_ image: UIImage) {
        guard isConnected() else { toast = "Connect your watch first"; return }
        let side: CGFloat = 900
        let scale = min(1, side / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let resized = UIGraphicsImageRenderer(size: size, format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            .image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = resized.jpegData(compressionQuality: 0.7) else { return }
        photoBusy = true
        let id = UUID().uuidString
        let part = 32 * 1024
        let parts = (jpeg.count + part - 1) / part
        for i in 0..<parts {
            let chunk = jpeg.subdata(in: i * part..<min(jpeg.count, (i + 1) * part))
            post("photo", ["id": id, "part": i, "parts": parts, "name": "iPhone", "data": chunk.base64EncodedString()])
        }
        toast = "Sending photo (\(jpeg.count / 1024) KB)…"
    }

    // MARK: Recordings

    func requestRecordings() { post("getRecordings") }

    func download(_ r: Recording) {
        guard downloading == nil else { return }
        downloading = r.id
        downloadProgress = 0
        recordingBuffer = Data()
        recordingParts = 0
        post("getRecording", ["id": r.id])
    }

    private func receiveRecordingPart(_ b: [String: Any]) {
        guard let id = (b["id"] as? NSNumber)?.int64Value, id == downloading,
              let part = b["part"] as? Int, let parts = b["parts"] as? Int, (1...2000).contains(parts), part == recordingParts,
              let data = (b["data"] as? String).flatMap({ Data(base64Encoded: $0) }) else { return }
        recordingBuffer.append(data)
        recordingParts += 1
        downloadProgress = Double(recordingParts) / Double(parts)
        guard recordingParts == parts else { return }
        let name = recordings.first { $0.id == id }?.name ?? "Recording-\(id).m4a"
        let safe = name.filter { $0.isLetter || $0.isNumber || "-_. ".contains($0) }
        do {
            let dir = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                .appendingPathComponent("Watch recordings", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try recordingBuffer.write(to: dir.appendingPathComponent(safe.isEmpty ? "Recording-\(id).m4a" : safe), options: .completeFileProtection)
            toast = "Saved to Files → On My iPhone → Wear OS Bridge"
        } catch {
            toast = "Couldn't save the recording"
        }
        downloading = nil
        recordingBuffer = Data()
    }

    // MARK: Camera

    func sendCameraState() {
        guard let camera else { return }
        post("camState", ["open": camera.ready, "front": camera.position == .front, "timer": camera.timerSeconds])
    }

    func sendFrame(_ jpeg: Data) {
        guard jpeg.count < 40_000 else { return }
        post("camFrame", ["data": jpeg.base64EncodedString()])
    }
}

// MARK: - Screens

/// Every companion feature on one compact panel: pick a category, then a 4-across icon grid.
/// Keeps the Watch tab to roughly one screen instead of a long run of sections.
struct FeatureLauncher: View {
    @AppStorage("launcher.category") private var category = 0

    private struct Item: Identifiable {
        let title: String, symbol: String, color: Color
        let dest: AnyView
        var id: String { title }
    }

    private static let names = ["Customise", "Connect", "Media", "Tools"]

    private var items: [Item] {
        switch category {
        case 0: return [
            Item(title: "Gallery", symbol: "sparkles", color: .pink, dest: AnyView(FaceGalleryView())),
            Item(title: "Faces", symbol: "paintpalette.fill", color: .orange, dest: AnyView(WatchFacesView())),
            Item(title: "Schedule", symbol: "clock.arrow.2.circlepath", color: .purple, dest: AnyView(FaceScheduleView())),
            Item(title: "Apps", symbol: "square.grid.2x2.fill", color: .blue, dest: AnyView(AppsView())),
            Item(title: "Tiles", symbol: "rectangle.stack.fill", color: .teal, dest: AnyView(TilesInfoView())),
            Item(title: "Tile manager", symbol: "square.stack.3d.up.fill", color: .mint, dest: AnyView(TileManagerView())),
            Item(title: "Widget", symbol: "rectangle.3.group.fill", color: .cyan, dest: AnyView(WidgetsView())),
        ]
        case 1: return [
            Item(title: "Calls", symbol: "phone.fill", color: .green, dest: AnyView(CallsView())),
            Item(title: "Favourites", symbol: "star.fill", color: .yellow, dest: AnyView(CallsFavouritesView())),
            Item(title: "Contacts", symbol: "person.crop.circle.fill", color: .gray, dest: AnyView(ContactsView())),
            Item(title: "Safety", symbol: "sos", color: .red, dest: AnyView(SafetyView())),
            Item(title: "Reminders", symbol: "checklist", color: .orange, dest: AnyView(RemindersView())),
            Item(title: "Alarms", symbol: "alarm.fill", color: .indigo, dest: AnyView(AlarmsView())),
        ]
        case 2: return [
            Item(title: "Music", symbol: "music.note.list", color: .purple, dest: AnyView(WatchMusicView())),
            Item(title: "iPhone media", symbol: "airplayaudio", color: .red, dest: AnyView(IPhoneMediaView())),
            Item(title: "Photos", symbol: "photo.fill", color: .pink, dest: AnyView(PhotosToWatchView())),
            Item(title: "Recordings", symbol: "waveform", color: .cyan, dest: AnyView(RecordingsView())),
            Item(title: "Clicker", symbol: "rectangle.on.rectangle.angled", color: .orange, dest: AnyView(ClickerInfoView())),
        ]
        default: return [
            Item(title: "Translate", symbol: "character.bubble.fill", color: .blue, dest: AnyView(TranslateView())),
            Item(title: "World clock", symbol: "globe", color: .teal, dest: AnyView(WorldClockView())),
            Item(title: "Checklist", symbol: "checklist.checked", color: .green, dest: AnyView(ChecklistView())),
            Item(title: "Passes", symbol: "qrcode", color: .indigo, dest: AnyView(PassesView())),
            Item(title: "Home", symbol: "house.fill", color: .orange, dest: AnyView(HomeShortcutsView())),
        ]
        }
    }

    var body: some View {
        VStack(spacing: 14) {
            Picker("Category", selection: $category) {
                ForEach(Self.names.indices, id: \.self) { Text(Self.names[$0]).tag($0) }
            }
            .pickerStyle(.segmented)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 14) {
                ForEach(items) { item in
                    NavigationLink { item.dest } label: { icon(item) }
                        .buttonStyle(.plain)
                }
            }
            .animation(.snappy, value: category)
        }
        .padding(14)
        .insetCard()
    }

    private func icon(_ i: Item) -> some View {
        VStack(spacing: 6) {
            Image(systemName: i.symbol)
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 48, height: 48)
                .background(i.color, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityHidden(true)
            Text(i.title)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Blood oxygen and skin temperature from the watch's Samsung-only sensors, written to Apple
/// Health. These need Samsung Health's developer mode on (one-time, on the watch); the watch
/// then streams the sensors through Samsung's own tracking service.
struct BodySensorsView: View {
    @EnvironmentObject var hub: FeatureHub
    @EnvironmentObject var link: WatchLink
    @State private var heightCm = 170.0
    @State private var weightKg = 70.0
    @State private var isMale = true
    @State private var age = 30

    private func prefill(_ p: HealthSync.BiaProfileInput) {
        if let h = p.heightCm { heightCm = min(max(h, 100), 230) }
        if let w = p.weightKg { weightKg = min(max(w, 30), 250) }
        if let g = p.gender { isMale = g == 1 }
        if let a = p.age { age = min(max(a, 10), 120) }
    }

    var body: some View {
        Form {
            Section {
                Text(hub.sensorsAvailable
                     ? "Your watch can read SpO₂ and skin temperature. Readings are written to Apple Health."
                     : "Checking whether your watch's Samsung health sensors are reachable…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Button { hub.openSensorSettings() } label: {
                    Label("1. Open Samsung Health on watch", systemImage: "arrow.up.forward.app.fill")
                }
                .disabled(link.status != .connected)

                Button { hub.measureSensors(["spo2", "skintemp"]) } label: {
                    HStack {
                        Label(hub.measuringSensors ? "Measuring on watch…" : "2. Measure SpO₂ & skin temp",
                              systemImage: "lungs.fill")
                        Spacer()
                        if hub.measuringSensors { ProgressView() }
                    }
                }
                .tint(.red)
                .disabled(link.status != .connected || hub.measuringSensors)
            } header: {
                Text("Blood oxygen & skin temperature")
            } footer: {
                Text("Enable Developer mode in Samsung Health on the watch so other apps may read the sensors. Keep the watch snug and still for about 30 seconds during an on-demand reading.")
            }

            Section {
                if let ecg = hub.ecg {
                    VStack(alignment: .leading, spacing: 6) {
                        EcgTraceView(strip: ecg)
                            .frame(height: 140)
                        Text(String(format: "%.0f s · %d Hz · recorded %@", ecg.durationSec, ecg.hz,
                                    ecg.at.formatted(date: .omitted, time: .shortened)))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
                Button { hub.measureEcg() } label: {
                    HStack {
                        Label(hub.measuringEcg ? "Recording on watch…" : "Record ECG", systemImage: "waveform.path.ecg")
                        Spacer()
                        if hub.measuringEcg { ProgressView() }
                    }
                }
                .tint(.pink)
                .disabled(link.status != .connected || hub.measuringEcg)
            } header: {
                Text("ECG")
            } footer: {
                Text("Records a single-lead ECG and shows the trace here. Not a medical device — don't use it to diagnose anything.")
            }

            Section {
                Stepper("Height: \(Int(heightCm)) cm", value: $heightCm, in: 100...230, step: 1)
                Stepper("Weight: \(Int(weightKg)) kg", value: $weightKg, in: 30...250, step: 1)
                Stepper("Age: \(age)", value: $age, in: 10...120)
                Picker("Sex", selection: $isMale) { Text("Male").tag(true); Text("Female").tag(false) }
                Button { Task { if let p = await hub.sendBiaProfileFromHealth() { prefill(p) } } } label: {
                    Label("Load from Apple Health", systemImage: "heart.text.square")
                }
                Button {
                    hub.setBiaProfile(heightCm: heightCm, weightKg: weightKg, gender: isMale ? 1 : 0, age: age)
                } label: {
                    Label("Save profile to watch", systemImage: "square.and.arrow.up")
                }
                .disabled(link.status != .connected)
            } header: {
                Text("Body profile")
            } footer: {
                Text("A body-composition reading needs your height, weight, sex and age. Load them from Apple Health or set them here, then save to the watch.")
            }

            Section {
                if let bc = hub.bodyComposition {
                    LabeledContent("Body fat", value: bc.fatPct.map { String(format: "%.1f%%", $0) } ?? "—")
                    LabeledContent("Lean mass", value: bc.leanKg.map { String(format: "%.1f kg", $0) } ?? "—")
                    LabeledContent("Skeletal muscle", value: bc.muscleKg.map { String(format: "%.1f kg", $0) } ?? "—")
                    LabeledContent("Body water", value: bc.waterPct.map { String(format: "%.1f%%", $0) } ?? "—")
                    LabeledContent("BMR", value: bc.bmr.map { String(format: "%.0f kcal", $0) } ?? "—")
                }
                Button { hub.measureBia() } label: {
                    HStack {
                        Label(hub.measuringBia ? "Measuring on watch…" : "Measure body composition",
                              systemImage: "figure.arms.open")
                        Spacer()
                        if hub.measuringBia { ProgressView() }
                    }
                }
                .tint(.teal)
                .disabled(link.status != .connected || hub.measuringBia)
            } header: {
                Text("Body composition")
            } footer: {
                Text("Bioimpedance reading. Body-fat % and lean mass are saved to Apple Health; water, muscle and BMR are shown here. Heart rate, steps, energy, distance and floors sync automatically.")
            }
        }
        .navigationTitle("Body sensors")
        .navigationBarTitleDisplayMode(.inline)
        .toast($hub.toast)
        .onAppear { hub.requestSensors() }
    }
}

/// Plots an ECG strip with Swift Charts, downsampling long strips with min/max buckets to keep QRS peaks sharp.
struct EcgTraceView: View {
    let strip: EcgStrip

    private struct SamplePoint: Identifiable {
        let id: Int
        let seconds: Double
        let mv: Double
    }

    private var points: [SamplePoint] {
        let mv = strip.millivolts
        guard mv.count > 1 else { return [] }
        let hz = Double(max(strip.hz, 1))
        let maxPoints = 360
        if mv.count <= maxPoints {
            return mv.enumerated().map { SamplePoint(id: $0.offset, seconds: Double($0.offset) / hz, mv: $0.element) }
        }
        let buckets = maxPoints / 2
        let step = Double(mv.count) / Double(buckets)
        var out: [SamplePoint] = []
        out.reserveCapacity(buckets * 2)
        var nextId = 0
        for b in 0..<buckets {
            let start = Int(Double(b) * step)
            let end = min(mv.count, Int(Double(b + 1) * step))
            guard start < end else { continue }
            var loIdx = start, hiIdx = start
            for i in start..<end {
                if mv[i] < mv[loIdx] { loIdx = i }
                if mv[i] > mv[hiIdx] { hiIdx = i }
            }
            if loIdx <= hiIdx {
                out.append(SamplePoint(id: nextId, seconds: Double(loIdx) / hz, mv: mv[loIdx])); nextId += 1
                if hiIdx != loIdx {
                    out.append(SamplePoint(id: nextId, seconds: Double(hiIdx) / hz, mv: mv[hiIdx])); nextId += 1
                }
            } else {
                out.append(SamplePoint(id: nextId, seconds: Double(hiIdx) / hz, mv: mv[hiIdx])); nextId += 1
                out.append(SamplePoint(id: nextId, seconds: Double(loIdx) / hz, mv: mv[loIdx])); nextId += 1
            }
        }
        return out
    }

    var body: some View {
        Chart(points) { pt in
            LineMark(
                x: .value("Seconds", pt.seconds),
                y: .value("mV", pt.mv)
            )
            .foregroundStyle(.pink)
            .lineStyle(StrokeStyle(lineWidth: 1.5, lineJoin: .round))
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 5)) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let s = value.as(Double.self) { Text("\(Int(s.rounded()))s") }
                }
            }
        }
        .chartYAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) {
                AxisGridLine()
            }
        }
        .accessibilityLabel("ECG waveform, \(String(format: "%.0f", strip.durationSec)) seconds")
    }
}

/// The face that's on the watch now, every face installed, and the watch's own picker.
/// Tapping an installed face applies it from the phone (classic faces switch directly; third-party
/// Watch Face Format faces deep-link to the watch's picker, one tap).
struct WatchFacesView: View {
    @EnvironmentObject var hub: FeatureHub
    @EnvironmentObject var link: WatchLink
    @State private var applyTarget: FeatureHub.WatchFace?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 12) {
                    WatchRender(battery: link.watchBattery, connected: link.status == .connected, face: hub.faceImage, size: 190)
                        .padding(.top, 4)
                    Text(hub.faceName ?? "Current face")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.primary)
                    Button { hub.openFacePicker() } label: {
                        Label("Change face on watch", systemImage: "hand.tap.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(link.status != .connected)
                }
                .padding(16)
                .insetCard()

                NavigationLink { FaceGalleryView() } label: {
                    HStack(spacing: 14) {
                        HStack(spacing: -14) {
                            ForEach(FaceCatalog.styles.prefix(4).map(\.cover)) {
                                FaceRender(design: $0, showComplications: false)
                                    .frame(width: 40, height: 40)
                                    .overlay(Circle().strokeBorder(Color(.secondarySystemGroupedBackground), lineWidth: 2))
                            }
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Face gallery")
                                .font(.headline)
                                .foregroundStyle(.primary)
                            Text("\(FaceCatalog.all.count) free faces in \(FaceCatalog.styles.count) styles")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                            .accessibilityHidden(true)
                    }
                    .padding(14)
                    .insetCard()
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Installed")
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 4)

                    VStack(spacing: 0) {
                        if hub.faces.isEmpty {
                            Text(link.status == .connected ? "Loading…" : "Connect your watch to see its faces.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(16)
                        }
                        ForEach(hub.faces) { f in
                            Button { if !f.active { applyTarget = f } } label: {
                                HStack(spacing: 12) {
                                    Group {
                                        if let img = hub.facePreviews[f.id] {
                                            Image(uiImage: img).resizable().scaledToFill()
                                        } else {
                                            Image(systemName: "applewatch").foregroundStyle(.secondary)
                                        }
                                    }
                                    .frame(width: 44, height: 44)
                                    .background(Color(.tertiarySystemGroupedBackground))
                                    .clipShape(Circle())
                                    .overlay(
                                        Circle().strokeBorder(
                                            f.active ? Color.accentColor : Color.secondary.opacity(0.2),
                                            lineWidth: f.active ? 2.5 : 1
                                        )
                                    )
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(f.name).foregroundStyle(.primary)
                                        Text(f.id).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    Spacer()
                                    if f.active {
                                        Label("On watch", systemImage: "checkmark.circle.fill")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(Color.accentColor)
                                    } else {
                                        Image(systemName: "arrow.up.forward.app")
                                            .foregroundStyle(.secondary)
                                            .accessibilityHidden(true)
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(link.status != .connected)
                            .accessibilityAddTraits(f.active ? .isSelected : [])
                            if f.id != hub.faces.last?.id { Divider().padding(.leading, 72) }
                        }
                    }
                    .insetCard()
                }

                NavigationLink { StoreScreen() } label: {
                    HStack {
                        Label("Get more faces on Google Play", systemImage: "bag.fill")
                            .foregroundStyle(.primary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                            .accessibilityHidden(true)
                    }
                    .padding(16)
                    .insetCard()
                }
                .buttonStyle(.plain)
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Watch faces")
        .navigationBarTitleDisplayMode(.inline)
        .task { hub.requestFaces(); hub.requestFace() }
        .refreshable { hub.requestFaces(); hub.requestFace() }
        .confirmationDialog("Apply \(applyTarget?.name ?? "")?",
                            isPresented: Binding(get: { applyTarget != nil }, set: { if !$0 { applyTarget = nil } }),
                            titleVisibility: .visible) {
            Button("Apply on watch") { if let f = applyTarget { hub.applyFace(f) } }
        } message: { Text("Switches the watch face. A third-party face may need one tap on the watch to confirm.") }
        .toast($hub.toast)
    }
}

private struct NotConnected: View {
    @EnvironmentObject var link: WatchLink
    var body: some View {
        if link.status != .connected {
            Label("Connect your watch to use this.", systemImage: "applewatch.slash").foregroundStyle(.secondary)
        }
    }
}

struct NotificationFiltersView: View {
    @EnvironmentObject var hub: FeatureHub

    var body: some View {
        Form {
            NotConnected()
            Section {
                ForEach(FeatureHub.categoryNames, id: \.0) { cat in
                    Toggle(cat.1, isOn: Binding(get: { hub.categories[cat.0] ?? true }, set: { hub.setCategory(cat.0, $0) }))
                }
            } header: { Text("Types") } footer: { Text("Incoming calls always ring on the watch.") }
            Section {
                if hub.notifApps.isEmpty {
                    Text("Apps appear here after they send a notification to the watch.").foregroundStyle(.secondary)
                }
                ForEach(hub.notifApps) { app in
                    Toggle(app.name, isOn: Binding(get: { !app.muted }, set: { hub.setMuted(app.id, !$0) }))
                }
            } header: { Text("Apps") }
        }
        .navigationTitle("Notifications")
        .onAppear { hub.requestNotifApps() }
    }
}

struct ContactsView: View {
    @EnvironmentObject var hub: FeatureHub
    var body: some View {
        Form {
            NotConnected()
            Section {
                Button { Task { await hub.syncContacts() } } label: {
                    HStack { Text("Copy contacts to watch"); Spacer(); if hub.contactsBusy { ProgressView() } }
                }
                .disabled(hub.contactsBusy)
            } footer: {
                Text("Names, phone numbers and email addresses go only to your watch, over Bluetooth. Run it again after changing contacts; it replaces the previous copy.")
            }
        }
        .navigationTitle("Contacts")
        .toast($hub.toast)
    }
}

struct RemindersView: View {
    @EnvironmentObject var hub: FeatureHub
    var body: some View {
        Form {
            Section {
                Toggle("Show reminders on watch", isOn: $hub.shareReminders)
                if !hub.remindersAllowed {
                    Button("Allow access to Reminders") { Task { await hub.requestRemindersAccess() } }
                }
            } footer: {
                Text("Add the \"Reminders (iPhone)\" tile on your watch: swipe left from the watch face, then tap +. Tick a reminder on the tile to complete it on your iPhone.")
            }
        }
        .navigationTitle("Reminders")
    }
}

struct AlarmsView: View {
    @EnvironmentObject var hub: FeatureHub
    @State private var time = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
    @State private var label = ""
    @State private var minutes = 5

    var body: some View {
        Form {
            NotConnected()
            Section("Alarm") {
                DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)
                TextField("Label", text: $label)
                Button("Set alarm on watch") { hub.setAlarm(time, label: label) }
            }
            Section("Timer") {
                Stepper("\(minutes) min", value: $minutes, in: 1...240)
                Button("Start timer on watch") { hub.setTimer(seconds: minutes * 60, label: label) }
            }
        }
        .navigationTitle("Alarms & timers")
        .toast($hub.toast)
    }
}

struct AppsView: View {
    @EnvironmentObject var hub: FeatureHub
    @EnvironmentObject var link: WatchLink
    @State private var showSystem = false
    @State private var confirm: FeatureHub.WatchApp?
    @State private var showInstall = false

    private static let apkType = UTType(filenameExtension: "apk") ?? .data

    var body: some View {
        List {
            NotConnected()
            Section {
                Button {
                    showInstall = true
                } label: {
                    HStack {
                        Label("Install an APK on the watch", systemImage: "square.and.arrow.down.on.square")
                        Spacer()
                        if hub.installing { ProgressView() }
                    }
                }
                .disabled(link.status != .connected || hub.installing)
            } footer: {
                Text("Pick an .apk from Files; it's sent over Bluetooth and you confirm the install on the watch. Enable \"Install unknown apps\" for Wear OS Bridge on the watch first.")
            }
            Toggle("Show system apps", isOn: $showSystem)
            ForEach(hub.apps.filter { showSystem || !$0.system }) { app in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(app.name)
                        Text([app.version, app.mb >= 0 ? "\(app.mb.formatted()) MB" : nil].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Menu {
                        Button("Open on watch", systemImage: "arrow.up.forward.app") { hub.launch(app.id) }
                        Button("Play Store page", systemImage: "bag") { hub.openStore(app.id) }
                        if !app.system { Button("Uninstall…", systemImage: "trash", role: .destructive) { confirm = app } }
                    } label: {
                        Image(systemName: "ellipsis.circle").font(.title3)
                    }
                    .accessibilityLabel("Actions for \(app.name)")
                }
            }
        }
        .overlay { if hub.loadingApps && hub.apps.isEmpty { ProgressView() } }
        .navigationTitle("Watch apps")
        .refreshable { hub.requestApps() }
        .onAppear { hub.requestApps() }
        .confirmationDialog("Uninstall \(confirm?.name ?? "")?", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
                            titleVisibility: .visible) {
            Button("Uninstall", role: .destructive) { if let a = confirm { hub.uninstall(a.id) } }
        } message: { Text("You'll confirm on the watch.") }
        .fileImporter(isPresented: $showInstall, allowedContentTypes: [Self.apkType, .data], allowsMultipleSelection: false) { result in
            if case let .success(urls) = result, let url = urls.first { hub.installApk(url) }
        }
        .toast($hub.toast)
    }
}

struct PhotosToWatchView: View {
    @EnvironmentObject var hub: FeatureHub
    @State private var item: PhotosPickerItem?

    var body: some View {
        Form {
            NotConnected()
            Section {
                PhotosPicker(selection: $item, matching: .images) {
                    HStack { Text("Choose a photo"); Spacer(); if hub.photoBusy { ProgressView() } }
                }
            } footer: {
                Text("Photos are resized for the watch and appear in its Gallery app, in the Wear OS Bridge album. Bluetooth is slow, so each takes a few seconds.")
            }
        }
        .navigationTitle("Photos")
        .onChange(of: item) { _, new in
            guard let new else { return }
            Task {
                if let data = try? await new.loadTransferable(type: Data.self), let image = UIImage(data: data) { hub.sendPhoto(image) }
                item = nil
            }
        }
        .toast($hub.toast)
    }
}

struct RecordingsView: View {
    @EnvironmentObject var hub: FeatureHub

    var body: some View {
        List {
            NotConnected()
            if hub.recordings.isEmpty {
                Text("No recordings found on the watch. Make one with the watch's Voice Recorder app.").foregroundStyle(.secondary)
            }
            ForEach(hub.recordings) { r in
                Button { hub.download(r) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.name).foregroundStyle(.primary)
                            Text("\(Duration.milliseconds(r.ms).formatted(.time(pattern: .minuteSecond))) · \(ByteCountFormatter.string(fromByteCount: r.bytes, countStyle: .file)) · \(r.at.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if hub.downloading == r.id { ProgressView(value: hub.downloadProgress).frame(width: 60) }
                        else { Image(systemName: "arrow.down.circle").accessibilityHidden(true) }
                    }
                }
                .disabled(hub.downloading != nil)
                .accessibilityHint("Downloads recording to Files")
            }
        }
        .navigationTitle("Voice recordings")
        .refreshable { hub.requestRecordings() }
        .onAppear { hub.requestRecordings() }
        .toast($hub.toast)
    }
}

struct WorkoutsInfoView: View {
    @EnvironmentObject var health: HealthSync
    var body: some View {
        Form {
            Section {
                Label("On the watch, open Wear OS Bridge → Workout and pick Run, Walk, Cycle, Hike or Workout.", systemImage: "1.circle")
                Label("Tap End when you finish. The workout and its GPS route go to Apple Health the next time the watch connects.", systemImage: "2.circle")
            }
            if let last = health.lastWorkout { Section("Last saved") { Text(last) } }
            Section { Button("Allow workouts in Apple Health") { Task { await health.requestAccess() } } }
        }
        .navigationTitle("Workouts")
    }
}

/// Place/answer/reject/hang up calls with the audio on the watch (HFP), and how texts work.
/// Call control needs a classic-Bluetooth hands-free bond between the watch and iPhone, which is
/// separate from the Wear OS Bridge (BLE) connection.
struct CallsView: View {
    @EnvironmentObject var hub: FeatureHub
    @EnvironmentObject var link: WatchLink
    @State private var number = ""

    var body: some View {
        Form {
            NotConnected()
            Section {
                TextField("Phone number", text: $number)
                    .keyboardType(.phonePad)
                Button { hub.call("dial", number: number) } label: {
                    Label("Call on watch", systemImage: "phone.arrow.up.right.fill")
                }
                .tint(.green)
                .disabled(link.status != .connected || number.isEmpty)
            } header: {
                Text("Calls on the watch")
            } footer: {
                Text("Answer, reject, hang up or place calls with the call audio on your watch, over its hands-free link to your iPhone.")
            }

            Section("Active call") {
                Button { hub.call("answer") } label: {
                    Label("Answer", systemImage: "phone.fill")
                }
                .tint(.green)
                Button(role: .destructive) { hub.call("reject") } label: {
                    Label("Reject", systemImage: "phone.down.fill")
                }
                Button(role: .destructive) { hub.call("hangup") } label: {
                    Label("Hang up", systemImage: "phone.down.fill")
                }
            }

            Section {
                Label("Incoming texts appear as notifications on the watch, like your other iPhone notifications.", systemImage: "bubble.left.fill")
                Label("To send a text, use Siri on the watch — iOS sends it over the hands-free link. The app never injects messages silently.", systemImage: "mic.fill")
            } header: {
                Text("Texts")
            } footer: {
                Text("Calls use a classic-Bluetooth hands-free pairing between the watch and iPhone, which is separate from the Wear OS Bridge connection and is set up in the watch's Bluetooth settings.")
            }
        }
        .navigationTitle("Calls & texts")
        .navigationBarTitleDisplayMode(.inline)
        .toast($hub.toast)
    }
}

struct TilesInfoView: View {
    var body: some View {
        Form {
            Section {
                Label("Calendar (iPhone)", systemImage: "calendar")
                Label("Reminders (iPhone): tap to tick off", systemImage: "checklist")
                Label("Weather (iPhone)", systemImage: "cloud.sun")
                Label("iPhone battery", systemImage: "battery.75percent")
                Label("Now playing (iPhone): play, pause, skip", systemImage: "music.note")
            } footer: {
                Text("On the watch, swipe left from the watch face to the end of your tiles, tap +, and add any of these.")
            }
        }
        .navigationTitle("Tiles")
    }
}

// MARK: - Toast

private struct Toast: ViewModifier {
    @Binding var text: String?
    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let text {
                Text(text)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.primary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .nativeGlassCapsule()
                    .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: text) { try? await Task.sleep(for: .seconds(3)); withAnimation { self.text = nil } }
            }
        }
        .animation(.default, value: text)
    }
}

extension View {
    func toast(_ text: Binding<String?>) -> some View { modifier(Toast(text: text)) }
}
