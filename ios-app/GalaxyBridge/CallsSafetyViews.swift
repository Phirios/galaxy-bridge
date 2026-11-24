import Contacts
import ContactsUI
import MapKit
import SwiftUI
import UIKit
import WearLink

// MARK: - Favourites, recents and quick replies

/// Favourites to call from the watch, its call history (one tap to call back from the watch) and
/// the quick replies it offers through Siri.
struct CallsFavouritesView: View {
    @EnvironmentObject var hub: FeatureHub
    @EnvironmentObject var link: WatchLink
    @ObservedObject private var store = CallsSafetyStore.shared
    @State private var newReply = ""

    private var connected: Bool { link.status == .connected }

    var body: some View {
        Form {
            Section {
                if store.favourites.isEmpty {
                    Text("Add up to \(CallsSafetyLimits.maxFavourites) people. They appear in Phone on the watch and on the Calls & SOS tile.")
                        .foregroundStyle(.secondary)
                }
                ForEach(Array(store.favourites.enumerated()), id: \.element.id) { i, f in
                    HStack(spacing: 12) {
                        Image(systemName: "star.fill")
                            .foregroundStyle(.yellow)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.name.isEmpty ? f.number : f.name)
                                .font(.body)
                            Text(f.number)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button { store.moveFavourite(f, up: true) } label: { Image(systemName: "chevron.up") }
                            .disabled(i == 0)
                            .accessibilityLabel("Move \(f.name.isEmpty ? f.number : f.name) up")
                        Button(role: .destructive) { store.removeFavourite(f) } label: { Image(systemName: "trash") }
                            .accessibilityLabel("Remove \(f.name.isEmpty ? f.number : f.name)")
                    }
                    .buttonStyle(.borderless)
                }
                Button {
                    ContactPhonePicker.present { name, number in store.addFavourite(name: name, number: number) }
                } label: {
                    Label("Add from Contacts", systemImage: "person.crop.circle.badge.plus")
                }
                .disabled(store.favourites.count >= CallsSafetyLimits.maxFavourites)
            } header: {
                Text("Favourites")
            }

            Section {
                if store.callLog.isEmpty {
                    Text(connected ? "No calls yet. Calls the watch places or sees appear here." : "Connect your watch to see its recent calls.")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.callLog) { e in
                    HStack(spacing: 12) {
                        Image(systemName: symbol(e.direction))
                            .foregroundStyle(e.direction == .missed ? .red : .green)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.name.isEmpty ? e.number : e.name)
                                .font(.body)
                                .foregroundStyle(e.direction == .missed ? .red : .primary)
                            HStack(spacing: 4) {
                                Text(directionLabel(e.direction))
                                Text("·")
                                Text(e.at, style: .relative)
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if !e.number.isEmpty {
                            Button { hub.call("dial", number: e.number) } label: {
                                Image(systemName: "phone.arrow.up.right.fill")
                            }
                            .buttonStyle(.bordered)
                            .tint(.green)
                            .disabled(!connected)
                            .accessibilityLabel("Call back \(e.name.isEmpty ? e.number : e.name) from the watch")
                        }
                    }
                }
            } header: {
                Text("Recent calls on the watch")
            }

            Section {
                ForEach(store.quickReplies, id: \.self) { r in
                    HStack {
                        Text(r)
                        Spacer()
                        Button(role: .destructive) { store.setQuickReplies(store.quickReplies.filter { $0 != r }) } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Remove \(r)")
                    }
                }
                if store.quickReplies.count < CallsSafetyLimits.maxQuickReplies {
                    HStack {
                        TextField("New reply", text: $newReply)
                        Button("Add") {
                            store.setQuickReplies(store.quickReplies + [newReply]); newReply = ""
                        }
                        .disabled(newReply.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            } header: {
                Text("Quick replies")
            } footer: {
                Text("Tap one on the watch: Siri starts on your iPhone over the hands-free link and you say the reply; iOS composes and sends it. The iPhone also gets a notification that opens Messages with the text filled in. Nothing is ever sent without you.")
            }

            Section {
                Text("Calls are placed by the watch over its hands-free (classic Bluetooth) pairing with this iPhone, which is set up in the watch's Bluetooth settings.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Favourites & recents")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { store.refresh() } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(!connected)
                    .accessibilityLabel("Refresh recent calls")
            }
        }
        .onAppear { store.attach(hub); store.refresh() }
        .toast($hub.toast)
    }

    private func symbol(_ d: CallLogEntry.Direction) -> String {
        switch d {
        case .incoming: "phone.arrow.down.left.fill"
        case .outgoing: "phone.arrow.up.right.fill"
        case .missed: "phone.down.fill"
        }
    }

    private func directionLabel(_ d: CallLogEntry.Direction) -> String {
        switch d {
        case .incoming: "Incoming"
        case .outgoing: "Outgoing"
        case .missed: "Missed"
        }
    }
}

// MARK: - Safety

/// Emergency contact, fall detection, SOS, no-movement check-in, heart-rate alerts and loud find.
struct SafetyView: View {
    @EnvironmentObject var hub: FeatureHub
    @EnvironmentObject var link: WatchLink
    @ObservedObject private var store = CallsSafetyStore.shared
    @State private var name = ""
    @State private var number = ""

    private var connected: Bool { link.status == .connected }
    private var hasContact: Bool { store.config.hasContact }

    var body: some View {
        Form {
            if let a = store.lastAlert, a.stage != .cancelled, Date().timeIntervalSince(a.at) < 3600 {
                alertSection(a)
            }

            Section {
                TextField("Name", text: $name)
                TextField("Phone number", text: $number)
                    .keyboardType(.phonePad)
                if !number.isEmpty, EmergencyNumbers.isPublicEmergency(number) {
                    Label("Choose a person, not an emergency service. Wear OS Bridge never calls emergency numbers automatically.", systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                Button {
                    ContactPhonePicker.present { n, p in name = n; number = p; save() }
                } label: {
                    Label("Choose from Contacts", systemImage: "person.crop.circle")
                }
                Button { save() } label: {
                    Label("Save contact", systemImage: "checkmark")
                }
                .disabled(!EmergencyNumbers.isAllowedForAutoCall(number) ||
                          (name == store.config.contactName && number == store.config.contactNumber))
                if hasContact {
                    Label("\(store.config.contactName.isEmpty ? store.config.contactNumber : store.config.contactName) will be called", systemImage: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            } header: {
                Text("Emergency contact")
            } footer: {
                Text("If you fall, press SOS or stop moving, your watch counts down so you can cancel, then calls the person you choose here through your iPhone. It never calls emergency services by itself.")
            }

            Section {
                Toggle(isOn: $store.config.sos) {
                    Label("Emergency SOS", systemImage: "sos")
                }
                Toggle(isOn: $store.config.fallDetection) {
                    Label("Fall detection", systemImage: "figure.fall")
                }
                Toggle(isOn: $store.config.checkIn) {
                    Label("No-movement check-in", systemImage: "figure.stand")
                }
                if store.config.checkIn {
                    Stepper("Ask after \(store.config.checkInHours) h without moving", value: $store.config.checkInHours,
                            in: CallsSafetyLimits.checkInHoursRange)
                }
                Stepper("Countdown: \(store.config.countdown) s", value: $store.config.countdown,
                        in: CallsSafetyLimits.countdownRange, step: 5)
            } header: {
                Text("On the watch")
            } footer: {
                Text("Hold the SOS button in Phone on the watch, tap SOS on the Calls & SOS tile, or open the SOS app. Fall detection uses the watch's motion sensor (hard impact followed by stillness) and can miss falls or trigger on other knocks, which is why there is always a countdown. No-movement check-in is skipped while charging or off your wrist.")
            }
            .disabled(!hasContact)

            Section {
                Toggle(isOn: $store.config.hrAlerts) {
                    Label("Alert me", systemImage: "heart.text.square.fill")
                }
                if store.config.hrAlerts {
                    Stepper("Above \(store.config.hrHigh) bpm", value: $store.config.hrHigh, in: CallsSafetyLimits.hrHighRange, step: 5)
                    Stepper("Below \(store.config.hrLow) bpm", value: $store.config.hrLow, in: CallsSafetyLimits.hrLowRange, step: 5)
                }
                if let h = store.lastHr {
                    Label("Last alert: \(h.bpm) bpm, \(h.at.formatted(date: .omitted, time: .shortened))", systemImage: "bell.fill")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            } header: {
                Text("Heart-rate alerts")
            } footer: {
                Text("The watch checks your heart rate every 10 minutes and alerts on both the watch and this iPhone when two checks in a row are outside your limits. Not a medical device; it doesn't diagnose anything.")
            }

            Section {
                Button { store.findWatchLoud(!store.watchRingingLoud) } label: {
                    Label(store.watchRingingLoud ? "Stop ringing watch" : "Find watch (loud)", systemImage: "speaker.wave.3.fill")
                }
                .tint(.orange)
                .disabled(!connected)
                if store.phoneRingingLoud {
                    Button(role: .destructive) { store.stopLoudRing() } label: {
                        Label("Stop iPhone alarm", systemImage: "speaker.slash.fill")
                    }
                }
            } header: {
                Text("Find")
            } footer: {
                Text("The watch's alarm gets louder and its vibration stronger until you stop it. \"Find iPhone (loud)\" on the watch does the same here while this app is open.")
            }

            if store.watchState != nil || !connected {
                Section("Status") {
                    if let s = store.watchState {
                        Label(s.hasContact ? "Watch has your contact" : "Watch has no contact yet",
                              systemImage: s.hasContact ? "checkmark.circle.fill" : "exclamationmark.circle")
                            .foregroundStyle(s.hasContact ? .green : .orange)
                        if store.config.fallDetection {
                            Label(s.fallRunning ? "Fall detection running" : "Fall detection not running",
                                  systemImage: s.fallRunning ? "checkmark.circle.fill" : "exclamationmark.circle")
                                .foregroundStyle(s.fallRunning ? .green : .orange)
                        }
                        if store.config.hrAlerts, !s.hrSensor {
                            Label("No heart-rate sensor available to the app", systemImage: "exclamationmark.circle")
                                .foregroundStyle(.orange)
                        }
                    }
                    if !connected {
                        Label("Connect your watch to send these settings.", systemImage: "applewatch.slash")
                            .foregroundStyle(.orange)
                    }
                }
            }
        }
        .navigationTitle("Safety")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            store.attach(hub)
            store.refresh()
            name = store.config.contactName
            number = store.config.contactNumber
        }
    }

    private func save() {
        guard EmergencyNumbers.isAllowedForAutoCall(number) else { return }
        store.config.contactName = name.trimmingCharacters(in: .whitespaces)
        store.config.contactNumber = number.trimmingCharacters(in: .whitespaces)
    }

    @ViewBuilder
    private func alertSection(_ a: SosAlert) -> some View {
        Section {
            Label("\(CallsSafetyStore.kindTitle(a.kind)) from your watch", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.red)
            LabeledContent("Time", value: a.at.formatted(date: .omitted, time: .shortened))
            if let lat = a.latitude, let lon = a.longitude {
                let c = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                Map(initialPosition: .region(MKCoordinateRegion(center: c, latitudinalMeters: 600, longitudinalMeters: 600))) {
                    Marker("Watch", systemImage: "applewatch", coordinate: c).tint(.red)
                }
                .frame(height: 180)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                Button {
                    if let u = store.sosTextURL(a) { UIApplication.shared.open(u) }
                } label: {
                    Label("Text location", systemImage: "message.fill")
                }
                .tint(.green)
                if let m = a.mapsURL {
                    ShareLink(item: m, message: Text(store.sosMessage(a))) {
                        Label("Share location", systemImage: "square.and.arrow.up")
                    }
                }
            } else {
                Text("No location from the watch yet.")
                    .foregroundStyle(.secondary)
            }
        } footer: {
            if a.latitude != nil, a.longitude != nil {
                Text("Messages opens with the text filled in; you tap Send.")
            }
        }
    }
}

// MARK: - Contact picker

/// Shows the system contact picker for a phone number. No Contacts permission is needed for this.
@MainActor
enum ContactPhonePicker {
    private static var delegate: Delegate?

    static func present(_ onPick: @escaping (String, String) -> Void) {
        let picker = CNContactPickerViewController()
        picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
        picker.predicateForEnablingContact = NSPredicate(format: "phoneNumbers.@count > 0")
        picker.predicateForSelectionOfContact = NSPredicate(format: "phoneNumbers.@count == 1")
        let d = Delegate(onPick)
        delegate = d
        picker.delegate = d
        guard let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else { return }
        var top = root
        while let next = top.presentedViewController { top = next }
        top.present(picker, animated: true)
    }

    private final class Delegate: NSObject, CNContactPickerDelegate {
        let onPick: (String, String) -> Void
        init(_ onPick: @escaping (String, String) -> Void) { self.onPick = onPick }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            guard let phone = contact.phoneNumbers.first?.value.stringValue else { return }
            onPick(CNContactFormatter.string(from: contact, style: .fullName) ?? "", phone)
        }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
            guard let phone = (contactProperty.value as? CNPhoneNumber)?.stringValue else { return }
            onPick(CNContactFormatter.string(from: contactProperty.contact, style: .fullName) ?? "", phone)
        }
    }
}
