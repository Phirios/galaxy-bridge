package nz.galaxybridge.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * Our own on-watch measurement panel. When the phone asks for an on-demand sensor read
 * (measureSensors / measureEcg / measureBia), the watch launches this activity, which runs the
 * SensorBridge capture itself, shows "Measuring…" with a progress ring and then the resulting
 * value(s), and sends the same result message back to the phone that the headless path sends.
 *
 * It uses our own UI only — it never opens Samsung Health's screens (that app may not be installed
 * on an iPhone-paired watch). Developer mode is assumed on; SensorBridge handles its absence by
 * degrading to "no reading".
 */
class MeasureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_SENSORS = "sensors"
        const val KIND_SENSORS = "sensors"
        const val KIND_ECG = "ecg"
        const val KIND_BIA = "bia"

        /** Launches the panel. Returns false if the activity couldn't be started. */
        fun launch(ctx: Context, kind: String, sensors: List<String> = emptyList()): Boolean = runCatching {
            ctx.startActivity(
                Intent(ctx, MeasureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_KIND, kind)
                    .putExtra(EXTRA_SENSORS, sensors.toTypedArray()),
            )
            true
        }.getOrDefault(false)
    }

    private val title = mutableStateOf("Measuring…")
    private val busy = mutableStateOf(true)
    private val value = mutableStateOf<String?>(null)
    private val hint = mutableStateOf("Keep the watch snug and still")
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val kind = intent.getStringExtra(EXTRA_KIND)
        title.value = when (kind) {
            KIND_SENSORS -> "Measuring SpO₂ & skin temp…"
            KIND_ECG -> "Recording ECG…"
            KIND_BIA -> "Measuring body composition…"
            else -> "Measuring…"
        }

        setContent {
            BridgeTheme {
                AppScaffold {
                    ScreenScaffold { contentPadding ->
                        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val done = value.value
                                if (busy.value) CircularProgressIndicator(Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    title.value,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center,
                                )
                                done?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                if (busy.value) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        hint.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        when (kind) {
            KIND_SENSORS -> runSensors(intent.getStringArrayExtra(EXTRA_SENSORS)?.toList().orEmpty())
            KIND_ECG -> runEcg()
            KIND_BIA -> runBia()
            else -> finish()
        }
    }

    private val service get() = LinkService.current()

    private fun runSensors(wires: List<String>) {
        val kinds = wires.mapNotNull { w -> SampleKind.values().firstOrNull { it.wire == w } }.toSet()
            .ifEmpty { setOf(SampleKind.SPO2) }
        SensorBridge.measure(this, kinds, onSamples = { samples ->
            val parts = ArrayList<String>()
            samples.lastOrNull { it.kind == SampleKind.HEART_RATE }?.let { parts.add("HR ${it.value.toInt()} bpm") }
            samples.lastOrNull { it.kind == SampleKind.SPO2 }?.let { parts.add("SpO₂ ${it.value.toInt()}%") }
            samples.lastOrNull { it.kind == SampleKind.SKIN_TEMP }?.let { parts.add("Skin %.1f°C".format(it.value)) }
            if (parts.isNotEmpty()) show(parts.joinToString(" · "), "Measuring SpO₂ & skin temp")
        }) { ok ->
            if (value.value == null) show(if (ok) "Done" else "No reading", title = "Measuring SpO₂ & skin temp")
            else runOnUiThread { busy.value = false }
            service?.let { Features.sendSensorsResult(it, ok) }
            finishSoon()
        }
    }

    private fun runEcg() {
        SensorBridge.captureEcg(this) { hz, uv ->
            if (uv.isEmpty()) show("No ECG captured", title = "ECG")
            else show("ECG recorded (${uv.size / maxOf(1, hz)}s)", title = "ECG")
            service?.let { Features.sendEcgResult(it, hz, uv) }
            finishSoon()
        }
    }

    private fun runBia() {
        SensorBridge.captureBia(this) { values ->
            if (values.isEmpty()) show("No reading", title = "Body composition")
            else {
                val fat = values["fatPct"]
                show(fat?.let { "Body fat %.1f%%".format(it) } ?: "Reading saved", title = "Body composition")
            }
            service?.let { Features.sendBiaResult(it, values) }
            finishSoon()
        }
    }

    private fun show(result: String, title: String) = runOnUiThread {
        busy.value = false
        value.value = result
        this.title.value = title
    }

    /** Leaves the result on screen briefly, then closes the panel. */
    private fun finishSoon() = main.postDelayed({ if (!isFinishing) finish() }, 3_500)

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
