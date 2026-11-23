package nz.galaxybridge.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Fall heuristic on the accelerometer magnitude (in g): an optional short free-fall, then a hard
 * impact, then the wearer staying still. Pure logic so it can be unit-tested; [FallSensor] feeds it.
 *
 * The thresholds are first guesses from the published wrist-worn fall literature and have NOT been
 * tuned on this watch: NEEDS LIVE TEST. This is a convenience feature, not a medical device; it
 * will miss some falls and flag some non-falls, which is why every alert has a countdown to cancel.
 */
class FallHeuristic(
    private val freeFallG: Double = 0.5,
    private val minFreeFallMs: Long = 80,
    private val impactG: Double = 2.7,
    /** An impact this hard counts even without a free-fall phase (wrists often don't see one). */
    private val hardImpactG: Double = 4.0,
    private val impactWindowMs: Long = 1_000,
    private val settleMs: Long = 1_500,
    private val stillWindowMs: Long = 4_000,
    private val stillStdG: Double = 0.12,
    private val stillMeanTolG: Double = 0.3,
) {
    private enum class State { IDLE, FREE_FALL, AWAIT_IMPACT, AWAIT_STILL }

    private var state = State.IDLE
    private var mark = 0L
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0

    fun reset() {
        state = State.IDLE
        n = 0; sum = 0.0; sumSq = 0.0
    }

    /** Feeds one sample; returns true once when a fall is confirmed. */
    fun feed(tMs: Long, g: Double): Boolean {
        when (state) {
            State.IDLE -> when {
                g >= hardImpactG -> impact(tMs)
                g < freeFallG -> { state = State.FREE_FALL; mark = tMs }
            }
            State.FREE_FALL -> if (g >= freeFallG) {
                if (tMs - mark < minFreeFallMs) { if (g >= hardImpactG) impact(tMs) else reset() }
                else if (g >= impactG) impact(tMs)
                else { state = State.AWAIT_IMPACT; mark = tMs }
            }
            State.AWAIT_IMPACT -> when {
                g >= impactG -> impact(tMs)
                tMs - mark > impactWindowMs -> reset()
            }
            State.AWAIT_STILL -> {
                val since = tMs - mark
                // A second hit while tumbling restarts the wait for stillness.
                if (since < settleMs) { if (g >= impactG) impact(tMs); return false }
                if (since <= settleMs + stillWindowMs) {
                    n++; sum += g; sumSq += g * g
                    return false
                }
                val mean = if (n > 0) sum / n else 0.0
                val std = if (n > 1) sqrt(((sumSq - n * mean * mean) / (n - 1)).coerceAtLeast(0.0)) else Double.MAX_VALUE
                val fell = n >= 10 && std < stillStdG && kotlin.math.abs(mean - 1.0) < stillMeanTolG
                reset()
                return fell
            }
        }
        return false
    }

    private fun impact(tMs: Long) {
        state = State.AWAIT_STILL
        mark = tMs
        n = 0; sum = 0.0; sumSq = 0.0
    }
}

/**
 * Runs [FallHeuristic] on the accelerometer while fall detection or the no-movement check-in is on,
 * and tracks the last time the wearer moved. Uses the wake-up accelerometer when the watch has one
 * so detection keeps working with the screen off (batched to save power). Battery impact and
 * screen-off delivery NEED LIVE TEST.
 */
class FallSensor(
    context: Context,
    private val detectFalls: () -> Boolean,
    private val onFall: () -> Unit,
) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val heuristic = FallHeuristic()
    private var registered = false

    /** Elapsed-realtime ms of the last noticeable movement. */
    @Volatile var lastMovementMs = android.os.SystemClock.elapsedRealtime()
        private set

    fun available() = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun start(): Boolean {
        if (registered) return true
        val s = sm ?: return false
        val sensor = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        registered = s.registerListener(this, sensor, 20_000, 2_000_000) // 50 Hz, up to 2 s batching
        lastMovementMs = android.os.SystemClock.elapsedRealtime()
        Log.i("FallSensor", "accelerometer wakeUp=${sensor.isWakeUpSensor} registered=$registered")
        return registered
    }

    fun stop() {
        if (registered) sm?.unregisterListener(this)
        registered = false
        heuristic.reset()
    }

    fun running() = registered

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val g = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        val tMs = e.timestamp / 1_000_000
        if (kotlin.math.abs(g - 1.0) > 0.15) lastMovementMs = tMs
        if (detectFalls() && heuristic.feed(tMs, g)) onFall()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
