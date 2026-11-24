import Charts
import SwiftUI
import WearLink

// Screens for health depth (Wave C), opened from cards on the Health tab and rows in Settings.

/// Stress (estimate) and heart-rate variability from the watch's beat-to-beat data.
struct StressHrvView: View {
    @ObservedObject private var depth = HealthDepthStore.shared
    @EnvironmentObject var link: WatchLink

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HealthSummaryCard(
                    title: "Stress & HRV",
                    symbol: "brain.head.profile",
                    color: .purple,
                    value: depth.latestHrv?.stress.map(String.init) ?? "–",
                    unit: depth.latestHrv?.stressLabel ?? "no reading",
                    timestamp: depth.latestHrv.map { $0.at.formatted(.relative(presentation: .named)) },
                    subtitle: depth.latestHrv.map { r in
                        "HRV \(Int(r.sdnn.rounded())) ms SDNN" + (r.rmssd.map { " · RMSSD \(Int($0.rounded())) ms" } ?? "")
                    },
                    largeValue: true
                )

                Button {
                    depth.measureHrv()
                } label: {
                    Label(depth.measuring ? "Measuring on watch…" : "Measure now (1 min)", systemImage: "waveform.path.ecg")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.purple)
                .controlSize(.large)
                .disabled(depth.measuring || link.status != .connected)

                if let m = depth.hrvMessage {
                    Text(m)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 4)
                }

                if depth.history.count > 1 {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Label("Recent HRV", systemImage: "waveform.path.ecg")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.purple)
                            Spacer()
                            Text("Last \(min(depth.history.count, 20)) readings")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        HrvHistoryChart(readings: Array(depth.history.suffix(20)))
                            .frame(height: 150)
                    }
                    .padding(16)
                    .insetCard()
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("About this reading")
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text("Your watch records the time between heartbeats for a minute. HRV (SDNN) goes to Apple Health as Heart Rate Variability; the stress score (0–100, from RMSSD) is shown here and on the watch only.")
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                    Text("Both are wellness estimates, not medical measurements.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if depth.ibiAttempts > 0 {
                        Label(
                            "Beat-to-beat data received in \(depth.ibiWithData) of \(depth.ibiAttempts) attempts on this watch.",
                            systemImage: depth.ibiWithData == 0 ? "exclamationmark.triangle.fill" : "checkmark.circle.fill"
                        )
                        .font(.subheadline)
                        .foregroundStyle(depth.ibiWithData == 0 ? .orange : .secondary)
                        if depth.ibiWithData == 0 {
                            Text("The watch's sensor hasn't sent any beat-to-beat intervals yet, so HRV can't be calculated. This depends on the watch firmware.")
                                .font(.subheadline)
                                .foregroundStyle(.orange)
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .insetCard()

                NavigationLink { BackgroundReadingsView() } label: {
                    HStack(spacing: 12) {
                        SettingsIconBadge(symbol: "clock.arrow.circlepath", color: .purple)
                        Text("Automatic & overnight readings")
                            .font(.body)
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
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Stress & HRV")
        .onAppear { depth.refresh() }
    }
}

private struct HrvHistoryChart: View {
    let readings: [HealthDepthStore.HrvReading]

    var body: some View {
        Chart(readings) { r in
            BarMark(
                x: .value("Time", r.at),
                y: .value("HRV", r.sdnn)
            )
            .foregroundStyle(.purple.gradient)
            .cornerRadius(4)
        }
        .chartYAxisLabel("ms")
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4))
        }
        .accessibilityLabel("HRV history, \(readings.count) readings")
    }
}

/// Daily goals for the rings, move/stand/water reminders on the watch, and workout auto-pause.
struct GoalsRemindersView: View {
    @ObservedObject private var depth = HealthDepthStore.shared

