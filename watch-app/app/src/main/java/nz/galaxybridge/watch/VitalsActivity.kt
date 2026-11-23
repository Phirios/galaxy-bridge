package nz.galaxybridge.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen stress and blood-oxygen measurement in the style of the watch's own health screens:
 * an edge gauge around the round display with a dot at the value, the latest reading large in the
 * middle and a Measure pill at the bottom. Readings come from Samsung's sensors through
 * [SensorBridge] and go to the iPhone / Apple Health like every other sample.
 */
class VitalsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_START = "start"
        const val MODE_STRESS = "stress"
        const val MODE_SPO2 = "spo2"
        private const val SPO2_MS = 35_000L
        private const val PREFS = "vitals"

        fun open(ctx: Context, mode: String, start: Boolean = false): Boolean = runCatching {
            ctx.startActivity(Intent(ctx, VitalsActivity::class.java).putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_START, start)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            true
        }.getOrDefault(false)
    }

    private enum class Phase { IDLE, MEASURING, FAILED }

    private var mode by mutableStateOf(MODE_STRESS)
    private var phase by mutableStateOf(Phase.IDLE)
    private var progress by mutableFloatStateOf(0f)
    private var startedAt = 0L
    private var failure by mutableStateOf("")
    private var refresh by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_STRESS
        setContent { MaterialTheme { Screen() } }
        if (intent.getBooleanExtra(EXTRA_START, false)) measure()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mode = intent.getStringExtra(EXTRA_MODE) ?: mode
        if (intent.getBooleanExtra(EXTRA_START, false)) measure()
    }

    private fun measureMs() = if (mode == MODE_SPO2) SPO2_MS else Hrv.MEASURE_MS

    private fun measure() {
        if (phase == Phase.MEASURING) return
        if (!SensorBridge.isAvailable(this)) { failure = "Samsung sensors aren't available"; phase = Phase.FAILED; return }
        phase = Phase.MEASURING
        progress = 0f
        startedAt = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (mode == MODE_SPO2) {
            SensorBridge.measure(this, setOf(SampleKind.SPO2, SampleKind.HEART_RATE), SPO2_MS, onSamples = { s ->
                val spo2 = s.lastOrNull { it.kind == SampleKind.SPO2 && it.isPlausible() }?.value
                val hr = s.filter { it.kind == SampleKind.HEART_RATE && it.isPlausible() }.map { it.value }.average()
                if (spo2 != null) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("spo2", spo2.toInt()).putInt("spo2Hr", if (hr.isNaN()) 0 else hr.toInt())
                    .putLong("spo2At", System.currentTimeMillis()).apply()
                finish(spo2 != null, "Couldn't measure. Rest your arm on a table, keep still and try again.")
            }) { }
        } else {
            Hrv.measure(this) { r, reason ->
                if (r == null) LinkService.sendFeature("hrvResult", Hrv.resultJson(null, reason))
                finish(r != null, reason.ifEmpty { "Couldn't measure. Keep still and try again." })
            }
        }
    }

    private fun finish(ok: Boolean, reason: String) = runOnUiThread {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        failure = reason
        phase = if (ok) Phase.IDLE else Phase.FAILED
        refresh++
    }

    @Composable
    private fun Screen() {
        LaunchedEffect(phase) {
            while (phase == Phase.MEASURING) {
                progress = ((SystemClock.elapsedRealtime() - startedAt).toFloat() / measureMs()).coerceIn(0f, 0.99f)
                delay(200)
            }
        }
        @Suppress("UNUSED_VARIABLE") val r = refresh
        if (phase == Phase.MEASURING) Measuring() else if (mode == MODE_SPO2) Spo2() else Stress()
    }

    // --- Stress ---

    private val stressZones = listOf(Color(0xFF2E8B8B), Color(0xFF3FA34D), Color(0xFFF2B705), Color(0xFFE0692B))

    private fun stressColor(stress: Int) = stressZones[(stress / 25).coerceIn(0, 3)]

    @Composable
    private fun Stress() {
        val last = Hrv.latest(this)
        val tint = last?.let { stressColor(it.stress) } ?: Color(0xFF3A3F5C)
        Face(tint) {
            drawSegmentedGauge(stressZones, last?.stress?.div(100f))
        }
        Overlay(
            top = last?.let { dateLabel(it.endMs) } ?: "Stress",
            symbol = "⚡",
            value = last?.let { Hrv.label(it.stress) } ?: "No reading",
            unit = null,
            detail = last?.let { "HRV ${it.sdnn.toInt()} ms" },
            measureLabel = "Measure",
        )
    }

    // --- Blood oxygen ---

    @Composable
    private fun Spo2() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val spo2 = p.getInt("spo2", 0).takeIf { it > 0 }
        val hr = p.getInt("spo2Hr", 0).takeIf { it > 0 }
        Face(Color(0xFF7A2048)) {
            drawArcGauge(Color(0xFF5E1F3C), spo2?.let { ((it - 70) / 30f).coerceIn(0f, 1f) })
        }
        Overlay(
            top = if (spo2 != null) dateLabel(p.getLong("spo2At", 0)) else "Blood oxygen",
            symbol = "💧",
            value = spo2?.toString() ?: "--",
            unit = "%",
            detail = hr?.let { "$it bpm" },
            measureLabel = "Measure",
        )
    }

    // --- Measuring ---

    @Composable
    private fun Measuring() {
        val t = rememberInfiniteTransition(label = "orbs")
        val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "a")
        val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3400), RepeatMode.Reverse), label = "b")
        val warm = if (mode == MODE_SPO2) Color(0xFFE8737A) else Color(0xFFF2B705)
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Color(0xFF3B2A55), Color.Black), center, r), r)
                val c1 = Offset(center.x - r * (0.25f - 0.15f * a), center.y + r * (0.05f + 0.1f * b))
                drawCircle(Brush.verticalGradient(listOf(Color(0xFF8FA8FF), warm), c1.y - r * 0.38f, c1.y + r * 0.38f),
                    r * (0.34f + 0.04f * b), c1)
                val c2 = Offset(center.x + r * (0.2f * b), center.y - r * (0.35f - 0.1f * a))
                drawCircle(Color(0xFF6C5BA8).copy(alpha = 0.7f), r * 0.1f, c2)
                drawArc(Color.White.copy(alpha = 0.12f), -90f, 360f, false, Offset(14f, 14f),
                    Size(size.width - 28f, size.height - 28f), style = Stroke(10f))
                drawArc(Color.White, -90f, 360f * progress, false, Offset(14f, 14f),
                    Size(size.width - 28f, size.height - 28f), style = Stroke(10f, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${(progress * 100).toInt()}", fontSize = 54.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("%", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
                }
                Text(if (mode == MODE_SPO2) "Rest your arm and keep still" else "Relax and keep still",
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
            }
        }
    }

    // --- Shared pieces ---

    @Composable
    private fun Face(tint: Color, gauge: DrawScope.() -> Unit) {
        Canvas(Modifier.fillMaxSize().background(Color.Black)) {
            val r = size.minDimension / 2
            drawCircle(Brush.verticalGradient(listOf(tint.copy(alpha = 0.10f), tint.copy(alpha = 0.45f))), r)
            gauge()
        }
    }

    @Composable
    private fun Overlay(top: String, symbol: String, value: String, unit: String?, detail: String?, measureLabel: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 30.dp)) {
                Text(top, fontSize = 14.sp, color = Color.White)
                Text(symbol, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, fontSize = if (value.length > 4) 34.sp else 56.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, maxLines = 1)
                    if (unit != null) Text(unit, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
                }
                if (phase == Phase.FAILED) Text(failure, fontSize = 11.sp, color = Color(0xFFFFC542),
                    textAlign = TextAlign.Center, maxLines = 3)
                else if (detail != null) Text(detail, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                Box(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(32.dp))
                    .background(Color.White.copy(alpha = 0.14f)).clickable { measure() }
                    .padding(horizontal = 30.dp, vertical = 14.dp)) {
                    Text(measureLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }

    private fun dateLabel(ms: Long): String {
        if (ms <= 0) return ""
        val today = System.currentTimeMillis() - ms < 20 * 3600_000L
        return if (today) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
        else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)).substringBeforeLast(',')
    }
}

