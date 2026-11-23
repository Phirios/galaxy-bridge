import SwiftUI
import WearLink

/// The companion-app settings screen: everything here is applied on the watch.
struct WatchSettingsView: View {
    @EnvironmentObject var link: WatchLink
    @EnvironmentObject var phone: PhoneServices
    @EnvironmentObject var health: HealthSync
    @EnvironmentObject var hub: FeatureHub
    @State private var brightness: Double = 128
    @State private var editingBrightness = false

    private var s: WatchStatus? { link.watchStatus }
    private var connected: Bool { link.status == .connected && s != nil }

    var body: some View {
        Form {
            if !connected {
                Section {
                    Label("Connect your watch to change its settings.", systemImage: "applewatch.slash")
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                toggle("Mirror iPhone notifications", .notifications, symbol: "bell.badge.fill", color: .red)
                toggle("Incoming calls", .calls, symbol: "phone.fill", color: .green)
                toggle("Clearing on watch clears iPhone", .clearOnIPhone, symbol: "arrow.left.arrow.right", color: .blue)
                NavigationLink { NotificationFiltersView() } label: {
                    Label { Text("Choose apps and types") } icon: { icon("line.3.horizontal.decrease.circle.fill", .red) }
                }
                if let perms = s?.perms, !perms.ancs {
                    Label {
                        Text("On your iPhone, open Settings → Bluetooth, tap ⓘ next to the watch, and turn on **Share System Notifications**.")
                            .font(.footnote)
                    } icon: {
                        Image(systemName: "info.circle.fill").foregroundStyle(.orange)
                    }
                }
            } header: {
                Text("Notifications")
            } footer: {
                Text("Notifications buzz on the watch with their actions. Calls can be answered or declined from the watch, and talked on through its speaker and mic.")
            }

            Section("Connection") {
                Toggle(isOn: $link.alertOnDisconnect) {
                    Label { Text("Alert when disconnected") } icon: { icon("antenna.radiowaves.left.and.right.slash", .orange) }
                }
                if let rssi = link.rssi {
                    LabeledContent("Signal", value: "\(rssi) dBm · \(rssi > -65 ? "Strong" : rssi > -80 ? "Good" : "Weak")")
                }
                NavigationLink("Connection history") { ConnectionHistoryView() }
                NavigationLink { NativePairingView() } label: {
                    Label { Text("Native pairing (experimental)") } icon: { icon("lock.shield.fill", .blue) }
                }
            }

            Section("Music") {
                toggle("Music controls on watch", .music, symbol: "music.note", color: .pink)
            }

            Section {
                HStack {
                    icon("sun.max.fill", .orange)
                    Slider(value: $brightness, in: 1...255) { editing in
                        editingBrightness = editing
                        if !editing { link.set(.brightness, Int(brightness)) }
                    }
                }
                .disabled(!(s?.perms.system ?? false))
                Picker(selection: binding(.screenTimeout, default: 15)) {
                    ForEach([5, 10, 15, 30, 60, 120, 300], id: \.self) { secs in
                        Text(secs < 60 ? "\(secs) seconds" : "\(secs / 60) minute\(secs == 60 ? "" : "s")").tag(secs)
                    }
                } label: {
                    Label { Text("Screen timeout") } icon: { icon("timer", .gray) }
                }
                .disabled(!(s?.perms.system ?? false))
                toggle("Do Not Disturb", .doNotDisturb, symbol: "moon.fill", color: .indigo)
                    .disabled(!(s?.perms.dnd ?? false))
                Picker(selection: binding(.soundMode, default: 0)) {
                    Text("Sound").tag(0)
                    Text("Vibrate").tag(1)
                    Text("Mute").tag(2)
                } label: {
                    Label { Text("Sound mode") } icon: { icon("bell.fill", .red) }
                }
                volume("Media volume", .mediaVolume, symbol: "speaker.wave.2.fill")
                volume("Ring volume", .ringVolume, symbol: "bell.and.waves.left.and.right.fill")
                volume("Notification volume", .notifyVolume, symbol: "bell.badge.waveform.fill")
                volume("Alarm volume", .alarmVolume, symbol: "alarm.fill")
                NavigationLink { SoundsView() } label: {
                    Label { Text("Ringtone & notification sound") } icon: { icon("music.note.list", .purple) }
                }
            } header: {
                Text("Display & sounds")
            } footer: {
                if let p = s?.perms, !p.system || !p.dnd {
                    Text("Some options need a one-time permission on the watch: Wear OS Bridge → Settings → Let iPhone change.")
                }
            }

            Section {
                toggle("Always-on display", .alwaysOn, symbol: "sun.min.fill", color: .yellow)
                toggle("Raise wrist to wake", .raiseToWake, symbol: "hand.raised.fill", color: .blue)
                toggle("Double-tap to wake", .tapToWake, symbol: "hand.tap.fill", color: .blue)
                toggle("Turn bezel to wake", .bezelWake, symbol: "circle.dashed", color: .blue)
                toggle("Touch sensitivity (gloves)", .touchSensitivity, symbol: "hand.point.up.left.fill", color: .gray)
                Picker(selection: binding(.fontSize, default: 1)) {
                    Text("Small").tag(0); Text("Default").tag(1); Text("Large").tag(2); Text("Larger").tag(3); Text("Largest").tag(4)
                } label: {
                    Label { Text("Font size") } icon: { icon("textformat.size", .gray) }
                }
            } header: {
                Text("Display & gestures")
            } footer: {
                if s?.perms.secure != true {
                    Text("Needs a one-time setup over adb (WRITE_SECURE_SETTINGS) on the watch.")
                }
            }
            .disabled(s?.perms.secure != true)

            Section {
                toggle("Theatre mode", .theatreMode, symbol: "theatermasks.fill", color: .purple)
                toggle("Bedtime mode", .bedtimeMode, symbol: "bed.double.fill", color: .indigo)
                toggle("Power saving", .batterySaver, symbol: "battery.25percent", color: .orange)
                toggle("Water lock", .waterLock, symbol: "drop.fill", color: .cyan)
            } header: {
                Text("Modes")
            }
            .disabled(s?.perms.secure != true)

            Section {
                toggle("Heart rate", .syncHeart, symbol: "heart.fill", color: Theme.heart)
                toggle("Steps", .syncSteps, symbol: "figure.walk", color: Theme.steps)
                toggle("Active energy", .syncEnergy, symbol: "flame.fill", color: Theme.energy)
                toggle("Distance", .syncDistance, symbol: "location.north.line.fill", color: Theme.distance)
                toggle("Floors climbed", .syncFloors, symbol: "figure.stairs", color: .orange)
                healthToggle("Blood oxygen", .syncSpo2, symbol: "lungs.fill", color: Theme.accent2,
                             latest: health.summary.spo2.map { "\(Int($0.rounded()))%" })
                healthToggle("Skin temperature", .syncSkinTemp, symbol: "thermometer.medium", color: .orange, latest: nil)
                healthToggle("Sleep", .syncSleep, symbol: "bed.double.fill", color: .indigo, latest: nil)
            } header: {
                Text("Apple Health")
            } footer: {
                Text("Heart rate, steps, energy, distance and floors sync automatically. Blood oxygen, skin temperature and sleep need Samsung Health developer mode on the watch (set it up under Health → Body sensors); turn them on to write their readings to Apple Health.")
            }

            Section {
                NavigationLink { BackgroundReadingsView() } label: {
                    sensorRow("Automatic & overnight readings", symbol: "clock.arrow.circlepath", color: .purple,
                              detail: HealthDepthStore.shared.config.autoLog || HealthDepthStore.shared.config.overnight ? "On" : "Off")
                }
                NavigationLink { GoalsRemindersView() } label: {
                    sensorRow("Goals & reminders", symbol: "target", color: .orange, detail: "")
                }
            } header: {
                Text("Health extras")
            } footer: {
                Text("HRV and sleeping heart rate follow the Heart rate toggle. Water, caffeine and cycle entries you log always go to Apple Health.")
            }

            Section {
                NavigationLink {
                    BodySensorsView()
                } label: {
                    sensorRow("Blood oxygen & skin temperature", symbol: "heart.text.square.fill", color: .red,
                              detail: "Measure on demand")
                }
                NavigationLink {
                    BodySensorsView()
                } label: {
                    sensorRow("ECG", symbol: "waveform.path.ecg", color: .pink,
                              detail: hub.ecg.map { "Recorded \($0.at.formatted(.relative(presentation: .named)))" } ?? "Record (display only)")
                }
                NavigationLink {
                    BodySensorsView()
                } label: {
                    sensorRow("Body composition", symbol: "figure.arms.open", color: .teal,
                              detail: hub.bodyComposition?.fatPct.map { "Body fat \(Int($0.rounded()))%" } ?? "Measure on demand")
                }
            } header: {
                Text("On-demand sensors")
            } footer: {
                Text("ECG is shown in the app only — Apple Health doesn't accept ECG from third-party apps.")
            }

            Section {
                Toggle(isOn: $phone.shareWeather) { Label { Text("Weather") } icon: { icon("cloud.sun.fill", .cyan) } }
                Toggle(isOn: $phone.shareCalendar) { Label { Text("Calendar") } icon: { icon("calendar", .red) } }
            } header: {
                Text("Show on watch")
            } footer: {
                Text("Weather comes from Open-Meteo using your approximate (~1 km) location. Calendar events go only to your watch.")
            }

            if let s {
                Section("About") {
                    row("Model", s.model)
                    row("Software", s.os)
                    row("Wear OS Bridge on watch", s.app)
                    row("Battery", "\(s.pct)%" + (s.charging ? " · charging" : ""))
                    if s.totalMB > 0 {
                        row("Storage", "\(gb(s.freeMB)) free of \(gb(s.totalMB))")
                    }
                }
            }
        }
        .auroraScreen()
        .navigationTitle("Watch settings")
        .navigationBarTitleDisplayMode(.inline)
        .toast($hub.toast)
        .disabled(!connected)
        .onAppear { link.send(.getStatus) }
        .onChange(of: s?.settings[WatchSetting.brightness.rawValue]) { _, v in
            if let v, !editingBrightness { brightness = Double(v) }
        }
        .task { if let v = s?.settings[WatchSetting.brightness.rawValue] { brightness = Double(v) } }
    }

    private func binding(_ key: WatchSetting, default d: Int) -> Binding<Int> {
        Binding(get: { s?.settings[key.rawValue] ?? d }, set: { link.set(key, $0) })
    }

    private func volume(_ title: String, _ key: WatchSetting, symbol: String) -> some View {
        HStack {
            icon(symbol, .pink)
            Slider(value: Binding(get: { Double(s?.settings[key.rawValue] ?? 8) },
                                  set: { link.set(key, Int($0.rounded())) }),
                   in: 0...15, step: 1) { Text(title) }
        }
        .accessibilityLabel(title)
    }

    private func toggle(_ title: String, _ key: WatchSetting, symbol: String, color: Color) -> some View {
        Toggle(isOn: Binding(get: { (s?.settings[key.rawValue] ?? 0) == 1 },
                             set: { link.set(key, $0 ? 1 : 0) })) {
            Label { Text(title) } icon: { icon(symbol, color) }
        }
    }

    /// A sync toggle that also shows the latest reading for that sensor, when one has arrived.
    private func healthToggle(_ title: String, _ key: WatchSetting, symbol: String, color: Color, latest: String?) -> some View {
        Toggle(isOn: Binding(get: { (s?.settings[key.rawValue] ?? 0) == 1 },
                             set: { link.set(key, $0 ? 1 : 0) })) {
            Label {
                HStack {
                    Text(title)
                    if let latest {
                        Spacer(minLength: 8)
                        Text(latest).font(.caption).foregroundStyle(.secondary)
                    }
                }
            } icon: { icon(symbol, color) }
        }
    }

    private func sensorRow(_ title: String, symbol: String, color: Color, detail: String) -> some View {
        Label {
            HStack {
                Text(title)
                Spacer(minLength: 8)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        } icon: { icon(symbol, color) }
    }

    private func icon(_ symbol: String, _ color: Color) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 28, height: 28)
            .background(color, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
    }

    private func row(_ title: String, _ value: String) -> some View {
        LabeledContent(title, value: value)
    }

    private func gb(_ mb: Int) -> String {
        (Double(mb) / 1000).formatted(.number.precision(.fractionLength(1))) + " GB"
    }
}

struct SoundsView: View {
    @EnvironmentObject var hub: FeatureHub