    var body: some View {
        Form {
            Section {
                Stepper("Steps: \(depth.config.goalSteps.formatted())", value: $depth.config.goalSteps, in: 1_000...50_000, step: 500)
                Stepper("Active energy: \(depth.config.goalKcal) kcal", value: $depth.config.goalKcal, in: 50...3_000, step: 25)
                Stepper("Active minutes: \(depth.config.goalActiveMin)", value: $depth.config.goalActiveMin, in: 5...300, step: 5)
            } header: {
                Text("Daily goals")
            } footer: {
                Text("Shown as rings on the Health tab and on the watch's Activity goals tile. Active minutes are estimated from your walking pace and recorded workouts.")
            }

            Section {
                Toggle("Inactivity", isOn: $depth.config.remindInactive)
                if depth.config.remindInactive {
                    Stepper("After \(depth.config.inactiveMin) min still", value: $depth.config.inactiveMin, in: 30...180, step: 15)
                }
                Toggle("Stand each hour", isOn: $depth.config.remindStand)
                Toggle("Drink water", isOn: $depth.config.remindWater)
                if depth.config.remindWater {
                    Stepper("Every \(depth.config.waterEveryMin) min", value: $depth.config.waterEveryMin, in: 30...240, step: 15)
                }
                Picker("Quiet from", selection: $depth.config.quietStart) { hours }
                Picker("Quiet until", selection: $depth.config.quietEnd) { hours }
            } header: {
                Text("Reminders on the watch")
            } footer: {
                Text("Reminders don't fire during quiet hours, while the watch thinks you're asleep, or during a workout. The water reminder has a Log button.")
            }

            Section {
                Toggle("Auto-pause workouts", isOn: $depth.config.autoPause)
            } footer: {
                Text("Pauses the workout clock when you stop moving, where the watch supports it (runs and walks). Pause and Lap buttons are on the watch's workout screen.")
            }

            if !depth.configOnWatch {
                Section { Text("Changes are sent the next time your watch connects.").foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("Goals & reminders")
    }

    @ViewBuilder private var hours: some View {
        ForEach(0..<24, id: \.self) { h in
            Text(Calendar.current.date(bySettingHour: h, minute: 0, second: 0, of: Date())?
                .formatted(date: .omitted, time: .shortened) ?? "\(h):00").tag(h)
        }
    }
}

/// Automatic background SpO₂ / skin-temperature / heart-rate / HRV readings, and overnight ones.
struct BackgroundReadingsView: View {
    @ObservedObject private var depth = HealthDepthStore.shared
    private let intervals = [15, 30, 60, 120, 180, 240]

    var body: some View {
        Form {
            Section {
                Toggle("Automatic readings", isOn: $depth.config.autoLog)
                if depth.config.autoLog {
                    Picker("Every", selection: $depth.config.autoLogMin) {
                        ForEach(intervals, id: \.self) { Text(label($0)).tag($0) }
                    }
                }
            } header: {
                Text("During the day")
            } footer: {
                Text("Each reading runs the watch's sensors for about a minute: heart rate, HRV, blood oxygen and skin temperature. More frequent readings use more battery.")
            }

            Section {
                Toggle("Overnight readings", isOn: $depth.config.overnight)
                if depth.config.overnight {
                    Picker("Every", selection: $depth.config.overnightMin) {
                        ForEach([15, 30, 60, 120], id: \.self) { Text(label($0)).tag($0) }
                    }
                }
            } header: {
                Text("While asleep")
            } footer: {
                Text("While the watch's sleep estimate says you're asleep, it logs blood oxygen, skin temperature, sleeping heart rate and HRV. Sleep is an estimate, so some readings may be taken while you're resting.")
            }

            Section {
                Stepper("Skip below \(depth.config.autoLogMinBattery)% battery", value: $depth.config.autoLogMinBattery, in: 10...80, step: 5)
            } header: {
                Text("Battery")
            } footer: {
                Text("Readings are also skipped while charging, in power saving mode and during workouts. Blood oxygen and skin temperature only reach Apple Health when their toggles are on in Settings → Apple Health; HRV and sleeping heart rate follow the Heart rate toggle.")
            }

            Section("Status") {
                LabeledContent("Last reading", value: depth.autoLastRun?.formatted(.relative(presentation: .named)) ?? "None yet")
                if !depth.autoSkipped.isEmpty { LabeledContent("Last skipped", value: depth.autoSkipped) }
                LabeledContent("Sleep estimate", value: depth.asleep ? "Asleep" : "Awake")
                if depth.watchSensors == false {
                    Label("Samsung health sensors aren't reachable — set up developer mode under Health → Body sensors.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle("Automatic readings")
        .onAppear { depth.refresh() }
    }

    private func label(_ m: Int) -> String { m < 60 ? "\(m) min" : m == 60 ? "hour" : "\(m / 60) hours" }
}

/// Water and caffeine logging, written to Apple Health (dietary water / caffeine).
struct IntakeView: View {
    @ObservedObject private var depth = HealthDepthStore.shared
    @EnvironmentObject var hub: FeatureHub

    private let drinks: [(String, String, Double)] = [
        ("Espresso", "cup.and.saucer.fill", 63), ("Coffee", "mug.fill", 95),
        ("Tea", "leaf.fill", 47), ("Energy drink", "bolt.fill", 80), ("Cola", "takeoutbag.and.cup.and.straw.fill", 34),
    ]

    var body: some View {
        Form {
            Section("Today") {
                HStack(spacing: 16) {
                    summaryMetric(title: "Water", symbol: "drop.fill", color: .cyan,
                                  value: "\(Int(depth.today.waterMl.rounded()))", unit: "ml")
                    Divider()
                    summaryMetric(title: "Caffeine", symbol: "cup.and.saucer.fill", color: .brown,
                                  value: "\(Int(depth.today.caffeineMg.rounded()))", unit: "mg")
                }
                .padding(.vertical, 4)
            }

            Section("Log water") {
                HStack(spacing: 10) {
                    ForEach([150.0, 250, 330, 500], id: \.self) { ml in
                        Button {
                            Task { await depth.logWater(ml); hub.toast = "Logged \(Int(ml)) ml" }
                        } label: {
                            VStack(spacing: 2) {
                                Image(systemName: "drop.fill")
                                    .foregroundStyle(.cyan)
                                Text("\(Int(ml))")
                                    .font(.headline)
                                    .monospacedDigit()
                                Text("ml")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                        }
                        .buttonStyle(.bordered)
                        .tint(.cyan)
                        .accessibilityLabel("Log \(Int(ml)) millilitres of water")
                    }
                }
            }

            Section("Log caffeine") {
                ForEach(drinks, id: \.0) { d in
                    Button {
                        Task { await depth.logCaffeine(d.2); hub.toast = "Logged \(d.0.lowercased()), \(Int(d.2)) mg" }
                    } label: {
                        LabeledContent {
                            Text("\(Int(d.2)) mg")
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        } label: {
                            Label(d.0, systemImage: d.1)
                                .foregroundStyle(.primary)
                        }
                    }
                }
            }

            Section {
                Stepper("Cup: \(depth.config.cupMl) ml", value: $depth.config.cupMl, in: 50...1_000, step: 50)
                Stepper("Coffee: \(depth.config.coffeeMg) mg", value: $depth.config.coffeeMg, in: 10...500, step: 5)
            } header: {
                Text("Quick log on the watch")
            } footer: {
                Text("Add the Water & caffeine tile on the watch to log with one tap.")
            }
        }
        .navigationTitle("Water & caffeine")
        .toast($hub.toast)
    }

    private func summaryMetric(title: String, symbol: String, color: Color, value: String, unit: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: symbol)
                .font(.caption.weight(.semibold))
                .foregroundStyle(color)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.system(.title2, design: .rounded).weight(.bold))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                Text(unit)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Period and symptom logging, written to Apple Health's cycle tracking.
struct CycleView: View {
    @ObservedObject private var depth = HealthDepthStore.shared
    @EnvironmentObject var hub: FeatureHub
    @State private var day = Date()
    @State private var flow: MenstrualFlow? = .medium
    @State private var cycleStart = false
    @State private var symptoms: [HealthDepthHK.Symptom: Int] = [:]
    @State private var saving = false

    private let flows: [(MenstrualFlow?, String)] = [
        (nil, "No entry"), (MenstrualFlow.none, "None"), (.light, "Light"), (.medium, "Medium"), (.heavy, "Heavy"), (.unspecified, "Spotting / unsure"),
    ]

    var body: some View {
        Form {
            Section {
                DatePicker("Day", selection: $day, in: ...Date(), displayedComponents: .date)
                Picker("Flow", selection: $flow) {
                    ForEach(flows, id: \.1) { Text($0.1).tag($0.0) }
                }
                Toggle("First day of period", isOn: $cycleStart).disabled(flow == nil || flow == MenstrualFlow.none)
            } header: {
                Text("Period")
            } footer: {
                Text("You can also log flow on the watch: Wear OS Bridge → Health → Period.")
            }

            Section("Symptoms") {
                ForEach(HealthDepthHK.Symptom.allCases) { s in
                    Picker(s.title, selection: Binding(get: { symptoms[s] ?? -1 },
                                                       set: { symptoms[s] = $0 < 0 ? nil : $0 })) {
                        Text("–").tag(-1)
                        Text("Mild").tag(2)
                        Text("Moderate").tag(3)
                        Text("Severe").tag(4)
                    }
                }
            }

            Section {
                Button {
                    saving = true
                    Task {
                        hub.toast = await depth.logCycle(day: day, flow: flow, cycleStart: cycleStart, symptoms: symptoms)
                        saving = false
                    }
                } label: {
                    Label(saving ? "Saving…" : "Save to Apple Health", systemImage: "heart.circle.fill")
                }
                .disabled(saving || (flow == nil && symptoms.isEmpty))
            } footer: {
                if let f = depth.today.flowLogged, let level = MenstrualFlow(rawValue: f) {
                    Text("Logged today: \(flows.first { $0.0 == level }?.1 ?? "flow").")
                }
            }
        }
        .navigationTitle("Cycle tracking")
        .toast($hub.toast)
    }
}
