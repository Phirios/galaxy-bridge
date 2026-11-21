import CoreBluetooth
import SwiftUI
import WearLink

/// First-run flow: the user puts the watch in pairing mode, picks it from the list, and confirms
/// the matching code that iOS and the watch both show (Bluetooth numeric comparison).
struct PairingView: View {
    @EnvironmentObject var link: WatchLink

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    hero
                    if link.isPairing {
                        nearbyList
                    } else {
                        steps
                        Button {
                            link.startPairing()
                        } label: {
                            Label("Find my watch", systemImage: "dot.radiowaves.left.and.right").font(.headline).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent).tint(Theme.accent)
                        .controlSize(.large)
                    }
                    if let error = link.lastError {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(20)
            }
            .auroraScreen()
            .toolbar {
                if link.isPairing {
                    Button("Cancel") { link.stopPairing() }
                }
            }
        }
        .tint(Theme.accent)
        .preferredColorScheme(.dark)
    }

    private var hero: some View {
        VStack(spacing: 14) {
            Wordmark()
            ZStack {
                ForEach(0..<3) { i in
                    Circle().strokeBorder(Theme.accent2.opacity(0.35 - Double(i) * 0.1), lineWidth: 1.5)
                        .frame(width: 250 + CGFloat(i) * 46, height: 250 + CGFloat(i) * 46)
                        .scaleEffect(link.isPairing ? 1.06 : 1)
                        .animation(link.isPairing ? .easeInOut(duration: 1.2).repeatForever().delay(Double(i) * 0.25) : .default, value: link.isPairing)
                }
                WatchRender(battery: nil, connected: link.isPairing, size: 200)
            }
            .frame(height: 300)
            Text(link.isPairing ? "Looking for your watch" : "Pair your Galaxy Watch")
                .font(.title2.weight(.bold)).foregroundStyle(.white)
            Text(link.isPairing
                 ? "Keep the watch close and its Wear OS Bridge app in pairing mode."
                 : "Wear OS Bridge syncs your health data to Apple Health and turns your watch into a camera remote.")
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.65))
                .multilineTextAlignment(.center)
        }
        .padding(.top, 12)
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 16) {
            Step(number: 1, text: "Open **Wear OS Bridge** on your watch.")
            Step(number: 2, text: "Tap **Pair iPhone** (or **Pair new phone**).")
            Step(number: 3, text: "Tap **Find my watch** below and choose it.")
            Step(number: 4, text: "Check the **code matches** on both screens, then accept on both.")
        }
        .padding(18)
        .card()
    }

    private var nearbyList: some View {
        VStack(alignment: .leading, spacing: 0) {
            if link.nearby.isEmpty {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Searching… make sure the watch shows “Ready to pair”.")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(18)
            } else {
                ForEach(link.nearby.sorted { $0.rssi > $1.rssi }) { watch in
                    Button { link.pair(with: watch) } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "applewatch")
                                .font(.title3)
                                .foregroundStyle(Theme.accent)
                                .frame(width: 36, height: 36)
                                .background(Theme.accent.opacity(0.12), in: Circle())
                            VStack(alignment: .leading, spacing: 2) {
                                Text(watch.name).font(.body.weight(.semibold))
                                Text("Ready to pair").font(.caption).foregroundStyle(.green)
                            }
                            Spacer()
                            SignalBars(rssi: watch.rssi)
                        }
                        .padding(16)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if watch.id != link.nearby.sorted(by: { $0.rssi > $1.rssi }).last?.id { Divider().padding(.leading, 66) }
                }
            }
        }
        .card()
    }
}

private struct Step: View {
    let number: Int
    let text: LocalizedStringKey

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(number)")
                .font(.footnote.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(Theme.accent, in: Circle())
            Text(text).font(.subheadline)
        }
    }
}

private struct SignalBars: View {
    let rssi: Int

