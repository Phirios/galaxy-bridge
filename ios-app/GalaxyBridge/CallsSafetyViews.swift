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
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                SectionTitle(text: "Favourites")
                VStack(alignment: .leading, spacing: 12) {
                    if store.favourites.isEmpty {
                        Text("Add up to \(CallsSafetyLimits.maxFavourites) people. They appear in Phone on the watch and on the Calls & SOS tile.")
                            .font(.callout).foregroundStyle(.white.opacity(0.7))
                    }
                    ForEach(Array(store.favourites.enumerated()), id: \.element.id) { i, f in
                        HStack(spacing: 12) {
                            Image(systemName: "star.fill").foregroundStyle(.yellow)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(f.name.isEmpty ? f.number : f.name).font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                                Text(f.number).font(.caption).foregroundStyle(.white.opacity(0.6))
                            }
                            Spacer()
                            Button { store.moveFavourite(f, up: true) } label: { Image(systemName: "chevron.up") }
                                .disabled(i == 0).accessibilityLabel("Move up")
                            Button(role: .destructive) { store.removeFavourite(f) } label: { Image(systemName: "trash") }
                                .accessibilityLabel("Remove \(f.name)")
                        }
                        .buttonStyle(.borderless)
                    }
                    Button {
                        ContactPhonePicker.present { name, number in store.addFavourite(name: name, number: number) }
                    } label: {
                        Label("Add from Contacts", systemImage: "person.crop.circle.badge.plus").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    .disabled(store.favourites.count >= CallsSafetyLimits.maxFavourites)
                }
                .padding(16).card(cornerRadius: 22)

                HStack {
                    SectionTitle(text: "Recent calls on the watch")
                    Spacer()
                    Button { store.refresh() } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(!connected).accessibilityLabel("Refresh")
                }
                VStack(alignment: .leading, spacing: 12) {
                    if store.callLog.isEmpty {
                        Text(connected ? "No calls yet. Calls the watch places or sees appear here." : "Connect your watch to see its recent calls.")
                            .font(.callout).foregroundStyle(.white.opacity(0.7))
                    }
                    ForEach(store.callLog) { e in
                        HStack(spacing: 12) {
                            Image(systemName: symbol(e.direction))
                                .foregroundStyle(e.direction == .missed ? .red : .green)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(e.name.isEmpty ? e.number : e.name).font(.subheadline.weight(.semibold))
                                    .foregroundStyle(e.direction == .missed ? .red : .white)
                                Text(e.at, style: .relative).font(.caption).foregroundStyle(.white.opacity(0.6))
                            }
                            Spacer()
                            if !e.number.isEmpty {
                                Button { hub.call("dial", number: e.number) } label: { Image(systemName: "phone.arrow.up.right.fill") }
                                    .buttonStyle(.bordered).tint(.green).disabled(!connected)
                                    .accessibilityLabel("Call back from the watch")
                            }
                        }
                    }
                }
                .padding(16).card(cornerRadius: 22)

                SectionTitle(text: "Quick replies")
                VStack(alignment: .leading, spacing: 12) {
                    Text("Tap one on the watch: Siri starts on your iPhone over the hands-free link and you say the reply; iOS composes and sends it. The iPhone also gets a notification that opens Messages with the text filled in. Nothing is ever sent without you.")
                        .font(.callout).foregroundStyle(.white.opacity(0.7))
                    ForEach(store.quickReplies, id: \.self) { r in
                        HStack {
                            Text(r).foregroundStyle(.white)
                            Spacer()
                            Button(role: .destructive) { store.setQuickReplies(store.quickReplies.filter { $0 != r }) } label: { Image(systemName: "minus.circle") }
                                .buttonStyle(.borderless).accessibilityLabel("Remove \(r)")
                        }
                    }
                    if store.quickReplies.count < CallsSafetyLimits.maxQuickReplies {
                        HStack {
                            TextField("New reply", text: $newReply).textFieldStyle(.roundedBorder)
                            Button("Add") {
                                store.setQuickReplies(store.quickReplies + [newReply]); newReply = ""
                            }
                            .disabled(newReply.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                    }
                }
                .padding(16).card(cornerRadius: 22)

                Text("Calls are placed by the watch over its hands-free (classic Bluetooth) pairing with this iPhone, which is set up in the watch's Bluetooth settings.")
                    .font(.caption).foregroundStyle(.white.opacity(0.55))
            }
            .padding(16)
        }
        .auroraScreen()
        .navigationTitle("Favourites & recents")
        .navigationBarTitleDisplayMode(.inline)
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
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("If you fall, press SOS or stop moving, your watch counts down so you can cancel, then calls the person you choose here through your iPhone. It never calls emergency services by itself.")
                    .font(.callout).foregroundStyle(.white.opacity(0.75))

                if let a = store.lastAlert, a.stage != .cancelled, Date().timeIntervalSince(a.at) < 3600 { alertCard(a) }

                SectionTitle(text: "Emergency contact")
                contactCard

                SectionTitle(text: "On the watch")
                VStack(alignment: .leading, spacing: 14) {
                    Toggle(isOn: $store.config.sos) {
                        Label("Emergency SOS", systemImage: "sos")
                    }
                    Text("Hold the SOS button in Phone on the watch, tap SOS on the Calls & SOS tile, or open the SOS app (you can map it to a double-press of the Home key in the watch's settings).")
                        .font(.caption).foregroundStyle(.white.opacity(0.6))
                    Divider().overlay(.white.opacity(0.1))
                    Toggle(isOn: $store.config.fallDetection) {
                        Label("Fall detection", systemImage: "figure.fall")
                    }
                    Text("Uses the watch's motion sensor: a hard impact followed by stillness. It can miss falls and can be set off by other knocks, which is why there is always a countdown. Off by default.")
                        .font(.caption).foregroundStyle(.white.opacity(0.6))
                    Divider().overlay(.white.opacity(0.1))
                    Toggle(isOn: $store.config.checkIn) {
                        Label("No-movement check-in", systemImage: "figure.stand")
                    }
                    if store.config.checkIn {
                        Stepper("Ask after \(store.config.checkInHours) h without moving", value: $store.config.checkInHours,
                                in: CallsSafetyLimits.checkInHoursRange)
                    }
                    Text("Skipped while the watch is charging or off your wrist.").font(.caption).foregroundStyle(.white.opacity(0.6))
                    Divider().overlay(.white.opacity(0.1))
                    Stepper("Countdown: \(store.config.countdown) s", value: $store.config.countdown,
                            in: CallsSafetyLimits.countdownRange, step: 5)
                }
                .disabled(!hasContact)
                .padding(16).card(cornerRadius: 22)
                .opacity(hasContact ? 1 : 0.55)

                SectionTitle(text: "Heart-rate alerts")
                VStack(alignment: .leading, spacing: 14) {
                    Toggle(isOn: $store.config.hrAlerts) { Label("Alert me", systemImage: "heart.text.square.fill") }
                    if store.config.hrAlerts {
                        Stepper("Above \(store.config.hrHigh) bpm", value: $store.config.hrHigh, in: CallsSafetyLimits.hrHighRange, step: 5)
                        Stepper("Below \(store.config.hrLow) bpm", value: $store.config.hrLow, in: CallsSafetyLimits.hrLowRange, step: 5)
                    }
                    Text("The watch checks your heart rate every 10 minutes and alerts on both the watch and this iPhone when two checks in a row are outside your limits. Not a medical device; it doesn't diagnose anything.")
                        .font(.caption).foregroundStyle(.white.opacity(0.6))
                    if let h = store.lastHr {
                        Label("Last alert: \(h.bpm) bpm, \(h.at.formatted(date: .omitted, time: .shortened))", systemImage: "bell.fill")
                            .font(.caption).foregroundStyle(Theme.heart)
                    }
                }
                .padding(16).card(cornerRadius: 22)

                SectionTitle(text: "Find")
                HStack(spacing: 12) {
                    Button { store.findWatchLoud(!store.watchRingingLoud) } label: {
                        Label(store.watchRingingLoud ? "Stop" : "Find watch (loud)", systemImage: "speaker.wave.3.fill").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(.orange).disabled(!connected)
                    if store.phoneRingingLoud {
                        Button { store.stopLoudRing() } label: { Label("Stop iPhone", systemImage: "speaker.slash.fill").frame(maxWidth: .infinity) }
                            .buttonStyle(.bordered).tint(.white)
                    }
                }
                .padding(16).card(cornerRadius: 22)
                Text("The watch's alarm gets louder and its vibration stronger until you stop it. \"Find iPhone (loud)\" on the watch does the same here while this app is open.")
                    .font(.caption).foregroundStyle(.white.opacity(0.55))

                if let s = store.watchState {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(s.hasContact ? "Watch has your contact" : "Watch has no contact yet", systemImage: s.hasContact ? "checkmark.circle.fill" : "exclamationmark.circle")
                        if store.config.fallDetection {
                            Label(s.fallRunning ? "Fall detection running" : "Fall detection not running", systemImage: s.fallRunning ? "checkmark.circle.fill" : "exclamationmark.circle")
                        }
                        if store.config.hrAlerts, !s.hrSensor {
                            Label("No heart-rate sensor available to the app", systemImage: "exclamationmark.circle")
                        }
                    }
                    .font(.caption).foregroundStyle(.white.opacity(0.7))
                }
                if !connected {
                    Label("Connect your watch to send these settings.", systemImage: "applewatch.slash").font(.caption).foregroundStyle(.orange)
                }
            }
            .padding(16)
        }
        .auroraScreen()
        .navigationTitle("Safety")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            store.attach(hub)
            store.refresh()
            name = store.config.contactName
            number = store.config.contactNumber
        }
    }

    private var contactCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Name", text: $name).textFieldStyle(.roundedBorder)
            TextField("Phone number", text: $number).keyboardType(.phonePad).textFieldStyle(.roundedBorder)
            if !number.isEmpty, EmergencyNumbers.isPublicEmergency(number) {
                Label("Choose a person, not an emergency service. Wear OS Bridge never calls emergency numbers automatically.", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption).foregroundStyle(.orange)
            }
            HStack(spacing: 12) {
                Button {
                    ContactPhonePicker.present { n, p in name = n; number = p; save() }
                } label: { Label("Contacts", systemImage: "person.crop.circle").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).tint(.white)
                Button { save() } label: { Label("Save", systemImage: "checkmark").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    .disabled(!EmergencyNumbers.isAllowedForAutoCall(number) ||
                              (name == store.config.contactName && number == store.config.contactNumber))
            }
            if hasContact {
                Label("\(store.config.contactName.isEmpty ? store.config.contactNumber : store.config.contactName) will be called", systemImage: "checkmark.circle.fill")
                    .font(.caption).foregroundStyle(.green)
            }
        }
        .padding(16).card(cornerRadius: 22)
    }

    private func save() {
        guard EmergencyNumbers.isAllowedForAutoCall(number) else { return }
        store.config.contactName = name.trimmingCharacters(in: .whitespaces)
        store.config.contactNumber = number.trimmingCharacters(in: .whitespaces)
    }

    private func alertCard(_ a: SosAlert) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("\(CallsSafetyStore.kindTitle(a.kind)) from your watch", systemImage: "exclamationmark.triangle.fill")
                .font(.headline).foregroundStyle(.red)
            Text(a.at.formatted(date: .omitted, time: .shortened)).font(.caption).foregroundStyle(.white.opacity(0.6))
            if let lat = a.latitude, let lon = a.longitude {
                let c = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                Map(initialPosition: .region(MKCoordinateRegion(center: c, latitudinalMeters: 600, longitudinalMeters: 600))) {
                    Marker("Watch", systemImage: "applewatch", coordinate: c).tint(.red)
                }
                .frame(height: 180).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                HStack(spacing: 12) {
                    Button {
                        if let u = store.sosTextURL(a) { UIApplication.shared.open(u) }
                    } label: { Label("Text location", systemImage: "message.fill").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).tint(.green)
                    if let m = a.mapsURL {
                        ShareLink(item: m, message: Text(store.sosMessage(a))) {
                            Label("Share", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered).tint(.white)
                    }
                }
                Text("Messages opens with the text filled in; you tap Send.").font(.caption).foregroundStyle(.white.opacity(0.6))
            } else {
                Text("No location from the watch yet.").font(.callout).foregroundStyle(.white.opacity(0.7))
            }
        }
        .padding(16).card(cornerRadius: 22)
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
