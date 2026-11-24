package nz.galaxybridge.watch

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * The watch side of media, productivity and customisation extras: music stored on the watch,
 * extra iPhone media controls, the presentation clicker, translate, calculator, unit converter,
 * checklist, passes and home shortcuts. Opened from the main screen and from our tiles.
 */
class ExtrasActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PAGE = "extrasPage"
        const val PAGE_HOME = 0
        const val PAGE_MUSIC = 1
        const val PAGE_MEDIA = 2
        const val PAGE_CLICKER = 3
        const val PAGE_TRANSLATE = 4
        const val PAGE_CALC = 5
        const val PAGE_CONVERT = 6
        const val PAGE_CHECKLIST = 7
        const val PAGE_PASSES = 8

        fun open(c: Context, page: Int) = c.startActivity(
            Intent(c, ExtrasActivity::class.java)
                .putExtra(EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private var page by mutableIntStateOf(PAGE_HOME)
    /** The page the activity was opened on; Back from it closes rather than going to the menu. */
    private var entry = PAGE_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getIntExtra(EXTRA_PAGE, PAGE_HOME)
        entry = page
        MusicLibrary.load(this); QrPasses.load(this); HomeScenes.load(this); ToolsConfig.load(this); Checklist.load(this)
        setContent { BridgeTheme { AppScaffold { Screen() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        page = intent.getIntExtra(EXTRA_PAGE, page)
        entry = page
    }

    override fun onDestroy() {
        // The clicker is a mode, not a background service: leave it when the screen is closed.
        if (isFinishing) Presenter.stop(this)
        super.onDestroy()
    }

    private fun setBright(on: Boolean) {
        window.attributes = window.attributes.apply { screenBrightness = if (on) 1f else -1f }
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Composable
    private fun Screen() {
        BackHandler(enabled = page != PAGE_HOME && page != entry) { page = PAGE_HOME }
        val listState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        LaunchedEffect(page) {
            listState.scrollToItem(0)
            setBright(page == PAGE_PASSES || page == PAGE_CLICKER)
            if (page != PAGE_CLICKER) Presenter.stop(this@ExtrasActivity)
        }
        val tracks by MusicLibrary.tracks.collectAsState()
        val checklist by Checklist.items.collectAsState()
        val listName by Checklist.list.collectAsState()
        val passes by QrPasses.passes.collectAsState()
        val scenes by HomeScenes.scenes.collectAsState()
        val lastSceneResult by HomeScenes.lastResult.collectAsState()
        val clocks by ToolsConfig.clocks.collectAsState()

        var addNote by remember { mutableStateOf("") }
        val addItemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val t = r.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("text")?.toString() }
            if (!t.isNullOrBlank()) addNote = if (Checklist.add(t)) "Added on iPhone" else "iPhone not connected"
        }

        if (page == PAGE_CLICKER) {
            ClickerPage()
            return
        }

        if (page == PAGE_CHECKLIST) {
            ScreenScaffold(
                scrollState = listState,
                edgeButton = {
                    EdgeButton(
                        onClick = { runCatching { addItemLauncher.launch(textInput("New item")) } },
                        buttonSize = EdgeButtonSize.Small,
                    ) { Text("Add item") }
                },
            ) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    checklistPage(spec, checklist, listName, addNote)
                }
            }
            return
        }

        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when (page) {
                    PAGE_MUSIC -> musicPage(spec, tracks)
                    PAGE_MEDIA -> mediaPage(spec)
                    PAGE_TRANSLATE -> translatePage(spec)
                    PAGE_CALC -> item { Calculator() }
                    PAGE_CONVERT -> item { Converter() }
                    PAGE_PASSES -> passesPage(spec, passes)
                    else -> homePage(spec, scenes, lastSceneResult, clocks)
                }
            }
        }
    }

    // --- Menu ---

    private fun TransformingLazyColumnScope.homePage(
        spec: TransformationSpec,
        scenes: List<HomeScenes.Scene>,
        lastSceneResult: String,
        clocks: List<ToolsConfig.Clock>,
    ) {
        header(spec, "Extras")
        item { Nav("Music on watch", "Offline, to earbuds", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_MUSIC } }
        item { Nav("iPhone media+", "Seek, like, skip 15 s", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_MEDIA } }
        item { Nav("Clicker", "Slides: next / previous", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_CLICKER } }
        item { Nav("Translate", "Speak, iPhone translates", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_TRANSLATE } }
        item { Nav("Checklist", "Ticks sync to Reminders", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_CHECKLIST } }
        item { Nav("Passes", "Tickets and boarding passes", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_PASSES } }
        item { Nav("Calculator", "", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_CALC } }
        item { Nav("Unit converter", "", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = PAGE_CONVERT } }
        if (scenes.isNotEmpty()) {
            item {
                ListSubHeader(
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec),
                ) { Text("Home shortcuts") }
            }
            items(scenes) { s ->
                FilledTonalButton(
                    onClick = { HomeScenes.run(this@ExtrasActivity, s.id) },
                    label = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                )
            }
            if (lastSceneResult.isNotEmpty()) {
                item {
                    Text(
                        lastSceneResult,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (clocks.isNotEmpty()) {
            item {
                ListSubHeader(
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec),
                ) { Text("World clock") }
            }
            item {
                WorldClocksCard(
                    clocks = clocks,
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun WorldClocksCard(
        clocks: List<ToolsConfig.Clock>,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
    ) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
        val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
        Card(
            onClick = {},
            enabled = false,
            transformation = transformation,
            modifier = modifier,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                for (c in clocks) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(c.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            Instant.ofEpochMilli(now).atZone(ZoneId.of(c.zone)).format(fmt),
                            style = MaterialTheme.typography.numeralExtraSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    // --- Music on watch ---

    private fun TransformingLazyColumnScope.musicPage(spec: TransformationSpec, tracks: List<MusicLibrary.Track>) {
        header(spec, "Music on watch")
        item { PlayerControls() }
        item { OutputHint(SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
        item { Incoming() }
        if (tracks.isEmpty()) {
            item {
                Text(
                    "Send songs or podcasts from the iPhone app: Watch → Music on watch.",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        items(tracks) { t ->
            FilledTonalButton(
                onClick = { MusicPlayer.play(this@ExtrasActivity, t.id) },
                label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(listOf(t.artist, mmss(t.ms)).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun PlayerControls() {
        val st by MusicPlayer.state.collectAsState()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(st) { while (st.playing) { now = System.currentTimeMillis(); delay(1000) } }
        val pos = if (st.playing) st.posMs + (now - st.at) else st.posMs
        val prevIx = remember { MutableInteractionSource() }
        val playIx = remember { MutableInteractionSource() }
        val nextIx = remember { MutableInteractionSource() }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                st.title.ifEmpty { "Not playing" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (st.durMs > 0) {
                Text(
                    "${mmss(pos.coerceAtMost(st.durMs))} / ${mmss(st.durMs)}",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            ButtonGroup(modifier = Modifier.fillMaxWidth()) {
                FilledTonalIconButton(
                    onClick = { MusicPlayer.previous(this@ExtrasActivity) },
                    interactionSource = prevIx,
                    modifier = Modifier.weight(1f).animateWidth(prevIx),
                ) { Text("⏮", style = MaterialTheme.typography.titleSmall) }
                FilledIconButton(
                    onClick = { MusicPlayer.toggle(this@ExtrasActivity) },
                    interactionSource = playIx,
                    modifier = Modifier.weight(1.25f).animateWidth(playIx),
                ) { Text(if (st.playing) "❚❚" else "▶", style = MaterialTheme.typography.titleMedium) }
                FilledTonalIconButton(
                    onClick = { MusicPlayer.next(this@ExtrasActivity) },
                    interactionSource = nextIx,
                    modifier = Modifier.weight(1f).animateWidth(nextIx),
                ) { Text("⏭", style = MaterialTheme.typography.titleSmall) }
            }
            if (st.durMs > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SmallBtn("−15") { MusicPlayer.seek(this@ExtrasActivity, (pos - 15_000).coerceAtLeast(0)) }
                    SmallBtn("+30") { MusicPlayer.seek(this@ExtrasActivity, (pos + 30_000).coerceAtMost(st.durMs)) }
                }
            }
        }
    }

    @Composable
    private fun OutputHint(transformation: SurfaceTransformation, modifier: Modifier = Modifier) {
        var headset by remember { mutableStateOf(MusicPlayer.hasHeadset(this@ExtrasActivity)) }
        LaunchedEffect(Unit) { while (true) { headset = MusicPlayer.hasHeadset(this@ExtrasActivity); delay(3000) } }
        FilledTonalButton(
            onClick = { MusicPlayer.openBluetooth(this@ExtrasActivity) },
            label = { Text(if (headset) "Earbuds connected" else "Connect earbuds") },
            secondaryLabel = { Text(if (headset) "Change output" else "Opens Bluetooth") },
            transformation = transformation,
            modifier = modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Incoming() {
        val inc by MusicLibrary.incoming.collectAsState()
        val (title, p) = inc ?: return
        Text(
            "Receiving ${title.ifEmpty { "track" }} · ${(p * 100).toInt()}%",
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
    }

    // --- iPhone media extras (AMS) ---

    private fun TransformingLazyColumnScope.mediaPage(spec: TransformationSpec) {
        header(spec, "iPhone media+")
        item { MediaPlus() }
    }

    @Composable
    private fun MediaPlus() {
        val np by AppleClient.nowPlaying.collectAsState()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(np) { while (np.playing) { now = System.currentTimeMillis(); delay(1000) } }
        val elapsed = np.elapsedSec + if (np.playing && np.elapsedAt > 0) (now - np.elapsedAt) / 1000.0 else 0.0
        var scrub by remember { mutableFloatStateOf(-1f) }
        val cs = MaterialTheme.colorScheme
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                np.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (np.artist.isNotEmpty()) {
                Text(np.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (np.durationSec > 0) {
                val shown = if (scrub >= 0) scrub.toDouble() else elapsed
                Text(
                    "${mmss((shown * 1000).toLong())} / ${mmss((np.durationSec * 1000).toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (scrub >= 0) cs.secondary else cs.onSurface,
                )
                // Turn the bezel to scrub, then tap Seek. AMS has no seek, so this goes through the iPhone app.
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(
                    Modifier
                        .focusRequester(focus)
                        .focusable()
                        .onRotaryScrollEvent {
                            val base = if (scrub >= 0) scrub else elapsed.toFloat()
                            scrub = (base + it.verticalScrollPixels / 8f).coerceIn(0f, np.durationSec.toFloat())
                            true
                        },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        SmallBtn("−15") { scrub = ((if (scrub >= 0) scrub else elapsed.toFloat()) - 15f).coerceAtLeast(0f) }
                        CompactButton(
                            onClick = { if (scrub >= 0) { AmsExtra.seek(scrub.toDouble()); scrub = -1f } },
                            label = { Text(if (scrub >= 0) "Seek" else "Bezel") },
                        )
                        SmallBtn("+15") { scrub = ((if (scrub >= 0) scrub else elapsed.toFloat()) + 15f).coerceAtMost(np.durationSec.toFloat()) }
                    }
                }
                Text("Seek works for Apple Music only", style = MaterialTheme.typography.bodyExtraSmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundBtn("⟲15") { LinkService.media(AmsExtra.CMD_SKIP_BACKWARD) }
                FilledIconButton(onClick = { LinkService.media(Ams.CMD_TOGGLE) }, modifier = Modifier.size(44.dp)) {
                    Text(if (np.playing) "❚❚" else "▶", style = MaterialTheme.typography.titleSmall)
                }
                RoundBtn("15⟳") { LinkService.media(AmsExtra.CMD_SKIP_FORWARD) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundBtn("♥") { LinkService.media(AmsExtra.CMD_LIKE) }
                RoundBtn("✕") { LinkService.media(AmsExtra.CMD_DISLIKE) }
                RoundBtn("🔖") { LinkService.media(AmsExtra.CMD_BOOKMARK) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundBtn("🔁") { LinkService.media(AmsExtra.CMD_REPEAT) }
                RoundBtn("🔀") { LinkService.media(AmsExtra.CMD_SHUFFLE) }
            }
            Text(
                "Like, skip 15 s and shuffle work when the iPhone app supports them. To change the iPhone's speaker or AirPods, use the output picker in the iPhone app.",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }

    // --- Clicker (HID keyboard) ---

    @Composable
    private fun ClickerPage() {
        val state by Presenter.state.collectAsState()
        val host by Presenter.hostName.collectAsState()
        val targets = remember { Presenter.targets(this@ExtrasActivity) }
        var chosen by remember { mutableIntStateOf(0) }
        val focus = remember { FocusRequester() }
        var acc by remember { mutableFloatStateOf(0f) }
        var lastAt by remember { mutableLongStateOf(0L) }
        val cs = MaterialTheme.colorScheme
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        ScreenScaffold { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .focusable()
                    .onRotaryScrollEvent { e ->
                        // One bezel click = one slide.
                        acc += e.verticalScrollPixels
                        val t = System.currentTimeMillis()
                        if (abs(acc) >= 40f && t - lastAt > 200) {
                            Presenter.press(if (acc > 0) Presenter.KEY_RIGHT else Presenter.KEY_LEFT)
                            acc = 0f; lastAt = t
                        }
                        true
                    },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(contentPadding).padding(bottom = 36.dp),
                ) {
                    Text("Clicker", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                    when (state) {
                        Presenter.State.OFF -> {
                            val target = targets.getOrNull(chosen)
                            Text(
                                target?.name ?: "No paired device",
                                style = MaterialTheme.typography.titleSmall,
                                color = cs.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (targets.size > 1) {
                                CompactButton(
                                    onClick = { chosen = (chosen + 1) % targets.size },
                                    colors = ButtonDefaults.filledTonalButtonColors(),
                                    label = { Text("Switch device ⇄") },
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Text(
                                "Bluetooth slide remote. Camera shutter pauses until you stop.",
                                style = MaterialTheme.typography.bodyExtraSmall,
                                color = cs.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        else -> {
                            Text(
                                when (state) {
                                    Presenter.State.CONNECTED -> "Connected to $host"
                                    Presenter.State.WAITING -> "Waiting for the device…"
                                    else -> "Starting…"
                                },
                                style = MaterialTheme.typography.bodyExtraSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                                FilledTonalIconButton(
                                    onClick = { Presenter.press(Presenter.KEY_LEFT) },
                                    modifier = Modifier.size(56.dp),
                                ) { Text("◀", style = MaterialTheme.typography.titleLarge) }
                                FilledIconButton(
                                    onClick = { Presenter.press(Presenter.KEY_RIGHT) },
                                    modifier = Modifier.size(56.dp),
                                ) { Text("▶", style = MaterialTheme.typography.titleLarge) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                SmallBtn("B") { Presenter.press(Presenter.KEY_B) }
                                SmallBtn("Esc") { Presenter.press(Presenter.KEY_ESC) }
                            }
                            Text(
                                "Turn the bezel to change slides",
                                style = MaterialTheme.typography.bodyExtraSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                if (state == Presenter.State.OFF) {
                    val target = targets.getOrNull(chosen)
                    EdgeButton(
                        onClick = { Presenter.start(this@ExtrasActivity, target) },
                        enabled = target != null,
                        buttonSize = EdgeButtonSize.Small,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) { Text("Start") }
                } else {
                    EdgeButton(
                        onClick = { Presenter.stop(this@ExtrasActivity) },
                        buttonSize = EdgeButtonSize.Small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cs.errorContainer,
                            contentColor = cs.onErrorContainer,
                        ),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) { Text("Stop") }
                }
            }
        }
    }

    // --- Translate ---

    private fun TransformingLazyColumnScope.translatePage(spec: TransformationSpec) {
        header(spec, "Translate")
        item { TranslateBlock(SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
    }

    @Composable
    private fun TranslateBlock(transformation: SurfaceTransformation, modifier: Modifier = Modifier) {
        val a by ToolsConfig.langA.collectAsState()
        val b by ToolsConfig.langB.collectAsState()
        val pending by Translate.pending.collectAsState()
        val result by Translate.result.collectAsState()
        var toB by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf("") }
        fun send(text: String?) {
            if (text.isNullOrBlank()) return
            error = if (Translate.request(text, toB)) "" else "iPhone not connected"
        }
        val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            send(r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
        }
        val type = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            send(r.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("text")?.toString() })
        }
        val from = if (toB) a else b
        val to = if (toB) b else a
        val cs = MaterialTheme.colorScheme
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { toB = !toB },
                label = { Text("${from.second} → ${to.second}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text("Tap to swap") },
                transformation = transformation,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            ButtonGroup(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, from.first)
                        error = if (runCatching { speak.launch(i) }.isSuccess) "" else "No speech input on this watch"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Speak") }
                FilledTonalButton(
                    onClick = {
                        error = if (runCatching { type.launch(textInput("Text to translate")) }.isSuccess) "" else "No keyboard available"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Type") }
            }
            if (pending != null) {
                Text("Translating on iPhone…", style = MaterialTheme.typography.bodySmall, color = cs.secondary, modifier = Modifier.padding(top = 8.dp))
            }
            if (error.isNotEmpty()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = BridgeTokens.Wait, modifier = Modifier.padding(top = 8.dp))
            }
            result?.let { r ->
                Card(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(r.source, style = MaterialTheme.typography.bodyExtraSmall, color = cs.onSurfaceVariant)
                    Text(
                        r.text,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (r.ok) cs.onSurface else BridgeTokens.Wait,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    /** The Wear OS text-input screen (keyboard, handwriting or voice), as RemoteInputIntentHelper builds it. */
    private fun textInput(label: String): Intent =
        Intent("android.support.wearable.input.action.REMOTE_INPUT")
            .putExtra("android.support.wearable.input.extra.REMOTE_INPUTS", arrayOf(RemoteInput.Builder("text").setLabel(label).build()))

    // --- Checklist ---

    private fun TransformingLazyColumnScope.checklistPage(
        spec: TransformationSpec,
        all: List<Checklist.Item>,
        listName: String,
        addNote: String,
    ) {
        header(spec, listName.ifEmpty { "Checklist" })
        if (addNote.isNotEmpty()) {
            item {
                Text(addNote, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        val items = all.sortedBy { it.done }
        if (items.isEmpty()) {
            item {
                Text(
                    "Choose a Reminders list in the iPhone app: Watch → Checklist.",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        items(items) { i ->
            CheckboxButton(
                checked = i.done,
                onCheckedChange = { Checklist.toggle(this@ExtrasActivity, i.id) },
                label = {
                    Text(
                        i.title,
                        maxLines = 2,
                        textDecoration = if (i.done) TextDecoration.LineThrough else TextDecoration.None,
                    )
                },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
    }

    // --- Passes ---

    private fun TransformingLazyColumnScope.passesPage(spec: TransformationSpec, passes: List<QrPasses.Pass>) {
        header(spec, "Passes")
        if (passes.isEmpty()) {
            item {
                Text(
                    "Send a ticket or boarding pass from the iPhone app: Watch → Passes.",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        items(passes) { p ->
            PassCard(
                p = p,
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun PassCard(p: QrPasses.Pass, transformation: SurfaceTransformation, modifier: Modifier = Modifier) {
        val bmp = remember(p.id) {
            runCatching { BitmapFactory.decodeFile(QrPasses.imageFile(this@ExtrasActivity, p.id).absolutePath)?.asImageBitmap() }.getOrNull()
        }
        Card(
            onClick = {},
            enabled = false,
            transformation = transformation,
            modifier = modifier,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                if (p.sub.isNotEmpty()) {
                    Text(p.sub, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (bmp != null) {
                    Box(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(6.dp)) {
                        Image(bmp, contentDescription = p.title, modifier = Modifier.size(144.dp))
                    }
                }
            }
        }
    }

    // --- Calculator & converter ---

    @Composable
    private fun Calculator() {
        var expr by remember { mutableStateOf("") }
        var shown by remember { mutableStateOf("0") }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                expr.ifEmpty { " " },
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                shown,
                style = MaterialTheme.typography.numeralSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Keypad(listOf("7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", "C", "0", ".", "+", "⌫", "(", ")", "=")) { k ->
                when (k) {
                    "C" -> { expr = ""; shown = "0" }
                    "⌫" -> { expr = expr.dropLast(1); shown = expr.ifEmpty { "0" } }
                    "=" -> { shown = Calc.eval(expr)?.let(Calc::format) ?: "Error"; if (shown != "Error") expr = shown }
                    else -> if (expr.length < 40) { expr += k; shown = expr }
                }
            }
        }
    }

    @Composable
    private fun Converter() {
        var unit by remember { mutableIntStateOf(0) }
        var input by remember { mutableStateOf("") }
        val conv = Calc.CONVERSIONS[unit]
        val value = input.toDoubleOrNull()
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { unit = (unit + 1) % Calc.CONVERSIONS.size },
                label = { Text("${conv.from} → ${conv.to}") },
                secondaryLabel = { Text("Tap for next") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${input.ifEmpty { "0" }} ${conv.from}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                value?.let { "${Calc.format(conv.convert(it))} ${conv.to}" } ?: "—",
                style = MaterialTheme.typography.numeralSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Keypad(listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", "−", "0", ".", "C", "⌫"), columns = 3) { k ->
                when (k) {
                    "C" -> input = ""
                    "⌫" -> input = input.dropLast(1)
                    "−" -> input = if (input.startsWith("-")) input.drop(1) else "-$input"
                    else -> if (input.length < 12) input += k
                }
            }
        }
    }

    @Composable
    private fun Keypad(keys: List<String>, columns: Int = 4, onKey: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 4.dp)) {
            for (row in keys.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (k in row) {
                        val isOp = k in setOf("=", "+", "−", "×", "÷")
                        TextButton(
                            onClick = { onKey(k) },
                            colors = if (isOp) TextButtonDefaults.filledTextButtonColors()
                            else TextButtonDefaults.filledTonalTextButtonColors(),
                            modifier = Modifier.size(34.dp),
                        ) { Text(k, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }

    // --- bits ---

    private fun TransformingLazyColumnScope.header(spec: TransformationSpec, text: String) = item {
        ListHeader(
            transformation = SurfaceTransformation(spec),
            modifier = Modifier.transformedHeight(this, spec),
        ) { Text(text) }
    }

    @Composable
    private fun Nav(
        label: String,
        secondary: String,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) = FilledTonalButton(
        onClick = onClick,
        label = { Text(label) },
        secondaryLabel = if (secondary.isEmpty()) null else ({ Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
        transformation = transformation,
        modifier = modifier.fillMaxWidth(),
    )

    @Composable
    private fun RoundBtn(symbol: String, onClick: () -> Unit) = TextButton(
        onClick = onClick,
        colors = TextButtonDefaults.filledTonalTextButtonColors(),
        modifier = Modifier.size(42.dp),
    ) { Text(symbol, style = MaterialTheme.typography.labelMedium) }

    @Composable
    private fun SmallBtn(symbol: String, onClick: () -> Unit) = TextButton(
        onClick = onClick,
        colors = TextButtonDefaults.filledTonalTextButtonColors(),
        modifier = Modifier.size(36.dp),
    ) { Text(symbol, style = MaterialTheme.typography.labelSmall) }

    private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

/** Entry button for the main watch screen (MainActivity home and music pages). */
@Composable
fun ExtrasEntryChip(page: Int = ExtrasActivity.PAGE_HOME, label: String = "Extras", secondary: String = "Music, clicker, translate…") {
    val c = LocalContext.current
    FilledTonalButton(
        onClick = { ExtrasActivity.open(c, page) },
        label = { Text(label) },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Calculator maths and unit conversions; pure functions so they can be unit-tested. */
object Calc {
    data class Conversion(val from: String, val to: String, val convert: (Double) -> Double)

    val CONVERSIONS = listOf(
        Conversion("km", "mi") { it / 1.609344 }, Conversion("mi", "km") { it * 1.609344 },
        Conversion("kg", "lb") { it / 0.45359237 }, Conversion("lb", "kg") { it * 0.45359237 },
        Conversion("°C", "°F") { it * 9 / 5 + 32 }, Conversion("°F", "°C") { (it - 32) * 5 / 9 },
        Conversion("cm", "in") { it / 2.54 }, Conversion("in", "cm") { it * 2.54 },
        Conversion("m", "ft") { it / 0.3048 }, Conversion("ft", "m") { it * 0.3048 },
        Conversion("L", "US gal") { it / 3.785411784 }, Conversion("US gal", "L") { it * 3.785411784 },
        Conversion("km/h", "mph") { it / 1.609344 }, Conversion("mph", "km/h") { it * 1.609344 },
    )

    /** Evaluates + − × ÷ with precedence and brackets. Returns null on a malformed expression. */
    fun eval(expr: String): Double? {
        val s = expr.replace('×', '*').replace('÷', '/').replace('−', '-').filterNot { it == ' ' }
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        lateinit var parseSum: () -> Double?
        fun parseAtom(): Double? {
            if (peek() == '-') { i++; return parseAtom()?.let { -it } }
            if (peek() == '(') { i++; val v = parseSum() ?: return null; if (peek() != ')') return null; i++; return v }
            val start = i
            while (peek().isDigit() || peek() == '.') i++
            return s.substring(start, i).toDoubleOrNull()
        }
        fun parseProduct(): Double? {
            var v = parseAtom() ?: return null
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                val r = parseAtom() ?: return null
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        parseSum = {
            var v = parseProduct()
            while (v != null && (peek() == '+' || peek() == '-')) {
                val op = s[i++]
                val r = parseProduct()
                v = if (r == null) null else if (op == '+') v + r else v - r
            }
            v
        }
        val v = parseSum() ?: return null
        return if (i == s.length && v.isFinite()) v else null
    }

    fun format(v: Double): String =
        if (v == Math.rint(v) && abs(v) < 1e12) v.toLong().toString()
        else "%.6g".format(v).let { if ('e' in it) it else it.trimEnd('0').trimEnd('.') }
}
