package nz.galaxybridge.watch

import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * "Phone" on the watch: favourites, recents with one-tap call-back, a keypad, the watch's copy of
 * the iPhone contacts, Siri quick replies, loud find-my-iPhone and a hold-to-start SOS button.
 * Calls are placed over HFP on the iPhone (see [CallLog.dial]); NEEDS LIVE TEST with an HFP bond.
 */
class CallsActivity : ComponentActivity() {
    private enum class Page { HOME, KEYPAD, RECENTS, CONTACTS, REPLIES }

    private var page by mutableStateOf(Page.HOME)
    private var toast by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallsSafety.load(this)
        CallLog.load(this)
        setContent { BridgeTheme { AppScaffold { Screen() } } }
    }

    private fun call(number: String, name: String = "") {
        toast = if (CallLog.dial(this, number, name)) "Calling ${name.ifEmpty { number }}…" else "iPhone not connected"
    }

    @Composable
    private fun Screen() {
        BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
        val listState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        LaunchedEffect(page) { listState.scrollToItem(0) }
        LaunchedEffect(toast) { if (toast != null) { delay(2_500); toast = null } }

        val favs by CallsSafety.favourites.collectAsState()
        val log by CallLog.entries.collectAsState()
        val quickReplies by CallsSafety.quickReplies.collectAsState()
        var people by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

        LaunchedEffect(page) {
            if (page == Page.CONTACTS && people == null) {
                people = withContext(Dispatchers.IO) {
                    if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        return@withContext emptyList()
                    }
                    runCatching {
                        contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ),
                            null,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
                        )?.use { c ->
                            val out = ArrayList<Pair<String, String>>()
                            while (c.moveToNext() && out.size < 300) out += (c.getString(0) ?: "") to (c.getString(1) ?: "")
                            out
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }
        }

        val clearRecentsEdge: (@Composable () -> Unit)? = if (page == Page.RECENTS && log.isNotEmpty()) {
            {
                EdgeButton(
                    onClick = { CallLog.clear(this@CallsActivity) },
                    buttonSize = EdgeButtonSize.Small,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text("Clear") }
            }
        } else null

        if (clearRecentsEdge != null) {
            ScreenScaffold(scrollState = listState, edgeButton = { clearRecentsEdge() }) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    pageItems(spec, favs, log, quickReplies, people)
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
                    pageItems(spec, favs, log, quickReplies, people)
                }
            }
        }
    }

    private fun TransformingLazyColumnScope.pageItems(
        spec: TransformationSpec,
        favs: List<CallsSafety.Favourite>,
        log: List<CallLog.Entry>,
        quickReplies: List<String>,
        people: List<Pair<String, String>>?,
    ) {
        toast?.let { t ->
            item {
                Text(
                    t,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        when (page) {
            Page.HOME -> home(spec, favs)
            Page.KEYPAD -> item { Keypad() }
            Page.RECENTS -> recents(spec, log)
            Page.CONTACTS -> contacts(spec, people)
            Page.REPLIES -> replies(spec, quickReplies)
        }
    }

    private fun TransformingLazyColumnScope.header(spec: TransformationSpec, text: String) = item {
        ListHeader(
            transformation = SurfaceTransformation(spec),
            modifier = Modifier.transformedHeight(this, spec),
        ) { Text(text) }
    }

    private fun TransformingLazyColumnScope.home(
        spec: TransformationSpec,
        favs: List<CallsSafety.Favourite>,
    ) {
        header(spec, "Phone")
        if (favs.isEmpty()) {
            item {
                Text(
                    "Pick favourites in Wear OS Bridge on your iPhone",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        } else {
            items(favs) { f ->
                CallButton(
                    name = f.name,
                    number = f.number,
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                ) { call(f.number, f.name) }
            }
        }
        item {
            Nav("Recents", "Call back", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = Page.RECENTS }
        }
        item {
            Nav("Keypad", "Dial a number", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = Page.KEYPAD }
        }
        item {
            Nav("Contacts", "From your iPhone", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = Page.CONTACTS }
        }
        item {
            Nav("Quick replies", "Reply with Siri", SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) { page = Page.REPLIES }
        }
        item {
            FilledTonalButton(
                onClick = {
                    Siri.start(this@CallsActivity) { ok ->
                        runOnUiThread { toast = if (ok) "Siri is listening" else "Siri isn't reachable" }
                    }
                },
                label = { Text("Siri") },
                secondaryLabel = { Text("Over your iPhone's hands-free link", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
        item {
            FilledTonalButton(
                onClick = {
                    toast = if (LinkService.sendFeature("findLoud", org.json.JSONObject().put("on", true).put("side", "phone")))
                        "Ringing your iPhone, getting louder" else "iPhone not connected"
                },
                label = { Text("Find iPhone (loud)") },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
        item { SosHold() }
        item {
            Text(
                "Calls use your iPhone over its hands-free (Bluetooth) link.",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }

    private fun TransformingLazyColumnScope.recents(
        spec: TransformationSpec,
        log: List<CallLog.Entry>,
    ) {
        header(spec, "Recents")
        if (log.isEmpty()) {
            item {
                Text("No calls yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        items(log) { e ->
            val arrow = when (e.dir) {
                CallLog.OUT -> "↗"
                CallLog.MISSED -> "✕"
                else -> "↙"
            }
            FilledTonalButton(
                onClick = { if (e.number.isNotEmpty()) call(e.number, e.name) else toast = "No number for ${e.label}" },
                label = {
                    Text(
                        e.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (e.dir == CallLog.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                },
                secondaryLabel = { Text("$arrow  ${fmt.format(Date(e.at))}") },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
    }

    private fun TransformingLazyColumnScope.replies(
        spec: TransformationSpec,
        replies: List<String>,
    ) {
        header(spec, "Quick replies")
        item {
            Text(
                "Tap one: Siri starts on your iPhone. Say \"Reply\" and the words; iOS composes and sends it.",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        items(replies) { r ->
            FilledTonalButton(
                onClick = { reply(r) },
                label = { Text(r, maxLines = 2) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
            )
        }
    }

    private fun TransformingLazyColumnScope.contacts(
        spec: TransformationSpec,
        people: List<Pair<String, String>>?,
    ) {
        header(spec, "Contacts")
        when {
            people == null -> item {
                Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            people.isEmpty() -> item {
                Text(
                    "No contacts. Copy them from your iPhone: Wear OS Bridge → Contacts.",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            else -> items(people) { (name, number) ->
                CallButton(
                    name = name,
                    number = number,
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec).fillMaxWidth(),
                ) { call(number, name) }
            }
        }
    }

    /** Starts Siri and shows the phrase to say; also offers it to the iPhone as a prefilled message. */
    private fun reply(text: String) {
        Siri.start(this) { ok ->
            runOnUiThread {
                toast = if (ok) "Say: \"Reply $text\"" else "Siri isn't reachable; sent to your iPhone instead"
            }
        }
        LinkService.sendFeature("quickReply", org.json.JSONObject().put("text", text))
    }

    @Composable
    private fun CallButton(
        name: String,
        number: String,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        FilledTonalButton(
            onClick = onClick,
            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            secondaryLabel = { Text(number, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            icon = {
                Box(Modifier.size(26.dp).clip(CircleShape).background(BridgeTokens.CallAccept), contentAlignment = Alignment.Center) {
                    Text("✆", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            },
            transformation = transformation,
            modifier = modifier,
        )
    }

    @Composable
    private fun Nav(
        label: String,
        sub: String,
        transformation: SurfaceTransformation,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        FilledTonalButton(
            onClick = onClick,
            label = { Text(label) },
            secondaryLabel = { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            transformation = transformation,
            modifier = modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Keypad() {
        var number by remember { mutableStateOf("") }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                number.ifEmpty { "Enter number" },
                style = if (number.isEmpty()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.numeralSmall,
                color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            for (row in listOf("123", "456", "789", "+0⌫")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in row) {
                        TextButton(
                            onClick = {
                                number = when (k) {
                                    '⌫' -> number.dropLast(1)
                                    else -> (number + k).take(20)
                                }
                            },
                            colors = TextButtonDefaults.filledTonalTextButtonColors(),
                            modifier = Modifier.size(38.dp),
                        ) { Text(k.toString(), style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
            FilledIconButton(
                onClick = { call(number) },
                enabled = CallsSafety.isDialable(number),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = BridgeTokens.CallAccept,
                    contentColor = Color.White,
                ),
                modifier = Modifier.padding(top = 2.dp).size(48.dp),
            ) { Text("✆", style = MaterialTheme.typography.titleMedium) }
        }
    }

    /** Hold for 2 seconds to start the SOS countdown (which can still be cancelled). */
    @Composable
    private fun SosHold() {
        val scope = rememberCoroutineScope()
        var progress by remember { mutableFloatStateOf(0f) }
        var holds by remember { mutableIntStateOf(0) }
        val cs = MaterialTheme.colorScheme
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 152.dp, height = 46.dp)
                    .clip(CircleShape)
                    .background(cs.errorDim.copy(alpha = 0.40f + 0.60f * progress))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            holds++
                            val job = scope.launch {
                                val steps = 20
                                for (i in 1..steps) { delay(100); progress = i / steps.toFloat() }
                                SafetyMonitor.trigger(this@CallsActivity, SafetyMonitor.Kind.SOS)
                            }
                            tryAwaitRelease()
                            job.cancel()
                            progress = 0f
                        })
                    },
            ) {
                Text(
                    if (progress > 0f) "Keep holding…" else "Hold for SOS",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Text(
                if (holds > 0 && progress == 0f) "Hold for 2 seconds" else "Counts down, then calls your emergency contact",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
