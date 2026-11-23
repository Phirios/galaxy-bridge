import AudioToolbox
import AVFoundation
import Foundation
import SwiftUI
import UIKit
import UserNotifications
import WearLink

/// The iPhone side of calls, messaging and safety (Wave B): favourites and quick replies sent to
/// the watch, call history read back from it, the safety settings (emergency contact, fall
/// detection, SOS, no-movement check-in, heart-rate limits), alerts from the watch, and loud find.
///
/// iOS limits, stated plainly: the app cannot place calls or send texts by itself. Calls are placed
/// by the watch over its hands-free link; texts go through Siri or through Messages, which opens
/// prefilled and still needs the user to tap Send.
@MainActor
final class CallsSafetyStore: NSObject, ObservableObject {
    static let shared = CallsSafetyStore()

    struct WatchState: Equatable {
        var hasContact = false
        var fallRunning = false
        var hrSensor = false
        var favourites = 0
    }

    @Published var config: SafetyConfig {
        didSet { if config != oldValue { persist(config, "safety.config"); post("safetyConfig", config.payload) } }
    }
    @Published private(set) var favourites: [CallFavourite]
    @Published private(set) var quickReplies: [String]
    @Published private(set) var callLog: [CallLogEntry] = []
    @Published private(set) var lastAlert: SosAlert?
    @Published private(set) var lastHr: HrAlert?
    @Published private(set) var watchState: WatchState?
    @Published private(set) var watchRingingLoud = false
    @Published private(set) var phoneRingingLoud = false

    static let defaultReplies = ["On my way", "Running late", "Can't talk now", "Call you back soon", "Yes", "No", "Thanks!"]

    private weak var hub: FeatureHub?
    private var delegateInstalled = false

    override init() {
        config = Self.load(SafetyConfig.self, "safety.config") ?? SafetyConfig()
        favourites = Self.load([CallFavourite].self, "safety.favourites") ?? []
        quickReplies = Self.load([String].self, "safety.replies") ?? Self.defaultReplies
        super.init()
    }

    // MARK: Link

    func attach(_ hub: FeatureHub) {
        self.hub = hub
        installNotificationDelegate()
    }

    /// Called from FeatureHub.onConnected: the watch gets the current settings every time.
    func onConnected(_ hub: FeatureHub) {
        attach(hub)
        post("safetyConfig", config.payload)
        post("callFavourites", CallFavourite.payload(favourites))
        post("quickReplies", ["items": quickReplies])
        post("safetyGet")
        post("callLogGet")
    }

    private func post(_ type: String, _ body: [String: Any] = [:]) {
        guard let hub, hub.isConnected(), let m = GBFeature.message(type, body) else { return }
        hub.send?(m)
    }

    var connected: Bool { hub?.isConnected() ?? false }

    func refresh() {
        post("safetyGet")
        post("callLogGet")
    }

    // MARK: Favourites and quick replies

    func addFavourite(name: String, number: String) {
        let n = number.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, !favourites.contains(where: { $0.number == n }),
              favourites.count < CallsSafetyLimits.maxFavourites else { return }
        favourites.append(CallFavourite(name: String(name.prefix(60)), number: String(n.prefix(32))))
        saveFavourites()
    }

    func removeFavourite(_ f: CallFavourite) {
        favourites.removeAll { $0 == f }
        saveFavourites()
    }

    func moveFavourite(_ f: CallFavourite, up: Bool) {
        guard let i = favourites.firstIndex(of: f) else { return }
        let j = up ? i - 1 : i + 1
        guard favourites.indices.contains(j) else { return }
        favourites.swapAt(i, j)
        saveFavourites()
    }

    private func saveFavourites() {
        persist(favourites, "safety.favourites")
        post("callFavourites", CallFavourite.payload(favourites))
    }