    var body: some View {
        let level = rssi > -60 ? 3 : rssi > -75 ? 2 : 1
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(1...3, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i <= level ? Theme.accent : Color.secondary.opacity(0.3))
                    .frame(width: 4, height: CGFloat(4 + i * 4))
            }
        }
        .accessibilityLabel("Signal \(level) of 3")
    }
}

// MARK: - Native (UKEY2) pairing

/// Drives the Wear OS "native" iPhone-mode pairing that the watch firmware itself supports:
/// Stage 1 writes iPhone capabilities to a watch on its setup screen (forcing a BLE bond), then
/// the app hosts the Clockwork setup service and runs the UKEY2 handshake (watch = initiator,
/// `UKey2Responder` = responder) so the app can *claim* a native pair and show the verification
/// code to compare. It is fully decoupled from `WatchLink`/GBLink — the rest of the app works
/// without it — and uses its own CoreBluetooth stacks only while this screen is open.
///
/// The handshake crypto is `WearLink.UKey2Responder`, unchanged. Logs carry stage names only
/// (never keys, decrypted bytes or the verification code), per `PairingLog`.
@MainActor
final class NativePairingManager: NSObject, ObservableObject {
    enum Phase: Equatable {
        case idle, scanning, settingUp, hosting, verifying, blocked, stopped(String)
    }

    struct Found: Identifiable, Equatable {
        let id: UUID
        var name: String
        var onSetupScreen: Bool
        var rssi: Int
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var watches: [Found] = []
    @Published private(set) var log: [String] = []
    @Published private(set) var verificationCode: String?
    @Published private(set) var isPaired: Bool = ((try? PairingRecords().load()) ?? nil) != nil
    @Published private(set) var approved = false

    private let pairingLog = PairingLog()
    private var central: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var setupPeripheral: CBPeripheral?

    // Serve (Stage 3–4) state.
    private var session: ClockworkSession?
    private var responder: UKey2Responder?
    private var characteristics: [UUID: CBMutableCharacteristic] = [:]
    private var notifyQueue: [(CBMutableCharacteristic, Data)] = []
    private var watchCentral: CBCentral?
    private var wantHosting = false

    override init() {
        super.init()
        pairingLog.onEvent = { [weak self] event in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.log.append(event.line)
                if self.log.count > 60 { self.log.removeFirst(self.log.count - 60) }
                self.reflect(event)
            }
        }
    }

    private func reflect(_ event: PairingEvent) {
        if let r = responder {
            verificationCode = r.verificationCode
            if r.isEstablished, phase == .hosting { phase = .verifying }
        }
        if event.result == .blocked { phase = .blocked }
    }

    // MARK: Stage 1 — scan for and set up a watch on its setup screen

