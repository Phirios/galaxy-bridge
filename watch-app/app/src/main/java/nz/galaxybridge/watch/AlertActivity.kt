package nz.galaxybridge.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** Full-screen incoming call (answer/decline on the iPhone) and "find my watch" screens. */
class AlertActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CALL = "call"
        const val MODE_FIND = "find"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val mode = intent.getStringExtra(EXTRA_MODE)
        setContent {
            BridgeTheme {
                AppScaffold {
                    if (mode == MODE_FIND) FindScreen() else CallScreen()
                }
            }
        }
    }

    @Composable
    private fun CallScreen() {
        val call by PhoneNotifier.incomingCall.collectAsState()
        LaunchedEffect(call) { if (call == null) finish() }
        val c = call ?: return
        val cs = MaterialTheme.colorScheme
        ScreenScaffold { contentPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(BridgeTokens.CallAccept.copy(alpha = 0.25f), cs.background)))
                    .padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("iPhone call", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text(
                    c.caller,
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                if (c.detail.isNotEmpty()) {
                    Text(
                        c.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    RoundAction("✕", BridgeTokens.CallDecline, c.declineLabel) {
                        LinkService.performAction(c.uid, positive = false); finish()
                    }
                    RoundAction("✓", BridgeTokens.CallAccept, c.acceptLabel) {
                        LinkService.performAction(c.uid, positive = true); finish()
                    }
                }
                Text(
                    "Audio stays on your iPhone",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun FindScreen() {
        val ringing by LinkService.findingWatch.collectAsState()
        LaunchedEffect(ringing) { if (!ringing) finish() }
        val cs = MaterialTheme.colorScheme
        ScreenScaffold { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(cs.primaryContainer.copy(alpha = 0.7f), cs.background))),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(contentPadding).padding(bottom = 36.dp),
                ) {
                    Text("Here I am!", style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
                    Text(
                        "Your iPhone is looking for this watch",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                EdgeButton(
                    onClick = { LinkService.stopFindingWatch(); finish() },
                    buttonSize = EdgeButtonSize.Medium,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) { Text("Found") }
            }
        }
    }

    @Composable
    private fun RoundAction(symbol: String, color: Color, label: String, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(
                onClick = onClick,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
                modifier = Modifier.size(56.dp),
            ) {
                Text(symbol, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                label.take(12),
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
