package nz.galaxybridge.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

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
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            when {
                live.autoPaused -> Text("Auto-paused", style = MaterialTheme.typography.labelSmall, color = BridgeTokens.Wait)
                live.paused -> Text("Paused", style = MaterialTheme.typography.labelSmall, color = BridgeTokens.Wait)
                live.autoPause -> Text("Auto-pause on", style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            ButtonGroup(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = if (live.paused) onResume else onPause,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (live.paused) "▶" else "❚❚")
                }
                Button(
                    onClick = onLap,
                    enabled = !live.paused,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Lap")
                }
            }
            if (laps.isNotEmpty()) Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (l in laps.takeLast(3).reversed()) {
                    Text(
                        "Lap ${l.n}  ${clock(l.activeMs)}  ${"%.2f".format(l.distM / 1000)} km",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
