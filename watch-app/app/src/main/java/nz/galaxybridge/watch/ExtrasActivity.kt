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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val XAccent = Color(0xFF5C6BFF)
private val XAccent2 = Color(0xFF2ED1DB)
private val XDim = Color.White.copy(alpha = 0.6f)

private val XColors = androidx.wear.compose.material.Colors(
    primary = Color(0xFF7C88FF), primaryVariant = XAccent, secondary = XAccent2, secondaryVariant = Color(0xFF1E8C95),
    surface = Color(0xFF1C2046), onSurface = Color.White, onSurfaceVariant = Color(0xFFB7BCE0),
    onPrimary = Color.White, onSecondary = Color(0xFF00262A), background = Color.Black, error = Color(0xFFFF6B6B),
)

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

        fun open(c: Context, page: Int) = c.startActivity(Intent(c, ExtrasActivity::class.java)
            .putExtra(EXTRA_PAGE, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var page by mutableIntStateOf(PAGE_HOME)
    /** The page the activity was opened on; Back from it closes rather than going to the menu. */
    private var entry = PAGE_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getIntExtra(EXTRA_PAGE, PAGE_HOME)
        entry = page
        MusicLibrary.load(this); QrPasses.load(this); HomeScenes.load(this); ToolsConfig.load(this); Checklist.load(this)
        setContent { MaterialTheme(colors = XColors) { Screen() } }
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
        val listState = rememberScalingLazyListState()
        LaunchedEffect(page) {
            listState.scrollToItem(0)
            setBright(page == PAGE_PASSES || page == PAGE_CLICKER)
            if (page != PAGE_CLICKER) Presenter.stop(this@ExtrasActivity)
        }
        val tracks by MusicLibrary.tracks.collectAsState()
        val checklist by Checklist.items.collectAsState()
        val listName by Checklist.list.collectAsState()
        val passes by QrPasses.passes.collectAsState()
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
            modifier = Modifier.background(Color.Black).background(Brush.radialGradient(
                listOf(Color(0xFF2A1F6B), Color(0xFF0B1F33), Color.Black), radius = 420f)),
        ) {
            if (page == PAGE_CLICKER) { ClickerPage(); return@Scaffold }
            ScalingLazyColumn(state = listState, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (page) {
                    PAGE_MUSIC -> musicPage(tracks)
                    PAGE_MEDIA -> mediaPage()
                    PAGE_TRANSLATE -> translatePage()
                    PAGE_CALC -> item { Calculator() }
                    PAGE_CONVERT -> item { Converter() }
                    PAGE_CHECKLIST -> checklistPage(checklist, listName)
                    PAGE_PASSES -> passesPage(passes)
                    else -> homePage()
                }
            }
        }
    }

    // --- Menu ---

    private fun ScalingLazyListScope.homePage() {
        item { Title("Extras") }
        item { Nav("Music on watch", "Offline, to earbuds") { page = PAGE_MUSIC } }
        item { Nav("iPhone media+", "Seek, like, skip 15 s") { page = PAGE_MEDIA } }
        item { Nav("Clicker", "Slides: next / previous") { page = PAGE_CLICKER } }
        item { Nav("Translate", "Speak, iPhone translates") { page = PAGE_TRANSLATE } }
        item { Nav("Checklist", "Ticks sync to Reminders") { page = PAGE_CHECKLIST } }
        item { Nav("Passes", "Tickets and boarding passes") { page = PAGE_PASSES } }
        item { Nav("Calculator", "") { page = PAGE_CALC } }
        item { Nav("Unit converter", "") { page = PAGE_CONVERT } }
        item { HomeScenesBlock() }
        item { WorldClocks() }
    }

    @Composable
    private fun HomeScenesBlock() {
        val scenes by HomeScenes.scenes.collectAsState()
        val last by HomeScenes.lastResult.collectAsState()
        if (scenes.isEmpty()) return
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Label("Home shortcuts")
            for (s in scenes) Chip(onClick = { HomeScenes.run(this@ExtrasActivity, s.id) }, label = { Text(s.name) },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (last.isNotEmpty()) Text(last, fontSize = 11.sp, color = XAccent2, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun WorldClocks() {
        val clocks by ToolsConfig.clocks.collectAsState()
        if (clocks.isEmpty()) return
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 24.dp)) {
            Label("World clock")
            for (c in clocks) Row(Modifier.padding(top = 2.dp)) {
                Text(c.label, color = XDim, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(Instant.ofEpochMilli(now).atZone(ZoneId.of(c.zone)).format(fmt), color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }

    // --- Music on watch ---

    private fun ScalingLazyListScope.musicPage(tracks: List<MusicLibrary.Track>) {
        item { Title("Music on watch") }
        item { PlayerControls() }
        item { OutputHint() }
        item { Incoming() }
        if (tracks.isEmpty()) item {
            Text("Send songs or podcasts from the iPhone app: Watch → Music on watch.", fontSize = 12.sp, color = XDim,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        }
        items(tracks) { t ->
            Chip(onClick = { MusicPlayer.play(this@ExtrasActivity, t.id) }, label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(listOf(t.artist, mmss(t.ms)).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    @Composable
    private fun PlayerControls() {
        val st by MusicPlayer.state.collectAsState()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(st) { while (st.playing) { now = System.currentTimeMillis(); delay(1000) } }
        val pos = if (st.playing) st.posMs + (now - st.at) else st.posMs
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(st.title.ifEmpty { "Not playing" }, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
            if (st.durMs > 0) Text("${mmss(pos.coerceAtMost(st.durMs))} / ${mmss(st.durMs)}", fontSize = 11.sp, color = XDim)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Round("⏮") { MusicPlayer.previous(this@ExtrasActivity) }
                Button(onClick = { MusicPlayer.toggle(this@ExtrasActivity) }, modifier = Modifier.size(52.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = XAccent)) { Text(if (st.playing) "❚❚" else "▶", fontSize = 18.sp) }
                Round("⏭") { MusicPlayer.next(this@ExtrasActivity) }
            }
            if (st.durMs > 0) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
                Round("−15") { MusicPlayer.seek(this@ExtrasActivity, (pos - 15_000).coerceAtLeast(0)) }
                Round("+30") { MusicPlayer.seek(this@ExtrasActivity, (pos + 30_000).coerceAtMost(st.durMs)) }
            }
        }
    }

    @Composable
    private fun OutputHint() {
        var headset by remember { mutableStateOf(MusicPlayer.hasHeadset(this@ExtrasActivity)) }
        LaunchedEffect(Unit) { while (true) { headset = MusicPlayer.hasHeadset(this@ExtrasActivity); delay(3000) } }
        Chip(onClick = { MusicPlayer.openBluetooth(this@ExtrasActivity) },
            label = { Text(if (headset) "Earbuds connected" else "Connect earbuds") },
            secondaryLabel = { Text(if (headset) "Change output" else "Opens Bluetooth") },
            colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun Incoming() {
        val inc by MusicLibrary.incoming.collectAsState()
        val (title, p) = inc ?: return
        Text("Receiving ${title.ifEmpty { "track" }} · ${(p * 100).toInt()}%", fontSize = 11.sp, color = XAccent2,
            textAlign = TextAlign.Center)
    }

    // --- iPhone media extras (AMS) ---

    private fun ScalingLazyListScope.mediaPage() {
        item { Title("iPhone media+") }
        item { MediaPlus() }
    }

    @Composable
    private fun MediaPlus() {
        val np by AppleClient.nowPlaying.collectAsState()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(np) { while (np.playing) { now = System.currentTimeMillis(); delay(1000) } }
        val elapsed = np.elapsedSec + if (np.playing && np.elapsedAt > 0) (now - np.elapsedAt) / 1000.0 else 0.0
        var scrub by remember { mutableFloatStateOf(-1f) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(np.title.ifEmpty { "Nothing playing" }, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
            Text(np.artist, fontSize = 11.sp, color = XDim, maxLines = 1)
            if (np.durationSec > 0) {
                val shown = if (scrub >= 0) scrub.toDouble() else elapsed
                Text("${mmss((shown * 1000).toLong())} / ${mmss((np.durationSec * 1000).toLong())}", fontSize = 12.sp,
                    color = if (scrub >= 0) XAccent2 else Color.White)
                // Turn the bezel to scrub, then tap Seek. AMS has no seek, so this goes through the iPhone app.
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.focusRequester(focus).focusable().onRotaryScrollEvent {
                    val base = if (scrub >= 0) scrub else elapsed.toFloat()
                    scrub = (base + it.verticalScrollPixels / 8f).coerceIn(0f, np.durationSec.toFloat())
                    true
                }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Small("−15") { scrub = ((if (scrub >= 0) scrub else elapsed.toFloat()) - 15f).coerceAtLeast(0f) }
                        Chip(onClick = { if (scrub >= 0) { AmsExtra.seek(scrub.toDouble()); scrub = -1f } },
                            label = { Text(if (scrub >= 0) "Seek" else "Bezel", fontSize = 12.sp) },
                            colors = ChipDefaults.primaryChipColors(), modifier = Modifier.width(76.dp))
                        Small("+15") { scrub = ((if (scrub >= 0) scrub else elapsed.toFloat()) + 15f).coerceAtMost(np.durationSec.toFloat()) }
                    }
                }
                Text("Seek works for Apple Music only", fontSize = 10.sp, color = XDim)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Round("⟲15") { LinkService.media(AmsExtra.CMD_SKIP_BACKWARD) }
                Round(if (np.playing) "❚❚" else "▶") { LinkService.media(Ams.CMD_TOGGLE) }
                Round("15⟳") { LinkService.media(AmsExtra.CMD_SKIP_FORWARD) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Round("♥") { LinkService.media(AmsExtra.CMD_LIKE) }
                Round("✕") { LinkService.media(AmsExtra.CMD_DISLIKE) }
                Round("🔖") { LinkService.media(AmsExtra.CMD_BOOKMARK) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Round("🔁") { LinkService.media(AmsExtra.CMD_REPEAT) }
                Round("🔀") { LinkService.media(AmsExtra.CMD_SHUFFLE) }
            }
            Text("Like, skip 15 s and shuffle work when the iPhone app supports them. To change the iPhone's speaker or AirPods, use the output picker in the iPhone app.",
                fontSize = 10.sp, color = XDim, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 24.dp))
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
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp).focusRequester(focus).focusable()
                .onRotaryScrollEvent { e ->
                    // One bezel click = one slide.
                    acc += e.verticalScrollPixels
                    val t = System.currentTimeMillis()
                    if (abs(acc) >= 40f && t - lastAt > 200) {
                        Presenter.press(if (acc > 0) Presenter.KEY_RIGHT else Presenter.KEY_LEFT)
                        acc = 0f; lastAt = t
                    }
                    true
                }) {
            Text("CLICKER", fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold, color = XAccent2)
            when (state) {
                Presenter.State.OFF -> {
                    val target = targets.getOrNull(chosen)
                    Text(target?.name ?: "No paired device", fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(top = 6.dp))
                    if (targets.size > 1) Text("Tap to change", fontSize = 10.sp, color = XDim,
                        modifier = Modifier.padding(bottom = 2.dp).clip(RoundedCornerShape(8.dp)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        if (targets.size > 1) Small("⇄") { chosen = (chosen + 1) % targets.size }
                        Chip(onClick = { Presenter.start(this@ExtrasActivity, target) }, enabled = target != null,
                            label = { Text("Start") }, colors = ChipDefaults.primaryChipColors())
                    }
                    Text("The watch becomes a Bluetooth keyboard with slide keys. The camera shutter pauses until you stop.",
                        fontSize = 10.sp, color = XDim, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp, 8.dp))
                }
                else -> {
                    Text(when (state) {
                        Presenter.State.CONNECTED -> "Connected to $host"
                        Presenter.State.WAITING -> "Waiting for the device…"
                        else -> "Starting…"
                    }, fontSize = 11.sp, color = XDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { Presenter.press(Presenter.KEY_LEFT) }, modifier = Modifier.size(64.dp),
                            colors = ButtonDefaults.secondaryButtonColors()) { Text("◀", fontSize = 22.sp) }
                        Button(onClick = { Presenter.press(Presenter.KEY_RIGHT) }, modifier = Modifier.size(64.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = XAccent)) { Text("▶", fontSize = 22.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Small("B") { Presenter.press(Presenter.KEY_B) }
                        Small("Esc") { Presenter.press(Presenter.KEY_ESC) }
                        Small("■") { Presenter.stop(this@ExtrasActivity) }
                    }
                    Text("Turn the bezel to change slides", fontSize = 10.sp, color = XDim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    // --- Translate ---

    private fun ScalingLazyListScope.translatePage() {
        item { Title("Translate") }
        item { TranslateBlock() }
    }

    @Composable
    private fun TranslateBlock() {
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Chip(onClick = { toB = !toB }, label = { Text("${from.second} → ${to.second}", maxLines = 1) },
                secondaryLabel = { Text("Tap to swap") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Chip(onClick = {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, from.first)
                    error = if (runCatching { speak.launch(i) }.isSuccess) "" else "No speech input on this watch"
                }, label = { Text("Speak") }, colors = ChipDefaults.primaryChipColors())
                Chip(onClick = {
                    error = if (runCatching { type.launch(textInput("Text to translate")) }.isSuccess) "" else "No keyboard available"
                }, label = { Text("Type") }, colors = ChipDefaults.secondaryChipColors())
            }
            if (pending != null) Text("Translating on iPhone…", fontSize = 12.sp, color = XAccent2, modifier = Modifier.padding(top = 8.dp))
            if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = Color(0xFFFFC542), modifier = Modifier.padding(top = 8.dp))
            result?.let { r ->
                Text(r.source, fontSize = 12.sp, color = XDim, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 0.dp))
                Text(r.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    color = if (r.ok) Color.White else Color(0xFFFFC542), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }

    /** The Wear OS text-input screen (keyboard, handwriting or voice), as RemoteInputIntentHelper builds it. */
    private fun textInput(label: String): Intent =
        Intent("android.support.wearable.input.action.REMOTE_INPUT")
            .putExtra("android.support.wearable.input.extra.REMOTE_INPUTS", arrayOf(RemoteInput.Builder("text").setLabel(label).build()))

    // --- Checklist ---

    private fun ScalingLazyListScope.checklistPage(all: List<Checklist.Item>, listName: String) {
        item { Title(listName.ifEmpty { "Checklist" }) }
        item { AddItem() }
        val items = all.sortedBy { it.done }
        if (items.isEmpty()) item {
            Text("Choose a Reminders list in the iPhone app: Watch → Checklist.", fontSize = 12.sp, color = XDim,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        }
        items(items) { i ->
            Chip(onClick = { Checklist.toggle(this@ExtrasActivity, i.id) },
                label = { Text(i.title, maxLines = 2, color = if (i.done) XDim else Color.White) },
                icon = { Text(if (i.done) "●" else "○", color = XAccent2, fontSize = 16.sp) },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    @Composable
    private fun AddItem() {
        var note by remember { mutableStateOf("") }
        val type = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val t = r.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("text")?.toString() }
            if (!t.isNullOrBlank()) note = if (Checklist.add(t)) "Added on iPhone" else "iPhone not connected"
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Chip(onClick = { runCatching { type.launch(textInput("New item")) } }, label = { Text("Add item") },
                colors = ChipDefaults.primaryChipColors())
            if (note.isNotEmpty()) Text(note, fontSize = 11.sp, color = XAccent2)
        }
    }

    // --- Passes ---

    private fun ScalingLazyListScope.passesPage(passes: List<QrPasses.Pass>) {
        item { Title("Passes") }
        if (passes.isEmpty()) item {
            Text("Send a ticket or boarding pass from the iPhone app: Watch → Passes.", fontSize = 12.sp, color = XDim,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        }
        items(passes) { p -> PassCard(p) }
        item { Spacer(Modifier.height(24.dp)) }
    }

    @Composable
    private fun PassCard(p: QrPasses.Pass) {
        val bmp = remember(p.id) {
            runCatching { BitmapFactory.decodeFile(QrPasses.imageFile(this@ExtrasActivity, p.id).absolutePath)?.asImageBitmap() }.getOrNull()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(p.title, fontWeight = FontWeight.SemiBold)
            if (p.sub.isNotEmpty()) Text(p.sub, fontSize = 11.sp, color = XDim)
            if (bmp != null) Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(6.dp)) {
                Image(bmp, contentDescription = p.title, modifier = Modifier.size(150.dp))
            }
        }
    }

    // --- Calculator & converter ---

    @Composable
    private fun Calculator() {
        var expr by remember { mutableStateOf("") }
        var shown by remember { mutableStateOf("0") }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 24.dp, bottom = 24.dp)) {
            Text(expr.ifEmpty { " " }, fontSize = 11.sp, color = XDim, maxLines = 1)
            Text(shown, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 24.dp, bottom = 24.dp)) {
            Chip(onClick = { unit = (unit + 1) % Calc.CONVERSIONS.size }, label = { Text("${conv.from} → ${conv.to}") },
                secondaryLabel = { Text("Tap for next") }, colors = ChipDefaults.secondaryChipColors())
            Text("${input.ifEmpty { "0" }} ${conv.from}", fontSize = 13.sp, color = XDim)
            Text(value?.let { "${Calc.format(conv.convert(it))} ${conv.to}" } ?: "—", fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
            for (row in keys.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (k in row) Button(onClick = { onKey(k) }, modifier = Modifier.size(34.dp),
                    colors = if (k in setOf("=", "+", "−", "×", "÷")) ButtonDefaults.buttonColors(backgroundColor = XAccent)
                    else ButtonDefaults.secondaryButtonColors()) { Text(k, fontSize = 14.sp) }
            }
        }
    }

    // --- bits ---

    @Composable
    private fun Title(text: String) = Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold,
        color = XAccent2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))

    @Composable
    private fun Label(text: String) = Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = XDim,
        modifier = Modifier.padding(top = 8.dp))

    @Composable
    private fun Nav(label: String, secondary: String, onClick: () -> Unit) = Chip(onClick = onClick, label = { Text(label) },
        secondaryLabel = if (secondary.isEmpty()) null else ({ Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())

    @Composable
    private fun Round(symbol: String, onClick: () -> Unit) = Button(onClick = onClick, modifier = Modifier.size(42.dp),
        colors = ButtonDefaults.secondaryButtonColors()) { Text(symbol, fontSize = 13.sp, color = Color.White) }

    @Composable
    private fun Small(symbol: String, onClick: () -> Unit) = Button(onClick = onClick, modifier = Modifier.size(36.dp),
        colors = ButtonDefaults.secondaryButtonColors()) { Text(symbol, fontSize = 11.sp, color = Color.White) }

    private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

/** Entry chip for the main watch screen (MainActivity home and music pages). */
@Composable
fun ExtrasEntryChip(page: Int = ExtrasActivity.PAGE_HOME, label: String = "Extras", secondary: String = "Music, clicker, translate…") {
    val c = LocalContext.current
    Chip(onClick = { ExtrasActivity.open(c, page) }, label = { Text(label) },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
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
