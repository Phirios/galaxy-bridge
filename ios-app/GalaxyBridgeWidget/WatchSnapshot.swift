import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

/// The latest watch readings, shared between the app and its widget extension through the App
/// Group. The app writes it whenever HR, steps or the watch battery change; the widget reads it.
/// Compiled into both targets.
struct WatchSnapshot: Codable, Equatable {
    var heartRate: Int?
    var steps: Int?
    var battery: Int?
    var charging = false
    var connected = false
    var updated = Date.distantPast

    static let appGroup = "group.nz.galaxybridge.app"
    private static let key = "watchSnapshot"

    static var defaults: UserDefaults? { UserDefaults(suiteName: appGroup) }

    static func load() -> WatchSnapshot {
        guard let data = defaults?.data(forKey: key),
              let s = try? JSONDecoder().decode(WatchSnapshot.self, from: data) else { return WatchSnapshot() }
        return s
    }

    func save() {
        guard let data = try? JSONEncoder().encode(self) else { return }
        Self.defaults?.set(data, forKey: Self.key)
    }

    /// Readings older than this are shown as stale.
    var isFresh: Bool { Date().timeIntervalSince(updated) < 30 * 60 }
}

#if canImport(ActivityKit)
/// Live Activity: watch heart rate, steps and battery on the Lock Screen and in the Dynamic Island.
struct WatchActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var heartRate: Int?
        var steps: Int?
        var battery: Int?
        var connected: Bool
        var updated: Date
    }

    var watchName: String
}
#endif
