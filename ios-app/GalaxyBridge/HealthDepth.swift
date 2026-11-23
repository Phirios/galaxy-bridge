import Foundation
import HealthKit
import WearLink

/// Health depth (Wave C): stress/HRV, goals and reminders, automatic and overnight readings,
/// water/caffeine intake and cycle tracking. The watch measures and reminds; this side keeps the
/// settings, shows the results and writes to Apple Health. Everything from the watch is untrusted
/// and range-checked. Stress and HRV are wellness estimates, not medical measurements.
@MainActor
final class HealthDepthStore: ObservableObject {
    static let shared = HealthDepthStore()

    /// Settings sent to the watch as one "hdConfig" message (see HealthDepth.kt for the ranges).
    struct Config: Codable, Equatable {
        var goalSteps = 10_000
        var goalKcal = 500
        var goalActiveMin = 30
        var autoLog = false
        var autoLogMin = 60
        var autoLogMinBattery = 30
        var overnight = false
        var overnightMin = 30
        var remindInactive = false
        var inactiveMin = 60
        var remindStand = false
        var remindWater = false
        var waterEveryMin = 120
        var quietStart = 22
        var quietEnd = 8
        var cupMl = 250
        var coffeeMg = 95
        var autoPause = true

        var wire: [String: Any] {
            [
                "goalSteps": goalSteps, "goalKcal": goalKcal, "goalActiveMin": goalActiveMin,
                "autoLog": autoLog ? 1 : 0, "autoLogMin": autoLogMin, "autoLogMinBattery": autoLogMinBattery,
                "overnight": overnight ? 1 : 0, "overnightMin": overnightMin,
                "remindInactive": remindInactive ? 1 : 0, "inactiveMin": inactiveMin,
                "remindStand": remindStand ? 1 : 0, "remindWater": remindWater ? 1 : 0, "waterEveryMin": waterEveryMin,
                "quietStart": quietStart, "quietEnd": quietEnd, "cupMl": cupMl, "coffeeMg": coffeeMg,
                "autoPause": autoPause ? 1 : 0,
            ]
        }

        /// Reads the watch's copy, keeping only in-range values.
        mutating func merge(_ b: [String: Any]) {
            func int(_ k: String, _ r: ClosedRange<Int>) -> Int? {
                guard let v = (b[k] as? NSNumber)?.intValue, r.contains(v) else { return nil }
                return v
            }
            func flag(_ k: String) -> Bool? { int(k, 0...1).map { $0 == 1 } }
            goalSteps = int("goalSteps", 1_000...50_000) ?? goalSteps
            goalKcal = int("goalKcal", 50...3_000) ?? goalKcal
            goalActiveMin = int("goalActiveMin", 5...300) ?? goalActiveMin
            autoLog = flag("autoLog") ?? autoLog
            autoLogMin = int("autoLogMin", 15...240) ?? autoLogMin
            autoLogMinBattery = int("autoLogMinBattery", 10...80) ?? autoLogMinBattery
            overnight = flag("overnight") ?? overnight
            overnightMin = int("overnightMin", 15...120) ?? overnightMin
            remindInactive = flag("remindInactive") ?? remindInactive
            inactiveMin = int("inactiveMin", 30...180) ?? inactiveMin
            remindStand = flag("remindStand") ?? remindStand
            remindWater = flag("remindWater") ?? remindWater
            waterEveryMin = int("waterEveryMin", 30...240) ?? waterEveryMin
            quietStart = int("quietStart", 0...23) ?? quietStart
            quietEnd = int("quietEnd", 0...23) ?? quietEnd
            cupMl = int("cupMl", 50...1_000) ?? cupMl
            coffeeMg = int("coffeeMg", 10...500) ?? coffeeMg
            autoPause = flag("autoPause") ?? autoPause
        }
    }

    struct HrvReading: Codable, Equatable, Identifiable {
        var sdnn: Double
        var rmssd: Double?
        var stress: Int?
        var at: Date
        var id: Date { at }

