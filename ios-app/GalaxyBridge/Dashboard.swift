import Charts
import SafariServices
import SwiftUI
import WearLink

// MARK: Root

struct Dashboard: View {
    @EnvironmentObject var link: WatchLink
    @EnvironmentObject var health: HealthSync
    @EnvironmentObject var phone: PhoneServices

    var body: some View {
        Group {
            if link.hasKnownWatch { MainTabs() } else { PairingView() }
        }
    }
}

private struct MainTabs: View {
    @EnvironmentObject var health: HealthSync
    @EnvironmentObject var phone: PhoneServices
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView(tab: $tab) }.tabItem { Label("Watch", systemImage: "applewatch.side.right") }.tag(0)
            NavigationStack { HealthView() }.tabItem { Label("Health", systemImage: "heart.fill") }.tag(1)
            NavigationStack { StoreScreen() }.tabItem { Label("Store", systemImage: "bag.fill") }.tag(2)
            NavigationStack { WatchSettingsView() }.tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag(4)
        }
        .nativeTabBarMinimize()
        .task {
            await health.requestAccess()
            await phone.requestPermissions()
            phone.refresh()
        }
    }
}

// MARK: Home

private struct HomeView: View {
    @EnvironmentObject var link: WatchLink
    @EnvironmentObject var health: HealthSync
    @EnvironmentObject var phone: PhoneServices
    @EnvironmentObject var hub: FeatureHub
    @Binding var tab: Int
    @State private var showCamera = false
    @State private var showGallery = false

    private var connected: Bool { link.status == .connected }
    private var dndOn: Bool { link.watchStatus?.settings[WatchSetting.doNotDisturb.rawValue] == 1 }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                hero

                quickActions

                LiveCard(tab: $tab)

                if phone.findingPhone { FindingPhoneBanner() }
                if connected, link.watchStatus?.perms.ancs == false { NotificationSetupCard() }
                if let off = link.watchStatus?.clockOffset, abs(off) > 60 { ClockWarningCard(offset: off) }
                if !health.authorized { HealthAccessCard() }

                FeatureLauncher()

                if let error = link.lastError ?? health.lastError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .insetCard()
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Watch")
        .fullScreenCover(isPresented: $showCamera) { CameraScreen() }
        .navigationDestination(isPresented: $showGallery) { FaceGalleryView() }
        .toast($hub.toast)
    }

    /// Compact watch card: watch render beside its model name, face, link status, battery and signal.
    private var hero: some View {
        let st = link.watchStatus
        return HStack(spacing: 16) {
            WatchRender(battery: link.watchBattery, connected: connected, face: hub.faceImage, size: 100)
                .frame(width: 112, height: 126)

            VStack(alignment: .leading, spacing: 6) {
                Text(st.map { WatchNames.marketingName($0.model) } ?? "Galaxy Watch")
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                if let face = hub.faceName {
                    Text(face)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                StatusPill(status: link.status)

                HStack(spacing: 12) {
                    Label(link.watchBattery.map { "\($0)%" } ?? "–",
                          systemImage: st?.charging == true ? "battery.100.bolt" : "battery.75percent")
                    if let rssi = link.rssi {
                        Label(rssi > -65 ? "Strong" : rssi > -80 ? "Good" : "Weak",
                              systemImage: "antenna.radiowaves.left.and.right")
                    }
                    if let st, st.totalMB > 0 {
                        Label("\((st.freeMB / 1000).formatted()) GB", systemImage: "internaldrive")
                    }
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .labelStyle(.titleAndIcon)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .insetCard()
    }

    private var quickActions: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 4), spacing: 10) {
            QuickActionButton(
                title: link.findingWatch ? "Stop" : "Find",
                symbol: link.findingWatch ? "speaker.slash.fill" : "speaker.wave.3.fill",
                tint: .orange,
                active: link.findingWatch,
                accessibilityLabel: link.findingWatch ? "Stop ringing watch" : "Find watch"
            ) {
                link.findWatch(!link.findingWatch)
            }
            .disabled(!connected)

            QuickActionButton(
                title: "Camera",
                symbol: "camera.fill",
                tint: .blue,
                accessibilityLabel: "Open camera remote"
            ) {
                showCamera = true
            }

            QuickActionButton(
                title: "Faces",
                symbol: "sparkles",
                tint: .pink,
                accessibilityLabel: "Browse watch faces"
            ) {
                showGallery = true
            }

            QuickActionButton(
                title: "DND",
                symbol: dndOn ? "moon.fill" : "moon",
                tint: .indigo,
                active: dndOn,
                accessibilityLabel: dndOn ? "Do Not Disturb, on" : "Do Not Disturb, off"
            ) {
                link.set(.doNotDisturb, dndOn ? 0 : 1)
            }
            .disabled(!connected || link.watchStatus?.perms.dnd != true)
        }
    }
}

