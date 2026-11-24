import SwiftUI

// MARK: Native surfaces & Liquid Glass

extension View {
    /// Native Apple inset-grouped card surface (adapts to Light, Dark, and High Contrast).
    func insetCard(cornerRadius: CGFloat = 16) -> some View {
        background(
            Color(.secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        )
    }

    /// Lets the iOS 26+ Liquid Glass tab bar minimize while scrolling; no-op on iOS 17–25.
    @ViewBuilder
    func nativeTabBarMinimize() -> some View {
        if #available(iOS 26, *) {
            tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
    }

    /// Liquid Glass capsule on iOS 26+, system material capsule on iOS 17–25.
    @ViewBuilder
    func nativeGlassCapsule(interactive: Bool = false) -> some View {
        if #available(iOS 26, *) {
            glassEffect(interactive ? .regular.interactive() : .regular, in: .capsule)
        } else {
            background(.regularMaterial, in: Capsule())
        }
    }

    /// Liquid Glass circle on iOS 26+, system material circle on iOS 17–25.
    @ViewBuilder
    func nativeGlassCircle(interactive: Bool = false) -> some View {
        if #available(iOS 26, *) {
            glassEffect(interactive ? .regular.interactive() : .regular, in: .circle)
        } else {
            background(.regularMaterial, in: Circle())
        }
    }

    /// Liquid Glass rounded surface on iOS 26+, system material on iOS 17–25.
    @ViewBuilder
    func nativeGlassSurface(cornerRadius: CGFloat = 16, interactive: Bool = false) -> some View {
        if #available(iOS 26, *) {
            glassEffect(interactive ? .regular.interactive() : .regular,
                        in: .rect(cornerRadius: cornerRadius, style: .continuous))
        } else {
            background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }

    /// Liquid Glass button style on iOS 26+, bordered button style on iOS 17–25.
    @ViewBuilder
    func nativeGlassButton(prominent: Bool = false) -> some View {
        if #available(iOS 26, *) {
            if prominent {
                buttonStyle(.glassProminent)
            } else {
                buttonStyle(.glass)
            }
        } else {
            if prominent {
                buttonStyle(.borderedProminent)
            } else {
                buttonStyle(.bordered)
            }
        }
    }
}

/// Apple Health-style summary card: tinted SF Symbol + category title, timestamp,
/// prominent Dynamic Type value + unit, and optional supplementary content (such as a chart).
struct HealthSummaryCard<Extra: View>: View {
    let title: String
    let symbol: String
    let color: Color
    let value: String
    let unit: String
    var timestamp: String? = nil
    var subtitle: String? = nil
    var showsChevron: Bool = false
    var largeValue: Bool = false
    @ViewBuilder var extra: () -> Extra

    init(
        title: String,
        symbol: String,
        color: Color,
        value: String,
        unit: String,
        timestamp: String? = nil,
        subtitle: String? = nil,
        showsChevron: Bool = false,
        largeValue: Bool = false,
        @ViewBuilder extra: @escaping () -> Extra = { EmptyView() }
    ) {
        self.title = title
        self.symbol = symbol
        self.color = color
        self.value = value
        self.unit = unit
        self.timestamp = timestamp
        self.subtitle = subtitle
        self.showsChevron = showsChevron
        self.largeValue = largeValue
        self.extra = extra
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Label(title, systemImage: symbol)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 4)
                if let timestamp {
                    Text(timestamp)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
            }

            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.system(largeValue ? .largeTitle : .title2, design: .rounded).weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
                    .contentTransition(.numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            extra()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .insetCard()
        .accessibilityElement(children: .combine)
    }
}

/// Settings-style rounded SF Symbol badge used in grouped lists.
struct SettingsIconBadge: View {
    let symbol: String
    let color: Color

    var body: some View {
        Image(systemName: symbol)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.white)
            .frame(width: 28, height: 28)
            .background(color, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .accessibilityHidden(true)
    }
}

// MARK: Theme (legacy — removed after screen migration)

enum Theme {
    static let accent = Color(red: 0.45, green: 0.40, blue: 1.0)
    static let accent2 = Color(red: 0.16, green: 0.86, blue: 0.90)
    static let accent3 = Color(red: 0.96, green: 0.36, blue: 0.86)
    static let heart = Color(red: 1.0, green: 0.27, blue: 0.40)
    static let steps = Color(red: 0.30, green: 0.92, blue: 0.52)
    static let distance = Color(red: 0.31, green: 0.67, blue: 1.0)
    static let energy = Color(red: 1.0, green: 0.60, blue: 0.20)
    static let ink = Color(red: 0.04, green: 0.04, blue: 0.10)