        var stressLabel: String {
            guard let stress else { return "–" }
            return stress < 34 ? "Low" : stress < 67 ? "Moderate" : "High"
        }
    }

    /// Activity totals the watch keeps for its rings (steps from its hardware counter).
    struct WatchToday: Equatable {
        var steps = 0, kcal = 0.0, activeMin = 0, water = 0, caffeine = 0
    }

    /// Intake and overnight values seen today on this phone (from the watch or logged here).
    struct PhoneToday: Codable, Equatable {
        var day = DaySummary.today()
        var waterMl = 0.0
        var caffeineMg = 0.0
        var sleepingHR: [Double] = []
        var flowLogged: Double?
        var counted: [String] = []
    }

    @Published var config: Config {
        didSet {
            guard config != oldValue else { return }
            configEdited = true
            save(config, "hdConfig")
            UserDefaults.standard.set(true, forKey: "hdConfigEdited")
            schedulePush()
        }
    }
    @Published private(set) var latestHrv: HrvReading?
    @Published private(set) var history: [HrvReading] = []
    @Published private(set) var watchToday: WatchToday?
    @Published private(set) var today = PhoneToday()
    @Published private(set) var measuring = false
    @Published private(set) var hrvMessage: String?
    /// How many HRV attempts on the watch got any beat-to-beat data — tells us whether the sensor
    /// exposes IBIs at all on this watch.
    @Published private(set) var ibiAttempts = 0
    @Published private(set) var ibiWithData = 0
    @Published private(set) var autoLastRun: Date?
    @Published private(set) var autoSkipped = ""
    @Published private(set) var asleep = false
    @Published private(set) var watchSensors: Bool?
    @Published private(set) var configOnWatch = false

    weak var hub: FeatureHub?
    weak var health: HealthSync?
    private var configEdited = UserDefaults.standard.bool(forKey: "hdConfigEdited")
    private var pushTask: Task<Void, Never>?

    private init() {
        config = Self.load(Config.self, "hdConfig") ?? Config()
        history = Self.load([HrvReading].self, "hdHrvHistory") ?? []
        latestHrv = history.last
        today = (Self.load(PhoneToday.self, "hdToday") ?? PhoneToday()).rolled()
    }

    // MARK: Link

    private func post(_ type: String, _ body: [String: Any] = [:]) -> Bool {
        guard let hub, hub.isConnected(), let m = GBFeature.message(type, body) else { return false }
        hub.send?(m)
        return true
    }

    func onConnected(_ hub: FeatureHub) {
        self.hub = hub
        health = hub.health
        _ = post("hdGetState")
        if configEdited && !configOnWatch { pushConfig() }
    }

    func refresh() { _ = post("hdGetState") }

