package nz.galaxybridge.watch

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feature messages from the iPhone, on top of the core link. Every field from the phone is
 * validated here before use; anything unexpected is ignored.
 *
 * Opening another app's screen from the background (alarms, Play Store, uninstall prompt) needs
 * "Display over other apps" for this app, which is granted once over adb on this watch.
 */
object Features {
    private const val TAG = "Features"

    /** Must match GBFeature.toWatch in GBLink.swift. */
    val FROM_PHONE = setOf(
        "contacts", "mute", "reminders", "alarm", "timer", "uninstall", "openStore", "launch",
        "getApps", "getSounds", "setSound", "photo", "getRecordings", "getRecording", "workoutAck",
        "camFrame", "camState", "getNotifApps", "getFace", "getFaces", "openFacePicker", "applyFace", "setDesign", "getDesign",
        "getSensors", "measureSensors", "openSensorSettings", "measureEcg", "measureBia",
        "install", "setBiaProfile", "call",
        // Wave D (media, customisation, productivity): handled in WaveD.kt.
        "musicPush", "musicList", "musicDelete", "faceSchedule", "getFaceSchedule",
        "qrPass", "qrPassDelete", "qrPassList", "homeScenes", "homeSceneResult",
        "toolsConfig", "translateResult", "checklist", "getTileState",
    )

    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    fun handle(s: LinkService, type: String, j: JSONObject) {
        runCatching {
            when (type) {
                "contacts" -> ContactsSync.receive(s, j)
                "mute" -> setMuted(s, j)
                "getNotifApps" -> s.send(WatchMessages.feature("notifApps", notifApps(s)))
                "reminders" -> Reminders.receive(s, j)
                "alarm" -> setAlarm(s, j)
                "timer" -> setTimer(s, j)
                "uninstall" -> pkg(j)?.let { uninstall(s, it) }
                "openStore" -> pkg(j)?.let { openStore(s, it) }
                "launch" -> pkg(j)?.let { launch(s, it) }
                "getApps" -> s.send(WatchMessages.feature("apps", JSONObject().put("items", apps(s))))
                "getSounds" -> s.send(WatchMessages.feature("sounds", sounds(s)))
                "setSound" -> setSound(s, j)
                "photo" -> MediaTransfer.receivePhoto(s, j)
                "getRecordings" -> MediaTransfer.listRecordings(s)
                "getRecording" -> MediaTransfer.sendRecording(s, j.optLong("id", -1))
                "workoutAck" -> Workouts.ack(s, j.optString("id"))
                "camFrame" -> CameraRemote.frame(j)
                "camState" -> CameraRemote.state(j)
                "getFace" -> WatchFaces.sendNow(s)
                "getFaces" -> Thread {
                    val items = WatchFaces.list(s)
                    s.send(WatchMessages.feature("faces", JSONObject().put("items", items)))
                    WatchFaces.sendPreviews(s, items)
                }.start()
                "setDesign" -> WatchFaces.setDesign(s, j)
                "getDesign" -> s.send(WatchMessages.feature("design", WatchFaces.designState(s)))
                "openFacePicker" -> result(s, "facePicker", WatchFaces.openPicker(s), "Choose a face on your watch")
                "applyFace" -> applyFace(s, j)
                "install" -> Installer.receive(s, j)
                "setBiaProfile" -> setBiaProfile(s, j)
                "call" -> call(s, j)
                "getSensors" -> s.send(WatchMessages.feature("sensors", JSONObject().put("available", SensorBridge.isAvailable(s))))
                "openSensorSettings" -> result(s, "sensorSettings", SensorBridge.openDeveloperModeSettings(s),
                    "Turn on Developer mode in Samsung Health on your watch")
                "measureSensors" -> measureSensors(s, j)
                "measureEcg" -> measureEcg(s)
                "measureBia" -> measureBia(s)
                in WaveD.FROM_PHONE -> WaveD.handle(s, type, j)
            }
        }.onFailure { Log.w(TAG, "feature $type failed", it) }
    }

    fun result(s: LinkService, what: String, ok: Boolean, detail: String = "") =
        s.send(WatchMessages.feature("result", JSONObject().put("what", what).put("ok", ok).put("detail", detail.take(120))))

