package nz.galaxybridge.watch

import android.content.Context

/**
 * Estimates sleep from the passive heart-rate and step stream, since Health Services doesn't
 * expose a sleep signal and the Samsung sleep engine is internal. The heuristic: once there has
 * been no stepping for [QUIET_MS] and recent heart rate sits near the wearer's resting low, a
 * sleep session is opened at the moment movement stopped; it closes when stepping resumes. Short
 * or run-away sessions are discarded. It's an estimate — clearly labelled as such in the app — not
 * a clinical sleep stage.
 *
 * State is a few longs in SharedPreferences so it survives the passive service being recycled.
 * Call [onData] from the passive listener with each batch; it returns any finalised sleep samples
 * for the caller to enqueue (so all SampleQueue writes stay in one place).
 */
object SleepTracker {
    private const val QUIET_MS = 45 * 60_000L       // no steps for this long → likely asleep
    private const val MIN_SESSION_MS = 20 * 60_000L // ignore sessions shorter than this
    private const val MAX_SESSION_MS = 16 * 3600_000L
    private const val STEP_MOVE = 10.0              // steps in a batch that count as "awake"
    private const val HR_MARGIN = 8.0               // bpm above resting still counts as "at rest"

    private const val PREFS = "sleep"
    private const val LAST_MOVE = "lastMove"
    private const val ASLEEP_START = "asleepStart"
    private const val RESTING_HR = "restingHr"

    fun onData(c: Context, samples: List<HealthSample>): List<HealthSample> {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = samples.maxOfOrNull { it.endMs } ?: System.currentTimeMillis()
        var lastMove = p.getLong(LAST_MOVE, now)
        var asleepStart = p.getLong(ASLEEP_START, 0L)
        var restingHr = p.getFloat(RESTING_HR, 60f)

        val moved = samples.any { it.kind == SampleKind.STEPS && it.value >= STEP_MOVE }
        val hrs = samples.filter { it.kind == SampleKind.HEART_RATE }.map { it.value }
        hrs.minOrNull()?.let { lo ->                 // track the resting low, drift up slowly
            restingHr = if (lo < restingHr) lo.toFloat() else (restingHr + 0.01f).coerceAtMost(90f)
        }
        val atRest = hrs.isNotEmpty() && hrs.min() <= restingHr + HR_MARGIN

        val out = ArrayList<HealthSample>()
        if (moved) {
            if (asleepStart > 0 && now - asleepStart >= MIN_SESSION_MS) {
                out += HealthSample(SampleKind.SLEEP, asleepStart, now, SleepStage.ASLEEP.value)
            }
            asleepStart = 0L
            lastMove = now
        } else {
            if (asleepStart == 0L && now - lastMove >= QUIET_MS && atRest) {
                asleepStart = lastMove                // sleep began when movement stopped
            } else if (asleepStart > 0 && now - asleepStart >= MAX_SESSION_MS) {
                out += HealthSample(SampleKind.SLEEP, asleepStart, asleepStart + MAX_SESSION_MS, SleepStage.ASLEEP.value)
                asleepStart = 0L
                lastMove = now
            }
        }

        p.edit().putLong(LAST_MOVE, lastMove).putLong(ASLEEP_START, asleepStart)
            .putFloat(RESTING_HR, restingHr).apply()
        return out.filter { it.isPlausible() }
    }
}