    private func schedulePush() {
        configOnWatch = false
        pushTask?.cancel()
        pushTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled else { return }
            self?.pushConfig()
        }
    }

    func pushConfig() { if post("hdConfig", config.wire) { configOnWatch = true } }

    func measureHrv() {
        guard post("hdMeasureHrv") else { hrvMessage = "Connect your watch first"; return }
        measuring = true
        hrvMessage = "Measuring on your watch — keep still for a minute"
    }

    func handle(_ type: String, _ b: [String: Any]) {
        switch type {
        case "hrvResult":
            measuring = false
            if b["ok"] as? Bool == true, let r = reading(b) {
                record(r)
                hrvMessage = nil
            } else {
                hrvMessage = String((b["reason"] as? String ?? "No reading").prefix(80))
            }
        case "hdState":
            if let c = b["config"] as? [String: Any] {
                if configEdited, !configOnWatch {
                    pushConfig()
                } else {
                    var merged = config
                    merged.merge(c)
                    if merged != config { setFromWatch(merged) }
                    configOnWatch = true
                }
            }
            if let t = b["today"] as? [String: Any] {
                func n(_ k: String) -> Double { min(max((t[k] as? NSNumber)?.doubleValue ?? 0, 0), 1_000_000) }
                watchToday = WatchToday(steps: Int(n("steps")), kcal: n("kcal"), activeMin: Int(n("activeMin")),
                                        water: Int(n("water")), caffeine: Int(n("caffeine")))
            }
            if let h = b["hrv"] as? [String: Any] {
                ibiAttempts = max(0, (h["attempts"] as? NSNumber)?.intValue ?? 0)
                ibiWithData = max(0, (h["withIbi"] as? NSNumber)?.intValue ?? 0)
                if let r = reading(h), r.at > (latestHrv?.at ?? .distantPast) { record(r) }
            }
            if let a = b["auto"] as? [String: Any] {
                let last = (a["lastRun"] as? NSNumber)?.doubleValue ?? 0
                autoLastRun = last > 0 ? Date(timeIntervalSince1970: last / 1000) : nil
                autoSkipped = String((a["skipped"] as? String ?? "").prefix(60))
                asleep = a["asleep"] as? Bool ?? false
            }
            watchSensors = b["sensors"] as? Bool
        default: break
        }
    }

    /// Applies the watch's config without marking it as a local edit.
    private func setFromWatch(_ c: Config) {
        let edited = configEdited
        config = c
        configEdited = edited
        UserDefaults.standard.set(edited, forKey: "hdConfigEdited")
        pushTask?.cancel()
        configOnWatch = true
    }

    private func reading(_ b: [String: Any]) -> HrvReading? {
        guard let sdnn = (b["sdnn"] as? NSNumber)?.doubleValue, (1...300).contains(sdnn),
              let at = (b["at"] as? NSNumber)?.doubleValue, at > 0 else { return nil }
        let date = Date(timeIntervalSince1970: at / 1000)
        guard date < Date().addingTimeInterval(600), date > Date().addingTimeInterval(-30 * 86_400) else { return nil }
        let rmssd = (b["rmssd"] as? NSNumber).map(\.doubleValue).flatMap { (0...500).contains($0) ? $0 : nil }
        let stress = (b["stress"] as? NSNumber).map(\.intValue).flatMap { (0...100).contains($0) ? $0 : nil }
        return HrvReading(sdnn: sdnn, rmssd: rmssd, stress: stress, at: date)
    }

    private func record(_ r: HrvReading) {
        if let i = history.firstIndex(where: { abs($0.at.timeIntervalSince(r.at)) < 30 }) { history[i] = r }
        else { history.append(r) }
        history.sort { $0.at < $1.at }
        if history.count > 60 { history.removeFirst(history.count - 60) }
        latestHrv = history.last
        save(history, "hdHrvHistory")
    }

    // MARK: Samples (hooked from HealthSync.save)

    func ingest(batchId: String, _ items: [HealthSample]) {
        today = today.rolled()
        guard !today.counted.contains(batchId) else { return }
        today.counted.append(batchId)
        if today.counted.count > 300 { today.counted.removeFirst(today.counted.count - 300) }
        let start = Calendar.current.startOfDay(for: Date())
        for s in items where s.startDate >= start {
            switch s.kind {
            case .water: today.waterMl += s.value
            case .caffeine: today.caffeineMg += s.value
            case .sleepingHeartRate: today.sleepingHR.append(s.value)
            case .menstrualFlow: today.flowLogged = s.value
            case .hrv where !history.contains(where: { abs($0.at.timeIntervalSince(s.endDate)) < 120 }):
                record(HrvReading(sdnn: s.value, rmssd: nil, stress: nil, at: s.endDate))
            default: break
            }
        }
        save(today, "hdToday")
    }

    // MARK: Logging from the iPhone

    func logWater(_ ml: Double) async { await log(.water, ml) }
    func logCaffeine(_ mg: Double) async { await log(.caffeine, mg) }

    private func log(_ kind: SampleKind, _ value: Double) async {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let s = HealthSample(kind: kind, start: now, end: now, value: value)
        guard GBMessage.isPlausible(s) else { return }
        guard let health else { ingest(batchId: UUID().uuidString, [s]); return }
        _ = await health.save(batchId: UUID().uuidString, [s])
    }

    /// Menstrual flow (and any symptoms) for [day], written straight to Apple Health.
    func logCycle(day: Date, flow: MenstrualFlow?, cycleStart: Bool, symptoms: [HealthDepthHK.Symptom: Int]) async -> String {
        let result = await HealthDepthHK.shared.saveCycle(day: day, flow: flow, cycleStart: cycleStart, symptoms: symptoms)
        if let flow, Calendar.current.isDateInToday(day) {
            today = today.rolled()
            today.flowLogged = flow.rawValue
            save(today, "hdToday")
        }
        return result
    }

    // MARK: Persistence

    private func save<T: Encodable>(_ v: T, _ key: String) {
        if let d = try? JSONEncoder().encode(v) { UserDefaults.standard.set(d, forKey: key) }
    }

    private static func load<T: Decodable>(_ t: T.Type, _ key: String) -> T? {
        UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(t, from: $0) }
    }
}

