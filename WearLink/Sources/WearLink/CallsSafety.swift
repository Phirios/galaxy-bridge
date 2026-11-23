import Foundation

/// Calls, messaging and safety features (Wave B) carried as GBFeature "raw" messages.
///
/// Phone → watch: `callFavourites`, `callLogGet`, `safetyConfig`, `safetyGet`, `findLoud`, `quickReplies`.
/// Watch → phone: `callLog`, `safetyState`, `sosAlert`, `hrAlert`, `findLoud`, `quickReply`.
/// The Kotlin side is `CallsSafety.kt` in watch-app/ and must stay in step with this file.
public enum CallsSafetyLimits {
    public static let maxFavourites = 12
    public static let maxQuickReplies = 8
    public static let maxQuickReplyLength = 60
    public static let maxLogEntries = 30
    public static let countdownRange = 15...60
    public static let hrHighRange = 100...220
    public static let hrLowRange = 30...70
    public static let checkInHoursRange = 1...12
}

/// Numbers the app refuses to auto-dial. Automatic calls (fall detection, SOS) only ever go to a
/// personal contact the user picked, never to a public emergency service: those calls need a
/// human on the line, and a false alarm there wastes a dispatcher's time.
public enum EmergencyNumbers {
    /// Public emergency and service short codes used around the world (NZ 111, US 911, EU 112, AU 000…).
    public static let blocked: Set<String> = [
        "000", "08", "100", "101", "102", "103", "108", "110", "111", "112", "113", "117", "118", "119",
        "122", "123", "999", "911", "990", "991", "995", "997", "998", "106", "190", "191", "192", "193", "197",
    ]

    /// Digits only (a leading "+" is dropped), so "1-1-1" and "+112" both match.
    public static func digits(_ number: String) -> String {
        number.filter(\.isNumber)
    }

    /// True for public emergency numbers and any short code (fewer than 5 digits).
    public static func isPublicEmergency(_ number: String) -> Bool {
        let d = digits(number)
        return d.count < 5 || blocked.contains(d)
    }

    /// A number the watch may be asked to call automatically.
    public static func isAllowedForAutoCall(_ number: String) -> Bool {
        let trimmed = number.trimmingCharacters(in: .whitespaces)
        guard trimmed.count <= 32, trimmed.range(of: "^[0-9+(][0-9 ().-]*$", options: .regularExpression) != nil else { return false }
        return !isPublicEmergency(trimmed)
    }
}

/// Safety settings the iPhone sends to the watch. Everything defaults off.
public struct SafetyConfig: Codable, Equatable {
    public var contactName: String = ""
    public var contactNumber: String = ""
    public var fallDetection = false
    public var sos = false
    public var countdown = 30
    public var hrAlerts = false
    public var hrHigh = 150
    public var hrLow = 40
    public var checkIn = false
    public var checkInHours = 3

    public init() {}

    /// Has a contact the watch is allowed to call.
    public var hasContact: Bool { EmergencyNumbers.isAllowedForAutoCall(contactNumber) }

    /// The config with every number clamped into range, and auto-call features switched off if
    /// there is no allowed contact.
    public func sanitized() -> SafetyConfig {
        var c = self
        c.contactName = String(contactName.filter { !$0.isNewline }.prefix(60))
        c.contactNumber = String(contactNumber.prefix(32))
        c.countdown = min(max(countdown, CallsSafetyLimits.countdownRange.lowerBound), CallsSafetyLimits.countdownRange.upperBound)
        c.hrHigh = min(max(hrHigh, CallsSafetyLimits.hrHighRange.lowerBound), CallsSafetyLimits.hrHighRange.upperBound)
        c.hrLow = min(max(hrLow, CallsSafetyLimits.hrLowRange.lowerBound), CallsSafetyLimits.hrLowRange.upperBound)
        c.checkInHours = min(max(checkInHours, CallsSafetyLimits.checkInHoursRange.lowerBound), CallsSafetyLimits.checkInHoursRange.upperBound)
        if !c.hasContact {
            c.contactNumber = ""
            c.fallDetection = false
            c.sos = false
            c.checkIn = false
        }
        return c
    }

    /// Body of the `safetyConfig` message.
    public var payload: [String: Any] {
        let c = sanitized()
        return [
            "name": c.contactName, "number": c.contactNumber,
            "fall": c.fallDetection, "sos": c.sos, "countdown": c.countdown,
            "hr": c.hrAlerts, "hrHigh": c.hrHigh, "hrLow": c.hrLow,
            "checkIn": c.checkIn, "checkInHours": c.checkInHours,
        ]
    }
}

public struct CallFavourite: Codable, Equatable, Identifiable, Hashable {
    public var name: String
    public var number: String
    public var id: String { number }

    public init(name: String, number: String) {
        self.name = name
        self.number = number
    }

