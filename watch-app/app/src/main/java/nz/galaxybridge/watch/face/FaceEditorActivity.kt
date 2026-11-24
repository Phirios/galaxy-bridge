package nz.galaxybridge.watch.face

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.watchface.editor.EditorSession
import kotlinx.coroutines.launch
import nz.galaxybridge.watch.BridgeTheme

/** The watch's "Customise" screen for the Wear OS Bridge face: pick what each complication shows. */
class FaceEditorActivity : ComponentActivity() {
    private var session: EditorSession? = null
    private var ready by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            session = EditorSession.createOnWatchEditorSession(this@FaceEditorActivity)
            ready = true
        }
        setContent {
            BridgeTheme {
                AppScaffold {
                    val listState = rememberTransformingLazyColumnState()
                    val spec = rememberTransformationSpec()
                    ScreenScaffold(
                        scrollState = listState,
                        edgeButton = {
                            EdgeButton(onClick = { finish() }, buttonSize = EdgeButtonSize.Small) {
                                Text("Done")
                            }
                        },
                    ) { contentPadding ->
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
                                ) { Text("Complications") }
                            }
                            for ((label, id) in listOf(
                                "Left" to BridgeFaceService.LEFT,
                                "Right" to BridgeFaceService.RIGHT,
                                "Bottom" to BridgeFaceService.BOTTOM,
                            )) {
                                item {
                                    FilledTonalButton(
                                        onClick = { lifecycleScope.launch { session?.openComplicationDataSourceChooser(id) } },
                                        enabled = ready,
                                        label = { Text(label) },
                                        transformation = SurfaceTransformation(spec),
                                        modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                                    )
                                }
                            }
                            item {
                                Text(
                                    "Pick designs in the iPhone app: More → Watch faces.",
                                    style = MaterialTheme.typography.bodyExtraSmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }
}