extension HealthDepthStore.PhoneToday {
    func rolled() -> Self {
        guard day != DaySummary.today() else { return self }
        var fresh = Self()
        fresh.counted = counted
        return fresh
    }
}

extension HealthSync {
    /// Lap and pause/resume events from a watch workout's "laps" [[start, end, m]] and
    /// "pauses" [[start, end]] (epoch ms), clipped to the workout. Malformed entries are skipped.
    static func workoutEvents(_ w: [String: Any], start: Date, end: Date) -> [HKWorkoutEvent] {
        func date(_ n: NSNumber) -> Date { Date(timeIntervalSince1970: n.doubleValue / 1000) }
        var out: [HKWorkoutEvent] = []
        for p in (w["pauses"] as? [[NSNumber]] ?? []).prefix(200) where p.count >= 2 {
            let a = date(p[0]), b = date(p[1])
            guard a >= start, b <= end, b >= a else { continue }
            out.append(HKWorkoutEvent(type: .pause, dateInterval: DateInterval(start: a, duration: 0), metadata: nil))
            out.append(HKWorkoutEvent(type: .resume, dateInterval: DateInterval(start: b, duration: 0), metadata: nil))
        }
        for l in (w["laps"] as? [[NSNumber]] ?? []).prefix(200) where l.count >= 2 {
            let a = date(l[0]), b = date(l[1])
            guard a >= start, b <= end, b > a else { continue }
            out.append(HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: a, end: b), metadata: nil))
        }
        return out.sorted { $0.dateInterval.start < $1.dateInterval.start }
    }
}

/// HealthKit writes this feature owns outright (cycle symptoms, iPhone-logged flow), plus the
/// mappings HealthSync uses for the new sample kinds.
final class HealthDepthHK: @unchecked Sendable {
    static let shared = HealthDepthHK()
    private let store = HKHealthStore()

    /// Cycle symptoms, all HealthKit severity-valued category types.
    enum Symptom: String, CaseIterable, Identifiable {
        case cramps, bloating, headache, fatigue, backPain, acne
        var id: String { rawValue }
        var title: String {
            switch self {
            case .cramps: return "Cramps"
            case .bloating: return "Bloating"
            case .headache: return "Headache"
            case .fatigue: return "Fatigue"
            case .backPain: return "Lower back pain"
            case .acne: return "Acne"
            }
        }
        var type: HKCategoryType {
            switch self {
            case .cramps: return HKCategoryType(.abdominalCramps)
            case .bloating: return HKCategoryType(.bloating)
            case .headache: return HKCategoryType(.headache)
            case .fatigue: return HKCategoryType(.fatigue)
            case .backPain: return HKCategoryType(.lowerBackPain)
            case .acne: return HKCategoryType(.acne)
            }
        }
    }

