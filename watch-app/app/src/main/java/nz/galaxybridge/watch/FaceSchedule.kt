package nz.galaxybridge.watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Switches watch faces by time of day (and optionally for workouts), as set up in the iPhone app.
 * Faces are applied through the same SysUI path as "apply from phone" ([WatchFaces.applyFace]),
 * which only works for classic (WallpaperService) faces; Watch Face Format / Play faces can't be
 * switched without a tap, so they are refused when the schedule is saved.
 *
 * Nothing happens unless the user saved a schedule with it switched on, and the face only changes
 * at a slot's start time; the current face is never touched just because a schedule was saved.
 *
 * {"t":"faceSchedule","on":bool,"slots":[{"h","m","pkg","cls","name"}],"workout":{"pkg","cls","name"}|null}
 */
object FaceSchedule {
    private const val TAG = "FaceSchedule"
    private const val PREFS = "faceSchedule"
    private const val MAX_SLOTS = 8
    private const val WINDOW_MS = 2 * 60_000L

    data class Face(val pkg: String, val cls: String, val name: String) {
        val component get() = ComponentName(pkg, cls)
        fun json(): JSONObject = JSONObject().put("pkg", pkg).put("cls", cls).put("name", name)
    }
    data class Slot(val h: Int, val m: Int, val face: Face) {
        val minute get() = h * 60 + m
    }
    data class Plan(val on: Boolean, val slots: List<Slot>, val workout: Face?)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val CLASS_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_.$]*$")

    /** Resolves a face the phone named to a classic face service on this watch, or null. */
    private fun resolve(c: Context, o: JSONObject?): Face? {
        o ?: return null
        val pkg = o.optString("pkg").takeIf { it.length <= 200 && PACKAGE.matches(it) } ?: return null
        val cls = o.optString("cls").takeIf { it.isEmpty() || (it.length <= 200 && CLASS_NAME.matches(it)) } ?: return null
        val services = c.packageManager.queryIntentServices(Intent(WallpaperService.SERVICE_INTERFACE).setPackage(pkg), PackageManager.GET_META_DATA)
        val si = services.firstOrNull { cls.isEmpty() || it.serviceInfo.name == cls }?.serviceInfo ?: return null
        val name = WaveD.text(o.optString("name"), 50).ifEmpty { si.loadLabel(c.packageManager).toString().take(50) }
        return Face(pkg, si.name, name)
    }

    fun receive(s: LinkService, j: JSONObject) {
        val arr = j.optJSONArray("slots") ?: JSONArray()
        if (arr.length() > MAX_SLOTS) return Features.result(s, "faceSchedule", false, "Up to $MAX_SLOTS times a day")
        val slots = ArrayList<Slot>()
        val refused = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val h = o.optInt("h", -1); val m = o.optInt("m", -1)
            if (h !in 0..23 || m !in 0..59) continue
            val face = resolve(s, o)
            if (face == null) { refused += WaveD.text(o.optString("name"), 30).ifEmpty { o.optString("pkg").take(30) }; continue }
            slots += Slot(h, m, face)
        }
        val workoutJson = j.optJSONObject("workout")
        val workout = resolve(s, workoutJson)
        if (workoutJson != null && workout == null) refused += WaveD.text(workoutJson.optString("name"), 30)
        val plan = Plan(j.optBoolean("on", false), slots.distinctBy { it.minute }.sortedBy { it.minute }, workout)
        save(s, plan)
        // A fresh plan starts from now: don't switch faces for a slot that began before it was saved.
        prefs(s).edit().putLong("appliedAt", System.currentTimeMillis()).apply()
        reschedule(s)
        val detail = when {
            refused.isNotEmpty() -> "Saved. Skipped ${refused.joinToString()} — only classic faces can switch automatically"
            !plan.on -> "Face schedule is off"
            else -> "Face schedule saved on your watch"
        }
        Features.result(s, "faceSchedule", refused.isEmpty(), detail.take(120))
        sendState(s)
    }

    fun load(c: Context): Plan {
        val o = runCatching { JSONObject(prefs(c).getString("plan", "{}")!!) }.getOrDefault(JSONObject())
        val slots = o.optJSONArray("slots")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                Slot(s.optInt("h"), s.optInt("m"), Face(s.optString("pkg"), s.optString("cls"), s.optString("name")))
            }
        } ?: emptyList()
        val w = o.optJSONObject("workout")?.let { Face(it.optString("pkg"), it.optString("cls"), it.optString("name")) }
        return Plan(o.optBoolean("on", false), slots, w)
    }

    private fun save(c: Context, p: Plan) {
        val o = JSONObject().put("on", p.on)
            .put("slots", JSONArray(p.slots.map { it.face.json().put("h", it.h).put("m", it.m) }))
        p.workout?.let { o.put("workout", it.json()) }
        prefs(c).edit().putString("plan", o.toString()).apply()
    }

    fun sendState(s: LinkService) {
        val p = load(s)
        val o = JSONObject().put("on", p.on)
            .put("slots", JSONArray(p.slots.map { it.face.json().put("h", it.h).put("m", it.m) }))
        p.workout?.let { o.put("workout", it.json()) }
        s.send(WatchMessages.feature("faceScheduleState", o))
    }

    /** Start time (epoch ms) of the slot in effect at [now], and the slot; null if none. */
    internal fun current(slots: List<Slot>, now: Long): Pair<Long, Slot>? {
        if (slots.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minuteNow = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = slots.lastOrNull { it.minute <= minuteNow }
        val slot = today ?: slots.last()
        cal.set(Calendar.HOUR_OF_DAY, slot.h); cal.set(Calendar.MINUTE, slot.m)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (today == null) cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis to slot
    }

    internal fun nextStart(slots: List<Slot>, now: Long): Long? {
        if (slots.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minuteNow = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val slot = slots.firstOrNull { it.minute > minuteNow } ?: slots.first()
        cal.set(Calendar.HOUR_OF_DAY, slot.h); cal.set(Calendar.MINUTE, slot.m)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (slot.minute <= minuteNow) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(c, 0,
        Intent(c, FaceScheduleReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Sets an inexact wake-up alarm for the next slot (exact alarms need a permission Android 14
     * no longer grants to apps like this). If doze holds it back, [catchUp] on screen-on applies it.
     */
    fun reschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(c)
        am.cancel(pi)
        val p = load(c)
        if (!p.on) return
        val next = nextStart(p.slots, System.currentTimeMillis()) ?: return
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, next, WINDOW_MS, pi) }
            .onFailure { Log.w(TAG, "alarm", it) }
    }

    /** Applies the slot in effect now if its start passed since the last switch. */
    fun catchUp(c: Context) {
        val p = load(c)
        if (!p.on || Workouts.live.value != null) return
        val (start, slot) = current(p.slots, System.currentTimeMillis()) ?: return
        val applied = prefs(c).getLong("appliedAt", 0)
        if (start <= applied) return
        prefs(c).edit().putLong("appliedAt", System.currentTimeMillis()).apply()
        apply(c, slot.face)
    }

    private fun apply(c: Context, f: Face) {
        if (WatchFaces.active(c) == f.component) return
        val ok = WatchFaces.applyFace(c, f.component)
        Log.i(TAG, "switch to ${f.name}: $ok")
    }

    /** Workout face: on start remember what was on and switch; on end put the right face back. */
    fun onWorkout(c: Context, running: Boolean) {
        val p = load(c)
        val w = p.workout ?: return
        if (!p.on) return
        val prefs = prefs(c)
        if (running) {
            if (prefs.getBoolean("inWorkout", false)) return
            prefs.edit().putBoolean("inWorkout", true)
                .putString("before", WatchFaces.active(c)?.flattenToString()).apply()
            apply(c, w)
        } else {
            if (!prefs.getBoolean("inWorkout", false)) return
            val before = prefs.getString("before", null)?.let { ComponentName.unflattenFromString(it) }
            prefs.edit().putBoolean("inWorkout", false).remove("before").apply()
            val slot = current(p.slots, System.currentTimeMillis())?.second
            when {
                slot != null -> apply(c, slot.face)
                before != null && isClassic(c, before) -> WatchFaces.applyFace(c, before)
                // The earlier face was a Watch Face Format face: it can't be re-applied without a
                // tap, so open the picker for the user rather than leave the workout face on.
                before != null && before != w.component -> WatchFaces.openPicker(c)
            }
        }
    }

    private fun isClassic(c: Context, cn: ComponentName) =
        c.packageManager.queryIntentServices(Intent(WallpaperService.SERVICE_INTERFACE).setPackage(cn.packageName), 0)
            .any { it.serviceInfo.name == cn.className && cn.packageName !in RUNTIME_PACKAGES }

    /** Hosts for Watch Face Format faces; their component is the runtime, not the face. */
    private val RUNTIME_PACKAGES = setOf("com.samsung.wear.watchface.runtime", "com.google.wear.watchface.runtime")
}

class FaceScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FaceSchedule.catchUp(context)
        FaceSchedule.reschedule(context)
    }
}
