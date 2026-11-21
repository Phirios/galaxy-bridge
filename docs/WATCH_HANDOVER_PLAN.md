# Watch handover plan — pre-reset RE + "all features, no native-pairing dependency"

**Goal.** When the watch is handed over (charged, still in its current paired/data state),
get the whole feature list working — including **calls and texts through the watch's own
built-in apps** — and do it so **nothing depends on native (UKEY2) pairing**.

**Governing principle (transport independence).** Every feature rides one of:
1. **our companion link** — `WatchLink` (iPhone GATT) ⇄ `LinkService` (watch). Already works.
2. **a Bluetooth profile the iPhone speaks natively** — HFP (calls), MAP (incoming texts),
   plus **Siri** for sending texts. iOS-sanctioned, same channels a car uses.

Nothing routes through the `ClockworkSession`/`UKey2Responder` native-pairing channel. Native
pairing stays a separate, optional capability; if it's off or never completes, all of the below
still works.

**Hard constraints (standing).** Do NOT factory-reset the watch without explicit go-ahead.
No plaintext fallback on the UKEY2 channel. Never log keys / verification codes / decrypted
payloads. Keep proprietary decompiled code and personal BT data out of git (already gitignored:
`research/*`, `tools/`). Scan every diff for MAC addresses, device serials,
local IPs, passwords, and email before any push. Do not delete/rewrite the pairing implementation or
the Android release key.

---

## Phase 0 — Pre-reset capture (do this FIRST; the reset wipes it)

The factory reset erases the current pairing state, Samsung relay config, bonds and app data.
Snapshot everything we might need *before* going near a reset.

```bash
# 0.1 Connect + confirm (watch in dev mode, ADB-over-Wi-Fi or USB)
adb devices
adb shell getprop ro.build.version.release          # Wear OS / Android version
adb shell getprop | grep -i bluetooth               # ** decisive: HFP-HF / MAP profile flags **

# 0.2 Enumerate exact package names (don't guess — capture the real ones)
adb shell pm list packages -f > research/pkgs_before_reset.txt

# 0.3 Pull the APKs that matter (resolve exact paths from 0.2 first)
#   - Bluetooth stack:      com.android.bluetooth
#   - Samsung dialer/phone: com.samsung.android.app.telephonyui  (confirm)
#   - Samsung Messages:     com.samsung.android.messaging        (confirm)
#   - Wear companion/relay: com.google.android.wearable.app, com.google.android.gms (confirm)
#   for each: adb shell pm path <pkg>  →  adb pull <apk> research/apks/

# 0.4 Bluetooth stack config + current state
adb shell cat /system/etc/bluetooth/bt_stack.conf 2>/dev/null > research/bt_stack.conf
adb shell dumpsys bluetooth_manager > research/bt_dumpsys.txt     # roles, bonded devices
#   look for: HFP Client (HeadsetClient/HFP-HF), MAP Client (MCE), profile_supported_* flags

# 0.5 Live logcat while attempting a CLASSIC-BT pair to the iPhone
adb logcat -c
# (now pair the watch to the iPhone over Bluetooth settings)
adb logcat -d > research/pair_logcat.txt
#   watch for: HFP/HSP negotiation, MAP MNS, SDP records, which roles the stack offers iOS
```

Then decompile with REA / jadx (`tools/jadx`) into `research/` (gitignored): the Bluetooth
stack (HFP-HF + MAP roles, API surface), the dialer (how it drives calls over BT), Messages
(relay protocol + MAP hooks), and the Wear companion/relay services.

---

## Phase 1 — Decision gate (answers the make-or-break question in minutes)

**Question:** can a non-system app use `BluetoothProfile.HEADSET_CLIENT` (HFP-HF) and the MAP
client role, or are those locked to system/privileged apps (or need root)?

From Phase 0.1/0.4:
- `bluetooth.profile.hfp.hf.enabled` / `profile_supported_hfpclient` present and on? 
- Does `dumpsys bluetooth_manager` list an HFP-Client and MAP-Client profile?