/** Edge arc that leaves the bottom open for the Measure button, with a white dot at [fraction]. */
private const val GAUGE_START = 135f
private const val GAUGE_SWEEP = 270f

private fun DrawScope.gaugeRect(): Pair<Offset, Size> {
    val inset = 20f
    return Offset(inset, inset) to Size(size.width - 2 * inset, size.height - 2 * inset)
}

private fun DrawScope.drawDot(fraction: Float) {
    val (tl, sz) = gaugeRect()
    val angle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * fraction).toDouble())
    val rx = sz.width / 2; val c = Offset(tl.x + rx, tl.y + sz.height / 2)
    val p = Offset(c.x + (rx * cos(angle)).toFloat(), c.y + (rx * sin(angle)).toFloat())
    drawCircle(Color.Black.copy(alpha = 0.35f), 17f, p)
    drawCircle(Color.White, 14f, p)
}

private fun DrawScope.drawArcGauge(color: Color, fraction: Float?) {
    val (tl, sz) = gaugeRect()
    drawArc(color, GAUGE_START, GAUGE_SWEEP, false, tl, sz, style = Stroke(34f, cap = StrokeCap.Round))
    fraction?.let { drawDot(it) }
}

private fun DrawScope.drawSegmentedGauge(zones: List<Color>, fraction: Float?) {
    val (tl, sz) = gaugeRect()
    val gap = 4f
    val seg = GAUGE_SWEEP / zones.size
    val active = fraction?.let { (it * zones.size).toInt().coerceIn(0, zones.size - 1) }
    zones.forEachIndexed { i, c ->
        drawArc(if (i == active) c else c.copy(alpha = 0.40f), GAUGE_START + i * seg + gap / 2, seg - gap,
            false, tl, sz, style = Stroke(34f, cap = StrokeCap.Butt))
    }
    fraction?.let { drawDot(it) }
}