    func startScan() {
        reset()
        phase = .scanning
        watches = []
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func setUp(_ watch: Found) {
        guard let central, let p = central.retrievePeripherals(withIdentifiers: [watch.id]).first else { return }
        central.stopScan()
        setupPeripheral = p
        p.delegate = self
        phase = .settingUp
        pairingLog.record(.setupDiscovery, .started, "connecting to the selected watch")
        central.connect(p)
    }

    // MARK: Stage 3–4 — host the Clockwork service the watch connects back to

    func startHosting() {
        let r = UKey2Responder()
        responder = r
        verificationCode = nil
        approved = false
        session = ClockworkSession(log: pairingLog, encryption: r) { [weak self] in self?.approved ?? false }
        wantHosting = true
        phase = .hosting
        if peripheralManager == nil {
            peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
        } else {
            publishClockworkService()
        }
    }

    func approve() { approved = true }

    func forget() {
        try? PairingRecords().forget()
        isPaired = false
    }

    func stop() { reset(); phase = .idle }

    private func reset() {
        central?.stopScan()
        if let p = setupPeripheral { central?.cancelPeripheralConnection(p) }
        setupPeripheral = nil
        peripheralManager?.stopAdvertising()
        if peripheralManager != nil { peripheralManager?.removeAllServices() }
        characteristics = [:]
        notifyQueue = []
        watchCentral = nil
        session = nil
        responder = nil
        wantHosting = false
        verificationCode = nil
    }

    fileprivate func publishClockworkService() {
        guard let pm = peripheralManager, pm.state == .poweredOn, wantHosting else { return }
        func make(_ id: UUID, _ props: CBCharacteristicProperties, _ perms: CBAttributePermissions) -> CBMutableCharacteristic {
            let c = CBMutableCharacteristic(type: CBUUID(nsuuid: id), properties: props, value: nil, permissions: perms)
            characteristics[id] = c
            return c
        }
        characteristics = [:]
        let service = CBMutableService(type: CBUUID(nsuuid: Clockwork.service), primary: true)
        service.characteristics = [
            make(Clockwork.watchToPhone, [.write, .writeWithoutResponse], .writeEncryptionRequired),
            make(Clockwork.phoneToWatch, [.notify], .readEncryptionRequired),
            make(Clockwork.reconnect, [.notify], .readEncryptionRequired),
            make(Clockwork.reset, [.notify, .write], [.readEncryptionRequired, .writeEncryptionRequired]),
            make(Clockwork.pairingConfirm, [.read], .readEncryptionRequired),
            make(Clockwork.decommission, [.read], .readEncryptionRequired),
        ]
        pm.removeAllServices()
        pm.add(service)
    }

    private func deliver(_ out: [ClockworkSession.Outgoing]) {
        for o in out { if let c = characteristics[o.characteristic] { notify(c, o.value) } }
        if let r = responder { verificationCode = r.verificationCode }
        if session?.isStopped == true, phase == .hosting || phase == .verifying {
            // The session stops at the (expected) Connect block once encryption is established.
            if responder?.isEstablished == true { phase = .verifying } else { phase = .blocked }
        }
    }

    private func notify(_ c: CBMutableCharacteristic, _ v: Data) {
        if notifyQueue.isEmpty, peripheralManager?.updateValue(v, for: c, onSubscribedCentrals: nil) == true { return }
        notifyQueue.append((c, v))
    }
}

extension NativePairingManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                if phase == .scanning {
                    central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
                    pairingLog.record(.setupDiscovery, .started, "scanning for a watch on its setup screen")
                }
            case .unauthorized, .poweredOff:
                phase = .stopped(central.state == .unauthorized ? "Bluetooth permission denied" : "Bluetooth is off")
            default: break
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData ad: [String: Any], rssi: NSNumber) {
        let services = (ad[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? [])
            + (ad[CBAdvertisementDataSolicitedServiceUUIDsKey] as? [CBUUID] ?? [])
        let name = (ad[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        let onSetup = services.contains(CBUUID(nsuuid: SetupPairing.service))
        let id = peripheral.identifier
        let value = rssi.intValue
        MainActor.assumeIsolated {
            guard onSetup || name.localizedCaseInsensitiveContains("watch") || name.localizedCaseInsensitiveContains("galaxy") else { return }
            let found = Found(id: id, name: name.isEmpty ? "Galaxy Watch" : name, onSetupScreen: onSetup, rssi: value)
            if let i = watches.firstIndex(where: { $0.id == id }) { watches[i] = found } else { watches.append(found) }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated { peripheral.discoverServices([CBUUID(nsuuid: SetupPairing.service)]) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { pairingLog.record(.setupDiscovery, .failed, "connect failed") }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { if phase == .settingUp { pairingLog.record(.setupBond, .failed, "watch disconnected during setup") } }
    }
}

extension NativePairingManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard let s = peripheral.services?.first(where: { $0.uuid == CBUUID(nsuuid: SetupPairing.service) }) else {
                pairingLog.record(.setupDiscovery, .failed, "watch isn't on its setup screen (no setup service)")
                return
            }
            peripheral.discoverCharacteristics([CBUUID(nsuuid: SetupPairing.pairingCharacteristic),
                                                CBUUID(nsuuid: SetupPairing.capabilitiesCharacteristic)], for: s)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            let chars = service.characteristics ?? []
            guard let caps = chars.first(where: { $0.uuid == CBUUID(nsuuid: SetupPairing.capabilitiesCharacteristic) }),
                  let status = chars.first(where: { $0.uuid == CBUUID(nsuuid: SetupPairing.pairingCharacteristic) }) else {
                pairingLog.record(.setupDiscovery, .failed, "setup service is missing its pairing characteristics")
                return
            }
            peripheral.setNotifyValue(true, for: status)
            pairingLog.record(.setupCapabilities, .started, "writing iPhone capabilities (this triggers the pairing prompt on both screens)")
            peripheral.writeValue(SetupPairing.capabilitiesPayload(), for: caps, type: .withResponse)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if let error {
                pairingLog.record(.setupCapabilities, .failed, "capabilities write rejected: \(error.localizedDescription)")
                return
            }
            pairingLog.record(.setupCapabilities, .passed, "watch accepted iPhone capabilities")
            pairingLog.record(.setupBond, .passed, "link is bonded and encrypted")
            do {
                try PairingRecords().save(PairingRecord(watchIdentifier: peripheral.identifier))
                isPaired = true
            } catch {
                pairingLog.record(.setupBond, .failed, "couldn't save the pairing record")
            }
        }
    }
}

extension NativePairingManager: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ pm: CBPeripheralManager) {
        MainActor.assumeIsolated {
            switch pm.state {
            case .poweredOn: publishClockworkService()
            case .unauthorized, .poweredOff:
                phase = .stopped(pm.state == .unauthorized ? "Bluetooth permission denied" : "Bluetooth is off")
            default: break
            }
        }
    }

    nonisolated func peripheralManager(_ pm: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            if let error {
                pairingLog.record(.clockworkConnect, .failed, "couldn't publish the setup service: \(error.localizedDescription)")
                return
            }
            pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(nsuuid: Clockwork.service)],
                                 CBAdvertisementDataLocalNameKey: "WearLink"])
            pairingLog.record(.clockworkConnect, .started, "hosting the setup service; waiting for the watch to connect")
        }
    }

    nonisolated func peripheralManager(_ pm: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        MainActor.assumeIsolated {
            watchCentral = request.central
            guard let session, let value = session.read(uuid(request.characteristic)) else {
                pm.respond(to: request, withResult: .readNotPermitted); return
            }
            guard request.offset <= value.count else { pm.respond(to: request, withResult: .invalidOffset); return }
            request.value = value.subdata(in: request.offset..<value.count)
            pm.respond(to: request, withResult: .success)
        }
    }

    nonisolated func peripheralManager(_ pm: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        MainActor.assumeIsolated {
            guard let session else { if let f = requests.first { pm.respond(to: f, withResult: .success) }; return }
            for r in requests {
                watchCentral = r.central
                deliver(session.write(uuid(r.characteristic), r.value ?? Data()))
            }
            if let first = requests.first { pm.respond(to: first, withResult: .success) }
        }
    }

    nonisolated func peripheralManager(_ pm: CBPeripheralManager, central: CBCentral, didSubscribeTo c: CBCharacteristic) {
        MainActor.assumeIsolated {
            if watchCentral == nil { watchCentral = central; session?.connected() }
            session?.subscribed(uuid(c))
        }
    }

    nonisolated func peripheralManagerIsReady(toUpdateSubscribers pm: CBPeripheralManager) {
        MainActor.assumeIsolated {
            while let (c, v) = notifyQueue.first {
                guard pm.updateValue(v, for: c, onSubscribedCentrals: nil) else { return }
                notifyQueue.removeFirst()
            }
        }
    }

    private func uuid(_ c: CBCharacteristic) -> UUID { UUID(uuidString: c.uuid.uuidString) ?? UUID() }
}