    public static func payload(_ items: [CallFavourite]) -> [String: Any] {
        ["items": items.prefix(CallsSafetyLimits.maxFavourites).map { ["n": String($0.name.prefix(60)), "p": String($0.number.prefix(32))] }]
    }
}

/// One call the watch saw (over HFP or as an iPhone call notification) or placed.
public struct CallLogEntry: Equatable, Identifiable {
    public enum Direction: String { case incoming = "in", outgoing = "out", missed = "missed" }
    public var name: String
    public var number: String
    public var direction: Direction
    public var at: Date
    public var id: String { "\(at.timeIntervalSince1970)-\(number)-\(name)" }

    public init(name: String, number: String, direction: Direction, at: Date) {
        self.name = name
        self.number = number
        self.direction = direction
        self.at = at
    }

    /// Parses a `callLog` message body, dropping malformed entries.
    public static func parse(_ body: [String: Any]) -> [CallLogEntry] {
        (body["items"] as? [[String: Any]] ?? []).prefix(CallsSafetyLimits.maxLogEntries).compactMap { o in
            guard let dir = (o["dir"] as? String).flatMap(Direction.init(rawValue:)),
                  let at = (o["at"] as? NSNumber)?.int64Value, at > 0 else { return nil }
            let name = String((o["n"] as? String ?? "").prefix(60))
            let number = String((o["p"] as? String ?? "").prefix(32))
            guard !(name.isEmpty && number.isEmpty) else { return nil }
            return CallLogEntry(name: name, number: number, direction: dir, at: Date(timeIntervalSince1970: Double(at) / 1000))
        }
    }
}

/// `sosAlert` from the watch: an SOS, fall or missed check-in, at some stage of its countdown.
public struct SosAlert: Equatable {
    public enum Kind: String { case sos, fall, checkIn = "checkin" }
    public enum Stage: String { case countdown, calling, cancelled, location }
    public var kind: Kind
    public var stage: Stage
    public var latitude: Double?
    public var longitude: Double?
    public var accuracy: Double?
    public var at: Date

    public init(kind: Kind, stage: Stage, latitude: Double? = nil, longitude: Double? = nil, accuracy: Double? = nil, at: Date) {
        self.kind = kind
        self.stage = stage
        self.latitude = latitude
        self.longitude = longitude
        self.accuracy = accuracy
        self.at = at
    }

    public var hasLocation: Bool { latitude != nil && longitude != nil }

    public static func parse(_ b: [String: Any], now: Date = Date()) -> SosAlert? {
        guard let kind = (b["kind"] as? String).flatMap(Kind.init(rawValue:)),
              let stage = (b["stage"] as? String).flatMap(Stage.init(rawValue:)) else { return nil }
        var a = SosAlert(kind: kind, stage: stage, at: now)
        if let lat = (b["lat"] as? NSNumber)?.doubleValue, let lon = (b["lon"] as? NSNumber)?.doubleValue,
           lat.isFinite, lon.isFinite, (-90...90).contains(lat), (-180...180).contains(lon) {
            a.latitude = lat
            a.longitude = lon
            if let acc = (b["acc"] as? NSNumber)?.doubleValue, acc.isFinite, acc >= 0 { a.accuracy = min(acc, 100_000) }
        }
        return a
    }

    /// An Apple Maps link to the reported location.
    public var mapsURL: URL? {
        guard let latitude, let longitude else { return nil }
        return URL(string: String(format: "https://maps.apple.com/?ll=%.6f,%.6f&q=Watch%%20location", latitude, longitude))
    }
}

/// `hrAlert` from the watch: the heart rate stayed above/below the user's threshold.
public struct HrAlert: Equatable {
    public enum Kind: String { case high, low }
    public var kind: Kind
    public var bpm: Int
    public var threshold: Int
    public var at: Date

    public static func parse(_ b: [String: Any], now: Date = Date()) -> HrAlert? {
        guard let kind = (b["kind"] as? String).flatMap(Kind.init(rawValue:)),
              let bpm = (b["bpm"] as? NSNumber)?.intValue, (25...250).contains(bpm) else { return nil }
        let threshold = (b["limit"] as? NSNumber)?.intValue ?? 0
        return HrAlert(kind: kind, bpm: bpm, threshold: threshold, at: now)
    }
}

/// Builds the `sms:` URL that opens Messages with a prefilled body. iOS never sends it by itself:
/// the user still taps Send.
public func prefilledMessageURL(to number: String, body: String) -> URL? {
    var allowed = CharacterSet.urlQueryAllowed
    allowed.remove(charactersIn: "&=+?")
    let to = number.filter { $0.isNumber || $0 == "+" }
    guard let text = body.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
    return URL(string: "sms:\(to)&body=\(text)")
}
