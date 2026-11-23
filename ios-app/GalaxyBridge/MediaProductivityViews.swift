import AVKit
import Combine
import MediaPlayer
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import WearLink
#if canImport(Translation)
import Translation
#endif

// Screens for the media, customisation and productivity features (Wave D). They sit on the
// existing Watch tab as FeatureTiles and follow the app's look: aurora background, Form or cards.

private struct WDNotConnected: View {
    @EnvironmentObject var link: WatchLink
    var body: some View {
        if link.status != .connected {
            Label("Connect your watch to use this.", systemImage: "applewatch.slash").foregroundStyle(.secondary)
        }
    }
}

private func mmss(_ ms: Int64) -> String { Duration.milliseconds(ms).formatted(.time(pattern: .minuteSecond)) }

// MARK: - Music on the watch

struct WatchMusicView: View {
    @EnvironmentObject var extras: MediaProductivityHub
    @State private var picking = false

    var body: some View {
        Form {
            WDNotConnected()
            Section {
                Button { picking = true } label: { Label("Send a song or podcast…", systemImage: "square.and.arrow.up") }
                    .disabled(extras.pushing != nil)
                if let p = extras.pushing {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Sending \(p.name)").font(.subheadline)
                        ProgressView(value: p.progress)
                        Button("Stop", role: .destructive) { extras.cancelPush() }.font(.caption)
                    }
                }
            } footer: {
                Text("Pick MP3, M4A/AAC, FLAC, OGG or WAV files from Files (up to 80 MB each). They're stored on the watch and play offline through Bluetooth earbuds paired with the watch. Bluetooth LE is slow: allow about a minute per few MB, with the app open. Apple Music downloads are DRM-protected and can't be copied.")
            }
            Section("On the watch") {
                if extras.tracks.isEmpty { Text("Nothing yet").foregroundStyle(.secondary) }
                ForEach(extras.tracks) { t in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.title)
                        Text([t.artist, mmss(t.ms), ByteCountFormatter.string(fromByteCount: t.bytes, countStyle: .file)]
                            .filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                    }
                    .swipeActions { Button("Delete", role: .destructive) { extras.deleteTrack(t) } }
                }
                if let free = extras.watchFreeMB { Text("\(free) MB free on the watch").font(.caption).foregroundStyle(.secondary) }
            }
            Section {
                Label("On the watch: Wear OS Bridge → Extras → Music on watch.", systemImage: "applewatch")
                Label("Pair earbuds in the watch's Settings → Connections → Bluetooth.", systemImage: "airpodspro")
            }
        }
        .auroraScreen()
        .navigationTitle("Music on watch")
        .fileImporter(isPresented: $picking, allowedContentTypes: [.audio], allowsMultipleSelection: false) { result in
            if case let .success(urls) = result, let url = urls.first { Task { await extras.pushAudio(url) } }
        }
        .onAppear { extras.requestMusicList() }
        .refreshable { extras.requestMusicList() }
        .toast($extras.toast)
    }
}

// MARK: - iPhone media: seek, output, what the watch can do

/// The system AirPlay/Bluetooth output picker (the iPhone's own; iOS has no API to switch it silently).
private struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = UIColor(Theme.accent2)
        v.prioritizesVideoDevices = false
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

struct IPhoneMediaView: View {
    @EnvironmentObject var extras: MediaProductivityHub
    @State private var position = 0.0
    @State private var duration = 0.0
    @State private var title = ""
    @State private var dragging = false
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Form {
            Section {
                HStack {
                    Text("Audio output")
                    Spacer()
                    RoutePicker().frame(width: 44, height: 44)
                }
            } footer: {
                Text("Choose the iPhone's speaker, AirPods or AirPlay here. iOS doesn't let another device (or app) switch the output without this picker, so it can't be done from the watch.")
            }
            Section {
                if !extras.musicAuthorized {
                    Button("Allow access to Apple Music") { Task { await extras.requestMusicAccess() } }
                } else if duration > 0 {
                    Text(title.isEmpty ? "Apple Music" : title).font(.headline)
                    Slider(value: $position, in: 0...max(duration, 1)) { editing in
                        dragging = editing
                        if !editing { extras.seekAppleMusic(to: position) }
                    }
                    HStack {
                        Text(mmss(Int64(position * 1000))); Spacer(); Text(mmss(Int64(duration * 1000)))
                    }.font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Nothing playing in Apple Music").foregroundStyle(.secondary)
                }
            } header: { Text("Seek (Apple Music)") } footer: {
                Text("On the watch: Music → More controls, then turn the bezel and tap Seek. Only the Apple Music player can be scrubbed from outside; Spotify, YouTube and other apps can't.")
            }
            Section("From the watch") {
                Label("Skip back/forward 15 s, like, dislike, bookmark, repeat and shuffle (Apple Media Service). Each works if the playing app supports it.", systemImage: "applewatch")
                Label("Play, pause, next, previous and volume as before.", systemImage: "playpause.fill")
            }
        }
        .auroraScreen()
        .navigationTitle("iPhone media")
        .onReceive(timer) { _ in refresh() }
        .onAppear { refresh() }
        .toast($extras.toast)
    }

    private func refresh() {
        guard extras.musicAuthorized, !dragging else { return }
        let p = MPMusicPlayerController.systemMusicPlayer
        let item = p.nowPlayingItem
        duration = item?.playbackDuration ?? 0
        title = item?.title ?? ""
        let t = p.currentPlaybackTime
        position = t.isFinite ? min(t, duration) : 0
    }
}