    static let headerGradient = LinearGradient(
        colors: [accent, accent2], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let brandGradient = LinearGradient(
        colors: [accent2, accent, accent3], startPoint: .leading, endPoint: .trailing)
}

// MARK: Background

/// A slow-moving aurora behind every screen.
struct AuroraBackground: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 20)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            let s = Float(sin(t / 7)), c = Float(cos(t / 9))
            if #available(iOS 18.0, *) {
                MeshGradient(width: 3, height: 3, points: [
                [0, 0], [0.5 + 0.08 * s, 0], [1, 0],
                [0, 0.5 + 0.06 * c], [0.5 + 0.12 * c, 0.45 + 0.1 * s], [1, 0.5 - 0.06 * s],
                [0, 1], [0.5 - 0.08 * s, 1], [1, 1],
            ], colors: [
                Theme.ink, Color(red: 0.13, green: 0.10, blue: 0.36), Theme.ink,
                Color(red: 0.03, green: 0.20, blue: 0.30), Color(red: 0.20, green: 0.10, blue: 0.42), Color(red: 0.24, green: 0.05, blue: 0.30),
                Theme.ink, Color(red: 0.02, green: 0.10, blue: 0.18), Theme.ink,
            ])

            } else {
                ZStack {
                    Theme.ink
                    RadialGradient(colors: [Color(red: 0.20, green: 0.10, blue: 0.42), .clear],
                                   center: UnitPoint(x: 0.5 + 0.12 * Double(c), y: 0.45 + 0.1 * Double(s)), startRadius: 0, endRadius: 420)
                    RadialGradient(colors: [Color(red: 0.03, green: 0.20, blue: 0.30), .clear],
                                   center: UnitPoint(x: 0, y: 0.5 + 0.06 * Double(c)), startRadius: 0, endRadius: 300)
                }
            }
        }
        .ignoresSafeArea()
    }
}

// MARK: Glass

extension View {
    /// Frosted glass panel with a soft gradient rim.
    func card(cornerRadius: CGFloat = 24) -> some View {
        background {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(LinearGradient(colors: [.white.opacity(0.28), .white.opacity(0.04)],
                                                     startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 18, y: 10)
        }
    }

    /// A screen in the app's style: aurora behind, transparent lists.
    func auroraScreen() -> some View {
        scrollContentBackground(.hidden).background(AuroraBackground())
    }
}

struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.caption.weight(.bold)).tracking(1.6)
            .foregroundStyle(.white.opacity(0.55))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 6)
    }
}

/// Round glass button with a glowing icon.
struct OrbButton: View {
    let title: String
    let symbol: String
    let color: Color
    var active = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                ZStack {
                    Circle().fill(.ultraThinMaterial)
                    Circle().fill(color.opacity(active ? 0.55 : 0.18))
                    Circle().strokeBorder(color.opacity(0.7), lineWidth: 1.2)
                    Image(systemName: symbol)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white)
                        .symbolEffect(.pulse, isActive: active)
                }
                .frame(width: 60, height: 60)
                .shadow(color: color.opacity(0.55), radius: active ? 16 : 8)
                Text(title).font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.9)).lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PressScale())
    }
}

struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

// MARK: Watch render

/// A Galaxy Watch4 Classic: rotating bezel with minute track, live analog hands,
/// and a battery arc.
struct WatchRender: View {
    let battery: Int?
    let connected: Bool
    /// A preview of the face that's active on the watch, when it has sent one.
    var face: UIImage? = nil
    var size: CGFloat = 230

    private var batteryColor: Color {
        guard let battery else { return .secondary }
        return battery <= 20 ? .orange : .green
    }

