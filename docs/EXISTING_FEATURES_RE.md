# Existing-feature RE — doing more from the phone

Reverse-engineering of the live SM-R890 (Android 16 / Wear OS 6, `ro.build.type=user`) and the
decompiled system apps, focused on **moving work off the watch and onto the phone** so the
Wear OS Bridge experience feels as close to a native Galaxy↔Android pairing as a normal
(non-privileged, user-key-signed) app can reach.

Method: `jadx` (local JDK) on the pulled system APKs under `research/apks/`
(decompiled trees in `research/jadx/`), plus live `adb` over wireless debugging for
state reads and non-destructive intent/broadcast tests. Decompiled Samsung/Google code is
reference only — no proprietary code is copied into the repo.

> **Honest headline.** There is a real, normal-permission path for our own watch app to
> **set any installed face active from the phone** (`EDIT_WATCH_FACE`, protection level
> `normal`). The "official" Samsung apply path (`SET_ACTIVE_WATCH_FACE`) and the Google
> WearServices apply API are both privilege-walled and unreachable by us. Silent app install
> is walled; a push-APK + one-tap-confirm install is reachable.

---

## Live device state captured (for anyone re-testing)

- Active watch face right now: **`com.usadesign.usascalarblack`**, `favoriteId=61`,
  `mType=AndroidxWatchFace` (a Watch Face Format / resource-only face — no service/activity
  components of its own). It is **not** `nz.galaxybridge.watch/.face.BridgeFaceService`; the
  task note on that is stale. Source: `dumpsys wear_service` → `activeWatchFaceFavoriteId`.
  **I did not change it** — every live test below targeted the current face or a no-op and
  it is unchanged after testing.
- The system live-wallpaper host is `com.samsung.android.wearable.sysui/…experiences.
  defaultwatchface.DefaultWatchFace` (the Wear runtime host). The *selected* face is tracked
  by WearServices (`WatchFacesManager`), not by the wallpaper component — so
  `WallpaperManager.getWallpaperInfo()` returns the host, not the chosen WFF face.
- Our app `nz.galaxybridge.watch` is installed and already carries adb-granted elevated perms
  (`WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`, `DUMP`, `PACKAGE_USAGE_STATS`,
  `QUERY_ALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`).
- `com.google.android.packageinstaller` (with `com.android.packageinstaller.InstallStart`
  confirm activity) is present; `REQUEST_INSTALL_PACKAGES` is a known platform permission.

---

## Feature 1 — Set / apply the active watch face from the phone

### How applying a face actually works (the chain behind the wall)

All "apply" roads inside SysUI converge on WearServices:

```
SecWatchFacePickerController.setActiveWatchface(..)
  → WatchFacePickerFavoritesManagerImpl.setCurrent(..)
    → WearSdkWatchFacePickerBackend.setCurrentWatchFaceFromFavorites(int)
      → com.google.wear.services.watchfaces.WatchFacesManager.setCurrentFavorite(int, ..)   // PRIVILEGED
```

`WatchFacesManager` is a WearServices system API. The actual wallpaper swap
(`SET_WALLPAPER_COMPONENT`, Google `SET_WATCH_FACE_PRIVILEGED`) is done by WearServices/SysUI,
which hold those signature/privileged permissions. **A normal app cannot call this directly.**
What matters is whether any *exported component that already holds the privilege* will perform
the apply on our behalf, and under what permission it is reachable.

### Ranked mechanisms

#### A. ⭐ RECOMMENDED — `EDIT_WATCH_FACE` broadcast (protection level `normal`)

`com.samsung.android.wearable.sysui` exports:

```
<receiver WatchFaceEditorBroadcastReceiver
    android:exported="true"
    android:permission="com.samsung.android.wearable.sysui.permission.EDIT_WATCH_FACE">
  <intent-filter><action android:name="com.samsung.android.wearable.sysui.action.EDIT_WATCH_FACE"/></intent-filter>
```

