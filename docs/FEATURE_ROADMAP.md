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
- 🔨 **Live Activity / iOS widget** on the phone showing live watch HR/steps/battery.
- ⚠️ Toggle iPhone DND/Focus from watch — largely walled; can mirror watch DND the other way.
- 🚫 Toggling iPhone Wi-Fi/Bluetooth/torch, unlocking the iPhone, screen mirroring, Handoff/Continuity.

## 3. Health & fitness (Samsung sensors → Apple Health)
- ✅ HR, steps, calories, distance, floors, workouts + GPS route.
- ✅ **SpO₂, skin temperature** (validated live) → Apple Health.
- ✅ **ECG** waveform in-app (display only — iOS bars 3rd-party ECG writes; **no AFib claims**). Waveform now auto-ranged (fixes the fixed-×1000 overflow that flat-lined the trace).
- ✅ **Body composition (BIA)** → body-fat % + lean mass to Apple Health (now with real profile).
- ✅ **Sleep** (estimated) → Apple Health; ⚠️ **overnight SpO₂ + skin-temp + sleeping HR + HRV** built (AutoLog.kt, off by default) — needs a live overnight test. Sleep estimate now gets movement from the hardware step counter (it had stopped seeing steps when they moved off Health Services).
- ⚠️ **Stress / HRV** built (Hrv.kt): IBIs read from HeartRateSet slots [3]=count, [4+2i]=IBI, [5+2i]=status; SDNN → Apple Health HRV, RMSSD-based 0–100 stress (estimate) on iPhone + watch tile. Needs live test: whether this firmware fills the IBI slots (the app counts attempts with/without IBI and says so).
- ⚠️ **Automatic background readings** built: inexact 15-min alarm, configurable interval on iPhone, battery/charging/power-saver/workout aware, off by default. Needs live test for doze reliability and battery cost.
- ⚠️ **Goals + rings** (iPhone Health tab + watch Activity goals tile) and **inactivity / stand / hydration reminders** built (off by default, quiet hours) — reminders need a live test.
- ✅ **Cycle tracking** (flow + symptoms, iPhone; flow also on watch) and **water/caffeine** logging (iPhone + watch Quick log tile) → HealthKit — build-verified, Health writes need a live check.
- ⚠️ Watch workout screen: **auto-pause** (where supported), pause/resume, **lap splits** (laps + pauses sent with the workout) — needs a live run. 🔨 Driving it from the phone.
- 🚫 Blood pressure (Samsung BP needs Samsung Health Monitor + per-region cuff calibration + Galaxy phone); medical AFib/irregular-rhythm diagnosis (regulatory).

## 4. Safety (high value, very native-feeling)
- 🔨 **Fall detection → auto-call** an emergency contact over HFP + on-watch countdown to cancel.
- 🔨 **Emergency SOS** (hold the watch button) → HFP call + share location to the phone.
- 🔨 **Hard-fall / no-movement check-in**; **low-HR / high-HR alerts** from the live stream.

## 5. Media & camera
- ✅ Media control (AMS): play/pause/skip/volume, now-playing on the watch.
- ✅ Camera remote: shutter, flip, timer, live preview.
- 🔨 **Scrub/seek, like/favourite, output picker**; **remote slideshow/presentation clicker**.
- 🔨 **Offline music / podcast files pushed to the watch** (we already push files) + on-watch player.

## 6. Customisation & apps
- ✅ 1,600+ in-app watch faces; **apply any installed face from the phone**; complications; tiles.
- ✅ Watch apps: list / open / uninstall; **push-install an APK from the phone**.
- ✅ Google Play browse (in-app) with "install on watch".
- 🔨 **Face scheduling** (auto-switch by time/activity), **per-face complication editing from the phone**.
- 🔨 **Tile manager from the phone** (reorder/add/remove), **app drawer reorder**.

## 7. Productivity & daily
- ✅ Calendar, reminders, alarms, timers, stopwatch, weather, contacts, voice recordings, photos→Gallery.
- 🔨 **Two-way reminders/checklists**, **world clock**, **translate** (voice), **calculator/converter tiles**.
- 🔨 **Boarding passes / tickets as QR on the watch** (pushed images — NOT Wallet/Pay).
- 🔨 **Smart-home shortcuts** tile (HomeKit/SmartThings scenes triggered via the phone).

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