    static let flowType = HKCategoryType(.menstrualFlow)

    /// Extra types to request write access for (the SampleKind types are requested by HealthSync).
    static var shareTypes: Set<HKSampleType> { Set(Symptom.allCases.map(\.type)) }

    static func quantityType(for kind: SampleKind) -> HKQuantityType? {
        switch kind {
        case .hrv: return HKQuantityType(.heartRateVariabilitySDNN)
        case .water: return HKQuantityType(.dietaryWater)
        case .caffeine: return HKQuantityType(.dietaryCaffeine)
        case .sleepingHeartRate: return HKQuantityType(.heartRate)
        default: return nil
        }
    }

    static func quantity(for kind: SampleKind, value: Double) -> HKQuantity? {
        switch kind {
        case .hrv: return HKQuantity(unit: .secondUnit(with: .milli), doubleValue: value)
        case .water: return HKQuantity(unit: .literUnit(with: .milli), doubleValue: value)
        case .caffeine: return HKQuantity(unit: .gramUnit(with: .milli), doubleValue: value)
        case .sleepingHeartRate: return HKQuantity(unit: .count().unitDivided(by: .minute()), doubleValue: value)
        default: return nil
        }
    }

    /// Extra metadata per kind (merged into the sync-identifier metadata).
    static func metadata(for kind: SampleKind) -> [String: Any] {
        switch kind {
        case .sleepingHeartRate: return [HKMetadataKeyHeartRateMotionContext: HKHeartRateMotionContext.sedentary.rawValue]
        case .menstrualFlow: return [HKMetadataKeyMenstrualCycleStart: false]
        default: return [:]
        }
    }

    /// A flow sample spans the local day it was logged on.
    static func flowSample(_ item: HealthSample, device: HKDevice?, metadata: [String: Any]) -> HKCategorySample {
        let start = Calendar.current.startOfDay(for: item.startDate)
        let end = Calendar.current.date(byAdding: .day, value: 1, to: start) ?? start
        return HKCategorySample(type: flowType, value: Int(item.value.rounded()), start: start, end: end,
                                device: device, metadata: metadata)
    }

    func saveCycle(day: Date, flow: MenstrualFlow?, cycleStart: Bool, symptoms: [Symptom: Int]) async -> String {
        guard HKHealthStore.isHealthDataAvailable() else { return "Apple Health isn't available" }
        let start = Calendar.current.startOfDay(for: day)
        let end = Calendar.current.date(byAdding: .day, value: 1, to: start) ?? start
        let stamp = Int(start.timeIntervalSince1970)
        var types: Set<HKSampleType> = Self.shareTypes
        types.insert(Self.flowType)
        try? await store.requestAuthorization(toShare: types, read: [])
        var samples: [HKSample] = []
        if let flow, store.authorizationStatus(for: Self.flowType) == .sharingAuthorized {
            samples.append(HKCategorySample(type: Self.flowType, value: Int(flow.rawValue), start: start, end: end, metadata: [
                HKMetadataKeyMenstrualCycleStart: cycleStart,
                HKMetadataKeySyncIdentifier: "gb-flow-day-\(stamp)", HKMetadataKeySyncVersion: 1,
            ]))
        }
        for (s, severity) in symptoms where (0...4).contains(severity) {
            guard store.authorizationStatus(for: s.type) == .sharingAuthorized else { continue }
            samples.append(HKCategorySample(type: s.type, value: severity, start: start, end: end, metadata: [
                HKMetadataKeySyncIdentifier: "gb-\(s.rawValue)-\(stamp)", HKMetadataKeySyncVersion: 1,
            ]))
        }
        guard !samples.isEmpty else { return "Allow cycle tracking in Apple Health to save this" }
        do {
            try await store.save(samples)
            return "Saved to Apple Health"
        } catch {
            return error.localizedDescription
        }
    }
}