`WatchFaceEditorBroadcastReceiver.onReceive` (decompiled):
- reads String extra `"watchface"` = a **flattened ComponentName** (`unflattenFromString`),
- `getWatchFaceInfo(component)` (must be an installed face),
- `addFavoriteWithCallback(info, AnonymousClass20(…, classId=0), "setActiveWatchfaceAndStartEditor")`.

`AnonymousClass20` (classId 0) → `onSuccess` → **`setActiveWatchface(favInfo, cb)` → `setCurrent(..)`**
(the privileged apply, run *inside* SysUI), then `cb.onSuccess` logs
*"Go to Watchface and start editor"*, calls `moduleBus.goToWatchFace(..)` and
`editingManager.startEditing(..)`.

Net effect of the broadcast: **the target face becomes the active face, and its editor opens.**
Because the privileged `setCurrent` runs inside SysUI, our app needs only to *send* the
broadcast — which requires holding `EDIT_WATCH_FACE`.

**Permission is reachable.** Confirmed on the live device:
`dumpsys package permission com.samsung.android.wearable.sysui.permission.EDIT_WATCH_FACE`
→ `prot=normal`. A `normal` permission is auto-granted at install to any app that declares it —
no adb grant, no user prompt.

- **Possible for our app?** Yes. This is the one path that gives phone-driven *apply* of an
  arbitrary installed face without a privilege wall.
- **Proven live:** protection level `normal` (on-device); receiver is exported and enabled;
  full code path to `setCurrent` + editor (static).
- **Not live-applied:** I deliberately did **not** fire it end-to-end, because it *would* change
  the active face and the current face is a WFF face I cannot cleanly restore from adb (no
  `cmd` to set a favorite by id; `SET_ACTIVE_WATCH_FACE` is walled from shell). My shell
  broadcasts of both `EDIT_WATCH_FACE` and `SET_ACTIVE_WATCH_FACE` were **not delivered** —
  the `am`/`shell` package does not declare these custom permissions, so a shell sender is
  rejected at delivery (and on a `user` build SysUI's `logD/logI` are suppressed, so absence of
  logs is not a disproof). The implementer should verify end-to-end from inside our app, which
  *will* hold the permission — see the test recipe below.

**Caveats to handle in code:**
1. **Component form.** `getWatchFaceInfo` takes a `ComponentName`. For a classic
   `WallpaperService` face (incl. **our own** `nz.galaxybridge.watch/.face.BridgeFaceService`)
   the component is `pkg/service` and resolves cleanly. For third-party **WFF** faces the
   WearServices id is `WatchFaceId[pkg, null]` (null class) — flattening to `pkg/` likely won't
   resolve. So `EDIT_WATCH_FACE` is reliable for **our own face and classic faces**; for WFF
   faces fall back to mechanism **B**. (Determine a WFF face's real component by querying the
   favorites/families list, or just route all third-party faces through B.)
2. **Side-effect: the editor opens** on the watch after the switch. For "apply our Bridge
   face" that is acceptable (user taps Back; face stays). If an editor pop-up is unwanted, use B.

**Precise change (described — NOT applied; `WatchFaces.kt` is editable, the dispatcher in
`Features.kt` is a shared file the main session owns):**

- `watch-app/app/src/main/AndroidManifest.xml`: add
  `<uses-permission android:name="com.samsung.android.wearable.sysui.permission.EDIT_WATCH_FACE"/>`
- `WatchFaces.kt`: add
  ```kotlin
  /** Sets ANY installed face active via SysUI's normal-perm editor broadcast. */
  fun applyFace(c: Context, component: ComponentName): Boolean = runCatching {
      c.sendBroadcast(Intent("com.samsung.android.wearable.sysui.action.EDIT_WATCH_FACE")
          .setClassName("com.samsung.android.wearable.sysui",
              "com.google.android.clockwork.sysui.mainui.module.watchfacepicker2.receivers.WatchFaceEditorBroadcastReceiver")
          .putExtra("watchface", component.flattenToString()))
      true
  }.getOrDefault(false)
  ```
  and in `setDesign(..)` replace the "ask the watch to switch" branch so that when our face is
  not active it calls `applyFace(s, OURS)` first (direct switch to the Bridge face, no picker),
  then falls back to the B deep-link / `openPicker` only if `applyFace` fails.
