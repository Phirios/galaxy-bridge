package nz.galaxybridge.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_HOME = 0
        const val PAGE_MUSIC = 1
        const val PAGE_CAMERA = 2
        const val PAGE_FIND = 3
        const val PAGE_SETTINGS = 4
        const val PAGE_WORKOUT = 5
    }

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BODY_SENSORS,
        // Health Services won't deliver passive heart-rate data without this granted on Wear OS 5+.
        "android.permission.health.READ_HEART_RATE",
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startIfAllowed()
    }

    private var page by mutableIntStateOf(PAGE_HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getIntExtra(EXTRA_PAGE, PAGE_HOME)
        setContent { BridgeTheme { AppScaffold { Screen() } } }
        if (missing().isEmpty()) startIfAllowed() else request.launch(missing().toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        page = intent.getIntExtra(EXTRA_PAGE, page)
    }

    private fun missing() = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun startIfAllowed() {
        val needed = setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (missing().any { it in needed }) return
        LinkService.start(this)
        askToIgnoreBatteryOptimisation()
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch { runCatching { Health.register(this@MainActivity); Health.flush(this@MainActivity) } }
        }
    }

    /** Without this Samsung's battery manager stops the link service when the watch idles. */
    private fun askToIgnoreBatteryOptimisation() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    @Composable
    private fun Screen() {
        BackHandler(enabled = page != PAGE_HOME) { page = PAGE_HOME }
        val listState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        LaunchedEffect(page) { listState.scrollToItem(0) }

        val liveWorkout by Workouts.live.collectAsState()
        val finding by LinkService.findingPhone.collectAsState()
        val linkStatus by LinkService.statusFlow().collectAsState()
        val hasPhone by LinkService.hasPhone.collectAsState()
        val pairingUntil by LinkService.pairingUntil.collectAsState()
        val clockSkew by LinkService.clockSkew.collectAsState()
        val zoneMismatch by LinkService.zoneMismatch.collectAsState()
        val weather by LinkService.weather.collectAsState()
        val events by LinkService.events.collectAsState()
        val nextEvent = events.firstOrNull { it.end > System.currentTimeMillis() }
        var workoutError by remember { mutableStateOf<String?>(null) }
        var confirmForget by remember { mutableStateOf(false) }

        val edgeAction: (@Composable () -> Unit)? = when (page) {
            PAGE_HOME -> {
                {
                    EdgeButton(
                        onClick = { page = PAGE_SETTINGS },
                        buttonSize = EdgeButtonSize.Small,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Icon(painterResource(R.drawable.ic_tune), contentDescription = "Settings", modifier = Modifier.size(20.dp))
                    }
                }
            }
            PAGE_MUSIC -> {
                {
                    EdgeButton(
                        onClick = { ExtrasActivity.open(this@MainActivity, ExtrasActivity.PAGE_MEDIA) },
                        buttonSize = EdgeButtonSize.Small,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Text("More")
                    }
                }
            }
            PAGE_WORKOUT -> if (liveWorkout != null) {
                {
                    EdgeButton(
                        onClick = { lifecycleScope.launch { Workouts.stop(this@MainActivity) } },
                        buttonSize = EdgeButtonSize.Small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text("End")
                    }
                }
            } else null
            PAGE_FIND -> {
                {
                    val ok = linkStatus == LinkService.Status.CONNECTED
                    EdgeButton(
                        onClick = { LinkService.findPhone(!finding) },
                        enabled = ok,
                        buttonSize = EdgeButtonSize.Small,
                        colors = if (finding) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) else ButtonDefaults.buttonColors(),
                    ) {
                        Text(if (finding) "Stop" else "Ring")
                    }
                }
            }
            else -> null
        }

        if (edgeAction != null) {
            ScreenScaffold(
                scrollState = listState,
                edgeButton = { edgeAction() },
            ) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    pageContent(spec, liveWorkout, hasPhone, pairingUntil, clockSkew, zoneMismatch, weather, nextEvent, workoutError, { workoutError = it }) {
                        confirmForget = true
                    }
                }
            }
        } else {
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    pageContent(spec, liveWorkout, hasPhone, pairingUntil, clockSkew, zoneMismatch, weather, nextEvent, workoutError, { workoutError = it }) {
                        confirmForget = true
                    }
                }
            }
        }

        AlertDialog(
            visible = confirmForget,
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget iPhone?", textAlign = TextAlign.Center) },
            text = { Text("Notifications and health sync stop until you pair again.", textAlign = TextAlign.Center) },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(
                    onClick = { LinkService.forgetPhone(this@MainActivity); confirmForget = false },
                )
            },
            dismissButton = {
                AlertDialogDefaults.DismissButton(onClick = { confirmForget = false })
            },
            transformationSpec = spec,
        )
    }

    private fun TransformingLazyColumnScope.pageContent(
        spec: TransformationSpec,
        liveWorkout: Workouts.Live?,
        hasPhone: Boolean,
        pairingUntil: Long,
        clockSkew: Long,
        zoneMismatch: String,
        weather: PhoneMessage.Weather?,
        nextEvent: PhoneMessage.Event?,
        workoutError: String?,
        onWorkoutError: (String?) -> Unit,
        onConfirmForget: () -> Unit,
    ) {
        when (page) {
            PAGE_MUSIC -> musicPage(spec)
            PAGE_CAMERA -> cameraPage(spec)
            PAGE_FIND -> findPage(spec)
            PAGE_SETTINGS -> settingsPage(spec, hasPhone, pairingUntil, onConfirmForget)
            PAGE_WORKOUT -> workoutPage(spec, liveWorkout, workoutError, onWorkoutError)
            else -> homePage(spec, hasPhone, pairingUntil, clockSkew, zoneMismatch, weather, nextEvent)
        }
    }

    // --- Home ---

    private fun TransformingLazyColumnScope.homePage(
        spec: TransformationSpec,
        hasPhone: Boolean,
        pairingUntil: Long,
        clockSkew: Long,
        zoneMismatch: String,
        weather: PhoneMessage.Weather?,
        nextEvent: PhoneMessage.Event?,
    ) {
        item { HeroRing() }
        if (pairingUntil > 0) {
            item {
                PairingCard(
                    until = pairingUntil,
                    onCancel = { LinkService.cancelPairing() },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                )
            }
        } else if (!hasPhone) {
            item {
                Button(
                    onClick = { LinkService.openPairing() },
                    label = { Text("Pair iPhone") },
                    secondaryLabel = { Text("Start here") },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                )
            }
        }
        if (clockSkew != 0L || zoneMismatch.isNotEmpty()) {
            item {
                ClockCard(
                    skew = clockSkew,
                    zone = zoneMismatch,
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                )
            }
        }
        if (weather != null || nextEvent != null) {
            item {
                TodayGlance(
                    weather = weather,
                    next = nextEvent,
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Launcher(R.drawable.ic_run, "Workout", BridgeTokens.Good) { page = PAGE_WORKOUT }
                Launcher(R.drawable.ic_health, "Health", BridgeTokens.Energy) { HealthDepthActivity.launch(this@MainActivity) }
                Launcher(R.drawable.ic_music, "Music", MaterialTheme.colorScheme.primary) { page = PAGE_MUSIC }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Launcher(R.drawable.ic_camera, "Camera", MaterialTheme.colorScheme.secondary) { page = PAGE_CAMERA }
                Launcher(R.drawable.ic_phone, "Phone", BridgeTokens.CallAccept) {
                    // The watch's own Samsung Phone app calls through the iPhone over hands-free;
                    // fall back to our dialer only if it is missing.
                    val samsung = packageManager.getLaunchIntentForPackage("com.samsung.android.dialer")
                    startActivity(samsung ?: Intent(this@MainActivity, CallsActivity::class.java))
                }
                Launcher(R.drawable.ic_find, "Find", BridgeTokens.Wait) { page = PAGE_FIND }
            }
        }
        item {
            FilledTonalButton(
                onClick = { ExtrasActivity.open(this@MainActivity, ExtrasActivity.PAGE_HOME) },
                label = { Text("Extras") },
                secondaryLabel = { Text("Music, clicker, translate…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(painterResource(R.drawable.ic_grid), contentDescription = null, modifier = Modifier.size(20.dp)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
    }

    /** Weather and the next calendar event from the iPhone in one card; hidden when neither is known. */
    @Composable
    private fun TodayGlance(
        weather: PhoneMessage.Weather?,
        next: PhoneMessage.Event?,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
    ) {
        val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
        Card(
            onClick = {},
            enabled = false,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            transformation = transformation,
            modifier = modifier,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                weather?.let { w ->
                    Text(weatherSymbol(w.code), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.size(6.dp))
                    Text("${w.tempC.toInt()}°", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (next != null) {
                        Text(
                            if (next.allDay) "All day" else fmt.format(Date(next.start)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                        Text(
                            next.title.ifEmpty { "Event" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else weather?.let { w ->
                        Text(
                            "H ${w.highC.toInt()}° · L ${w.lowC.toInt()}°",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        )
                        Text(
                            w.place.ifEmpty { "Weather" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    /** Apps can't set the system clock, so point the user at Date & time when it's wrong. */
    @Composable
    private fun ClockCard(
        skew: Long,
        zone: String,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
    ) {
        val minutes = kotlin.math.abs(skew) / 60_000
        val off = when {
            skew == 0L -> "Time zone differs from iPhone ($zone)"
            minutes >= 60 * 24 -> "Off by ${minutes / (60 * 24)} days"
            minutes >= 60 -> "Off by ${minutes / 60} h"
            else -> "Off by $minutes min"
        }
        Button(
            onClick = { open(Intent(Settings.ACTION_DATE_SETTINGS)) },
            label = { Text("Watch time is wrong") },
            secondaryLabel = { Text("$off · Tap to fix", maxLines = 2) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            transformation = transformation,
            modifier = modifier,
        )
    }

    // --- Music ---

    private fun TransformingLazyColumnScope.musicPage(spec: TransformationSpec) {
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec),
            ) { Text("Music") }
        }
        item { MusicControls() }
    }

    @Composable
    private fun MusicControls() {
        val np by AppleClient.nowPlaying.collectAsState()
        val ancs by AppleClient.connected.collectAsState()
        val prevIx = remember { MutableInteractionSource() }
        val playIx = remember { MutableInteractionSource() }
        val nextIx = remember { MutableInteractionSource() }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                np.title.ifEmpty { if (np.active) np.player else "Nothing playing" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                np.artist.ifEmpty { if (ancs) "Play something on your iPhone" else "Waiting for iPhone" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            ButtonGroup(modifier = Modifier.fillMaxWidth()) {
                FilledTonalIconButton(
                    onClick = { LinkService.media(Ams.CMD_PREVIOUS) },
                    interactionSource = prevIx,
                    modifier = Modifier.weight(1f).animateWidth(prevIx),
                ) { Text("⏮", style = MaterialTheme.typography.titleSmall) }
                FilledIconButton(
                    onClick = { LinkService.media(Ams.CMD_TOGGLE) },
                    interactionSource = playIx,
                    modifier = Modifier.weight(1.3f).animateWidth(playIx),
                ) { Text(if (np.playing) "❚❚" else "▶", style = MaterialTheme.typography.titleMedium) }
                FilledTonalIconButton(
                    onClick = { LinkService.media(Ams.CMD_NEXT) },
                    interactionSource = nextIx,
                    modifier = Modifier.weight(1f).animateWidth(nextIx),
                ) { Text("⏭", style = MaterialTheme.typography.titleSmall) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(onClick = { LinkService.media(Ams.CMD_VOLUME_DOWN) }) {
                    Text("−", style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalIconButton(onClick = { LinkService.media(Ams.CMD_VOLUME_UP) }) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    // --- Camera ---

    private fun TransformingLazyColumnScope.cameraPage(spec: TransformationSpec) {
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec),
            ) { Text("Camera remote") }
        }
        item { CameraPreviewBox() }
        item { CameraControls() }
        item {
            val remote by HidShutter.connected.collectAsState()
            val status by LinkService.statusFlow().collectAsState()
            ShutterButton(enabled = status == LinkService.Status.CONNECTED || remote) { LinkService.sendShutter() }
        }
        item {
            val remote by HidShutter.connected.collectAsState()
            Text(
                if (remote) "Works in the iPhone Camera app" else "Open the camera in Wear OS Bridge on your iPhone",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }

    @Composable
    private fun CameraPreviewBox() {
        val frame by CameraRemote.frame.collectAsState()
        val open by CameraRemote.open.collectAsState()
        DisposableEffect(Unit) {
            CameraRemote.view(true)
            onDispose { CameraRemote.view(false) }
        }
        val bmp = frame
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = "iPhone camera preview",
                modifier = Modifier.size(140.dp).clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop,
            )
        } else if (open) {
            Text("Loading preview…", style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun CameraControls() {
        val open by CameraRemote.open.collectAsState()
        val timer by CameraRemote.timer.collectAsState()
        if (!open) return
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { CameraRemote.command("flip") }) {
                Text("⟲", style = MaterialTheme.typography.titleSmall)
            }
            FilledTonalIconButton(onClick = { CameraRemote.command("timer") }) {
                Text(if (timer == 0) "⏱" else "${timer}s", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // --- Workout ---

    private fun TransformingLazyColumnScope.workoutPage(
        spec: TransformationSpec,
        current: Workouts.Live?,
        error: String?,
        onError: (String?) -> Unit,
    ) {
        if (current == null) {
            item {
                ListHeader(
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec),
                ) { Text("Workout") }
            }
            val tints = listOf(BridgeTokens.Good, BridgeTokens.Active, BridgeTokens.Wait, Color(0xFFB04CFF), Color(0xFF8E96B8))
            Workouts.Kind.entries.forEachIndexed { i, k ->
                item {
                    FilledTonalButton(
                        onClick = { lifecycleScope.launch { onError(Workouts.start(this@MainActivity, k)) } },
                        label = { Text(k.label) },
                        secondaryLabel = {
                            Text(if (k == Workouts.Kind.OTHER) "Heart rate and calories" else "GPS route, heart rate", maxLines = 1)
                        },
                        icon = {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(tints[i]), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_run), contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            }
                        },
                        transformation = SurfaceTransformation(spec),
                        modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                    )
                }
            }
            error?.let { msg ->
                item {
                    Text(msg, style = MaterialTheme.typography.bodyExtraSmall, color = BridgeTokens.Wait, textAlign = TextAlign.Center)
                }
            }
            item {
                Text(
                    "Saved to Apple Health with your route",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            item {
                ListHeader(
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec),
                ) {
                    Text(current.kind.label + if (current.gps) " · GPS" else "", color = BridgeTokens.Good)
                }
            }
            item { LiveWorkoutBody(current) }
        }
    }

    @Composable
    private fun LiveWorkoutBody(current: Workouts.Live) {
        var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = android.os.SystemClock.elapsedRealtime() } }
        // Active time (excludes pauses); `now` ticks to recompose each second.
        val secs = if (now > 0) Workouts.activeMs() / 1000 else 0
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60),
                style = MaterialTheme.typography.numeralLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "♥ ${current.hr.toInt().takeIf { it > 0 } ?: "--"}   ${"%.2f".format(current.distM / 1000)} km   ${current.kcal.toInt()} kcal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            WorkoutExtras.Controls(
                current,
                onPause = { lifecycleScope.launch { Workouts.pause(this@MainActivity) } },
                onResume = { lifecycleScope.launch { Workouts.resume(this@MainActivity) } },
                onLap = { lifecycleScope.launch { Workouts.lap(this@MainActivity) } },
            )
        }
    }

    // --- Find ---

    private fun TransformingLazyColumnScope.findPage(spec: TransformationSpec) {
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec),
            ) { Text("Find iPhone") }
        }
        item {
            val finding by LinkService.findingPhone.collectAsState()
            val status by LinkService.statusFlow().collectAsState()
            val ok = status == LinkService.Status.CONNECTED
            val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
                1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "ring",
            )
            val cs = MaterialTheme.colorScheme
            val bg = when {
                !ok -> cs.surfaceContainerHigh
                finding -> cs.errorContainer
                else -> cs.primaryContainer
            }
            val fg = when {
                !ok -> cs.onSurfaceVariant
                finding -> cs.onErrorContainer
                else -> cs.onPrimaryContainer
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(84.dp)
                        .scale(if (finding) pulse else 1f)
                        .clip(CircleShape)
                        .background(bg)
                        .clickable(enabled = ok, onClickLabel = if (finding) "Stop" else "Ring") { LinkService.findPhone(!finding) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(R.drawable.ic_find), contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
                        Text(if (finding) "Ringing" else "Ready", style = MaterialTheme.typography.labelSmall, color = fg)
                    }
                }
                if (!ok) {
                    Text(
                        "iPhone not connected",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = BridgeTokens.Wait,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            Text(
                "Your iPhone plays a sound, even on silent if Wear OS Bridge is allowed to send alerts.",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }

    // --- Settings ---

    private fun TransformingLazyColumnScope.settingsPage(
        spec: TransformationSpec,
        hasPhone: Boolean,
        pairingUntil: Long,
        onConfirmForget: () -> Unit,
    ) {
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec),
            ) { Text("Settings") }
        }
        item { SettingSwitch("iPhone notifications", WatchSettings.NOTIFICATIONS, SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
        item { SettingSwitch("Incoming calls", WatchSettings.CALLS, SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
        item { SettingSwitch("Clear on iPhone too", WatchSettings.CLEAR_ON_IPHONE, SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
        item { SettingSwitch("Music controls", WatchSettings.MUSIC, SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
        item {
            ListSubHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec),
            ) { Text("Let iPhone change") }
        }
        item {
            PermissionButton(
                label = "Brightness & timeout",
                check = { WatchSettings.canWriteSystem(it) },
                intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")),
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
        item {
            PermissionButton(
                label = "Do Not Disturb",
                check = { WatchSettings.canChangeDnd(it) },
                intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
        item {
            ListSubHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec),
            ) { Text("Phone") }
        }
        if (hasPhone && pairingUntil == 0L) {
            item {
                FilledTonalButton(
                    onClick = { LinkService.openPairing(); page = PAGE_HOME },
                    label = { Text("Pair new phone") },
                    secondaryLabel = { Text("Replaces this iPhone") },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                )
            }
        }
        item {
            FilledTonalButton(
                onClick = onConfirmForget,
                label = { Text("Forget iPhone") },
                secondaryLabel = { Text(if (hasPhone) "Stops syncing" else "Clears old pairings") },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
        item {
            Text(
                "Wear OS Bridge ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    @Composable
    private fun SettingSwitch(
        label: String,
        key: String,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
    ) {
        val context = LocalContext.current
        val version by WatchSettings.changes.collectAsState()
        val on = remember(version) { WatchSettings.enabled(context, key) }
        SwitchButton(
            checked = on,
            onCheckedChange = { WatchSettings.set(context, key, if (it) 1 else 0) },
            label = { Text(label) },
            transformation = transformation,
            modifier = modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun PermissionButton(
        label: String,
        check: (android.content.Context) -> Boolean,
        intent: Intent,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
    ) {
        val context = LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
        val allowed = remember(tick) { check(context) }
        FilledTonalButton(
            onClick = { open(intent) },
            label = { Text(label) },
            secondaryLabel = { Text(if (allowed) "Allowed" else "Tap to allow") },
            transformation = transformation,
            modifier = modifier,
        )
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
    }
}

/** Round status hero: the arc is the iPhone's battery, the glow shows the link state. */
@Composable
private fun HeroRing() {
    val status by LinkService.statusFlow().collectAsState()
    val battery by LinkService.phoneBattery.collectAsState()
    val ancs by AppleClient.connected.collectAsState()
    val pending by LinkService.pending.collectAsState()
    val connected = status == LinkService.Status.CONNECTED
    val level by animateFloatAsState((battery?.percent ?: 0) / 100f, label = "level")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 0.8f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glow",
    )
    val cs = MaterialTheme.colorScheme
    val glow = if (connected) cs.secondary else cs.primary
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(106.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 7.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.28f * pulse), Color.Transparent)))
                drawArc(cs.outlineVariant.copy(alpha = 0.45f), 0f, 360f, false, style = Stroke(w))
                if (!connected) drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, cs.primary, cs.secondary)),
                    spin, 110f, false, style = Stroke(w, cap = StrokeCap.Round),
                )
                if (connected && battery != null) drawArc(
                    Brush.sweepGradient(listOf(cs.secondary, cs.primary, cs.tertiary, cs.secondary)),
                    -90f, 360f * level, false, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "BRIDGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.secondary,
                )
                val pct = battery?.percent
                if (connected && pct != null) {
                    Text("$pct%", style = MaterialTheme.typography.numeralMedium, color = cs.onBackground)
                } else {
                    Icon(
                        painterResource(R.drawable.ic_stat_iphone),
                        contentDescription = null,
                        tint = cs.onBackground,
                        modifier = Modifier.padding(vertical = 2.dp).size(24.dp),
                    )
                }
                Text(
                    if (battery?.charging == true && connected) "iPhone ⚡" else "iPhone",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = cs.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (connected) BridgeTokens.Good else BridgeTokens.Wait))
                    Spacer(Modifier.size(4.dp))
                    Text(
                        when (status) {
                            LinkService.Status.CONNECTED -> if (ancs) "Linked · alerts" else "Linked"
                            LinkService.Status.WAITING -> "Waiting"
                            LinkService.Status.STOPPED -> "Permissions"
                        },
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = cs.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
        if (pending > 0) {
            Text(
                if (connected) "Syncing $pending to Health" else "$pending waiting for iPhone",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = cs.secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Round M3 Expressive launcher icon button with caption. */
@Composable
private fun Launcher(icon: Int, label: String, accent: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = accent.copy(alpha = 0.24f),
                contentColor = accent,
            ),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PairingCard(
    until: Long,
    onCancel: () -> Unit,
    transformation: SurfaceTransformation,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (System.currentTimeMillis() < until) { now = System.currentTimeMillis(); delay(1000) }
    }
    val seconds = ((until - now) / 1000).coerceAtLeast(0)
    Card(
        onClick = {},
        enabled = false,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        transformation = transformation,
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Ready to pair", style = MaterialTheme.typography.titleSmall)
            Text(
                "On your iPhone: Wear OS Bridge → Find my watch. Check the codes match.",
                style = MaterialTheme.typography.bodyExtraSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "${seconds / 60}:${"%02d".format(seconds % 60)}",
                style = MaterialTheme.typography.numeralSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            FilledTonalButton(
                onClick = onCancel,
                label = { Text("Cancel") },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** WMO weather code (as sent by the iPhone app) to a symbol. */
internal fun weatherSymbol(code: Int) = when (code) {
    0 -> "☀️"
    1, 2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..67, in 80..82 -> "🌧️"
    in 71..77, 85, 86 -> "❄️"
    in 95..99 -> "⛈️"
    else -> "🌡️"
}

/** Big circular shutter with a ring that pulses on press. */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press")
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(pressed) {
        if (pressed) { delay(120); pressed = false }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(92.dp)
            .scale(scale)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = "Take photo") { pressed = true; onClick() }
            .semantics { contentDescription = "Take photo" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = Brush.sweepGradient(listOf(cs.primary, cs.secondary, cs.primary))
            drawCircle(
                brush = ring,
                radius = size.minDimension / 2 - 4.dp.toPx(),
                style = Stroke(width = 5.dp.toPx()),
                alpha = if (enabled) 1f else 0.3f,
            )
            drawCircle(
                color = cs.onBackground.copy(alpha = if (enabled) 1f else 0.25f),
                radius = size.minDimension / 2 - 14.dp.toPx(),
            )
        }
    }
}