    var body: some View {
        Form {
            Section("Ringtone") {
                ForEach(hub.ringtones) { s in row(s, current: hub.ringNow) { hub.setSound("ring", s.uri) } }
            }
            Section("Notification sound") {
                ForEach(hub.notifSounds) { s in row(s, current: hub.notifNow) { hub.setSound("notif", s.uri) } }
            }
        }
        .overlay { if hub.ringtones.isEmpty { ProgressView() } }
        .auroraScreen()
        .navigationTitle("Sounds")
        .onAppear { hub.requestSounds() }
    }

    private func row(_ s: FeatureHub.Sound, current: String, pick: @escaping () -> Void) -> some View {
        Button(action: pick) {
            HStack { Text(s.title).foregroundStyle(.primary); Spacer(); if s.uri == current { Image(systemName: "checkmark") } }
        }
    }
}

struct ConnectionHistoryView: View {
    @EnvironmentObject var link: WatchLink

    var body: some View {
        List(link.history) { e in
            Label {
                VStack(alignment: .leading) {
                    Text(e.connected ? "Connected" : "Disconnected")
                    Text(e.date.formatted(date: .abbreviated, time: .standard)).font(.caption).foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: e.connected ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .foregroundStyle(e.connected ? .green : .red)
            }
        }
        .overlay { if link.history.isEmpty { ContentUnavailableView("No history yet", systemImage: "clock") } }
        .auroraScreen()
        .navigationTitle("Connection history")
    }
}
