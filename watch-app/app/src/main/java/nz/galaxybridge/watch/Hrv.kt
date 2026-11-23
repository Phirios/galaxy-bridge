package nz.galaxybridge.watch

import android.content.Context
import com.samsung.android.service.health.tracking.data.ServiceDataPoint
import org.json.JSONObject
import java.util.Collections
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Heart-rate variability and a stress estimate from the Samsung HEART_RATE tracker's beat-to-beat
 * (inter-beat interval) data.
 *
 * Where the IBIs come from: the tracker's ServiceDataPoint value list is laid out (HealthPlatform
 * 1.7, HeartRateTracker.fetchValueFromData / ServiceDataPoint.convertHrToDataPoint) as
 *   [0] HEART_RATE  [1] RR interval  [2] HEART_RATE_STATUS  [3] IBI count n
 *   then n pairs: [4 + 2i] IBI ms, [5 + 2i] IBI status (0 = good)
 * The slots exist in the wire format; whether the firmware fills them on this watch needs a live
 * test. If no usable IBIs arrive, [compute] returns null and callers say so instead of guessing.
 *
 * Wellness estimate only — not a medical measurement.
 */
object Hrv {
    /** Fewer good beats than this and SDNN/RMSSD are too noisy to report. */
    const val MIN_BEATS = 30
    const val MEASURE_MS = 60_000L

    data class Result(val sdnn: Double, val rmssd: Double, val stress: Int, val beats: Int,
                      val meanHr: Double, val startMs: Long, val endMs: Long)

    /** Good IBIs (ms) from one HEART_RATE data point's integer values. Pure, for tests. */
    fun ibisFrom(ints: List<Int>): List<Int> {
        if (ints.size < 4) return emptyList()
        val n = ints[3]
        if (n <= 0 || n > 64) return emptyList()
        val paired = ints.size >= 4 + 2 * n
        val out = ArrayList<Int>(n)
        for (i in 0 until n) {
            val ibi = ints.getOrNull(if (paired) 4 + 2 * i else 4 + i) ?: break
            val status = if (paired) ints[5 + 2 * i] else 0
            if (status == 0 && ibi in 300..2000) out += ibi
        }
        return out
    }

    fun ibis(point: ServiceDataPoint): List<Int> =
        runCatching { ibisFrom(point.getServiceValue().map { it.integerValue }) }.getOrDefault(emptyList())

    /**
     * SDNN and RMSSD over normal-to-normal intervals. Beats that jump more than 20% from the
     * previous kept beat are treated as artefacts (missed/extra beats) and dropped.
     */
    fun compute(ibisMs: List<Int>, startMs: Long = 0, endMs: Long = 0): Result? {
        val nn = ArrayList<Double>(ibisMs.size)
        for (v in ibisMs) {
            val x = v.toDouble()
            val prev = nn.lastOrNull()
            if (prev == null || kotlin.math.abs(x - prev) <= 0.2 * prev) nn += x
        }
        if (nn.size < MIN_BEATS) return null
        val mean = nn.average()
        val sdnn = sqrt(nn.sumOf { (it - mean) * (it - mean) } / nn.size)
        var sq = 0.0
        for (i in 1 until nn.size) { val d = nn[i] - nn[i - 1]; sq += d * d }
        val rmssd = sqrt(sq / (nn.size - 1))
        if (!sdnn.isFinite() || !rmssd.isFinite() || sdnn !in 1.0..300.0) return null
        return Result(sdnn, rmssd, stressScore(rmssd), nn.size, 60_000.0 / mean, startMs, endMs)
    }

    /**
     * 0–100 stress estimate from RMSSD on a log scale: RMSSD ≥ 100 ms → 0, ≤ 12 ms → 100.
     * Lower beat-to-beat variability usually goes with higher sympathetic load; it's a rough
     * wellness indicator, not a diagnosis.
     */
    fun stressScore(rmssd: Double): Int {
        if (!rmssd.isFinite() || rmssd <= 0) return 100
        val t = (ln(100.0) - ln(rmssd)) / (ln(100.0) - ln(12.0))
        return (t * 100).roundToInt().coerceIn(0, 100)
    }

