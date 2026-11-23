package nz.galaxybridge.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Steps straight from the hardware step counter (Sensor.TYPE_STEP_COUNTER) — the same sensor the
 * watch's own step widget uses. Health Services' passive step stream is a separate, more
 * conservative counter that batches and under-reports short walks (e.g. 25 vs the watch's 57), so
 * steps are sourced here instead (DataType.STEPS is left out of Health.TYPES to avoid double count).
 *
 * The sensor is cumulative since boot, so we persist the last reading and emit the delta as a STEPS
 * sample. A reading lower than the last means the watch rebooted: we rebaseline and emit nothing.
 */
class StepCounter(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start() {
        val s = sensor ?: return
        // Low latency so a walk shows up promptly; the sensor still coalesces in deep doze.
        sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 0)
    }

    fun stop() = runCatching { sm?.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
        val cur = event.values.firstOrNull()?.toLong() ?: return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_CUM, -1L)
        val lastTime = prefs.getLong(KEY_TIME, now)
        if (last < 0 || cur < last) {
            // First reading, or the counter reset on reboot: set a baseline, emit nothing.
            prefs.edit().putLong(KEY_CUM, cur).putLong(KEY_TIME, now).apply()
            return
        }
        val delta = cur - last
        prefs.edit().putLong(KEY_CUM, cur).putLong(KEY_TIME, now).apply()
        if (delta <= 0) return
        // Goal rings, reminders and the sleep estimate's movement signal (Goals.kt), whatever the sync setting.
        runCatching { Goals.onSteps(ctx, HealthSample(SampleKind.STEPS, lastTime, now, delta.toDouble())) }
        if (!WatchSettings.syncs(ctx, SampleKind.STEPS)) return
        SampleQueue(ctx.filesDir).add(listOf(HealthSample(SampleKind.STEPS, lastTime, now, delta.toDouble())))
        LinkService.samplesQueued()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val PREFS = "steps"
        const val KEY_CUM = "lastCum"
        const val KEY_TIME = "lastTime"
    }
}
