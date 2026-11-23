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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Teal = Color(0xFF2ED1DB)
private val Green = Color(0xFF30A46C)
private val Red = Color(0xFFE5484D)
private val Dim = Color.White.copy(alpha = 0.6f)

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
        setContent { MaterialTheme { Screen() } }
    }

    private fun call(number: String, name: String = "") {
        toast = if (CallLog.dial(this, number, name)) "Calling ${name.ifEmpty { number }}…" else "iPhone not connected"
    }

    @Composable
    private fun Screen() {
        BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
        val list = rememberScalingLazyListState()
        LaunchedEffect(page) { list.scrollToItem(0) }
        LaunchedEffect(toast) { if (toast != null) { delay(2_500); toast = null } }
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = list) },
            modifier = Modifier.background(Color.Black).background(Brush.radialGradient(
                listOf(Color(0xFF0E3B2E), Color(0xFF0B1F33), Color.Black), radius = 420f)),
        ) {
            ScalingLazyColumn(state = list, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                toast?.let { t -> item { Text(t, fontSize = 12.sp, color = Teal, textAlign = TextAlign.Center) } }
                when (page) {
                    Page.HOME -> home()
                    Page.KEYPAD -> item { Keypad() }
                    Page.RECENTS -> recents()
                    Page.CONTACTS -> item { Contacts() }
                    Page.REPLIES -> replies()
                }
            }
        }
    }

    private fun ScalingLazyListScope.title(text: String) = item {
        Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold, color = Teal,
            modifier = Modifier.padding(top = 22.dp, bottom = 2.dp))
    }

    private fun ScalingLazyListScope.home() {
        title("Phone")
        item {
            val favs by CallsSafety.favourites.collectAsState()
            if (favs.isEmpty()) Text("Pick favourites in Wear OS Bridge on your iPhone", fontSize = 11.sp, color = Dim,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
        }
        item {
            val favs by CallsSafety.favourites.collectAsState()
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in favs) CallChip(f.name, f.number) { call(f.number, f.name) }
            }
        }
        item { Nav("Recents", "Call back") { page = Page.RECENTS } }
        item { Nav("Keypad", "Dial a number") { page = Page.KEYPAD } }
        item { Nav("Contacts", "From your iPhone") { page = Page.CONTACTS } }
        item { Nav("Quick replies", "Reply with Siri") { page = Page.REPLIES } }
        item {
            Chip(onClick = { Siri.start(this@CallsActivity) { ok -> runOnUiThread { toast = if (ok) "Siri is listening" else "Siri isn't reachable" } } },
                label = { Text("Siri") }, secondaryLabel = { Text("Over your iPhone's hands-free link") },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = {
                toast = if (LinkService.sendFeature("findLoud", org.json.JSONObject().put("on", true).put("side", "phone")))
                    "Ringing your iPhone, getting louder" else "iPhone not connected"
            }, label = { Text("Find iPhone (loud)") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        item { SosHold() }
        item {
            Text("Calls use your iPhone over its hands-free (Bluetooth) link.", fontSize = 10.sp, color = Dim,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }

    private fun ScalingLazyListScope.recents() {
        title("Recents")
        item {
            val log by CallLog.entries.collectAsState()
            if (log.isEmpty()) Text("No calls yet", fontSize = 12.sp, color = Dim)
        }
        item {
            val log by CallLog.entries.collectAsState()
            val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in log) {
                    val arrow = when (e.dir) { CallLog.OUT -> "↗"; CallLog.MISSED -> "✕"; else -> "↙" }
                    Chip(onClick = { if (e.number.isNotEmpty()) call(e.number, e.name) else toast = "No number for ${e.label}" },
                        label = { Text(e.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (e.dir == CallLog.MISSED) Color(0xFFFF8A8A) else Color.White) },
                        secondaryLabel = { Text("$arrow  ${fmt.format(Date(e.at))}", color = Dim) },
                        colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            val log by CallLog.entries.collectAsState()
            if (log.isNotEmpty()) Chip(onClick = { CallLog.clear(this@CallsActivity) }, label = { Text("Clear") },
                colors = ChipDefaults.childChipColors())
        }
    }

    private fun ScalingLazyListScope.replies() {
        title("Quick replies")
        item {
            Text("Tap one: Siri starts on your iPhone. Say \"Reply\" and the words; iOS composes and sends it.",
                fontSize = 10.sp, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp))
        }
        item {
            val replies by CallsSafety.quickReplies.collectAsState()
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in replies) Chip(onClick = { reply(r) }, label = { Text(r, maxLines = 2) },
                    colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
            }
        }
    }

    /** Starts Siri and shows the phrase to say; also offers it to the iPhone as a prefilled message. */
    private fun reply(text: String) {
        Siri.start(this) { ok -> runOnUiThread { toast = if (ok) "Say: \"Reply $text\"" else "Siri isn't reachable; sent to your iPhone instead" } }
        LinkService.sendFeature("quickReply", org.json.JSONObject().put("text", text))
    }

    @Composable
    private fun CallChip(name: String, number: String, onClick: () -> Unit) {
        Chip(onClick = onClick, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            secondaryLabel = { Text(number, maxLines = 1, color = Dim) },
            icon = { Box(Modifier.size(24.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                Text("✆", fontSize = 13.sp, color = Color.White) } },
            colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun Nav(label: String, sub: String, onClick: () -> Unit) {
        Chip(onClick = onClick, label = { Text(label) }, secondaryLabel = { Text(sub, maxLines = 1) },
            colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun Keypad() {
        var number by remember { mutableStateOf("") }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 24.dp)) {
            Text(number.ifEmpty { "Enter number" }, fontSize = if (number.isEmpty()) 13.sp else 18.sp,
                color = if (number.isEmpty()) Dim else Color.White, maxLines = 1)
            for (row in listOf("123", "456", "789", "+0⌫")) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in row) Button(onClick = {
                    number = when (k) { '⌫' -> number.dropLast(1); else -> (number + k).take(20) }
                }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(38.dp)) { Text(k.toString(), fontSize = 16.sp) }
            }
            Button(onClick = { call(number) }, enabled = CallsSafety.isDialable(number),
                colors = ButtonDefaults.buttonColors(backgroundColor = Green), modifier = Modifier.size(48.dp)) { Text("✆", fontSize = 20.sp) }
        }
    }

    @Composable
    private fun Contacts() {
        var people by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
        LaunchedEffect(Unit) {
            people = withContext(Dispatchers.IO) {
                if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
                runCatching {
                    contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
                        val out = ArrayList<Pair<String, String>>()
                        while (c.moveToNext() && out.size < 300) out += (c.getString(0) ?: "") to (c.getString(1) ?: "")
                        out
                    } ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 24.dp)) {
            val p = people
            when {
                p == null -> Text("Loading…", color = Dim)
                p.isEmpty() -> Text("No contacts. Copy them from your iPhone: Wear OS Bridge → Contacts.", fontSize = 11.sp,
                    color = Dim, textAlign = TextAlign.Center)
                else -> for ((name, number) in p) CallChip(name, number) { call(number, name) }
            }
        }
    }

    /** Hold for 2 seconds to start the SOS countdown (which can still be cancelled). */
    @Composable
    private fun SosHold() {
        val scope = rememberCoroutineScope()
        var progress by remember { mutableFloatStateOf(0f) }
        var holds by remember { mutableIntStateOf(0) }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 6.dp).size(width = 150.dp, height = 46.dp)
            .clip(CircleShape).background(Red.copy(alpha = 0.35f + 0.65f * progress))
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
            }) {
            Text(if (progress > 0f) "Keep holding…" else "Hold for SOS", fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(if (holds > 0 && progress == 0f) "Hold for 2 seconds" else "Counts down, then calls your emergency contact",
            fontSize = 10.sp, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp).height(28.dp))
    }
}
