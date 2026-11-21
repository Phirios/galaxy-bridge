import Foundation
import CoreLocation
import HealthKit
import WearLink

/// Writes watch samples into Apple Health. Writes are idempotent: each sample carries a
/// sync identifier, so a batch re-sent after a lost ack replaces rather than duplicates.
@MainActor
final class HealthSync: ObservableObject {
    @Published private(set) var authorized = false
    @Published private(set) var lastSync: Date?
    @Published private(set) var savedCount = 0
    @Published private(set) var lastError: String?
    /// Summary for the dashboard only (Apple Health remains the source of truth).
    @Published private(set) var summary = DaySummary.load()
    @Published var lastWorkout: String?

    private let store = HKHealthStore()

    private static let sleepType = HKCategoryType(.sleepAnalysis)

    /// The HealthKit quantity type a kind writes to, or nil for sleep (a category type).
    private static func quantityType(for kind: SampleKind) -> HKQuantityType? {
        switch kind {
        case .heartRate: return HKQuantityType(.heartRate)
        case .restingHeartRate: return HKQuantityType(.restingHeartRate)
        case .steps: return HKQuantityType(.stepCount)
        case .activeEnergy: return HKQuantityType(.activeEnergyBurned)
        case .distance: return HKQuantityType(.distanceWalkingRunning)
        case .floors: return HKQuantityType(.flightsClimbed)
        case .spo2: return HKQuantityType(.oxygenSaturation)
        case .skinTemp: return HKQuantityType(.bodyTemperature)
        case .sleep: return nil
        }
    }

    /// The object type used for authorization (quantity, or the sleep category type).
    private static func objectType(for kind: SampleKind) -> HKSampleType {
        quantityType(for: kind) ?? sleepType
    }

    /// Builds the HealthKit quantity for a kind, converting units where HealthKit differs from the
    /// wire value (oxygen saturation is a 0–1 fraction; the watch sends a percentage).
    private static func quantity(for kind: SampleKind, value: Double) -> HKQuantity {
        switch kind {
        case .heartRate, .restingHeartRate:
            return HKQuantity(unit: .count().unitDivided(by: .minute()), doubleValue: value)
        case .steps, .floors: return HKQuantity(unit: .count(), doubleValue: value)
        case .activeEnergy: return HKQuantity(unit: .kilocalorie(), doubleValue: value)
        case .distance: return HKQuantity(unit: .meter(), doubleValue: value)
        case .spo2: return HKQuantity(unit: .percent(), doubleValue: value / 100)
        case .skinTemp: return HKQuantity(unit: .degreeCelsius(), doubleValue: value)
        case .sleep: return HKQuantity(unit: .count(), doubleValue: value) // unused (category)
        }
    }

    /// Maps a sleep sample's value to a HealthKit sleep-analysis category value.
    private static func sleepValue(_ value: Double) -> HKCategoryValueSleepAnalysis {
        switch SleepStage(rawValue: value) {
        case .inBed: return .inBed
        case .awake: return .awake
        case .deep: return .asleepDeep
        default: return .asleepUnspecified
        }
    }

