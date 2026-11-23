package nz.galaxybridge.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/** Live-workout additions for the watch workout screen: pause/resume, lap button and splits. */
object WorkoutExtras {
    fun clock(ms: Long): String {
        val secs = ms / 1000
        return if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
        else "%d:%02d".format(secs / 60, secs % 60)
    }

    /** Status line, pause/resume + lap buttons, and the last three lap splits. */
    @Composable
    fun Controls(live: Workouts.Live, onPause: () -> Unit, onResume: () -> Unit, onLap: () -> Unit) {
        val laps by Workouts.laps.collectAsState()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                live.autoPaused -> Text("Auto-paused", style = MaterialTheme.typography.caption2, color = Color(0xFFFFC542))
                live.paused -> Text("Paused", style = MaterialTheme.typography.caption2, color = Color(0xFFFFC542))
                live.autoPause -> Text("Auto-pause on", style = MaterialTheme.typography.caption3, color = Color.White.copy(alpha = 0.5f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = if (live.paused) onResume else onPause,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3A3F5C))) {
                    Text(if (live.paused) "▶" else "❚❚")
                }
                Button(onClick = onLap, enabled = !live.paused,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E8C95))) { Text("Lap") }
            }
            for (l in laps.takeLast(3).reversed()) {
                Text("Lap ${l.n}  ${clock(l.activeMs)}  ${"%.2f".format(l.distM / 1000)} km",
                    style = MaterialTheme.typography.caption3, color = Color.White)
            }
        }
    }
}