    fun label(stress: Int) = when {
        stress < 34 -> "Low"
        stress < 67 -> "Moderate"
        else -> "High"
    }

    // --- Latest result, kept on the watch for the tile/screen and re-sent on connect ---

    private const val PREFS = "hrv"

    fun save(c: Context, r: Result) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("sdnn", r.sdnn.toFloat()).putFloat("rmssd", r.rmssd.toFloat())
            .putInt("stress", r.stress).putInt("beats", r.beats).putFloat("hr", r.meanHr.toFloat())
            .putLong("at", r.endMs).apply()
    }

    /** Counts measurement attempts and how many delivered any IBI, so the phone can show it honestly. */
    fun noteAttempt(c: Context, sawIbi: Boolean) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt("attempts", p.getInt("attempts", 0) + 1)
            .putInt("withIbi", p.getInt("withIbi", 0) + if (sawIbi) 1 else 0).apply()
    }

    fun latest(c: Context): Result? {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (at == 0L) return null
        return Result(p.getFloat("sdnn", 0f).toDouble(), p.getFloat("rmssd", 0f).toDouble(), p.getInt("stress", 0),
            p.getInt("beats", 0), p.getFloat("hr", 0f).toDouble(), at, at)
    }

    fun stateJson(c: Context): JSONObject {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject().put("attempts", p.getInt("attempts", 0)).put("withIbi", p.getInt("withIbi", 0))
        latest(c)?.let { o.put("sdnn", it.sdnn).put("rmssd", it.rmssd).put("stress", it.stress).put("at", it.endMs) }
        return o
    }

    fun resultJson(r: Result?, reason: String = ""): JSONObject =
        if (r == null) JSONObject().put("ok", false).put("reason", reason.take(80))
        else JSONObject().put("ok", true).put("sdnn", r.sdnn).put("rmssd", r.rmssd).put("stress", r.stress)
            .put("beats", r.beats).put("hr", r.meanHr).put("start", r.startMs).put("at", r.endMs)

    /**
     * One on-demand HRV measurement: streams the HEART_RATE tracker for [MEASURE_MS], computes
     * HRV, queues an HRV sample (if heart-rate sync is on) and reports the result. [done] gets
     * null plus a reason when there was no usable beat-to-beat data.
     */
    fun measure(c: Context, done: (Result?, String) -> Unit) {
        val ibis = Collections.synchronizedList(ArrayList<Int>())
        var first = 0L
        var last = 0L
        var points = 0
        SensorBridge.stream(c, setOf(SampleKind.HEART_RATE), MEASURE_MS,
            onPoint = { _, pt ->
                points++
                val got = ibis(pt)
                if (got.isNotEmpty()) {
                    if (first == 0L) first = pt.timeStamp
                    last = pt.timeStamp
                    ibis.addAll(got)
                }
            },
            done = { ok ->
                val all = synchronized(ibis) { ArrayList(ibis) }
                noteAttempt(c, all.isNotEmpty())
                val r = compute(all, first, last)
                val reason = when {
                    !ok && points == 0 -> "Samsung sensors unavailable (developer mode?)"
                    all.isEmpty() -> "The sensor sent no beat-to-beat data"
                    r == null -> "Not enough steady beats — keep still and try again"
                    else -> ""
                }
                if (r != null) record(c, r)
                done(r, reason)
            })
    }

    /** Stores, queues and announces a result. */
    fun record(c: Context, r: Result) {
        save(c, r)
        if (WatchSettings.syncs(c, SampleKind.HRV)) {
            val start = if (r.startMs > 0) r.startMs else r.endMs
            SampleQueue(c.filesDir).add(listOf(HealthSample(SampleKind.HRV, start, maxOf(start, r.endMs), r.sdnn)))
            LinkService.samplesQueued()
        }
        LinkService.sendFeature("hrvResult", resultJson(r))
        HealthDepthTiles.refresh(c)
    }
}