    func requestAccess() async {
        guard HKHealthStore.isHealthDataAvailable() else {
            lastError = "Apple Health isn't available on this device"
            return
        }
        // Write-only: this app never reads anyone's existing health data.
        var types: Set<HKSampleType> = Set(SampleKind.allCases.map(Self.objectType(for:)))
        types.insert(HKObjectType.workoutType())
        types.insert(HKSeriesType.workoutRoute())
        // Body composition (from the watch's BIA sensor); optional, like the other sensors.
        types.insert(HKQuantityType(.bodyFatPercentage))
        types.insert(HKQuantityType(.leanBodyMass))
        do {
            try await store.requestAuthorization(toShare: types, read: [])
            // The core activity types gate "authorized"; the Samsung-only sensors (SpO₂, skin
            // temperature) are optional and may be individually declined without blocking sync.
            let core: [SampleKind] = [.heartRate, .steps, .activeEnergy, .distance, .floors]
            authorized = core.allSatisfy {
                store.authorizationStatus(for: Self.objectType(for: $0)) == .sharingAuthorized
            }
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Returns true once the batch is settled: every sample is stored, or belongs to a type the
    /// user chose not to share. Returns false (so the watch keeps it) if Health hasn't been set up.
    func save(batchId: String, _ items: [HealthSample]) async -> Bool {
        let device = HKDevice(name: "Galaxy Watch", manufacturer: "Samsung", model: nil,
                              hardwareVersion: nil, firmwareVersion: nil, softwareVersion: nil,
                              localIdentifier: nil, udiDeviceIdentifier: nil)
        var samples: [HKSample] = []
        for item in items {
            let type = Self.objectType(for: item.kind)
            switch store.authorizationStatus(for: type) {
            case .sharingAuthorized: break
            case .sharingDenied: continue // The user's choice; drop rather than retry forever.
            default:
                lastError = "Allow Wear OS Bridge to write to Apple Health"
                return false
            }
            let metadata: [String: Any] = [
                HKMetadataKeySyncIdentifier: "gb-\(item.kind.rawValue)-\(item.start)-\(item.end)",
                HKMetadataKeySyncVersion: 1,
            ]
            if item.kind == .sleep {
                samples.append(HKCategorySample(
                    type: Self.sleepType, value: Self.sleepValue(item.value).rawValue,
                    start: item.startDate, end: item.endDate, device: device, metadata: metadata))
            } else {
                samples.append(HKQuantitySample(
                    type: Self.quantityType(for: item.kind)!,
                    quantity: Self.quantity(for: item.kind, value: item.value),
                    start: item.startDate, end: item.endDate, device: device, metadata: metadata))
            }
        }
        if samples.isEmpty { return true }
        do {
            try await store.save(samples)
            summary.add(batchId: batchId, items)
            summary.save()
            savedCount += samples.count
            lastSync = Date()
            lastError = nil
            return true
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    /// Writes the parts of a body-composition reading that HealthKit accepts from third parties:
    /// body-fat percentage and lean (fat-free) mass. Water, muscle and BMR have no writable
    /// HealthKit type, so they're shown in-app only. Each field is skipped if absent or denied.
    func saveBodyComposition(_ bc: BodyComposition) async {
        let device = HKDevice(name: "Galaxy Watch", manufacturer: "Samsung", model: nil,
                              hardwareVersion: nil, firmwareVersion: nil, softwareVersion: nil,
                              localIdentifier: nil, udiDeviceIdentifier: nil)
        let at = bc.at
        var samples: [HKSample] = []
        func add(_ id: HKQuantityTypeIdentifier, _ unit: HKUnit, _ value: Double?, _ tag: String) {
            guard let value, value.isFinite, value > 0 else { return }
            let type = HKQuantityType(id)
            guard store.authorizationStatus(for: type) == .sharingAuthorized else { return }
            samples.append(HKQuantitySample(
                type: type, quantity: HKQuantity(unit: unit, doubleValue: value),
                start: at, end: at, device: device,
                metadata: [HKMetadataKeySyncIdentifier: "gb-\(tag)-\(Int(at.timeIntervalSince1970))",
                           HKMetadataKeySyncVersion: 1]))
        }
        add(.bodyFatPercentage, .percent(), bc.fatPct.map { $0 / 100.0 }, "bodyfat")
        add(.leanBodyMass, .gramUnit(with: .kilo), bc.leanKg, "leanmass")
        guard !samples.isEmpty else { return }
        do { try await store.save(samples); savedCount += samples.count; lastSync = Date(); lastError = nil }
        catch { lastError = error.localizedDescription }
    }
}

// MARK: Body profile (for the watch's BIA reading)

extension HealthSync {
    /// The body profile a BIA (body-composition) reading needs: height (cm), weight (kg),
    /// gender (1 = male, 0 = female), and age (years). Read from Apple Health characteristics
    /// where possible; any field that isn't available comes back nil for manual entry on the phone.
    struct BiaProfileInput { var heightCm: Double?; var weightKg: Double?; var gender: Int?; var age: Int? }

    func readBiaProfile() async -> BiaProfileInput {
        var out = BiaProfileInput()
        guard HKHealthStore.isHealthDataAvailable() else { return out }
        let height = HKQuantityType(.height)
        let bodyMass = HKQuantityType(.bodyMass)
        let read: Set<HKObjectType> = [
            HKCharacteristicType(.biologicalSex), HKCharacteristicType(.dateOfBirth), height, bodyMass,
        ]
        // Reading characteristics needs read authorization (NSHealthShareUsageDescription).
        try? await store.requestAuthorization(toShare: [], read: read)

        if let sex = try? store.biologicalSex().biologicalSex {
            switch sex {
            case .male: out.gender = 1
            case .female: out.gender = 0
            default: break // .other/.notSet → leave nil for manual entry
            }
        }
        if let dob = try? store.dateOfBirthComponents(), let year = dob.year {
            let nowYear = Calendar.current.component(.year, from: Date())
            let age = nowYear - year
            if (1...130).contains(age) { out.age = age }
        }
        out.heightCm = await latestQuantity(height, unit: HKUnit.meterUnit(with: .centi))
        out.weightKg = await latestQuantity(bodyMass, unit: .gramUnit(with: .kilo))
        return out
    }

    /// The most recent sample of a quantity type, in [unit], or nil.
    private func latestQuantity(_ type: HKQuantityType, unit: HKUnit) async -> Double? {
        await withCheckedContinuation { cont in
            let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
            let q = HKSampleQuery(sampleType: type, predicate: nil, limit: 1, sortDescriptors: [sort]) { _, samples, _ in
                let v = (samples?.first as? HKQuantitySample)?.quantity.doubleValue(for: unit)
                cont.resume(returning: v.flatMap { $0.isFinite && $0 > 0 ? $0 : nil })
            }
            store.execute(q)
        }
    }
}

// MARK: Workouts

extension HealthSync {
    /// Saves a workout recorded on the watch, with its GPS route. Heart rate, energy and distance
    /// during the workout already arrive through the normal sync, so the workout carries only
    /// its totals (adding samples here would count them twice). Idempotent by workout id.
    func saveWorkout(_ w: [String: Any]) async -> Bool {
        guard let id = w["id"] as? String, UUID(uuidString: id) != nil,
              let start = (w["start"] as? NSNumber)?.int64Value, let end = (w["end"] as? NSNumber)?.int64Value,
              end > start, end - start < 24 * 3600 * 1000 else { return true } // malformed: drop it
        let kind = w["kind"] as? String ?? "other"
        let kcal = min(max((w["kcal"] as? NSNumber)?.doubleValue ?? 0, 0), 20_000)
        let dist = min(max((w["dist"] as? NSNumber)?.doubleValue ?? 0, 0), 500_000)
        let type: HKWorkoutActivityType = switch kind {
        case "run": .running
        case "walk": .walking
        case "cycle": .cycling
        case "hike": .hiking
        default: .other
        }
        guard store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized else {
            lastError = "Allow Wear OS Bridge to save workouts in Apple Health"
            return false
        }
        let startDate = Date(timeIntervalSince1970: Double(start) / 1000)
        let endDate = Date(timeIntervalSince1970: Double(end) / 1000)
        let device = HKDevice(name: "Galaxy Watch", manufacturer: "Samsung", model: nil, hardwareVersion: nil,
                              firmwareVersion: nil, softwareVersion: nil, localIdentifier: nil, udiDeviceIdentifier: nil)
        let metadata: [String: Any] = [HKMetadataKeySyncIdentifier: "gb-workout-\(id)", HKMetadataKeySyncVersion: 1]
        let workout = HKWorkout(activityType: type, start: startDate, end: endDate, duration: endDate.timeIntervalSince(startDate),
                                totalEnergyBurned: kcal > 0 ? HKQuantity(unit: .kilocalorie(), doubleValue: kcal) : nil,
                                totalDistance: dist > 0 ? HKQuantity(unit: .meter(), doubleValue: dist) : nil,
                                device: device, metadata: metadata)
        do {
            try await store.save(workout)
            let points = (w["route"] as? [[NSNumber]] ?? []).compactMap { p -> CLLocation? in
                guard p.count >= 3 else { return nil }
                let lat = p[1].doubleValue, lon = p[2].doubleValue
                guard (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
                return CLLocation(coordinate: .init(latitude: lat, longitude: lon),
                                  altitude: p.count > 3 ? p[3].doubleValue : 0, horizontalAccuracy: 10, verticalAccuracy: 15,
                                  timestamp: Date(timeIntervalSince1970: p[0].doubleValue / 1000))
            }
            if !points.isEmpty, store.authorizationStatus(for: HKSeriesType.workoutRoute()) == .sharingAuthorized {
                let route = HKWorkoutRouteBuilder(healthStore: store, device: device)
                try await route.insertRouteData(points)
                _ = try await route.finishRoute(with: workout, metadata: nil)
            }
            lastWorkout = workout.workoutActivityType == .other ? "Workout" : kind.capitalized
            lastError = nil
            return true
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
}

/// Today's totals and the latest heart rate, kept small and on-device.
/// Remembers recent batch IDs so a batch re-sent after a lost ack isn't counted twice.
struct DaySummary: Codable, Equatable {
    var day: String
    var heartRate: Double?
    var heartRateAt: Date?
    /// Latest blood-oxygen reading (percent) and when it was measured, for the live card.
    var spo2: Double?
    var spo2At: Date?
    /// Latest skin/wrist temperature (°C) and resting heart rate (bpm); newest-wins like spo2.
    var skinTemp: Double?
    var skinTempAt: Date?
    var restingHeartRate: Double?
    var restingHeartRateAt: Date?
    var steps: Double = 0
    var distance: Double = 0
    var energy: Double = 0
    var floors: Double = 0
    var countedBatches: [String] = []

    private static let key = "daySummary"

    static func today() -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return "\(c.year!)-\(c.month!)-\(c.day!)"
    }

    static func load() -> DaySummary {
        if let data = UserDefaults.standard.data(forKey: key),
           let s = try? JSONDecoder().decode(DaySummary.self, from: data) {
            return s.rolled()
        }
        return DaySummary(day: today())
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: Self.key) }
    }

    /// Keeps the last heart rate but resets daily totals at midnight.
    func rolled() -> DaySummary {
        guard day != Self.today() else { return self }
        var fresh = DaySummary(day: Self.today())
        fresh.heartRate = heartRate
        fresh.heartRateAt = heartRateAt
        fresh.spo2 = spo2
        fresh.spo2At = spo2At
        fresh.skinTemp = skinTemp
        fresh.skinTempAt = skinTempAt
        fresh.restingHeartRate = restingHeartRate
        fresh.restingHeartRateAt = restingHeartRateAt
        fresh.countedBatches = countedBatches
        return fresh
    }

    mutating func add(batchId: String, _ items: [HealthSample]) {
        self = rolled()
        guard !countedBatches.contains(batchId) else { return }
        countedBatches.append(batchId)
        if countedBatches.count > 500 { countedBatches.removeFirst(countedBatches.count - 500) }
        let startOfDay = Calendar.current.startOfDay(for: Date())
        for item in items {
            switch item.kind {
            case .heartRate:
                if heartRateAt.map({ item.endDate > $0 }) ?? true {
                    heartRate = item.value
                    heartRateAt = item.endDate
                }
            case .spo2:
                if spo2At.map({ item.endDate > $0 }) ?? true {
                    spo2 = item.value
                    spo2At = item.endDate
                }
            case .steps where item.startDate >= startOfDay: steps += item.value
            case .distance where item.startDate >= startOfDay: distance += item.value
            case .activeEnergy where item.startDate >= startOfDay: energy += item.value
            case .floors where item.startDate >= startOfDay: floors += item.value
            case .skinTemp:
                if skinTempAt.map({ item.endDate > $0 }) ?? true {
                    skinTemp = item.value
                    skinTempAt = item.endDate
                }
            case .restingHeartRate:
                if restingHeartRateAt.map({ item.endDate > $0 }) ?? true {
                    restingHeartRate = item.value
                    restingHeartRateAt = item.endDate
                }
            default: break
            }
        }
    }
}