- New phone→watch feature message (iOS side is a shared file; main session): `{"feature":"applyFace","pkg":"<facepkg>","cls":"<service or empty>"}` → watch calls
  `applyFace(ComponentName(pkg, cls))` for classic faces, else mechanism B for WFF.

#### B. One-tap deep-link to the picker — `CHANGE_LIVE_WALLPAPER` → `WatchFacePickerAllFacesActivity` (exported, **no permission**)

```
<activity WatchFacePickerAllFacesActivity android:exported="true">   (no android:permission)
  <intent-filter>
    <action android:name="android.service.wallpaper.CHANGE_LIVE_WALLPAPER"/>
    <action android:name="android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"/>
```

`onResume` reads extra `android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT` (a
`ComponentName`) and scrolls/positions the all-faces grid to that face
(`controller.requestedStartId = SysUiWatchFaceId.fromComponentName(..)`). Applying still needs
the **user to tap the face** (`onItemSelected` → `addFavorite(..,true)` → active).

- **Possible for our app?** Yes — it is exported with no permission, reachable by any app.
- **Proven live:** ✅ I launched it from shell (an untrusted uid, same footing as a normal app)
  with the action + `--ecn …LIVE_WALLPAPER_COMPONENT nz.galaxybridge.watch/.face.BridgeFaceService`;
  the picker opened scrolled to that face; pressed HOME; **active face unchanged**
  (`com.usadesign.usascalarblack` fav 61). This is the honest "from the phone" path for
  arbitrary (incl. WFF) faces: it reduces to **one tap on the watch**.
- Our app **already** uses this exact intent, but only hard-wired to our own face
  (`WatchFaces.setDesign`/`openPicker`). Improvement: generalise it to accept any target
  component sent from the phone, so the iPhone face list can deep-link to *any* installed face.

**Precise change:** generalise `WatchFaces.openPicker`/`setDesign` to take a `ComponentName?`
and, when present, build `Intent(ACTION_CHANGE_LIVE_WALLPAPER).putExtra(EXTRA_LIVE_WALLPAPER_COMPONENT, target)`.

#### C. `SET_ACTIVE_WATCH_FACE` broadcast — WALLED (do not pursue)

`SecWatchFacePickerBroadCastReceiver` (exported) action
`com.samsung.android.wearable.sysui.action.SET_ACTIVE_WATCH_FACE`, extra `watchface`
(`ComponentName`) → `controller.setActiveWatchface(..)`. This is the "clean apply, no editor"
path, BUT its guarding permission is `signature|privileged` (on-device
`dumpsys package permission … SET_ACTIVE_WATCH_FACE` → `prot=signature|privileged`). Our
user-key-signed, non-privileged app **cannot hold it**, so it cannot send this broadcast.
(There is also a `TEST_ACTIVE_WATCH_FACE` action taking `watchfacePkg`/`watchfaceClass`
strings, but it is gated to non-`user` builds — dead on this retail watch.) Live shell attempt:
not delivered, face unchanged.

#### D. WearServices `WatchFacesManager.setCurrentFavorite(int)` — WALLED
The underlying API. Requires the WearServices client privilege held by SysUI. Not reachable.

#### E. Samsung WCS extension — `WatchfaceClient.sendWatchfaceOrder` — not useful for iPhone
SysUI also binds a Samsung "WCS extension" service
(`com.samsung.android.wcs.extension.sdk.contract.watchface.WatchfaceApi`, bind action of the
same name; companion settings via ContentProvider authority
`com.samsung.android.wcs.extension.provider.watchface`). `WatchfaceClient` exposes
`sendWatchfaceOrder` (transact 2), active/addable/response channels (3/6/9). This is Samsung's
**phone↔watch bridge for watch faces** — it relays to/from the **Galaxy Wearable** app over
Samsung's proprietary companion link. It presupposes a Galaxy phone and Samsung's bond; an
iPhone cannot drive it. Documented for completeness; not a path for us.

