package nz.galaxybridge.watch

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

/** Full-screen safety countdown (fall / SOS / check-in) and the loud "find my watch" screen. */
class SafetyActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_FIND = "find"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val find = intent.getStringExtra(EXTRA_MODE) == MODE_FIND
        setContent { BridgeTheme { AppScaffold { if (find) FindScreen() else AlertScreen() } } }
    }

    @Composable
    private fun AlertScreen() {
        val alert by SafetyMonitor.active.collectAsState()
        LaunchedEffect(alert == null) { if (alert == null) finish() }
        val a = alert ?: return
        val cs = MaterialTheme.colorScheme
        val showOk = a.stage == SafetyMonitor.Stage.NO_CONTACT || a.stage == SafetyMonitor.Stage.NOT_CONNECTED
        ScreenScaffold { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(cs.errorContainer.copy(alpha = 0.85f), cs.background), radius = 380f)),
            ) {
                AlertBody(a, cs, Modifier.padding(contentPadding).padding(bottom = if (showOk) 36.dp else 0.dp))
                if (showOk) {
                    EdgeButton(
                        onClick = { SafetyMonitor.cancel(this@SafetyActivity) },
                        buttonSize = EdgeButtonSize.Small,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) { Text("OK") }
                }
            }
        }
    }

    @Composable
    private fun AlertBody(a: SafetyMonitor.Alert, cs: androidx.wear.compose.material3.ColorScheme, modifier: Modifier = Modifier) {
        Column(
            Modifier.fillMaxSize().then(modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                a.kind.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
                textAlign = TextAlign.Center,
            )
            when (a.stage) {
                SafetyMonitor.Stage.COUNTDOWN -> Countdown(a)
                SafetyMonitor.Stage.CALLING -> {
                    Message("Calling ${a.contact} through your iPhone")
                    if (a.location != null) Message("Location sent to your iPhone", dim = true)
                    ButtonGroup(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Button(
                            onClick = { CallLog.hangUp(this@SafetyActivity) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cs.errorDim,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("End") }
                        FilledTonalButton(
                            onClick = { SafetyMonitor.cancel(this@SafetyActivity) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Close") }
                    }
                }
                SafetyMonitor.Stage.NO_CONTACT -> {
                    Message("Emergency calling isn't set up. On your iPhone: Wear OS Bridge → Watch → Safety.")
                }
                SafetyMonitor.Stage.NOT_CONNECTED -> {
                    Message("Couldn't reach your iPhone to call ${a.contact}. Call for help another way.")
                }
            }
        }
    }

    @Composable
    private fun Countdown(a: SafetyMonitor.Alert) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(a.endsAt) { while (true) { now = SystemClock.elapsedRealtime(); delay(200) } }
        val total = CallsSafety.config.value.countdown.coerceAtLeast(CallsSafety.MIN_COUNTDOWN) * 1000f
        val left = (a.endsAt - now).coerceAtLeast(0)
        val cs = MaterialTheme.colorScheme
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp).size(84.dp)) {
            CircularProgressIndicator(
                progress = { (left / total).coerceIn(0f, 1f) },
                strokeWidth = 6.dp,
                colors = ProgressIndicatorDefaults.colors(
                    indicatorColor = cs.error,
                    trackColor = cs.onSurface.copy(alpha = 0.16f),
                ),
                modifier = Modifier.fillMaxSize(),
            )
            Text("${(left + 999) / 1000}", style = MaterialTheme.typography.numeralLarge, color = cs.onBackground)
        }
        Message("Calling ${a.contact} when it reaches 0", dim = true)
        Button(
            onClick = { SafetyMonitor.cancel(this@SafetyActivity) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(if (a.kind == SafetyMonitor.Kind.SOS) "Cancel" else "I'm OK", fontWeight = FontWeight.Bold)
        }
        CompactButton(
            onClick = { SafetyMonitor.callNow(this@SafetyActivity) },
            colors = ButtonDefaults.childButtonColors(contentColor = cs.onSurfaceVariant),
            label = { Text("Call now") },
        )
    }

    @Composable
    private fun Message(text: String, dim: Boolean = false) {
        val cs = MaterialTheme.colorScheme
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (dim) cs.onSurfaceVariant else cs.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
        )
    }

    @Composable
    private fun FindScreen() {
        val ringing by FindLoud.ringing.collectAsState()
        LaunchedEffect(ringing) { if (!ringing) finish() }
        val cs = MaterialTheme.colorScheme
        ScreenScaffold { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(BridgeTokens.Wait.copy(alpha = 0.45f), cs.background))),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(contentPadding).padding(bottom = 36.dp),
                ) {
                    Text("Here I am!", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
                    Message("Getting louder until you find me")
                }
                EdgeButton(
                    onClick = { FindLoud.stop(this@SafetyActivity) },
                    buttonSize = EdgeButtonSize.Medium,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) { Text("Found") }
            }
        }
    }
}

/** Launcher entry "SOS": opening it starts the SOS countdown (map it to a button double-press in Samsung settings). */
class SosActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallsSafety.load(this)
        SafetyMonitor.trigger(this, SafetyMonitor.Kind.SOS)
        finish()
    }
}