/// Optional, experimental screen that surfaces native (UKEY2) pairing. Reached from More.
struct NativePairingView: View {
    @StateObject private var manager = NativePairingManager()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if manager.isPaired { pairedCard }
                if let code = manager.verificationCode { verifyCard(code) }
                controls
                if !manager.log.isEmpty { logCard }
            }
            .padding(18)
        }
        .auroraScreen()
        .navigationTitle("Native pairing")
        .onDisappear { manager.stop() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Experimental", systemImage: "wand.and.stars")
                .font(.caption.weight(.bold)).foregroundStyle(Theme.accent2)
            Text("Pair a factory-reset watch directly with this iPhone, the way the watch's own firmware pairs with an Android phone, using the encrypted UKEY2 handshake. This is optional — Wear OS Bridge works fully over its own companion pairing without it.")
                .font(.subheadline).foregroundStyle(.white.opacity(0.7))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16).card()
    }

    private var pairedCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.seal.fill").foregroundStyle(.green).font(.title2)
            VStack(alignment: .leading) {
                Text("Native pairing record saved").font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                Text("This iPhone has claimed a native pair.").font(.caption).foregroundStyle(.white.opacity(0.6))
            }
            Spacer()
            Button("Forget") { manager.forget() }.font(.caption.weight(.semibold))
        }
        .padding(16).card()
    }

    private func verifyCard(_ code: String) -> some View {
        VStack(spacing: 10) {
            Text("Verification code").font(.caption).foregroundStyle(.white.opacity(0.6))
            Text(code).font(.system(.title, design: .monospaced).weight(.bold)).foregroundStyle(.white)
                .textSelection(.enabled)
            Text("Check this matches the code on the watch, then confirm on both.")
                .font(.caption).foregroundStyle(.white.opacity(0.6)).multilineTextAlignment(.center)
            Button("It matches — confirm") { manager.approve() }
                .buttonStyle(.borderedProminent).tint(Theme.accent).disabled(manager.approved)
        }
        .frame(maxWidth: .infinity)
        .padding(16).card()
    }

    private var controls: some View {
        VStack(spacing: 12) {
            Text(statusText).font(.footnote.weight(.semibold)).foregroundStyle(.white.opacity(0.8))
                .frame(maxWidth: .infinity, alignment: .leading)
            if manager.phase == .scanning {
                if manager.watches.isEmpty {
                    HStack(spacing: 10) { ProgressView(); Text("Scanning…").font(.subheadline).foregroundStyle(.secondary) }
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    ForEach(manager.watches.sorted { $0.rssi > $1.rssi }) { w in
                        Button { manager.setUp(w) } label: {
                            HStack {
                                Image(systemName: "applewatch").foregroundStyle(Theme.accent)
                                VStack(alignment: .leading) {
                                    Text(w.name).font(.body.weight(.semibold)).foregroundStyle(.white)
                                    Text(w.onSetupScreen ? "On setup screen" : "Not on setup screen")
                                        .font(.caption).foregroundStyle(w.onSetupScreen ? .green : .orange)
                                }
                                Spacer()
                            }
                            .padding(.vertical, 6).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            HStack(spacing: 10) {
                Button { manager.startScan() } label: {
                    Label("Scan", systemImage: "dot.radiowaves.left.and.right").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered)
                Button { manager.startHosting() } label: {
                    Label("Host setup", systemImage: "antenna.radiowaves.left.and.right").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered)
            }
        }
        .padding(16).card()
    }

    private var logCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Progress").font(.caption).foregroundStyle(.white.opacity(0.6))
            ForEach(Array(manager.log.enumerated()), id: \.offset) { _, line in
                Text(line).font(.system(.caption2, design: .monospaced)).foregroundStyle(.white.opacity(0.75))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(16).card()
    }

    private var statusText: String {
        switch manager.phase {
        case .idle: return "Not started"
        case .scanning: return "Scanning for a watch on its setup screen"
        case .settingUp: return "Setting up — accept the pairing prompt on both screens"
        case .hosting: return "Hosting the setup service — waiting for the watch"
        case .verifying: return "Encrypted channel established — compare the code"
        case .blocked: return "Reached the known stopping point (setup flow beyond Connect isn't implemented)"
        case let .stopped(why): return why
        }
    }
}