    private fun pkg(j: JSONObject): String? = j.optString("pkg").takeIf { it.length <= 200 && PACKAGE.matches(it) }

    private val CLASS_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_.$]*$")
    private val PHONE_NUMBER = Regex("^[0-9+][0-9*#+ ().-]{0,31}$")

    /** Applies any installed face from the phone (classic via EDIT_WATCH_FACE, WFF via deep-link). */
    private fun applyFace(s: LinkService, j: JSONObject) {
        val p = pkg(j) ?: return result(s, "applyFace", false, "")
        val cls = j.optString("cls").takeIf { it.isEmpty() || (it.length <= 200 && CLASS_NAME.matches(it)) } ?: return
        val ok = WatchFaces.apply(s, p, cls)
        result(s, "applyFace", ok, if (ok) "Applying the face on your watch" else "Couldn't switch faces")
    }

    /** Stores the user's body profile for BIA (height cm, weight kg, gender 1=male/0=female, age). */
    private fun setBiaProfile(s: LinkService, j: JSONObject) {
        val height = j.optDouble("height", Double.NaN)
        val weight = j.optDouble("weight", Double.NaN)
        val gender = j.optInt("gender", -1)
        val age = j.optInt("age", -1)
        if (!height.isFinite() || !weight.isFinite()) return result(s, "biaProfile", false, "")
        val ok = SensorBridge.setProfile(s, height.toFloat(), weight.toFloat(), gender, age)
        result(s, "biaProfile", ok, if (ok) "Body profile saved on your watch" else "Profile values out of range")
    }

    /** Call control over HFP: place/answer/reject/hang up. See CallAudio (needs a classic-BT bond). */
    private fun call(s: LinkService, j: JSONObject) {
        val action = when (j.optString("action")) {
            "dial" -> CallAudio.Action.DIAL
            "answer" -> CallAudio.Action.ANSWER
            "reject" -> CallAudio.Action.REJECT
            "hangup" -> CallAudio.Action.HANGUP
            else -> return
        }
        val number = j.optString("number").takeIf { it.isEmpty() || PHONE_NUMBER.matches(it) } ?: return
        if (action == CallAudio.Action.DIAL && number.isEmpty()) return result(s, "call", false, "No number to dial")
        CallAudio.control(s, action, number)
        result(s, "call", true, "Sent to your watch")
    }

    /** The SpO2/skin-temp sensors the phone asked for (defaults to SpO2). */
    internal fun sensorKinds(j: JSONObject): Set<SampleKind> {
        val wanted = j.optJSONArray("sensors")
        return buildSet {
            // Always capture a live heart rate alongside whatever else was asked, so a tap on
            // "Measure" returns a current HR to the phone even when passive HR isn't being delivered.
            add(SampleKind.HEART_RATE)
            if (wanted == null) add(SampleKind.SPO2)
            else for (i in 0 until wanted.length()) when (wanted.optString(i)) {
                "spo2" -> add(SampleKind.SPO2)
                "skintemp" -> add(SampleKind.SKIN_TEMP)
                "hr" -> add(SampleKind.HEART_RATE)
            }
        }
    }

    /**
     * Reads the Samsung-only sensors on demand (SpO2, skin temperature) and syncs them. The
     * measurement runs in our own [MeasureActivity] on the watch so the user sees a progress panel
     * and the result; if that activity can't be launched, it falls back to a headless capture. The
     * same "result" message goes to the phone either way.
     */
    private fun measureSensors(s: LinkService, j: JSONObject) {
        val kinds = sensorKinds(j)
        if (kinds.isEmpty() || !SensorBridge.isAvailable(s)) {
            return result(s, "sensors", false, "Samsung health sensors aren't available on this watch")
        }
        if (!MeasureActivity.launch(s, MeasureActivity.KIND_SENSORS, kinds.map { it.wire })) {
            SensorBridge.measure(s, kinds) { ok -> sendSensorsResult(s, ok) }
        }
    }

    private fun measureEcg(s: LinkService) {
        if (!SensorBridge.isAvailable(s)) return result(s, "ecg", false, "ECG isn't available on this watch")
        if (!MeasureActivity.launch(s, MeasureActivity.KIND_ECG)) {
            SensorBridge.captureEcg(s) { hz, uv -> sendEcgResult(s, hz, uv) }
        }
    }

    private fun measureBia(s: LinkService) {
        if (!SensorBridge.isAvailable(s)) return result(s, "bia", false, "Body composition isn't available on this watch")
        if (!MeasureActivity.launch(s, MeasureActivity.KIND_BIA)) {
            SensorBridge.captureBia(s) { values -> sendBiaResult(s, values) }
        }
    }

    // --- Result messages to the phone; shared by the headless path and MeasureActivity. ---

    internal fun sendSensorsResult(s: LinkService, ok: Boolean) =
        result(s, "sensors", ok, if (ok) "Reading synced to Apple Health" else "No reading; check Developer mode and permissions")

    /** Records an ECG strip and sends the waveform to the iPhone for display (not to Apple Health). */
    internal fun sendEcgResult(s: LinkService, hz: Int, uv: ShortArray) {
        if (uv.isEmpty()) return result(s, "ecg", false, "No ECG captured; keep still with a finger on the bezel")
        val bytes = java.nio.ByteBuffer.allocate(uv.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in uv) bytes.putShort(v)
        s.send(WatchMessages.feature("ecg", JSONObject()
            .put("hz", hz).put("n", uv.size).put("start", System.currentTimeMillis())
            .put("uv", android.util.Base64.encodeToString(bytes.array(), android.util.Base64.NO_WRAP))))
    }

    internal fun sendBiaResult(s: LinkService, values: Map<String, Double>) {
        if (values.isEmpty()) return result(s, "bia", false, "No reading; hold two fingers on the buttons and stay still")
        val body = JSONObject().put("at", System.currentTimeMillis())
        for ((k, v) in values) body.put(k, v)
        s.send(WatchMessages.feature("bia", body))
    }

    // --- Notifications: per-app and per-category filtering ---

    private fun prefs(c: Context) = c.getSharedPreferences("notifFilter", Context.MODE_PRIVATE)

    /** Remembers which iPhone apps have sent notifications, so the phone can offer them as a list. */
    fun allowNotification(c: Context, e: Ancs.Event, d: Ancs.Details, app: String): Boolean {
        if (d.appId.isNotEmpty()) prefs(c).edit().putString("seen:${d.appId}", app.take(40)).apply()
        if (e.category == Ancs.CATEGORY_INCOMING_CALL) return true // calls always get through
        val p = prefs(c)
        if (p.getBoolean("mute:${d.appId}", false)) return false
        return p.getBoolean("cat:${e.category}", true)
    }

    private fun notifApps(c: Context): JSONObject {
        val p = prefs(c).all
        val apps = JSONArray()
        for ((k, v) in p) if (k.startsWith("seen:")) {
            val id = k.removePrefix("seen:")
            apps.put(JSONObject().put("id", id).put("name", v as? String ?: id).put("muted", p["mute:$id"] == true))
        }
        val cats = JSONObject()
        for (i in 0..11) cats.put(i.toString(), p["cat:$i"] as? Boolean ?: true)
        return JSONObject().put("items", apps).put("cats", cats)
    }

    private fun setMuted(c: Context, j: JSONObject) {
        val e = prefs(c).edit()
        j.optJSONObject("apps")?.let { apps ->
            for (id in apps.keys()) if (id.length <= 200) e.putBoolean("mute:$id", apps.optBoolean(id))
        }
        j.optJSONObject("cats")?.let { cats ->
            for (k in cats.keys()) k.toIntOrNull()?.takeIf { it in 0..11 }?.let { e.putBoolean("cat:$it", cats.optBoolean(k, true)) }
        }
        e.apply()
    }

    // --- Alarms and timers: handed to the watch's own Clock app ---

    private fun setAlarm(s: LinkService, j: JSONObject) {
        val h = j.optInt("h", -1); val m = j.optInt("m", -1)
        if (h !in 0..23 || m !in 0..59) return
        val label = clean(j.optString("label")).take(40)
        val ok = start(s, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        result(s, "alarm", ok)
    }

    private fun setTimer(s: LinkService, j: JSONObject) {
        val secs = j.optInt("secs", -1)
        if (secs !in 1..86_400) return
        val ok = start(s, Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, clean(j.optString("label")).take(40))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        result(s, "timer", ok)
    }

    // --- Apps ---

    private fun apps(c: Context): JSONArray {
        val pm = c.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val stats = c.getSystemService(android.app.usage.StorageStatsManager::class.java)
        val out = JSONArray()
        for (ri in pm.queryIntentActivities(launcher, 0).distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }) {
            val ai = ri.activityInfo.applicationInfo
            val info = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
            // Storage use needs usage access (granted over adb); leave it out otherwise.
            val bytes = runCatching {
                stats.queryStatsForPackage(ai.storageUuid, ai.packageName, android.os.Process.myUserHandle())
                    .let { it.appBytes + it.dataBytes + it.cacheBytes }
            }.getOrNull()
            out.put(JSONObject()
                .put("pkg", ai.packageName)
                .put("name", ri.loadLabel(pm).toString().take(40))
                .put("ver", info?.versionName?.take(24) ?: "")
                .put("system", ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                .put("mb", bytes?.let { (it / 100_000) / 10.0 } ?: -1.0))
        }
        return out
    }

    private fun uninstall(s: LinkService, pkg: String) {
        val ok = start(s, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        result(s, "uninstall", ok, if (ok) "Confirm on the watch" else "")
    }

    private fun openStore(s: LinkService, pkg: String) {
        val ok = start(s, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage("com.android.vending"))
        result(s, "openStore", ok, if (ok) "Tap Install on your watch" else "Play Store didn't open")
    }

    private fun launch(s: LinkService, pkg: String) {
        val intent = s.packageManager.getLaunchIntentForPackage(pkg) ?: return result(s, "launch", false)
        result(s, "launch", start(s, intent))
    }

    // --- Ringtone and notification sound ---

    private fun sounds(c: Context): JSONObject {
        fun list(type: Int): JSONArray {
            val out = JSONArray()
            val rm = RingtoneManager(c).apply { setType(type) }
            val cur = rm.cursor
            while (cur.moveToNext() && out.length() < 80) {
                out.put(JSONObject().put("title", cur.getString(RingtoneManager.TITLE_COLUMN_INDEX).take(40))
                    .put("uri", rm.getRingtoneUri(cur.position).toString()))
            }
            return out
        }
        fun current(type: Int) = RingtoneManager.getActualDefaultRingtoneUri(c, type)?.toString() ?: ""
        return JSONObject()
            .put("ring", list(RingtoneManager.TYPE_RINGTONE)).put("ringNow", current(RingtoneManager.TYPE_RINGTONE))
            .put("notif", list(RingtoneManager.TYPE_NOTIFICATION)).put("notifNow", current(RingtoneManager.TYPE_NOTIFICATION))
    }

    private fun setSound(s: LinkService, j: JSONObject) {
        val type = when (j.optString("kind")) {
            "ring" -> RingtoneManager.TYPE_RINGTONE
            "notif" -> RingtoneManager.TYPE_NOTIFICATION
            else -> return
        }
        val uri = j.optString("uri")
        // Only accept a sound the watch itself offered.
        val offered = sounds(s).optJSONArray(if (type == RingtoneManager.TYPE_RINGTONE) "ring" else "notif") ?: return
        if ((0 until offered.length()).none { offered.getJSONObject(it).optString("uri") == uri }) return
        val ok = WatchSettings.canWriteSystem(s) &&
            runCatching { RingtoneManager.setActualDefaultRingtoneUri(s, type, Uri.parse(uri)); true }.getOrDefault(false)
        result(s, "sound", ok)
        s.send(WatchMessages.feature("sounds", sounds(s)))
    }

    // --- helpers ---

    /** Starts another app's screen from the background. */
    fun start(c: Context, intent: Intent): Boolean = runCatching {
        if (intent.resolveActivity(c.packageManager) == null) return false
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { Log.w(TAG, "start failed", it) }.getOrDefault(false)

    fun clean(s: String) = s.filter { !it.isISOControl() }

    @Suppress("unused")
    private fun isInstalled(pm: PackageManager, pkg: String) =
        runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
