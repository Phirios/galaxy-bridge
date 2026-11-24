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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * The watch's health hub: goal rings, the stress/HRV readout (with a one-minute measurement),
 * water/caffeine quick-log and period logging. Opened from the home screen, a reminder, or by the
 * iPhone's "Measure stress" (with [EXTRA_MEASURE]).
 */
class HealthDepthActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MEASURE = "measure"

        fun launch(ctx: Context, measure: Boolean = false): Boolean = runCatching {
            ctx.startActivity(
                Intent(ctx, HealthDepthActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_MEASURE, measure),
            )
            true
        }.getOrDefault(false)
    }

    private var tick by mutableIntStateOf(0)
    private var measuring by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BridgeTheme { AppScaffold { Screen() } } }
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
        val listState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        @Suppress("UNUSED_VARIABLE") val t = tick // recompose on refresh
        val r = Hrv.latest(this)
        val spo2 = getSharedPreferences("vitals", MODE_PRIVATE).getInt("spo2", 0).takeIf { it > 0 }
        val cup = HealthDepth.get(this, "cupMl")
        val mg = HealthDepth.get(this, "coffeeMg")
        val today = Goals.today(this)

        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    ListHeader(
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec),
                    ) { Text("Health") }
                }
                item { Rings() }
                if (measuring) {
                    item {
                        Card(
                            onClick = {},
                            enabled = false,
                            transformation = SurfaceTransformation(spec),
                            modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Measuring stress…", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                message?.let { msg ->
                    item {
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item {
                    TitleCard(
                        onClick = { VitalsActivity.open(this@HealthDepthActivity, VitalsActivity.MODE_STRESS) },
                        title = { Text("Stress") },
                        subtitle = {
                            Text(r?.let { "${Hrv.label(it.stress)} · HRV ${it.sdnn.toInt()} ms" } ?: "Tap to measure")
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF3A2D0B),
                            titleColor = Color.White,
                            subtitleColor = Color(0xFFF7E2A6),
                        ),
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                    )
                }
                item {
                    TitleCard(
                        onClick = { VitalsActivity.open(this@HealthDepthActivity, VitalsActivity.MODE_SPO2) },
                        title = { Text("Blood oxygen") },
                        subtitle = { Text(spo2?.let { "$it%" } ?: "Tap to measure") },
                        colors = CardDefaults.cardColors(
                            containerColor = BridgeTokens.Spo2Track,
                            titleColor = Color.White,
                            subtitleColor = Color(0xFFF6C6D8),
                        ),
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                    )
                }
                item {
                    ListSubHeader(
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec),
                    ) { Text("Quick log") }
                }
                item {
                    LogButton(
                        label = "Water",
                        sub = "+$cup ml",
                        accent = BridgeTokens.Water,
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                    ) { Intake.logWater(this@HealthDepthActivity); refresh() }
                }
                item {
                    LogButton(
                        label = "Coffee",
                        sub = "+$mg mg caffeine",
                        accent = BridgeTokens.Coffee,
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                    ) { Intake.logCaffeine(this@HealthDepthActivity); refresh() }
                }
                item {
                    Text(
                        "Today ${today.waterMl} ml water · ${today.caffeineMg} mg caffeine",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    ListSubHeader(
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec),
                    ) { Text("Period") }
                }
                for ((label, level) in listOf("Light" to 2, "Medium" to 3, "Heavy" to 4, "Spotting / unsure" to 1)) {
                    item {
                        LogButton(
                            label = label,
                            sub = "Log flow for today",
                            accent = BridgeTokens.Period,
                            transformation = SurfaceTransformation(spec),
                            modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                        ) {
                            message = if (Intake.logFlow(this@HealthDepthActivity, level)) "Logged $label" else "Couldn't log"
                        }
                    }
                }
                item {
                    Text(
                        "Wellness estimates, not medical advice. Synced to Apple Health on your iPhone.",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun LogButton(
        label: String,
        sub: String,
        accent: Color,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        FilledTonalButton(
            onClick = onClick,
            label = { Text(label) },
            secondaryLabel = { Text(sub, maxLines = 1) },
            icon = { Box(Modifier.size(12.dp).clip(CircleShape).background(accent)) },
            transformation = transformation,
            modifier = modifier,
        )
    }

    @Composable
    private fun Rings() {
        val (s, e, a) = Goals.progress(this)
        val today = Goals.today(this)
        Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val colors = listOf(BridgeTokens.Good, BridgeTokens.Energy, BridgeTokens.Active)
                val values = listOf(s, e, a)
                val w = 11.dp.toPx()
                for (i in 0..2) {
                    val inset = w / 2 + i * (w + 3.dp.toPx())
                    val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
                    val tl = Offset(inset, inset)
                    drawArc(colors[i].copy(alpha = 0.2f), -90f, 360f, false, tl, sz, style = Stroke(w))
                    drawArc(
                        colors[i],
                        -90f,
                        (values[i].coerceIn(0.0, 1.0) * 360).toFloat().coerceAtLeast(1f),
                        false,
                        tl,
                        sz,
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%,d".format(today.steps),
                    style = MaterialTheme.typography.numeralSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text("${today.kcal.toInt()} kcal", style = MaterialTheme.typography.bodyExtraSmall, color = BridgeTokens.Energy)
                Text("${today.activeMin} min", style = MaterialTheme.typography.bodyExtraSmall, color = BridgeTokens.Active)
            }
        }
    }
}