    var body: some View {
        ZStack {
            // Lugs
            ForEach([0.0, 180.0], id: \.self) { angle in
                RoundedRectangle(cornerRadius: size * 0.05)
                    .fill(LinearGradient(colors: [Color(white: 0.36), Color(white: 0.18)], startPoint: .leading, endPoint: .trailing))
                    .frame(width: size * 0.5, height: size * 0.22)
                    .offset(y: -size * 0.5)
                    .rotationEffect(.degrees(angle))
            }
            // Side buttons
            ForEach([-18.0, 18.0], id: \.self) { a in
                Capsule().fill(Color(white: 0.28)).frame(width: size * 0.07, height: size * 0.13)
                    .offset(x: size * 0.53).rotationEffect(.degrees(a))
            }

            // Case + bezel
            Circle()
                .fill(AngularGradient(colors: [Color(white: 0.55), Color(white: 0.20), Color(white: 0.44), Color(white: 0.16), Color(white: 0.55)],
                                      center: .center))
                .frame(width: size, height: size)
                .shadow(color: .black.opacity(0.22), radius: size * 0.06, y: size * 0.03)
            Circle()
                .fill(Color(white: 0.08))
                .frame(width: size * 0.9, height: size * 0.9)
            BezelTrack(size: size * 0.9)

            // Dial
            Circle()
                .fill(Color(white: 0.05))
                .frame(width: size * 0.72, height: size * 0.72)
            Circle()
                .trim(from: 0, to: CGFloat(battery ?? 0) / 100)
                .stroke(batteryColor, style: StrokeStyle(lineWidth: size * 0.022, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: size * 0.64, height: size * 0.64)
                .animation(.easeOut(duration: 0.8), value: battery)

            if let face {
                Image(uiImage: face).resizable().scaledToFill()
                    .frame(width: size * 0.6, height: size * 0.6)
                    .clipShape(Circle())
            } else {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let comps = Calendar.current.dateComponents([.hour, .minute, .second], from: context.date)
                    let h = Double(comps.hour ?? 0), m = Double(comps.minute ?? 0), s = Double(comps.second ?? 0)
                    ZStack {
                        VStack(spacing: 2) {
                            Text(context.date, format: .dateTime.weekday(.abbreviated).day())
                                .font(.system(size: size * 0.055, weight: .semibold, design: .rounded))
                                .foregroundStyle(.white.opacity(0.85))
                            Text(battery.map { "\($0)%" } ?? "—")
                                .font(.system(size: size * 0.05, weight: .medium, design: .rounded))
                                .foregroundStyle(.white.opacity(0.6))
                        }
                        .offset(y: size * 0.13)
                        hand(width: size * 0.028, length: size * 0.17, color: .white, angle: (h.truncatingRemainder(dividingBy: 12) + m / 60) * 30)
                        hand(width: size * 0.02, length: size * 0.25, color: .white, angle: (m + s / 60) * 6)
                        hand(width: size * 0.008, length: size * 0.28, color: .orange, angle: s * 6)
                        Circle().fill(.orange).frame(width: size * 0.035)
                    }
                }
            }

            // Glass sheen
            Circle()
                .fill(LinearGradient(colors: [.white.opacity(0.12), .clear], startPoint: .topLeading, endPoint: .center))
                .frame(width: size * 0.72, height: size * 0.72)
                .allowsHitTesting(false)
        }
        .frame(width: size * 1.1, height: size * 1.25)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(connected ? "Watch connected" + (battery.map { ", battery \($0) percent" } ?? "") : "Watch not connected")
    }

    private func hand(width: CGFloat, length: CGFloat, color: Color, angle: Double) -> some View {
        Capsule().fill(color)
            .frame(width: width, height: length)
            .offset(y: -length / 2)
            .rotationEffect(.degrees(angle))
            .shadow(color: .black.opacity(0.5), radius: 2, y: 1)
    }
}

private struct BezelTrack: View {
    let size: CGFloat
    var body: some View {
        ZStack {
            ForEach(0..<60, id: \.self) { i in
                Capsule()
                    .fill(.white.opacity(i % 5 == 0 ? 0.95 : 0.4))
                    .frame(width: i % 5 == 0 ? size * 0.014 : size * 0.007, height: i % 5 == 0 ? size * 0.06 : size * 0.03)
                    .offset(y: -size * 0.44)
                    .rotationEffect(.degrees(Double(i) * 6))
            }
            ForEach(1..<12, id: \.self) { i in
                if i % 2 == 0 {
                    Text("\(i * 5)")
                        .font(.system(size: size * 0.055, weight: .bold, design: .rounded))
                        .foregroundStyle(.white.opacity(0.85))
                        .rotationEffect(.degrees(-Double(i) * 30))
                        .offset(y: -size * 0.37)
                        .rotationEffect(.degrees(Double(i) * 30))
                }
            }
            Triangle().fill(.orange).frame(width: size * 0.06, height: size * 0.05).offset(y: -size * 0.37)
        }
    }
}

private struct Triangle: Shape {
    func path(in r: CGRect) -> Path {
        Path { p in p.move(to: CGPoint(x: r.midX, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.minY)); p.closeSubpath() }
    }
}

// MARK: Activity rings

struct ActivityRings: View {
    let rings: [(progress: Double, color: Color)]
    var lineWidth: CGFloat = 14

    var body: some View {
        ZStack {
            ForEach(rings.indices, id: \.self) { i in
                let inset = CGFloat(i) * (lineWidth + 4)
                Circle().stroke(rings[i].color.opacity(0.2), lineWidth: lineWidth).padding(inset)
                Circle()
                    .trim(from: 0, to: min(1, max(0.001, rings[i].progress)))
                    .stroke(rings[i].color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(inset)
                    .animation(.spring(duration: 0.9), value: rings[i].progress)
            }
        }
        .padding(lineWidth / 2)
    }
}

/// A beating heart; speed follows the latest heart rate.
struct HeartBeat: View {
    let bpm: Double?
    var body: some View {
        Image(systemName: "heart.fill")
            .foregroundStyle(.red)
            .phaseAnimator([1.0, 1.18, 1.0]) { v, s in v.scaleEffect(s) } animation: { _ in
                .easeInOut(duration: 60 / max(40, bpm ?? 70) / 3)
            }
    }
}

/// Wordmark: "Wear OS Bridge".
struct Wordmark: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "applewatch.radiowaves.left.and.right")
                .foregroundStyle(Theme.brandGradient)
            Text("Wear OS \(Text("Bridge").foregroundStyle(Theme.accent2))").font(.title2.weight(.heavy)).foregroundStyle(.white)
        }
    }
}
