# Wear OS Bridge — "as native as an Android pairing" feature roadmap

Goal: make the Galaxy Watch4 do, with an iPhone, **everything it does on Android** (plus extras), as
natively as iOS allows. Excludes Apple Pay **and** Google Pay (by request). Honest about iOS walls.

Legend: ✅ done · 🔨 buildable (how noted) · ⚠️ partial / voice-only · 🚫 walled by iOS (no workaround).

Transport we ride (all already proven on this watch): our **companion BLE link** (WatchLink⇄LinkService);
**HFP-HF** (calls, green on this watch); **ANCS** (iPhone notifications incl. actions); **AMS** (media);
**HID** (camera/volume); **Samsung Health Binder** (SpO₂/ECG/BIA/skin-temp, validated live).

---

## 1. Phone & messaging
- ✅ Incoming calls on the watch (HFP) — answer / reject / hang-up, call audio on the watch.
- 🔨 **Outgoing calls from the watch** — dial a contact/number → HFP places it on the iPhone's cellular. (UI built; needs the classic-BT bond.)
- ✅ Incoming texts/iMessages shown on the watch (ANCS).
- ⚠️ **Reply to a text** — by **Siri over HFP** ("reply …"); iOS composes & sends. No silent typed send.
- ✅ **Notification quick-actions** — ANCS positive/negative actions performed from the watch notification (accept/decline, clear) → `PerformNotificationAction`.
- ✅ **Dismiss-sync** — clearing on the watch performs the ANCS negative action to clear on the iPhone (opt-in).
- 🔨 **Favourite contacts / recent calls tile**, call history, one-tap call-back.
- 🔨 **Canned quick-replies** that fire Siri phrases for the common cases.
- 🚫 Reading full message history / smart-reply suggestions (ANCS is notify-only); silent typed SMS.

## 2. Notifications & phone status
- ✅ Full notification mirroring (ANCS), per-app filters.
- ✅ **Phone battery + charging on the watch** (tile/complication) and **watch battery on the phone** (live card, pushed on change); **"find" my phone** done. 🚫 phone signal + ringer state (no iOS API).
- 🔨 **Ring/silence/find my phone** (done) + **find my watch** (done) — add loud escalating find.
- ✅ **Live Activity / iOS widget** (Home + Lock Screen + Dynamic Island) showing watch HR/steps/battery — WidgetKit extension `GalaxyBridgeWidgetExtension`, data via App Group `group.nz.galaxybridge.app` (needs the App Group registered at signing; needs live test).
- ⚠️ Toggle iPhone DND/Focus from watch — largely walled; can mirror watch DND the other way.
- 🚫 Toggling iPhone Wi-Fi/Bluetooth/torch, unlocking the iPhone, screen mirroring, Handoff/Continuity.

## 3. Health & fitness (Samsung sensors → Apple Health)
- ✅ HR, steps, calories, distance, floors, workouts + GPS route.
- ✅ **SpO₂, skin temperature** (validated live) → Apple Health.
- ✅ **ECG** waveform in-app (display only — iOS bars 3rd-party ECG writes; **no AFib claims**). Waveform now auto-ranged (fixes the fixed-×1000 overflow that flat-lined the trace).
- ✅ **Body composition (BIA)** → body-fat % + lean mass to Apple Health (now with real profile).
- ✅ **Sleep** (estimated) → Apple Health; 🔨 add **overnight SpO₂ + skin-temp + sleeping HR**.
- 🔨 **Stress / HRV** — sensor exposes IBI list + EDA; compute HRV/stress → Health (mindfulness/HRV).
- 🔨 **Continuous/auto background logging** — scheduled SpO₂, skin-temp, HR to Health without tapping.
- 🔨 **Activity rings / goals**, inactivity + hydration + stand reminders (watch tiles).
- 🔨 **Women's health / cycle** (HealthKit category), **water/caffeine intake** logging.
- 🔨 **Live workout screen** on the watch driven from the phone; auto-pause; lap splits.
- 🚫 Blood pressure (Samsung BP needs Samsung Health Monitor + per-region cuff calibration + Galaxy phone); medical AFib/irregular-rhythm diagnosis (regulatory).

## 4. Safety (high value, very native-feeling)
- 🔨 **Fall detection → auto-call** an emergency contact over HFP + on-watch countdown to cancel.
- 🔨 **Emergency SOS** (hold the watch button) → HFP call + share location to the phone.
- 🔨 **Hard-fall / no-movement check-in**; **low-HR / high-HR alerts** from the live stream.

