import ActivityKit
import SwiftUI
import WidgetKit

/// Home Screen / Lock Screen widget and Live Activity for the watch's heart rate, steps and battery.
/// Data comes from the app through the App Group (see WatchSnapshot.swift); the extension never
/// talks to the watch itself.
@main
struct GalaxyBridgeWidgets: WidgetBundle {
    var body: some Widget {
        WatchStatusWidget()
        WatchLiveActivity()
    }
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WatchSnapshot
}

struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: Date(), snapshot: WatchSnapshot(heartRate: 72, steps: 6_400, battery: 80, connected: true, updated: Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : SnapshotEntry(date: Date(), snapshot: .load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        // The app reloads timelines when readings change; this refresh only ages the "updated" label.
        let entry = SnapshotEntry(date: Date(), snapshot: .load())
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60))))
    }
}

struct WatchStatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "WatchStatus", provider: SnapshotProvider()) { entry in
            WatchStatusView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Galaxy Watch")
        .description("Heart rate, steps and battery from your watch.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

private func text(_ v: Int?) -> String { v.map { $0.formatted() } ?? "–" }

struct WatchStatusView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SnapshotEntry
    private var s: WatchSnapshot { entry.snapshot }

    var body: some View {
        switch family {
        case .accessoryInline:
            Text("♥ \(text(s.heartRate))  ⌚︎ \(text(s.battery))%")
        case .accessoryCircular:
            Gauge(value: Double(s.battery ?? 0), in: 0...100) {
                Image(systemName: "applewatch")
            } currentValueLabel: {
                Text(text(s.heartRate))
            }
            .gaugeStyle(.accessoryCircular)
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 2) {
                Label("\(text(s.heartRate)) bpm", systemImage: "heart.fill").font(.headline)
                Text("\(text(s.steps)) steps · \(text(s.battery))%").font(.caption)
            }
        case .systemMedium:
            VStack(spacing: 8) {
                HStack(spacing: 14) {
                    metric("heart.fill", .red, text(s.heartRate), "bpm")
                    metric("figure.walk", .green, text(s.steps), "steps")
                    metric(s.charging ? "battery.100.bolt" : "applewatch", .blue, text(s.battery), "%")
                }
                HStack {
                    Spacer()
                    footer
                }
            }
        default:
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "heart.fill")
                        .foregroundStyle(.red)
                        .accessibilityHidden(true)
                    Text(text(s.heartRate))
                        .font(.system(.title, design: .rounded).weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(.primary)
                    Text("bpm")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                Label("\(text(s.steps)) steps", systemImage: "figure.walk")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.green)
                Label("\(text(s.battery))%", systemImage: s.charging ? "battery.100.bolt" : "applewatch")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.blue)
                Spacer(minLength: 0)
                footer
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var footer: some View {
        Group {
            if s.updated == .distantPast { Text("Open the app once") }
            else { Text(s.updated, style: .relative) + Text(" ago") }
        }
        .font(.caption2)
        .foregroundStyle(s.isFresh ? .secondary : .tertiary)
    }

    private func metric(_ symbol: String, _ color: Color, _ value: String, _ unit: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol)
                .foregroundStyle(color)
                .accessibilityHidden(true)
            Text(value)
                .font(.system(.title3, design: .rounded).weight(.bold))
                .monospacedDigit()
                .foregroundStyle(.primary)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            Text(unit)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

struct WatchLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WatchActivityAttributes.self) { context in
            let s = context.state
            HStack(spacing: 16) {
                Image(systemName: "applewatch")
                    .font(.title2)
                    .foregroundStyle(.blue)
                    .accessibilityHidden(true)
                stat("heart.fill", .red, text(s.heartRate), "bpm")
                stat("figure.walk", .green, text(s.steps), "steps")
                stat("battery.75percent", .blue, text(s.battery), "%")
            }
            .padding()
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(text(s.heartRate), systemImage: "heart.fill")
                        .foregroundStyle(.red)
                        .font(.title3.bold())
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Label("\(text(s.battery))%", systemImage: "applewatch")
                        .foregroundStyle(.blue)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text("\(text(s.steps)) steps today · \(s.connected ? "connected" : "out of reach")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "heart.fill").foregroundStyle(.red)
            } compactTrailing: {
                Text(text(s.heartRate)).foregroundStyle(.red)
            } minimal: {
                Text(text(s.heartRate)).foregroundStyle(.red)
            }
        }
    }

    private func stat(_ symbol: String, _ color: Color, _ value: String, _ unit: String) -> some View {
        VStack(spacing: 2) {
            Image(systemName: symbol)
                .foregroundStyle(color)
                .accessibilityHidden(true)
            Text(value)
                .font(.system(.headline, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.primary)
            Text(unit)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}