// MARK: - Presentation clicker

struct ClickerInfoView: View {
    var body: some View {
        Form {
            Section {
                Label("On the watch: Wear OS Bridge → Extras → Clicker.", systemImage: "1.circle")
                Label("Pick the device (this iPhone or a paired computer) and tap Start.", systemImage: "2.circle")
                Label("Turn the bezel or tap ◀ ▶ to change slides. B blanks the screen, Esc leaves the show.", systemImage: "3.circle")
            } footer: {
                Text("The watch acts as a small Bluetooth keyboard with only slide keys (arrows, B, Esc). Works with Keynote, PowerPoint and Google Slides. To use it with a computer, pair the watch with it first in the watch's Bluetooth settings. While it's connected to the iPhone, iOS hides the on-screen keyboard and the camera shutter is paused; tap ■ to stop.")
            }
        }
        .auroraScreen()
        .navigationTitle("Clicker")
    }
}

// MARK: - Face schedule

struct FaceScheduleView: View {
    @EnvironmentObject var extras: MediaProductivityHub
    @EnvironmentObject var hub: FeatureHub
    @EnvironmentObject var link: WatchLink
    @State private var newTime = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
    @State private var newFace = ""

    var body: some View {
        Form {
            WDNotConnected()
            Section {
                Toggle("Switch faces automatically", isOn: $extras.plan.on)
            } footer: {
                Text("Your current face isn't changed when you save — only when a time below comes round. Works with classic faces (including the Wear OS Bridge face); Watch Face Format faces from Google Play can't be switched without a tap and are skipped. The watch shows the face's edit screen briefly after a switch.")
            }
            Section("Times") {
                ForEach(extras.plan.slots.sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }) { s in
                    HStack {
                        Text(String(format: "%02d:%02d", s.hour, s.minute)).monospacedDigit().font(.headline)
                        Text(s.name).foregroundStyle(.secondary)
                    }
                    .swipeActions { Button("Remove", role: .destructive) { extras.plan.slots.removeAll { $0.id == s.id } } }
                }
                if extras.plan.slots.count < 8 {
                    DatePicker("From", selection: $newTime, displayedComponents: .hourAndMinute)
                    facePicker("Face", selection: $newFace)
                    Button("Add time") {
                        let c = Calendar.current.dateComponents([.hour, .minute], from: newTime)
                        guard let f = hub.faces.first(where: { $0.id == newFace }) else { return }
                        extras.plan.slots.removeAll { $0.hour == c.hour && $0.minute == c.minute }
                        extras.plan.slots.append(.init(hour: c.hour ?? 0, minute: c.minute ?? 0, pkg: f.id, name: f.name))
                    }
                    .disabled(newFace.isEmpty)
                }
            }
            Section {
                facePicker("Workout face", selection: Binding(get: { extras.plan.workoutPkg ?? "" }, set: { pkg in
                    extras.plan.workoutPkg = pkg.isEmpty ? nil : pkg
                    extras.plan.workoutName = hub.faces.first { $0.id == pkg }?.name
                }), allowNone: true)
            } header: { Text("During workouts") } footer: {
                Text("Optional. Switches when you start a workout in Wear OS Bridge on the watch, and back afterwards.")
            }
            Section {
                Button("Save to watch") { extras.saveFacePlan() }.disabled(link.status != .connected)
            }
        }
        .auroraScreen()
        .navigationTitle("Face schedule")
        .task { hub.requestFaces(); extras.requestFacePlan() }
        .toast($extras.toast)
    }

    private func facePicker(_ title: String, selection: Binding<String>, allowNone: Bool = false) -> some View {
        Picker(title, selection: selection) {
            Text(allowNone ? "None" : "Choose…").tag("")
            ForEach(hub.faces) { Text($0.name).tag($0.id) }
        }
    }
}