Outcome picks the build path:
- **Path A — drive the watch's own apps.** HFP-HF/MAP exist but are system-owned → we trigger
  the built-in Phone/Messages apps (intents / accessibility / exposed services) and let *them*
  own the Bluetooth profiles. Preferred: "through the watch's built-in apps," exactly as asked.
- **Path B — our app presents the profiles.** The roles are reachable by our app → we implement
  HFP-HF + MAP client ourselves.
- **Path C — privileged/root needed.** Neither works unprivileged → document the limit and the
  options (privileged install, or accept calls/texts can't run on a stock non-rooted watch).

---

## Phase 2 — Calls (Bluetooth HFP, watch as hands-free unit)

Precedent: the Tizen Galaxy Watches did iPhone calls over HFP; the hardware/role is real — the
only question is whether Wear OS 5 still exposes it (Phase 1).

- Watch presents/into HFP-HF to the iPhone (AG). Place / answer / reject; route call audio to
  the watch mic+speaker.
- Drive it from the watch's built-in Phone app (Path A) or our control (Path B).
- Verify: incoming call rings on watch → answer, talk; reject with no action on iPhone; outgoing
  dial from the watch places the call on the iPhone's cellular.

## Phase 3 — Texts (MAP in, Siri out)

- **Read incoming:** MAP client on the watch receives new-message notifications + content from
  the iPhone (the way a car shows texts). Surface them in the watch's Messages/notification UI.
- **Reply / send:** trigger **Siri** over the HFP link ("reply …" / "text <name> …"); **iOS**
  composes and sends through Messages. No silent injection — that path stays walled, but the
  voice path gives real send capability through the watch.
- Verify: incoming SMS/iMessage appears on the watch; voice reply is actually delivered.

> Silent, typed-and-sent texts with no voice remain impossible on iOS — the one thing in this
> list that no amount of RE changes. Everything else here is reachable.

## Phase 4 — Sensors (already built; just verify live)

All of SpO₂, skin temp, ECG, BIA, sleep is built and compiles. On the watch:
1. Enable Samsung Health **Developer mode** (the "Open Samsung Health on watch" button).
2. **Confirm the Samsung SDK names** reflection uses (first thing to check live):
   `HealthTrackerType` constants, `ValueKey.SpO2Set.SPO2`, `SkinTemperatureSet.OBJECT_TEMPERATURE`,
   `EcgSet.ECG_MV`, `BiaSet.*`. Fix any mismatch in `SensorBridge.kt` (names are isolated at top).
3. Live test: SpO₂ + skin temp → Apple Health; ECG trace shows in-app; BIA writes fat%/lean mass;
   sleep session detected and written.

## Phase 5 — Native pairing (separate, optional)

Live UKEY2 handshake on hardware + verification-code display. Independent of everything above;
run it when convenient. If it fails, nothing else is affected.

---

## The list — status

| Feature | Status | Path | Depends on native pair? |
|---|---|---|---|
| Notifications, media, camera, settings, contacts, faces, apps, recordings, photos | ✅ working | companion link | No |
| Health: HR, steps, energy, distance, floors | ✅ working | companion link | No |
| SpO₂, skin temp | ✅ built | Samsung SDK (verify names) | No |
| ECG (in-app trace) | ✅ built | Samsung SDK (verify names) | No |
| Body composition (BIA → fat%/lean to Health) | ✅ built | Samsung SDK (verify names) | No |
| Sleep (estimated → Health) | ✅ built | heuristic on watch | No |
| **Calls via watch's Phone app** | ⏳ RE | HFP-HF (Phase 1–2) | No |
| **Texts: read incoming** | ⏳ RE | MAP (Phase 3) | No |
| **Texts: reply/send** | ⏳ RE | Siri over HFP (Phase 3) | No |
| Silent typed-and-sent texts | ❌ iOS wall | — | — |
| Apple Pay | excluded by you | — | — |

> If anything is missing from this table that's on your list, add it and we'll slot it into a phase.
