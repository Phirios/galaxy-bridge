package nz.galaxybridge.watch

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
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
        setContent { MaterialTheme { if (find) FindScreen() else AlertScreen() } }
    }

    @Composable
    private fun AlertScreen() {
        val alert by SafetyMonitor.active.collectAsState()
        LaunchedEffect(alert == null) { if (alert == null) finish() }
        val a = alert ?: return
        Column(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF7A1020), Color.Black), radius = 380f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(a.kind.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            when (a.stage) {
                SafetyMonitor.Stage.COUNTDOWN -> Countdown(a)
                SafetyMonitor.Stage.CALLING -> {
                    Message("Calling ${a.contact} through your iPhone")
                    if (a.location != null) Message("Location sent to your iPhone", dim = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { CallLog.hangUp(this@SafetyActivity) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5484D))) { Text("End") }
                        Button(onClick = { SafetyMonitor.cancel(this@SafetyActivity) },
                            colors = ButtonDefaults.secondaryButtonColors()) { Text("Close") }
                    }
                }
                SafetyMonitor.Stage.NO_CONTACT -> {
                    Message("Emergency calling isn't set up. On your iPhone: Wear OS Bridge → Watch → Safety.")
                    Button(onClick = { SafetyMonitor.cancel(this@SafetyActivity) }, modifier = Modifier.padding(top = 8.dp)) { Text("OK") }
                }
                SafetyMonitor.Stage.NOT_CONNECTED -> {
                    Message("Couldn't reach your iPhone to call ${a.contact}. Call for help another way.")
                    Button(onClick = { SafetyMonitor.cancel(this@SafetyActivity) }, modifier = Modifier.padding(top = 8.dp)) { Text("OK") }
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp).size(84.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, style = Stroke(6.dp.toPx()))
                drawArc(Color(0xFFFF6B6B), -90f, 360f * (left / total).coerceIn(0f, 1f), false,
                    style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            }
            Text("${(left + 999) / 1000}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Message("Calling ${a.contact} when it reaches 0", dim = true)
        Button(onClick = { SafetyMonitor.cancel(this@SafetyActivity) },
            colors = ButtonDefaults.buttonColors(backgroundColor = Color.White),
            modifier = Modifier.padding(top = 6.dp).size(width = 120.dp, height = 44.dp)) {
            Text(if (a.kind == SafetyMonitor.Kind.SOS) "Cancel" else "I'm OK", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Text("Call now", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp).clickable { SafetyMonitor.callNow(this@SafetyActivity) })
    }

    @Composable
    private fun Message(text: String, dim: Boolean = false) {
        Text(text, fontSize = 12.sp, color = Color.White.copy(alpha = if (dim) 0.6f else 0.9f), textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 3.dp))
    }

    @Composable
    private fun FindScreen() {
        val ringing by FindLoud.ringing.collectAsState()
        LaunchedEffect(ringing) { if (!ringing) finish() }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFFFF7A2E), Color.Black))),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Here I am!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Message("Getting louder until you find me")
                Button(onClick = { FindLoud.stop(this@SafetyActivity) }, modifier = Modifier.size(72.dp)) { Text("Found") }
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