## 5. Media & camera
- ✅ Media control (AMS): play/pause/skip/volume, now-playing on the watch.
- ✅ Camera remote: shutter, flip, timer, live preview.
- ⚠️ **Scrub/seek** — AMS has no seek; the watch asks the iPhone app, which can only seek Apple Music (`MPMusicPlayerController.systemMusicPlayer`). ✅ **Like/dislike/bookmark, ±15 s, repeat, shuffle** (AMS commands 7–13; honoured only by players that support them). ⚠️ **Output picker** — iPhone-side `AVRoutePickerView` only; 🚫 switching the iPhone's route from the watch (no iOS API). ✅ **Presentation clicker** — HID keyboard (arrows/B/Esc) + bezel, to the iPhone or a paired computer (needs live test).
- ✅ **Offline music / podcasts**: Files picker → windowed push over GBLink (≤80 MB, acked every 8 parts) → on-watch MediaSession player, Bluetooth earbuds (needs live test; DRM'd Apple Music files can't be copied).

## 6. Customisation & apps
- ✅ 1,600+ in-app watch faces; **apply any installed face from the phone**; complications; tiles.
- ✅ Watch apps: list / open / uninstall; **push-install an APK from the phone**.
- ✅ Google Play browse (in-app) with "install on watch".
- ✅ **Face scheduling** by time of day + optional workout face, classic faces only (WFF refused), inexact alarm + screen-on catch-up; SysUI opens the face editor briefly after each switch (needs live test). 🔨 per-face complication editing.
- ⚠️ **Tile manager**: phone shows which of our tiles are added (`TileService.getActiveTilesAsync`) and configures their content. 🚫 add/remove/reorder tiles and app-drawer order (Samsung's TileClient/AppTrayClient relay only to Galaxy Wearable; no public API).

## 7. Productivity & daily
- ✅ Calendar, reminders, alarms, timers, stopwatch, weather, contacts, voice recordings, photos→Gallery.
- ✅ **Two-way checklist** (one Reminders list: tick/untick + add from the watch), **world clock tile**, **calculator + unit converter**, ✅/⚠️ **translate** (speak/type on watch → Apple Translation on the iPhone; headless needs iOS 26 + downloaded languages).
- ✅ **Passes**: QR/Aztec/PDF417/Code 128 rendered on the iPhone (or re-drawn from a screenshot via Vision) → Passes tile + full-brightness viewer. Not Wallet.
- ⚠️ **Home shortcuts tile** runs named Apple Shortcuts on the iPhone. iOS only opens Shortcuts / allows HomeKit with the app in the foreground, so a locked phone gets a tap-to-run notification instead. No HomeKit entitlement used.

## 8. Voice & assistant
- ⚠️ **Siri over HFP** (voice commands, dictated replies, "call …", "set a timer", "navigate…") — the native voice path. 🚫 Deep on-watch Siri UI / Bixby.

## 9. Pairing & platform
- ✅ Custom BLE companion pairing (works with no Android phone).
- ✅ **Native UKey2 pairing** surfaced in the iPhone app (More → Pairing & setup → Native pairing): an in-app pairing manager drives Stage 1 capabilities + the Clockwork setup service with the `UKey2Responder` handshake and shows the verification code. Decoupled/optional; full reset-watch flow still needs hardware (Stage 4b unknown).
- 🔨 **Multi-watch**, **auto-reconnect polish**, **setup wizard** in-app.

---

## The only hard "no"s on iOS (everything else above is reachable)
Apple Pay / Google Pay (excluded); silent typed-and-sent SMS (Siri voice only); reading full message
threads; unlocking the iPhone / screen-mirror / Handoff; toggling the iPhone's own system radios; medical
BP & certified arrhythmia detection.

## Suggested build order (biggest "feels native" wins first)
1. ✅ **Home live card**: HR + SpO₂ + watch & phone battery (top of the dashboard). 2. ✅ **Notification quick-actions + dismiss-sync** (ANCS). 3. **Outgoing calls + call history + favourites** (after the HFP bond). 4. **Safety: fall-detect/SOS auto-call**. 5. **Stress/HRV + overnight SpO₂ + auto-logging** (per-sensor Apple Health sync toggles for SpO₂/skin-temp/sleep now in Watch settings). 6. ✅ **Native UKey2 pairing in the app**. 7. **Offline music + seek**, face scheduling, tile manager.