    func setQuickReplies(_ list: [String]) {
        let clean = list.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(CallsSafetyLimits.maxQuickReplyLength)) }
            .filter { !$0.isEmpty }.prefix(CallsSafetyLimits.maxQuickReplies)
        quickReplies = clean.isEmpty ? Self.defaultReplies : Array(clean)
        persist(quickReplies, "safety.replies")
        post("quickReplies", ["items": quickReplies])
    }

    // MARK: Find

    func findWatchLoud(_ on: Bool) {
        watchRingingLoud = on && connected
        post("findLoud", ["on": on])
    }

    // MARK: Incoming from the watch

    func handle(_ type: String, _ b: [String: Any], hub: FeatureHub) {
        attach(hub)
        switch type {
        case "callLog":
            callLog = CallLogEntry.parse(b)
        case "safetyState":
            watchState = WatchState(hasContact: b["hasContact"] as? Bool ?? false, fallRunning: b["fallRunning"] as? Bool ?? false,
                                    hrSensor: b["hrSensor"] as? Bool ?? false, favourites: (b["favourites"] as? Int) ?? 0)
        case "sosAlert":
            guard var a = SosAlert.parse(b) else { return }
            // A location update keeps the stage it arrived in.
            if a.stage == .location, let prev = lastAlert, prev.kind == a.kind {
                a.stage = prev.stage == .cancelled ? .cancelled : prev.stage
            } else if !a.hasLocation, let prev = lastAlert, prev.kind == a.kind, prev.hasLocation,
                      a.at.timeIntervalSince(prev.at) < 600 {
                a.latitude = prev.latitude; a.longitude = prev.longitude; a.accuracy = prev.accuracy
            }
            lastAlert = a
            notifySos(a)
        case "hrAlert":
            guard let h = HrAlert.parse(b) else { return }
            lastHr = h
            notify(id: "hrAlert", title: h.kind == .high ? "High heart rate on your watch" : "Low heart rate on your watch",
                   body: "\(h.bpm) bpm (your limit: \(h.threshold)). This is not a medical reading.", category: nil)
        case "findLoud":
            let on = b["on"] as? Bool ?? false
            if (b["side"] as? String) == "watch" { watchRingingLoud = on } else if on { startLoudRing() } else { stopLoudRing() }
        case "quickReply":
            guard let text = (b["text"] as? String).map({ String($0.prefix(CallsSafetyLimits.maxQuickReplyLength)) }), !text.isEmpty,
                  let url = prefilledMessageURL(to: "", body: text) else { return }
            notify(id: "quickReply", title: "Reply from your watch", body: "Tap to send \u{201C}\(text)\u{201D} in Messages. You choose who and tap Send.",
                   category: "gbQuickReply", info: ["open": url.absoluteString])
        default: break
        }
    }

    // MARK: SOS sharing

    /// The message to share with the emergency contact. Opens in Messages; never sent automatically.
    func sosMessage(_ a: SosAlert) -> String {
        let what: String
        switch a.kind {
        case .sos: what = "I pressed SOS on my watch and may need help."
        case .fall: what = "My watch thinks I may have fallen."
        case .checkIn: what = "My watch noticed I haven't moved for a while."
        }
        if let url = a.mapsURL { return "\(what) My location: \(url.absoluteString)" }
        return what
    }

    func sosTextURL(_ a: SosAlert) -> URL? { prefilledMessageURL(to: config.contactNumber, body: sosMessage(a)) }

    private func notifySos(_ a: SosAlert) {
        let who = config.contactName.isEmpty ? config.contactNumber : config.contactName
        let title: String
        let body: String
        switch a.stage {
        case .countdown:
            title = "\(Self.kindTitle(a.kind)) on your watch"
            body = "Your watch will call \(who) unless it's cancelled on the watch."
        case .calling, .location:
            title = "Your watch is calling \(who)"
            body = a.hasLocation ? "Tap to text your location to \(who). You still tap Send." : "Waiting for the watch's location…"
        case .cancelled:
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["sosAlert"])
            return
        }
        var info: [String: String] = [:]
        if a.hasLocation, let u = sosTextURL(a) { info["open"] = u.absoluteString }
        if let m = a.mapsURL { info["maps"] = m.absoluteString }
        notify(id: "sosAlert", title: title, body: body, category: a.hasLocation ? "gbSos" : nil, info: info, urgent: true)
    }

    static func kindTitle(_ k: SosAlert.Kind) -> String {
        switch k { case .sos: "SOS"; case .fall: "Possible fall"; case .checkIn: "No-movement check-in" }
    }

    // MARK: Notifications

    private func installNotificationDelegate() {
        guard !delegateInstalled else { return }
        delegateInstalled = true
        let center = UNUserNotificationCenter.current()
        // The app had no notification delegate before; only our categories change behaviour.
        if center.delegate == nil { center.delegate = self }
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "gbSos", actions: [
                UNNotificationAction(identifier: "text", title: "Text my location", options: [.foreground]),
                UNNotificationAction(identifier: "maps", title: "Open in Maps", options: [.foreground]),
            ], intentIdentifiers: []),
            UNNotificationCategory(identifier: "gbQuickReply", actions: [
                UNNotificationAction(identifier: "text", title: "Open in Messages", options: [.foreground]),
            ], intentIdentifiers: []),
        ])
    }

    private func notify(id: String, title: String, body: String, category: String?, info: [String: String] = [:], urgent: Bool = false) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.sound = urgent ? .defaultCritical : .default
        c.interruptionLevel = urgent ? .timeSensitive : .active
        c.threadIdentifier = "gb." + id
        if let category { c.categoryIdentifier = category }
        c.userInfo = info
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }

    // MARK: Loud find (this iPhone)

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var ringTimer: Timer?
    private var ringTicks = 0

    /// Rings this iPhone with a tone that gets louder (and vibrates) until stopped or for a minute.
    /// Apps can't raise the system volume; this ramps the app's own output up to it. Full volume
    /// in the background NEEDS LIVE TEST (the app has no background-audio mode).
    func startLoudRing() {
        stopLoudRing()
        phoneRingingLoud = true
        notify(id: "findLoud", title: "Your watch is looking for this iPhone", body: "Open the app or tap to stop.", category: nil, urgent: true)
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, options: [.duckOthers])
        try? session.setActive(true)
        let e = AVAudioEngine()
        let p = AVAudioPlayerNode()
        let rate = 44_100.0
        if let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1),
           let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(rate)) {
            buf.frameLength = buf.frameCapacity
            let data = buf.floatChannelData![0]
            for i in 0..<Int(buf.frameLength) {
                let t = Double(i) / rate
                // Two quick beeps then a pause, once a second.
                let on = (t < 0.18) || (t > 0.28 && t < 0.46)
                data[i] = on ? Float(sin(2 * .pi * (t < 0.2 ? 1_760 : 2_093) * t)) * 0.9 : 0
            }
            e.attach(p)
            e.connect(p, to: e.mainMixerNode, format: format)
            p.volume = 0.3
            if (try? e.start()) != nil {
                p.scheduleBuffer(buf, at: nil, options: .loops)
                p.play()
                engine = e
                player = p
            }
        }
        ringTicks = 0
        ringTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.ringStep() }
        }
        ringStep()
    }

    private func ringStep() {
        ringTicks += 1
        player?.volume = min(1, 0.3 + Float(ringTicks) * 0.07)
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        if engine == nil { AudioServicesPlayAlertSound(SystemSoundID(1005)) }
        if ringTicks >= 30 { stopLoudRing() }
    }

    func stopLoudRing() {
        ringTimer?.invalidate(); ringTimer = nil
        player?.stop(); engine?.stop()
        player = nil; engine = nil
        if phoneRingingLoud { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        phoneRingingLoud = false
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["findLoud"])
    }

    // MARK: Storage

    private func persist<T: Encodable>(_ value: T, _ key: String) {
        if let d = try? JSONEncoder().encode(value) { UserDefaults.standard.set(d, forKey: key) }
    }

    private static func load<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(type, from: $0) }
    }
}

extension CallsSafetyStore: UNUserNotificationCenterDelegate {
    nonisolated private static let ours: Set<String> = ["sosAlert", "hrAlert", "quickReply", "findLoud"]

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Keep the app's previous behaviour (no banner while open) for everything that isn't ours.
        completionHandler(Self.ours.contains(notification.request.identifier) ? [.banner, .sound, .list] : [])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let id = response.notification.request.identifier
        let action = response.actionIdentifier
        let key = action == "maps" ? "maps" : "open"
        let url = (info[key] as? String).flatMap(URL.init(string:))
        Task { @MainActor in
            if id == "findLoud" { CallsSafetyStore.shared.stopLoudRing() }
            if let url, action != UNNotificationDismissActionIdentifier { UIApplication.shared.open(url) }
        }
        completionHandler()
    }
}
