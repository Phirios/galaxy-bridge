import SwiftUI
import WearLink

// Screens for health depth (Wave C), opened from tiles on the Health tab and rows in Settings.

/// Stress (estimate) and heart-rate variability from the watch's beat-to-beat data.
struct StressHrvView: View {
    @ObservedObject private var depth = HealthDepthStore.shared
    @EnvironmentObject var link: WatchLink

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(spacing: 6) {
                    Text(depth.latestHrv?.stress.map(String.init) ?? "–")
                        .font(.system(size: 64, weight: .heavy, design: .rounded)).foregroundStyle(.white)
                        .contentTransition(.numericText())
                    Text("Stress · \(depth.latestHrv?.stressLabel ?? "no reading")")
                        .font(.headline).foregroundStyle(Theme.accent3)
                    if let r = depth.latestHrv {
                        Text("HRV \(Int(r.sdnn.rounded())) ms SDNN\(r.rmssd.map { " · RMSSD \(Int($0.rounded())) ms" } ?? "") · \(r.at.formatted(.relative(presentation: .named)))")
                            .font(.caption).foregroundStyle(.white.opacity(0.6)).multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 8)

                Button {
                    depth.measureHrv()
                } label: {
                    Label(depth.measuring ? "Measuring on watch…" : "Measure now (1 min)", systemImage: "waveform.path.ecg")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Theme.accent3)
                .disabled(depth.measuring || link.status != .connected)

                if let m = depth.hrvMessage {
                    Text(m).font(.callout).foregroundStyle(.white.opacity(0.75))
                }

                if depth.history.count > 1 {
                    SectionTitle(text: "Recent readings")
                    HrvHistoryChart(readings: Array(depth.history.suffix(20)))
                        .frame(height: 120)
                        .padding(16)
                        .card()
                }

                SectionTitle(text: "About this reading")
                VStack(alignment: .leading, spacing: 8) {
                    Text("Your watch records the time between heartbeats for a minute. HRV (SDNN) goes to Apple Health as Heart Rate Variability; the stress score (0–100, from RMSSD) is shown here and on the watch only.")
                    Text("Both are wellness estimates, not medical measurements.")
                        .foregroundStyle(.white.opacity(0.6))
                    if depth.ibiAttempts > 0 {
                        Text("Beat-to-beat data received in \(depth.ibiWithData) of \(depth.ibiAttempts) attempts on this watch.")
                            .foregroundStyle(depth.ibiWithData == 0 ? .orange : .white.opacity(0.6))
                        if depth.ibiWithData == 0 {
                            Text("The watch's sensor hasn't sent any beat-to-beat intervals yet, so HRV can't be calculated. This depends on the watch firmware.")
                                .foregroundStyle(.orange)
                        }
                    }
                }
                .font(.subheadline)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .card()

                NavigationLink { BackgroundReadingsView() } label: {
                    Label("Automatic & overnight readings", systemImage: "clock.arrow.circlepath")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .card()
                }
                .buttonStyle(PressScale())
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 40)
        }
        .background(AuroraBackground())
        .navigationTitle("Stress & HRV")
        .onAppear { depth.refresh() }
    }
}

private struct HrvHistoryChart: View {
    let readings: [HealthDepthStore.HrvReading]

    var body: some View {
        GeometryReader { geo in
            let top = max(readings.map(\.sdnn).max() ?? 1, 1)
            let w = geo.size.width / CGFloat(max(readings.count, 1))
            HStack(alignment: .bottom, spacing: 0) {
                ForEach(readings) { r in
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Theme.accent3.gradient)
                        .frame(width: max(w - 4, 2), height: max(4, geo.size.height * CGFloat(r.sdnn / top)))
                        .frame(width: w)
                }
            }
            .frame(maxHeight: .infinity, alignment: .bottom)
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
        .auroraScreen()
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
                    Text("Samsung health sensors aren't reachable — set up developer mode under Health → Body sensors.")
                        .foregroundStyle(.orange)
                }
            }
        }
        .auroraScreen()
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
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    total("Water", "\(Int(depth.today.waterMl.rounded()))", "ml", "drop.fill", .cyan)
                    total("Caffeine", "\(Int(depth.today.caffeineMg.rounded()))", "mg", "cup.and.saucer.fill", .brown)
                }

                SectionTitle(text: "Water")
                HStack(spacing: 10) {
                    ForEach([150.0, 250, 330, 500], id: \.self) { ml in
                        Button { Task { await depth.logWater(ml); hub.toast = "Logged \(Int(ml)) ml" } } label: {
                            VStack(spacing: 4) {
                                Image(systemName: "drop.fill").foregroundStyle(.cyan)
                                Text("\(Int(ml))").font(.headline)
                                Text("ml").font(.caption2).foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity).padding(.vertical, 12).card(cornerRadius: 18)
                        }
                        .buttonStyle(PressScale())
                    }
                }

                SectionTitle(text: "Caffeine")
                VStack(spacing: 10) {
                    ForEach(drinks, id: \.0) { d in
                        Button { Task { await depth.logCaffeine(d.2); hub.toast = "Logged \(d.0.lowercased()), \(Int(d.2)) mg" } } label: {
                            HStack {
                                Image(systemName: d.1).foregroundStyle(.brown).frame(width: 28)
                                Text(d.0).font(.subheadline.weight(.semibold))
                                Spacer()
                                Text("\(Int(d.2)) mg").font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(14).card(cornerRadius: 18)
                        }
                        .buttonStyle(PressScale())
                    }
                }

                SectionTitle(text: "Quick log on the watch")
                VStack(alignment: .leading, spacing: 10) {
                    Stepper("Cup: \(depth.config.cupMl) ml", value: $depth.config.cupMl, in: 50...1_000, step: 50)
                    Stepper("Coffee: \(depth.config.coffeeMg) mg", value: $depth.config.coffeeMg, in: 10...500, step: 5)
                    Text("Add the Water & caffeine tile on the watch to log with one tap.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(16).card()
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 40)
        }
        .background(AuroraBackground())
        .navigationTitle("Water & caffeine")
        .toast($hub.toast)
    }

    private func total(_ title: String, _ value: String, _ unit: String, _ symbol: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: symbol).foregroundStyle(color).frame(width: 30, height: 30).background(color.opacity(0.15), in: Circle())
            Text("\(title) today").font(.caption).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value).font(.system(.title, design: .rounded).weight(.bold))
                Text(unit).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16).card()
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
        (nil, "No entry"), (.none, "None"), (.light, "Light"), (.medium, "Medium"), (.heavy, "Heavy"), (.unspecified, "Spotting / unsure"),
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
        .auroraScreen()
        .navigationTitle("Cycle tracking")
        .toast($hub.toast)
    }
}