// MARK: - Tile manager

struct TileManagerView: View {
    @EnvironmentObject var extras: MediaProductivityHub

    var body: some View {
        Form {
            WDNotConnected()
            Section {
                if extras.tiles.isEmpty { Text("Loading…").foregroundStyle(.secondary) }
                ForEach(extras.tiles) { t in
                    HStack {
                        Text(t.label)
                        Spacer()
                        switch t.active {
                        case .some(true): Label("On watch", systemImage: "checkmark.circle.fill").foregroundStyle(Theme.accent2).labelStyle(.titleAndIcon)
                        case .some(false): Text("Not added").foregroundStyle(.secondary)
                        case .none: Text("—").foregroundStyle(.secondary)
                        }
                    }
                    .font(.subheadline)
                }
            } header: { Text("Wear OS Bridge tiles") } footer: {
                Text(extras.tilesKnown ? "Live from the watch." : "This watch didn't report which tiles are added.")
            }
            Section("Set up what they show") {
                NavigationLink("World clock cities") { WorldClockView() }
                NavigationLink("Checklist list") { ChecklistView() }
                NavigationLink("Passes") { PassesView() }
                NavigationLink("Home shortcuts") { HomeShortcutsView() }
                NavigationLink("Translate languages") { TranslateView() }
            }
            Section {
                Label("Add, remove or reorder tiles on the watch: swipe to the end of your tiles and tap +, or touch and hold a tile.", systemImage: "hand.tap")
            } footer: {
                Text("Wear OS doesn't let other apps add, remove or reorder tiles, or reorder the app drawer: on Galaxy watches those editors only talk to Samsung's Galaxy Wearable app on an Android phone. So the iPhone can see which of our tiles are added and change what they show, but the arranging stays on the watch.")
            }
        }
        .auroraScreen()
        .navigationTitle("Tile manager")
        .task { extras.requestTiles() }
        .refreshable { extras.requestTiles() }
    }
}

// MARK: - Translate

struct TranslateView: View {
    @EnvironmentObject var extras: MediaProductivityHub
    @State private var preparing = false
    @State private var prepareKey = 0

    var body: some View {
        Form {
            Section {
                languagePicker("Language 1", $extras.langA)
                languagePicker("Language 2", $extras.langB)
                Button("Send to watch") { extras.sendTools() }
            } footer: {
                Text("On the watch: Tools tile → Translate, or Extras → Translate. Speak or type, and the iPhone translates it with Apple's on-device Translation and sends it back. Tap the language pair on the watch to swap direction.")
            }
            Section {
                if #available(iOS 18.0, *) {
                    Button(preparing ? "Downloading…" : "Download these languages") { preparing = true; prepareKey += 1 }
                        .disabled(preparing)
                        .modifier(PrepareTranslation(a: extras.langA, b: extras.langB, key: prepareKey, done: { preparing = false }))
                }
                if #unavailable(iOS 26.0) {
                    Label("Translating requests from the watch in the background needs iOS 26 or later on this iPhone.", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            } footer: {
                Text("Translation runs on the iPhone, offline, once both languages are downloaded.")
            }
            if let last = extras.lastTranslation {
                Section("Last from the watch") {
                    Text(last.source).foregroundStyle(.secondary)
                    Text(last.target)
                }
            }
        }
        .auroraScreen()
        .navigationTitle("Translate")
        .toast($extras.toast)
    }

    private func languagePicker(_ title: String, _ value: Binding<String>) -> some View {
        Picker(title, selection: value) {
            ForEach(MediaProductivityHub.languageChoices, id: \.self) { Text(MediaProductivityHub.languageName($0)).tag($0) }
        }
    }
}

/// Asks iOS to download the pair (it shows its own sheet). Only the view-attached session can do that.
private struct PrepareTranslation: ViewModifier {
    let a: String, b: String, key: Int
    let done: () -> Void