#### F. adb-only privileged path (setup-time, not runtime)
The `shell` package holds Google's `com.google.android.permission.SET_WATCH_FACE_PRIVILEGED`
(`granted=true`) and `BIND_WATCH_FACE_CONTROL`. So a face *can* be set over adb by a
sufficiently-privileged caller, but this is a shell/privileged capability, not something our
runtime app can use, and there is no plain `cmd wear_service`/`cmd wallpaper` sub-command to set
the active face (`cmd wallpaper` only does dim; `cmd wear_service` has `watchface-resync` for the
supported-component list, not "set active"). Mention only as an adb/setup convenience.

### Feature 1 recommendation
Ship **A + B together**: use `EDIT_WATCH_FACE` (A) to switch directly to our Bridge face and to
any classic face (true from-phone apply), and the `CHANGE_LIVE_WALLPAPER` deep-link (B,
one tap) for third-party WFF faces where A's component can't be resolved. This makes "pick a
face on the phone, it appears on the watch" work for our own face with zero taps and for any
other face with at most one tap — as native as a non-privileged app gets.

---

## Feature 2 — Install apps from the phone

### What is reachable
- **Silent / unattended install** needs `android.permission.INSTALL_PACKAGES` — on-device
  `prot=signature|privileged`. **Walled** for our app.
- **Push-APK + one-tap confirm** is reachable: our watch app receives the APK bytes over our
  link (`WatchLink`⇄`LinkService`), writes them into a `PackageInstaller.Session`, and commits;
  the system shows `com.android.packageinstaller.InstallStart` (present on the watch) as a
  single confirm screen on the watch. This needs `android.permission.REQUEST_INSTALL_PACKAGES`.
  On-device that permission is `prot=signature|appop` — i.e. **not** auto-granted: the user must
  enable "Install unknown apps" for our app, **or** we pre-authorise it at setup with
  `adb shell appops set nz.galaxybridge.watch REQUEST_INSTALL_PACKAGES allow` (our app already
  uses adb-granted perms at setup, so this fits the existing model). After that, each install is
  one confirm tap on the watch.
- **Google's own remote install** (phone "install on watch") works by **Play Store streaming**
  from the paired Android phone's Play Store to the watch — an Android-companion mechanism that
  an iPhone cannot originate. Not a path for us; our push-APK model is the equivalent.

### Precise change (described)
- `AndroidManifest.xml`: add `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>`.
- New `Installer.kt` (non-shared): accept an APK transferred in chunks over the link (reuse the
  existing `MediaTransfer`/chunking), then:
  ```kotlin
  val pi = context.packageManager.packageInstaller
  val params = PackageInstaller.SessionParams(SessionParams.MODE_FULL_INSTALL)
  val id = pi.createSession(params); val s = pi.openSession(id)
  s.openWrite("app", 0, len).use { out -> apk.copyTo(out); s.fsync(out) }
  s.commit(PendingIntent.getBroadcast(context, 0, Intent(ACTION_INSTALL_RESULT), FLAG_MUTABLE).intentSender)
  ```
  Report the `PackageInstaller.STATUS_*` back to the phone as a feature message
  (e.g. `{"feature":"install","pkg":..,"status":"ok|confirm|failed"}`); `STATUS_PENDING_USER_ACTION`
  means launch the returned confirm `Intent` on the watch.
- Also offer **uninstall from the phone**: our app already holds `REQUEST_DELETE_PACKAGES`, so
  `startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))` gives a one-tap
  uninstall confirm. (Launch-app and app-list are already present.)

---

## Feature 3 — Other on-watch → from-phone improvements worth doing

1. **Direct "switch to our Bridge face" (uses Feature 1A).** Today `WatchFaces.setDesign`
   can only *pre-load* a design and then relies on the user choosing our face in the picker.
   With `EDIT_WATCH_FACE` the phone can switch straight to `nz.galaxybridge.watch/.face.
   BridgeFaceService` (classic `WallpaperService`, resolves cleanly) — so "apply this design"
   from the iPhone becomes a real one-shot action instead of an instruction to the user.