private struct QuickActionButton: View {
    let title: String
    let symbol: String
    let tint: Color
    var active = false
    var accessibilityLabel: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.title3.weight(.semibold))
                    .symbolEffect(.pulse, isActive: active)
                    .frame(height: 24)
                Text(title)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
        }
        .modifier(QuickActionStyle(tint: tint, active: active))
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

private struct QuickActionStyle: ViewModifier {
    let tint: Color
    let active: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            if active {
                content
                    .buttonStyle(.glassProminent)
                    .tint(tint)
            } else {
                content
                    .buttonStyle(.glass)
                    .tint(tint)
            }
        } else {
            content
                .foregroundStyle(active ? .white : tint)
                .background(
                    active ? AnyShapeStyle(tint) : AnyShapeStyle(Color(.secondarySystemGroupedBackground)),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                )
        }
    }
}

/// Live snapshot card on the Watch tab: latest heart rate, SpO₂, steps and iPhone battery.
private struct LiveCard: View {
    @Binding var tab: Int
    @EnvironmentObject var link: WatchLink
    @EnvironmentObject var health: HealthSync
    @EnvironmentObject var phone: PhoneServices

    var body: some View {
        let s = health.summary
        Button { tab = 1 } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label("Summary", systemImage: "heart.text.square.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.red)
                    Spacer()
                    HStack(spacing: 4) {
                        if let at = s.heartRateAt {
                            Text(at.formatted(.relative(presentation: .named)))
                        } else {
                            Text("Health")
                        }
                        Image(systemName: "chevron.right")
                            .font(.caption2.weight(.semibold))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                HStack(spacing: 8) {
                    metric(symbol: "heart.fill", color: .red, title: "Heart",
                           value: s.heartRate.map { "\(Int($0.rounded()))" } ?? "–", unit: "bpm")
                    Divider().frame(height: 36)
                    metric(symbol: "lungs.fill", color: .cyan, title: "SpO₂",
                           value: s.spo2.map { "\(Int($0.rounded()))" } ?? "–", unit: "%")
                    Divider().frame(height: 36)
                    metric(symbol: "figure.walk", color: .green, title: "Steps",
                           value: Int(s.steps).formatted(), unit: "")
                    Divider().frame(height: 36)
                    metric(symbol: phone.phoneCharging ? "battery.100.bolt" : "iphone",
                           color: .blue, title: "Phone",
                           value: phone.phoneBattery.map { "\($0)" } ?? "–", unit: "%")
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .insetCard()
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens the Health tab")
    }

    private func metric(symbol: String, color: Color, title: String, value: String, unit: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(color)
                .accessibilityHidden(true)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(.headline, design: .rounded).weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
                    .contentTransition(.numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

enum WatchNames {
    static func marketingName(_ model: String) -> String {
        switch model {
        case "SM-R890", "SM-R895", "SM-R880", "SM-R885": return "Galaxy Watch4 Classic"
        case "SM-R860", "SM-R865", "SM-R870", "SM-R875": return "Galaxy Watch4"
        case "SM-R900", "SM-R905", "SM-R910", "SM-R915": return "Galaxy Watch5"
        case "SM-R920", "SM-R925": return "Galaxy Watch5 Pro"
        case "SM-R930", "SM-R935", "SM-R940", "SM-R945": return "Galaxy Watch6"
        case "SM-R950", "SM-R955", "SM-R960", "SM-R965": return "Galaxy Watch6 Classic"
        default: return model.isEmpty ? "Galaxy Watch" : model
        }
    }
}

// MARK: Health

private struct HealthView: View {
    @EnvironmentObject var health: HealthSync
    @EnvironmentObject var hub: FeatureHub
    @ObservedObject private var depth = HealthDepthStore.shared

    private var sleepingHrText: String {
        guard !depth.today.sleepingHR.isEmpty else { return "–" }
        let avg = depth.today.sleepingHR.reduce(0, +) / Double(depth.today.sleepingHR.count)
        return "\(Int(avg.rounded()))"
    }

    var body: some View {
        let s = health.summary
        let goal = depth.config

        ScrollView {
            VStack(spacing: 16) {
                // Heart Rate hero card (Health app style)
                heartRateCard(s)

                // Activity rings card
                activityCard(summary: s, goal: goal)

                // HRV summary card with inline Swift Chart when readings exist
                NavigationLink { StressHrvView() } label: {
                    HealthSummaryCard(
                        title: "Heart Rate Variability",
                        symbol: "waveform.path.ecg",
                        color: .purple,
                        value: depth.latestHrv.map { "\(Int($0.sdnn.rounded()))" } ?? "–",
                        unit: "ms",
                        timestamp: depth.latestHrv.map { $0.at.formatted(.relative(presentation: .named)) } ?? "No data",
                        subtitle: depth.latestHrv?.stress.map { "Stress \($0) · \(depth.latestHrv?.stressLabel ?? "")" },
                        showsChevron: true
                    ) {
                        if depth.history.count > 1 {
                            Chart(Array(depth.history.suffix(14))) { reading in
                                BarMark(
                                    x: .value("Time", reading.at),
                                    y: .value("HRV", reading.sdnn)
                                )
                                .foregroundStyle(.purple.gradient)
                                .cornerRadius(3)
                            }
                            .chartXAxis(.hidden)
                            .chartYAxis(.hidden)
                            .frame(height: 52)
                            .padding(.top, 4)
                            .accessibilityHidden(true)
                        }
                    }
                }
                .buttonStyle(.plain)

                // Health summary grid
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                    HealthSummaryCard(
                        title: "Blood Oxygen",
                        symbol: "lungs.fill",
                        color: .cyan,
                        value: s.spo2.map { "\(Int($0.rounded()))" } ?? "–",
                        unit: "%",
                        timestamp: s.spo2At?.formatted(.relative(presentation: .named))
                    )
                    HealthSummaryCard(
                        title: "Resting HR",
                        symbol: "heart.text.square.fill",
                        color: .pink,
                        value: s.restingHeartRate.map { "\(Int($0.rounded()))" } ?? "–",
                        unit: "bpm",
                        timestamp: s.restingHeartRateAt?.formatted(.relative(presentation: .named))
                    )
                    HealthSummaryCard(
                        title: "Skin Temp",
                        symbol: "thermometer.medium",
                        color: .orange,
                        value: s.skinTemp.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? "–",
                        unit: "°C",
                        timestamp: s.skinTempAt?.formatted(.relative(presentation: .named))
                    )
                    HealthSummaryCard(
                        title: "Floors",
                        symbol: "figure.stairs",
                        color: .blue,
                        value: s.floors.formatted(.number.precision(.fractionLength(0))),
                        unit: "climbed",
                        timestamp: "Today"
                    )
                    NavigationLink { StressHrvView() } label: {
                        HealthSummaryCard(
                            title: "Stress",
                            symbol: "brain.head.profile",
                            color: .purple,
                            value: depth.latestHrv?.stress.map(String.init) ?? "–",
                            unit: depth.latestHrv?.stressLabel ?? "estimate",
                            showsChevron: true
                        )
                    }
                    .buttonStyle(.plain)
                    NavigationLink { IntakeView() } label: {
                        HealthSummaryCard(
                            title: "Water",
                            symbol: "drop.fill",
                            color: .cyan,
                            value: "\(Int(depth.today.waterMl.rounded()))",
                            unit: "ml",
                            timestamp: "Today",
                            showsChevron: true
                        )
                    }
                    .buttonStyle(.plain)
                    HealthSummaryCard(
                        title: "Sleeping HR",
                        symbol: "bed.double.fill",
                        color: .indigo,
                        value: sleepingHrText,
                        unit: "bpm",
                        timestamp: "Today"
                    )
                    HealthSummaryCard(
                        title: "Distance",
                        symbol: "location.north.line.fill",
                        color: .blue,
                        value: (s.distance / 1000).formatted(.number.precision(.fractionLength(2))),
                        unit: "km",
                        timestamp: "Today"
                    )
                }

                SyncCard()

                sensorsAndWorkoutsSection

                if !health.authorized { HealthAccessCard() }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Health")
        .toast($hub.toast)
    }

    private func heartRateCard(_ s: DaySummary) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                HStack(spacing: 6) {
                    HeartBeat(bpm: s.heartRate)
                        .font(.subheadline)
                    Text("Heart Rate")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.red)
                }
                Spacer()
                Text(s.heartRateAt?.formatted(.relative(presentation: .named)) ?? "No reading")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(s.heartRate.map { "\(Int($0.rounded()))" } ?? "–")
                    .font(.system(.largeTitle, design: .rounded).weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
                    .contentTransition(.numericText())
                Text("BPM")
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .insetCard()
        .accessibilityElement(children: .combine)
    }

    private func activityCard(summary s: DaySummary, goal: HealthDepthStore.Config) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("Activity", systemImage: "flame.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.orange)
                Spacer()
                Text("Today")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 18) {
                ActivityRings(
                    rings: [
                        (s.energy / Double(max(goal.goalKcal, 1)), .orange),
                        (s.steps / Double(max(goal.goalSteps, 1)), .green),
                        (s.distance / 8_000, .blue),
                    ],
                    lineWidth: 12
                )
                .frame(width: 112, height: 112)
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 10) {
                    activityRow(
                        symbol: "flame.fill",
                        color: .orange,
                        title: "Active Energy",
                        value: s.energy.formatted(.number.precision(.fractionLength(0))),
                        unit: "/ \(goal.goalKcal) kcal"
                    )
                    activityRow(
                        symbol: "figure.walk",
                        color: .green,
                        title: "Steps",
                        value: s.steps.formatted(.number.precision(.fractionLength(0))),
                        unit: "/ \(goal.goalSteps.formatted())"
                    )
                    activityRow(
                        symbol: "location.north.line.fill",
                        color: .blue,
                        title: "Distance",
                        value: (s.distance / 1000).formatted(.number.precision(.fractionLength(2))),
                        unit: "km"
                    )
                }
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .insetCard()
    }

    private func activityRow(symbol: String, color: Color, title: String, value: String, unit: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Label(title, systemImage: symbol)
                .font(.caption.weight(.medium))
                .foregroundStyle(color)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.system(.headline, design: .rounded).weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
                    .contentTransition(.numericText())
                Text(unit)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var sensorsAndWorkoutsSection: some View {
        let rows: [(String, String, String, Color, AnyView)] = [
            ("Body sensors", "SpO₂, ECG, body composition", "heart.text.square.fill", .red, AnyView(BodySensorsView())),
            ("Workouts", "Saved to Apple Health", "figure.run", .green, AnyView(WorkoutsInfoView())),
            ("Stress & HRV", "Measure and history", "waveform.path.ecg", .purple, AnyView(StressHrvView())),
            ("Goals & reminders", "Rings, move, water", "target", .orange, AnyView(GoalsRemindersView())),
            ("Water & caffeine", "Log intake", "drop.fill", .cyan, AnyView(IntakeView())),
            ("Cycle tracking", "Period and symptoms", "calendar.circle.fill", .pink, AnyView(CycleView())),
        ]
        return VStack(alignment: .leading, spacing: 8) {
            Text("Sensors & Workouts")
                .font(.headline)
                .foregroundStyle(.primary)
                .padding(.horizontal, 4)

            VStack(spacing: 0) {
                ForEach(rows.indices, id: \.self) { i in
                    let row = rows[i]
                    NavigationLink { row.4 } label: {
                        HStack(spacing: 12) {
                            SettingsIconBadge(symbol: row.2, color: row.3)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.0)
                                    .font(.body)
                                    .foregroundStyle(.primary)
                                Text(row.1)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                                .accessibilityHidden(true)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if i < rows.count - 1 {
                        Divider().padding(.leading, 56)
                    }
                }
            }
            .insetCard()
        }
    }
}

// MARK: Cards

private struct NotificationSetupCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Turn on watch notifications", systemImage: "bell.badge.fill")
                .font(.headline)
                .foregroundStyle(.red)
            Text("Open **Settings → Bluetooth**, tap ⓘ next to your watch, and switch on **Share System Notifications**. Calls and music controls use the same setting.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(.bordered)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .insetCard()
    }
}

/// Android only lets system apps set the clock, so the fix happens on the watch itself.
private struct ClockWarningCard: View {
    let offset: TimeInterval

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Watch time is wrong", systemImage: "clock.badge.exclamationmark.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            Text("It's \(Duration.seconds(abs(offset)).formatted(.units(allowed: [.days, .hours, .minutes], width: .wide, maximumUnitCount: 2))) \(offset < 0 ? "behind" : "ahead"). On the watch, open Wear OS Bridge and tap **Watch time is wrong**, or go to Settings → General → Date and time and turn on **Automatic date and time** with Wi-Fi on. Health data is corrected automatically.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .insetCard()
    }
}

private struct FindingPhoneBanner: View {
    @EnvironmentObject var phone: PhoneServices

    var body: some View {
        HStack {
            Label("Your watch is ringing this iPhone", systemImage: "iphone.radiowaves.left.and.right")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            Spacer()
            Button("Stop") { phone.findPhone(false) }
                .buttonStyle(.borderedProminent)
        }
        .padding(16)
        .insetCard()
    }
}

private struct StatusPill: View {
    let status: WatchLink.Status

    var body: some View {
        Label(text, systemImage: symbol)
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(color.opacity(0.14), in: Capsule())
    }

    private var text: String {
        switch status {
        case .connected: return "Connected"
        case .notPaired: return "Not paired"
        case .connecting: return "Connecting…"
        case .searching: return "Looking for watch"
        case .bluetoothOff: return "Bluetooth off"
        case .needsRepair: return "Re-pair needed"
        case .unauthorized: return "Bluetooth not allowed"
        }
    }

    private var symbol: String {
        switch status {
        case .connected: return "checkmark.circle.fill"
        case .connecting, .searching: return "dot.radiowaves.left.and.right"
        case .bluetoothOff, .unauthorized, .notPaired, .needsRepair: return "exclamationmark.triangle.fill"
        }
    }

    private var color: Color {
        switch status {
        case .connected: return .green
        case .connecting, .searching: return .orange
        case .bluetoothOff, .unauthorized, .notPaired, .needsRepair: return .red
        }
    }
}

struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

private struct SyncCard: View {
    @EnvironmentObject var health: HealthSync

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "heart.text.square.fill")
                .font(.title2)
                .foregroundStyle(.red)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Apple Health")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                if let last = health.lastSync {
                    Text("Synced \(last.formatted(.relative(presentation: .named))) · \(health.savedCount) samples")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("Waiting for the first readings from your watch")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
        .padding(16)
        .insetCard()
        .accessibilityElement(children: .combine)
    }
}

private struct HealthAccessCard: View {
    @EnvironmentObject var health: HealthSync

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Connect Apple Health", systemImage: "heart.circle.fill")
                .font(.headline)
                .foregroundStyle(.red)
            Text("Allow Wear OS Bridge to write heart rate, steps, distance, energy, floors and workouts. It never reads your existing data.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button("Allow access") { Task { await health.requestAccess() } }
                .buttonStyle(.borderedProminent)
                .tint(.red)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .insetCard()
    }
}