    func body(content: Content) -> some View {
        #if canImport(Translation)
        if #available(iOS 18.0, *), key > 0 {
            content.translationTask(TranslationSession.Configuration(source: Locale.Language(identifier: a),
                                                                    target: Locale.Language(identifier: b))) { session in
                try? await session.prepareTranslation()
                await MainActor.run { done() }
            }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

// MARK: - World clock

struct WorldClockView: View {
    @EnvironmentObject var extras: MediaProductivityHub
    @State private var search = ""

    private var matches: [String] {
        let q = search.lowercased()
        return q.count < 2 ? [] : TimeZone.knownTimeZoneIdentifiers.filter { $0.lowercased().contains(q) }.prefix(30).map { $0 }
    }

    var body: some View {
        Form {
            Section {
                ForEach(extras.clocks) { c in
                    HStack {
                        Text(c.label)
                        Spacer()
                        Text(Date(), format: .dateTime.hour().minute().timeZone()).environment(\.timeZone, TimeZone(identifier: c.tz) ?? .current)
                            .foregroundStyle(.secondary)
                    }
                }
                .onDelete { extras.clocks.remove(atOffsets: $0); extras.sendTools() }
                .onMove { extras.clocks.move(fromOffsets: $0, toOffset: $1); extras.sendTools() }
            } header: { Text("Cities") } footer: {
                Text("Shown on the World clock tile (add it on the watch) and in Extras. Up to 6; the tile shows the first 4.")
            }
            if extras.clocks.count < 6 {
                Section("Add a city") {
                    TextField("Search, e.g. London or Tokyo", text: $search).autocorrectionDisabled()
                    ForEach(matches, id: \.self) { tz in
                        Button(tz.replacingOccurrences(of: "_", with: " ")) {
                            let label = tz.split(separator: "/").last.map { String($0).replacingOccurrences(of: "_", with: " ") } ?? tz
                            extras.clocks.append(.init(tz: tz, label: label))
                            extras.sendTools()
                            search = ""
                        }
                    }
                }
            }
        }
        .auroraScreen()
        .navigationTitle("World clock")
        .toolbar { EditButton() }
    }
}

// MARK: - Checklist

struct ChecklistView: View {
    @EnvironmentObject var extras: MediaProductivityHub

    var body: some View {
        Form {
            WDNotConnected()
            if extras.reminderLists.isEmpty {
                Section { Button("Allow access to Reminders") { Task { await extras.requestRemindersAccess() } } }
            } else {
                Section {
                    Picker("List", selection: $extras.checklistId) {
                        Text("None").tag("")
                        ForEach(extras.reminderLists, id: \.id) { Text($0.title).tag($0.id) }
                    }
                } footer: {
                    Text("This Reminders list appears on the Checklist tile and in Extras on the watch, including items ticked in the last day. Ticking or unticking on the watch updates Reminders here, changes here show on the watch, and Add item on the watch creates a reminder in this list.")
                }
            }
        }
        .auroraScreen()
        .navigationTitle("Checklist")
        .onAppear { extras.loadReminderLists() }
    }
}

// MARK: - Passes (QR / barcode images, not Wallet)

struct PassesView: View {
    @EnvironmentObject var extras: MediaProductivityHub
    @State private var code = ""
    @State private var symbology = MediaProductivityHub.Symbology.qr
    @State private var title = ""
    @State private var sub = ""
    @State private var photo: PhotosPickerItem?

    var body: some View {
        Form {
            WDNotConnected()
            Section {
                TextField("Name, e.g. Flight NZ123", text: $title)
                TextField("Detail, e.g. Seat 14A · Gate 5", text: $sub)
            }
            Section {
                PhotosPicker(selection: $photo, matching: .images) { Label("From a screenshot or photo", systemImage: "photo") }
            } footer: {
                Text("If the picture has a QR, Aztec, PDF417 or Code 128 code, it's redrawn sharply for the watch; otherwise the picture itself is sent.")
            }
            Section {
                TextField("Code text", text: $code, axis: .vertical).lineLimit(1...4).autocorrectionDisabled()
                Picker("Type", selection: $symbology) { ForEach(MediaProductivityHub.Symbology.allCases) { Text($0.rawValue).tag($0) } }
                if let img = MediaProductivityHub.barcode(code, symbology) {
                    Image(uiImage: img).interpolation(.none).resizable().scaledToFit().frame(maxHeight: 160)
                        .padding(8).background(.white, in: RoundedRectangle(cornerRadius: 10))
                }
                Button("Send to watch") { extras.sendCode(code, symbology: symbology, title: title.isEmpty ? "Pass" : title, sub: sub) }
                    .disabled(code.isEmpty)
            } header: { Text("Or type the code") }
            Section("On the watch") {
                if extras.passes.isEmpty { Text("None yet").foregroundStyle(.secondary) }
                ForEach(extras.passes) { p in
                    VStack(alignment: .leading) { Text(p.title); if !p.sub.isEmpty { Text(p.sub).font(.caption).foregroundStyle(.secondary) } }
                        .swipeActions { Button("Delete", role: .destructive) { extras.deletePass(p) } }
                }
            }
            Section {
                Text("Add the Passes tile on the watch: the newest pass shows on the tile, and tapping it opens it full screen at full brightness for scanning. These are plain pictures — not Apple Wallet, and nothing to do with payments.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .auroraScreen()
        .navigationTitle("Passes")
        .onAppear { extras.requestPasses() }
        .onChange(of: photo) { _, new in
            guard let new else { return }
            Task {
                if let data = try? await new.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                    await extras.sendPassPhoto(img, title: title.isEmpty ? "Pass" : title, sub: sub)
                }
                photo = nil
            }
        }
        .toast($extras.toast)
    }
}

// MARK: - Home shortcuts

struct HomeShortcutsView: View {
    @EnvironmentObject var extras: MediaProductivityHub
    @State private var name = ""
    @State private var shortcut = ""

    var body: some View {
        Form {
            WDNotConnected()
            Section {
                ForEach(extras.scenes) { s in
                    HStack {
                        VStack(alignment: .leading) { Text(s.name); Text("Shortcut: \(s.shortcut)").font(.caption).foregroundStyle(.secondary) }
                        Spacer()
                        Button { extras.runScene(s) } label: { Image(systemName: "play.circle.fill") }.buttonStyle(.borderless)
                    }
                }
                .onDelete { extras.scenes.remove(atOffsets: $0); extras.sendScenes() }
            } header: { Text("On the Home tile") }
            if extras.scenes.count < 8 {
                Section {
                    TextField("Button name, e.g. Lights off", text: $name)
                    TextField("Shortcut name (exactly as in Shortcuts)", text: $shortcut).autocorrectionDisabled()
                    Button("Add") {
                        extras.scenes.append(.init(name: String(name.prefix(30)), shortcut: shortcut))
                        extras.sendScenes()
                        name = ""; shortcut = ""
                        Task { await extras.requestNotificationAccess() }
                    }
                    .disabled(name.isEmpty || shortcut.isEmpty)
                } header: { Text("Add a shortcut") }
            }
            Section {
                Text("Make a shortcut in Apple's Shortcuts app that controls your Home scene or accessories (Home → Control My Home, or a SmartThings/Hue action), then add it here. Tapping it on the watch runs it on the iPhone.")
                Text("iOS only lets an app start Shortcuts — or use HomeKit — while it's on screen. If the iPhone is locked or Wear OS Bridge isn't open, you'll get a notification on the iPhone: tap it and the shortcut runs.")
                    .foregroundStyle(.secondary)
            }
            .font(.footnote)
        }
        .auroraScreen()
        .navigationTitle("Home shortcuts")
        .toast($extras.toast)
    }
}

// MARK: - Widget & Live Activity

struct WidgetsView: View {
    @EnvironmentObject var extras: MediaProductivityHub

    var body: some View {
        Form {
            Section {
                HStack {
                    reading("heart.fill", Theme.heart, extras.snapshot.heartRate.map(String.init) ?? "–", "bpm")
                    reading("figure.walk", Theme.steps, extras.snapshot.steps.map { $0.formatted() } ?? "–", "steps")
                    reading("applewatch", Theme.accent2, extras.snapshot.battery.map { "\($0)%" } ?? "–", "battery")
                }
            } header: { Text("Shared with the widget") }
            Section {
                Toggle("Live Activity", isOn: Binding(get: { extras.liveActivityOn }, set: { $0 ? extras.startLiveActivity() : extras.stopLiveActivity() }))
            } footer: {
                Text("Shows the watch's heart rate, steps and battery on the Lock Screen and in the Dynamic Island, updated as readings arrive. iOS ends Live Activities after about 8 hours; turn it on again when you need it.")
            }
            Section {
                Label("Home Screen: touch and hold, tap Edit → Add Widget, choose Wear OS Bridge.", systemImage: "apps.iphone")
                Label("Lock Screen: touch and hold the Lock Screen, Customise → Lock Screen, add the Wear OS Bridge widget.", systemImage: "lock.iphone")
            } header: { Text("Widgets") }
        }
        .auroraScreen()
        .navigationTitle("Widget & Live Activity")
        .toast($extras.toast)
    }

    private func reading(_ symbol: String, _ color: Color, _ value: String, _ unit: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).foregroundStyle(color)
            Text(value).font(.headline)
            Text(unit).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}
