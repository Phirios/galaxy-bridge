package nz.galaxybridge.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/**
 * The watch's health hub: goal rings, the stress/HRV readout (with a one-minute measurement),
 * water/caffeine quick-log and period logging. Opened from the home screen, a reminder, or by the
 * iPhone's "Measure stress" (with [EXTRA_MEASURE]).
 */
class HealthDepthActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MEASURE = "measure"

        fun launch(ctx: Context, measure: Boolean = false): Boolean = runCatching {
            ctx.startActivity(Intent(ctx, HealthDepthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_MEASURE, measure))
            true
        }.getOrDefault(false)
    }

    private val dim = Color.White.copy(alpha = 0.6f)
    private var tick by mutableIntStateOf(0)
    private var measuring by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
        if (intent.getBooleanExtra(EXTRA_MEASURE, false)) measure()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_MEASURE, false)) measure()
    }

    private fun measure() {
        if (measuring) return
        if (!SensorBridge.isAvailable(this)) { message = "Samsung sensors aren't available"; return }
        measuring = true
        message = null
        Hrv.measure(this) { r, reason ->
            runOnUiThread {
                measuring = false
                message = if (r == null) reason else null
                tick++
                if (r == null) LinkService.sendFeature("hrvResult", Hrv.resultJson(null, reason))
            }
        }
    }

    private fun refresh() { tick++ }

    @Composable
    private fun Screen() {
        val state = rememberScalingLazyListState()
        @Suppress("UNUSED_VARIABLE") val t = tick // recompose on refresh
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = state) },
            modifier = Modifier.background(Color.Black).background(Brush.radialGradient(
                listOf(Color(0xFF3A1430), Color(0xFF0B1F33), Color.Black), radius = 420f)),
        ) {
            ScalingLazyColumn(state = state, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("HEALTH", fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF2ED1DB), modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
                }
                item { Rings() }
                item { StressCard() }
                item { Label("Quick log") }
                item {
                    val cup = HealthDepth.get(this@HealthDepthActivity, "cupMl")
                    LogChip("Water", "+$cup ml", Color(0xFF4DA3FF)) { Intake.logWater(this@HealthDepthActivity); refresh() }
                }
                item {
                    val mg = HealthDepth.get(this@HealthDepthActivity, "coffeeMg")
                    LogChip("Coffee", "+$mg mg caffeine", Color(0xFFFFB347)) { Intake.logCaffeine(this@HealthDepthActivity); refresh() }
                }
                item {
                    val today = Goals.today(this@HealthDepthActivity)
                    Text("Today ${today.waterMl} ml water · ${today.caffeineMg} mg caffeine",
                        style = MaterialTheme.typography.caption3, color = dim, textAlign = TextAlign.Center)
                }
                item { Label("Period") }
                for ((label, level) in listOf("Light" to 2, "Medium" to 3, "Heavy" to 4, "Spotting / unsure" to 1)) {
                    item {
                        LogChip(label, "Log flow for today", Color(0xFFFF5C9A)) {
                            message = if (Intake.logFlow(this@HealthDepthActivity, level)) "Logged $label" else "Couldn't log"
                        }
                    }
                }
                item {
                    Text("Wellness estimates, not medical advice. Synced to Apple Health on your iPhone.",
                        style = MaterialTheme.typography.caption3, color = dim, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }

    @Composable
    private fun Label(text: String) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = dim, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun LogChip(label: String, sub: String, color: Color, onClick: () -> Unit) {
        Chip(onClick = onClick, label = { Text(label, fontWeight = FontWeight.SemiBold) },
            secondaryLabel = { Text(sub, maxLines = 1) },
            colors = ChipDefaults.gradientBackgroundChipColors(startBackgroundColor = color.copy(alpha = 0.55f),
                endBackgroundColor = Color(0xFF1C2046)),
            modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun Rings() {
        val c = this
        val (s, e, a) = Goals.progress(c)
        val today = Goals.today(c)
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(150.dp)) {
                val colors = listOf(Color(0xFF3DDC84), Color(0xFFFF5C7A), Color(0xFF2ED1DB))
                val values = listOf(s, e, a)
                val w = 11.dp.toPx()
                for (i in 0..2) {
                    val inset = w / 2 + i * (w + 3.dp.toPx())
                    val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
                    val tl = Offset(inset, inset)
                    drawArc(colors[i].copy(alpha = 0.2f), -90f, 360f, false, tl, sz, style = Stroke(w))
                    drawArc(colors[i], -90f, (values[i].coerceIn(0.0, 1.0) * 360).toFloat().coerceAtLeast(1f), false, tl, sz,
                        style = Stroke(w, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%,d".format(today.steps), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${today.kcal.toInt()} kcal", fontSize = 10.sp, color = Color(0xFFFF5C7A))
                Text("${today.activeMin} min", fontSize = 10.sp, color = Color(0xFF2ED1DB))
            }
        }
    }

    @Composable
    private fun StressCard() {
        val r = Hrv.latest(this)
        val spo2 = getSharedPreferences("vitals", MODE_PRIVATE).getInt("spo2", 0).takeIf { it > 0 }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(onClick = { VitalsActivity.open(this@HealthDepthActivity, VitalsActivity.MODE_STRESS) },
                label = { Text("Stress") },
                secondaryLabel = { Text(r?.let { Hrv.label(it.stress) + " · HRV ${it.sdnn.toInt()} ms" } ?: "Measure") },
                colors = ChipDefaults.gradientBackgroundChipColors(Color(0xFFF2B705).copy(alpha = 0.55f), Color(0xFF3A2A05)),
                modifier = Modifier.fillMaxWidth())
            Chip(onClick = { VitalsActivity.open(this@HealthDepthActivity, VitalsActivity.MODE_SPO2) },
                label = { Text("Blood oxygen") },
                secondaryLabel = { Text(spo2?.let { "$it%" } ?: "Measure") },
                colors = ChipDefaults.gradientBackgroundChipColors(Color(0xFF7A2048), Color(0xFF2A0A18)),
                modifier = Modifier.fillMaxWidth())
        }
    }

}
