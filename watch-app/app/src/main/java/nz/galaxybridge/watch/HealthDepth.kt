package nz.galaxybridge.watch

import android.content.Context
import org.json.JSONObject

/**
 * Health-depth settings sent from the iPhone (one "hdConfig" message): daily goals, reminders,
 * automatic background readings and workout auto-pause. Kept in their own prefs file rather than
 * WatchSettings so the status message's settings map stays small. Every field is range-checked;
 * anything unknown or out of range is ignored. Background readings and reminders are off by default.
 */
object HealthDepth {
    private const val PREFS = "healthDepth"

    /** key → (allowed range, default). */
    val FIELDS: Map<String, Pair<IntRange, Int>> = linkedMapOf(
        "goalSteps" to ((1_000..50_000) to 10_000),
        "goalKcal" to ((50..3_000) to 500),
        "goalActiveMin" to ((5..300) to 30),
        // Automatic background readings (SpO2, skin temperature, heart rate, HRV).
        "autoLog" to ((0..1) to 0),
        "autoLogMin" to ((15..240) to 60),
        "autoLogMinBattery" to ((10..80) to 30),
        // Overnight readings while the sleep estimate says the wearer is asleep.
        "overnight" to ((0..1) to 0),
        "overnightMin" to ((15..120) to 30),
        // Reminders (watch notifications).
        "remindInactive" to ((0..1) to 0),
        "inactiveMin" to ((30..180) to 60),
        "remindStand" to ((0..1) to 0),
        "remindWater" to ((0..1) to 0),
        "waterEveryMin" to ((30..240) to 120),
        "quietStart" to ((0..23) to 22),
        "quietEnd" to ((0..23) to 8),
        // Quick-log amounts.
        "cupMl" to ((50..1_000) to 250),
        "coffeeMg" to ((10..500) to 95),
        // Workouts.
        "autoPause" to ((0..1) to 1),
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, key: String): Int {
        val (range, def) = FIELDS[key] ?: return 0
        return prefs(c).getInt(key, def).coerceIn(range)
    }

    fun on(c: Context, key: String) = get(c, key) == 1

    /** Applies the valid fields of [j]. Returns how many were accepted. */
    fun apply(c: Context, j: JSONObject): Int {
        val e = prefs(c).edit()
        var n = 0
        for ((k, spec) in FIELDS) {
            if (!j.has(k)) continue
            val v = j.optInt(k, Int.MIN_VALUE)
            if (v in spec.first) { e.putInt(k, v); n++ }
        }
        e.apply()
        return n
    }

    fun configJson(c: Context): JSONObject = JSONObject().apply { for (k in FIELDS.keys) put(k, get(c, k)) }

    /** Everything the iPhone's goals/stress screens show. */
    fun state(c: Context): JSONObject = JSONObject()
        .put("config", configJson(c))
        .put("today", Goals.todayJson(c))
        .put("hrv", Hrv.stateJson(c))
        .put("auto", AutoLog.stateJson(c))
        .put("sensors", SensorBridge.isAvailable(c))

    fun handle(s: LinkService, type: String, j: JSONObject) {
        when (type) {
            "hdConfig" -> {
                val n = apply(s, j)
                AutoLog.schedule(s)
                HealthDepthTiles.refresh(s)
                Features.result(s, "hdConfig", n > 0, if (n > 0) "Saved on your watch" else "Nothing to change")
                s.send(WatchMessages.feature("hdState", state(s)))
            }
            "hdGetState" -> s.send(WatchMessages.feature("hdState", state(s)))
            "hdMeasureHrv" -> {
                if (!SensorBridge.isAvailable(s)) {
                    s.send(WatchMessages.feature("hrvResult", Hrv.resultJson(null, "Samsung sensors aren't available on this watch")))
                    return
                }
                if (!HealthDepthActivity.launch(s, measure = true)) {
                    Hrv.measure(s) { r, reason -> if (r == null) LinkService.sendFeature("hrvResult", Hrv.resultJson(null, reason)) }
                }
            }
        }
    }

    /** Called when the iPhone (re)connects. */
    fun onConnected(s: LinkService) {
        s.send(WatchMessages.feature("hdState", state(s)))
    }
}
