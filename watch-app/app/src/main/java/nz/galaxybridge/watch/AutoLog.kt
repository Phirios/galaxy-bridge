package nz.galaxybridge.watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.Collections

/**
 * Automatic background readings without tapping, plus the tick that drives reminders.
 *
 * An inexact, doze-friendly alarm fires about every [TICK_MS] while anything here is switched on
 * (background readings, overnight readings or a reminder); with all of it off, no alarm is set.
 * On each tick:
 *  - reminders are checked (Goals.checkReminders);
 *  - if the sleep estimate says the wearer is asleep and overnight readings are on, and the
 *    overnight interval has passed: one [MEASURE_MS] session of SpO2 + skin temperature +
 *    heart rate (logged as sleeping heart rate) + HRV;
 *  - otherwise, if background readings are on and their interval has passed: the same session,
 *    with heart rate logged as normal heart rate.
 * Battery-aware: skipped below the configured battery level, while charging (the watch is off the
 * wrist), in power-saving mode and during a workout. Samples respect the per-type sync toggles,
 * so SpO2/skin temperature still need their Apple Health toggles on.
 *
 * Battery impact and how reliably the alarm fires in deep doze need a live test.
 */
object AutoLog {
    private const val TAG = "AutoLog"
    const val ACTION_TICK = "nz.galaxybridge.watch.AUTOLOG_TICK"
    const val TICK_MS = 15 * 60_000L
    const val MEASURE_MS = 60_000L
    private const val PREFS = "autoLog"

    @Volatile private var busy = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wanted(c: Context) =
        HealthDepth.on(c, "autoLog") || HealthDepth.on(c, "overnight") || Goals.anyReminder(c)

    private fun pending(c: Context, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(c, 7, Intent(c, HealthDepthReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)

    /** Sets (or clears) the next tick to match the current settings. Safe to call often. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        if (!wanted(c)) { pending(c, false)?.let { am.cancel(it) }; return }
        val pi = pending(c, true) ?: return
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MS, pi)
        }.onFailure { Log.w(TAG, "schedule", it) }
    }

    fun onTick(c: Context) {
        runCatching { Goals.checkReminders(c) }.onFailure { Log.w(TAG, "reminders", it) }
        runCatching { maybeMeasure(c) }.onFailure { Log.w(TAG, "measure", it) }
        schedule(c)
    }

    enum class Mode { DAY, OVERNIGHT }

    /** Why a reading was skipped last time, for the iPhone screen ("" when it ran). */
    private fun note(c: Context, skipped: String) = prefs(c).edit().putString("skipped", skipped).apply()

    private fun maybeMeasure(c: Context) {
        val now = System.currentTimeMillis()
        val asleep = SleepTracker.isAsleep(c)
        val p = prefs(c)
        val mode = when {
            asleep && HealthDepth.on(c, "overnight") &&
                now - p.getLong("lastOvernight", 0L) >= HealthDepth.get(c, "overnightMin") * 60_000L -> Mode.OVERNIGHT
            !asleep && HealthDepth.on(c, "autoLog") &&
                now - p.getLong("lastDay", 0L) >= HealthDepth.get(c, "autoLogMin") * 60_000L -> Mode.DAY
            else -> return
        }
        val bm = c.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val pm = c.getSystemService(PowerManager::class.java)
        when {
            busy -> return
            pct < HealthDepth.get(c, "autoLogMinBattery") -> return note(c, "battery low ($pct%)")
            bm?.isCharging == true -> return note(c, "charging")
            pm?.isPowerSaveMode == true -> return note(c, "power saving on")
            Workouts.live.value != null -> return note(c, "workout running")
            !SensorBridge.isAvailable(c) -> return note(c, "Samsung sensors unavailable")
        }
        measure(c, mode)
    }

    /** One combined sensor session; queues what it got. */
    fun measure(c: Context, mode: Mode, done: ((Int) -> Unit)? = null) {
        if (busy) return
        busy = true
        val wake = c.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "galaxybridge:autolog")
        runCatching { wake?.acquire(MEASURE_MS + 15_000L) }
        val hr = Collections.synchronizedList(ArrayList<Double>())
        val ibis = Collections.synchronizedList(ArrayList<Int>())
        var spo2: Pair<Long, Double>? = null
        var skin: Pair<Long, Double>? = null
        var first = 0L
        var last = 0L
        SensorBridge.stream(c, setOf(SampleKind.SPO2, SampleKind.SKIN_TEMP, SampleKind.HEART_RATE), MEASURE_MS,
            onPoint = { kind, pt ->
                val v = pt.getServiceValue()
                when (kind) {
                    // Same value slots SensorBridge.measure reads (SpO2Set[1], SkinTempSet[0], HeartRateSet[0]).
                    SampleKind.SPO2 -> v.getOrNull(1)?.integerValue?.toDouble()
                        ?.takeIf { it in SampleKind.SPO2.range }?.let { spo2 = pt.timeStamp to it }
                    SampleKind.SKIN_TEMP -> v.getOrNull(0)?.floatValue?.toDouble()
                        ?.takeIf { it in SampleKind.SKIN_TEMP.range }?.let { skin = pt.timeStamp to it }
                    SampleKind.HEART_RATE -> {
                        v.getOrNull(0)?.integerValue?.toDouble()?.takeIf { it in 30.0..220.0 }?.let { hr.add(it) }
                        val got = Hrv.ibis(pt)
                        if (got.isNotEmpty()) { if (first == 0L) first = pt.timeStamp; last = pt.timeStamp; ibis.addAll(got) }
                    }
                    else -> {}
                }
            },
            done = { _ ->
                val now = System.currentTimeMillis()
                val out = ArrayList<HealthSample>()
                val hrs = synchronized(hr) { hr.sorted() }
                if (hrs.isNotEmpty()) {
                    val median = hrs[hrs.size / 2]
                    out += HealthSample(if (mode == Mode.OVERNIGHT) SampleKind.SLEEPING_HEART_RATE else SampleKind.HEART_RATE,
                        now, now, median)
                }
                spo2?.let { (t, v) -> out += HealthSample(SampleKind.SPO2, t, t, v) }
                skin?.let { (t, v) -> out += HealthSample(SampleKind.SKIN_TEMP, t, t, v) }
                val beats = synchronized(ibis) { ArrayList(ibis) }
                Hrv.noteAttempt(c, beats.isNotEmpty())
                val r = Hrv.compute(beats, first, last)
                val keep = out.filter { it.isPlausible() && WatchSettings.syncs(c, it.kind) }
                if (keep.isNotEmpty()) { SampleQueue(c.filesDir).add(keep); LinkService.samplesQueued() }
                if (r != null) Hrv.record(c, r)
                prefs(c).edit().putLong(if (mode == Mode.OVERNIGHT) "lastOvernight" else "lastDay", now)
                    .putLong("lastRun", now).putInt("lastCount", keep.size + if (r != null) 1 else 0)
                    .putString("skipped", "").apply()
                busy = false
                runCatching { if (wake?.isHeld == true) wake.release() }
                done?.invoke(keep.size)
            })
    }

    fun stateJson(c: Context): JSONObject {
        val p = prefs(c)
        return JSONObject().put("lastRun", p.getLong("lastRun", 0L)).put("lastCount", p.getInt("lastCount", 0))
            .put("skipped", p.getString("skipped", "") ?: "").put("asleep", SleepTracker.isAsleep(c))
    }
}