2. **Open a specific face's editor from the phone (reference only).** The same
   `EDIT_WATCH_FACE` broadcast also opens the SysUI editor for the applied face
   (`startEditing`). If the phone ever wants to drive complication/style editing, this is the
   entry point — but editing is interactive on the watch; there is no headless style-apply for
   third-party faces at `normal` permission.
3. **App-tray ordering / tiles / quick-replies (investigated, not useful for iPhone).** SysUI
   also binds Samsung WCS clients: `AppTrayClient` (`sendAppsOrdering`, `getAppsRestore`),
   `TileClient` (addable tiles), `NotificationClient` (now-bar), `TextTemplateClient`
   (quick responses). All are relays to the **Galaxy Wearable phone app** over Samsung's
   companion link and assume a Galaxy phone; they do not give an iPhone companion anything our
   own link doesn't already do. Not recommended.
4. **Active-WFF-face detection without `DUMP`.** `WatchFaces.activeWffPackage` currently needs
   the adb-granted `DUMP` permission to read the runtime's dump. WearServices tracks the active
   favorite in `dumpsys wear_service` (`activeWatchFaceFavoriteId = WatchFaceId[pkg, null]`),
   but there is no `normal`-permission content provider/API exposing it that I found — so `DUMP`
   (already adb-granted at setup) or the last-updated-WFF heuristic remain the options. No change
   recommended; just confirming the current approach is the best available unprivileged one.

---

## Privilege-wall summary

| Capability | Mechanism | Guard (on-device) | Our app? |
|---|---|---|---|
| Apply ANY face, switch + open editor | `EDIT_WATCH_FACE` broadcast | `normal` | ✅ reachable (auto-granted) |
| Deep-link picker to a face (1 tap) | `CHANGE_LIVE_WALLPAPER` + `EXTRA_LIVE_WALLPAPER_COMPONENT` → `WatchFacePickerAllFacesActivity` | exported, none | ✅ live-proven |
| Apply face cleanly, no editor | `SET_ACTIVE_WATCH_FACE` broadcast | `signature\|privileged` | ❌ walled |
| Apply face (underlying) | WearServices `WatchFacesManager.setCurrentFavorite` | WearServices privilege | ❌ walled |
| Apply face via Samsung bridge | WCS `WatchfaceClient.sendWatchfaceOrder` | Galaxy-phone relay | ❌ N/A for iPhone |
| Install app, one-tap confirm | `PackageInstaller` + `REQUEST_INSTALL_PACKAGES` | `signature\|appop` (user/appops toggle) | ✅ with setup appop or user toggle |
| Install app silently | `PackageInstaller` + `INSTALL_PACKAGES` | `signature\|privileged` | ❌ walled |
| Uninstall, one-tap confirm | `ACTION_DELETE` / `REQUEST_DELETE_PACKAGES` | held already | ✅ |

## Proven live vs. static
- **Live (adb):** current active face + that it is WFF/resource-only; `EDIT_WATCH_FACE` =
  `normal` and `SET_ACTIVE_WATCH_FACE` = `signature|privileged`; `REQUEST_INSTALL_PACKAGES`
  present; PackageInstaller + confirm activity present; `WatchFacePickerAllFacesActivity`
  deep-link launches from an untrusted uid and leaves the active face unchanged; `cmd
  wear_service`/`cmd wallpaper` have no set-active-face sub-command; shell holds Google
  `SET_WATCH_FACE_PRIVILEGED`.
- **Static (decompiled):** the full `EDIT_WATCH_FACE` → `setActiveWatchface` → `setCurrent`
  (+ editor) code path; the `SET_ACTIVE_WATCH_FACE`/WearServices/WCS chains; the picker
  deep-link extra parsing. The `EDIT_WATCH_FACE` end-to-end *apply* was not fired to avoid
  mutating the user's face; verify from inside our app (which will hold the permission) with a
  round-trip between two **classic** faces so it is cleanly reversible.
