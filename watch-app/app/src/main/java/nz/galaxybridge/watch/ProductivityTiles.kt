package nz.galaxybridge.watch

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders.argb as protoArgb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders as L
import androidx.wear.protolayout.ModifiersBuilders as M
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.card
import androidx.wear.protolayout.material3.compactButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.background
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// Data pushed from the iPhone
// ---------------------------------------------------------------------------------------------

/**
 * Boarding passes, tickets and other codes, shown on the watch as an image the iPhone rendered
 * (a QR/barcode it generated from the code text, or a cropped screenshot). Not Wallet: these are
 * plain pictures with no payment or pass-signing involved.
 *
 * {"t":"qrPass","id","title","sub","img": base64 PNG/JPEG ≤ 48 KB}
 */
object QrPasses {
    private const val MAX_IMG = 48 * 1024
    private const val MAX_PASSES = 12

    data class Pass(val id: String, val title: String, val sub: String, val at: Long)

    val passes = MutableStateFlow<List<Pass>>(emptyList())

    private fun dir(c: Context) = File(c.filesDir, "passes").apply { mkdirs() }
    fun imageFile(c: Context, id: String) = File(dir(c), "$id.img")

    fun load(c: Context): List<Pass> {
        if (passes.value.isEmpty()) {
            val arr = runCatching { JSONArray(File(dir(c), "index.json").readText()) }.getOrNull() ?: return emptyList()
            passes.value = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Pass(o.optString("id"), o.optString("title"), o.optString("sub"), o.optLong("at"))
                    .takeIf { imageFile(c, it.id).exists() }
            }
        }
        return passes.value
    }

    private fun save(c: Context, list: List<Pass>) {
        passes.value = list
        File(dir(c), "index.json").writeText(JSONArray(list.map {
            JSONObject().put("id", it.id).put("title", it.title).put("sub", it.sub).put("at", it.at)
        }).toString())
        WaveDTiles.refresh(c)
    }

    fun receive(s: LinkService, j: JSONObject) {
        val id = WaveD.id(j.optString("id")) ?: return
        val bytes = runCatching { Base64.decode(j.optString("img"), Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMG) return Features.result(s, "qrPass", false, "Image too large")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth !in 16..1200 || opts.outHeight !in 16..1200 ||
            opts.outMimeType !in setOf("image/png", "image/jpeg")) return Features.result(s, "qrPass", false, "Not a PNG or JPEG")
        imageFile(s, id).writeBytes(bytes)
        val pass = Pass(id, WaveD.text(j.optString("title"), 40).ifEmpty { "Pass" }, WaveD.text(j.optString("sub"), 60),
            System.currentTimeMillis())
        val list = (listOf(pass) + load(s).filterNot { it.id == id }).take(MAX_PASSES)
        load(s).drop(MAX_PASSES - 1).filterNot { p -> list.any { it.id == p.id } }.forEach { imageFile(s, it.id).delete() }
        save(s, list)
        Features.result(s, "qrPass", true, "${pass.title} is on your watch")
        sendList(s)
    }

    fun delete(s: LinkService, raw: String) {
        val id = WaveD.id(raw) ?: return
        imageFile(s, id).delete()
        save(s, load(s).filterNot { it.id == id })
        sendList(s)
    }

    fun sendList(s: LinkService) {
        s.send(WatchMessages.feature("qrPasses", JSONObject().put("items", JSONArray(load(s).map {
            JSONObject().put("id", it.id).put("title", it.title).put("sub", it.sub).put("at", it.at)
        }))))
    }
}

/**
 * Smart-home shortcuts: named scenes the user set up in the iPhone app. Tapping one asks the
 * iPhone to run it (an Apple Shortcut). iOS only lets an app open Shortcuts while it's on screen,
 * so if the phone is locked it shows a notification to tap instead; the reply says which.
 */
object HomeScenes {
    data class Scene(val id: String, val name: String)

    val scenes = MutableStateFlow<List<Scene>>(emptyList())
    val lastResult = MutableStateFlow("")
    private fun prefs(c: Context) = c.getSharedPreferences("homeScenes", Context.MODE_PRIVATE)

    fun receive(s: LinkService, j: JSONObject) {
        val arr = j.optJSONArray("items") ?: return
        if (arr.length() > 8) return
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = WaveD.id(o.optString("id")) ?: return@mapNotNull null
            Scene(id, WaveD.text(o.optString("name"), 30).ifEmpty { return@mapNotNull null })
        }
        scenes.value = list
        prefs(s).edit().putString("items", JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name) }).toString()).apply()
        WaveDTiles.refresh(s)
    }

    fun load(c: Context): List<Scene> {
        if (scenes.value.isEmpty()) {
            val arr = runCatching { JSONArray(prefs(c).getString("items", "[]")) }.getOrNull() ?: return emptyList()
            scenes.value = (0 until arr.length()).map { Scene(arr.getJSONObject(it).optString("id"), arr.getJSONObject(it).optString("name")) }
        }
        return scenes.value
    }

    fun run(c: Context, id: String): Boolean {
        val scene = load(c).firstOrNull { it.id == id } ?: return false
        val ok = LinkService.sendFeature("homeScene", JSONObject().put("id", scene.id))
        lastResult.value = if (ok) "Sent ${scene.name} to iPhone" else "iPhone not connected"
        return ok
    }

    fun result(s: LinkService, j: JSONObject) {
        lastResult.value = WaveD.text(j.optString("detail"), 80)
        WaveDTiles.refresh(s)
    }
}

/** World-clock cities and the translate language pair, set on the iPhone. */
object ToolsConfig {
    data class Clock(val zone: String, val label: String)

    val clocks = MutableStateFlow<List<Clock>>(emptyList())
    /** BCP-47 codes and display names; "a" ⇄ "b" is the pair translate works with. */
    val langA = MutableStateFlow("en" to "English")
    val langB = MutableStateFlow("es" to "Spanish")

    private val LANG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$")
    private fun prefs(c: Context) = c.getSharedPreferences("toolsConfig", Context.MODE_PRIVATE)

    fun receive(s: LinkService, j: JSONObject) {
        prefs(s).edit().putString("json", j.toString().take(8000)).apply()
        apply(j)
        WaveDTiles.refresh(s)
    }

    fun load(c: Context) {
        if (clocks.value.isNotEmpty()) return
        prefs(c).getString("json", null)?.let { runCatching { apply(JSONObject(it)) } }
    }

    private fun apply(j: JSONObject) {
        j.optJSONArray("clocks")?.let { arr ->
            clocks.value = (0 until minOf(arr.length(), 6)).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val zone = o.optString("tz").takeIf { it.length <= 64 && runCatching { ZoneId.of(it) }.isSuccess }
                    ?: return@mapNotNull null
                Clock(zone, WaveD.text(o.optString("label"), 20).ifEmpty { zone.substringAfterLast('/').replace('_', ' ') })
            }
        }
        fun lang(code: String, name: String, into: MutableStateFlow<Pair<String, String>>) {
            val c = j.optString(code)
            if (LANG.matches(c)) into.value = c to WaveD.text(j.optString(name), 24).ifEmpty { c }
        }
        lang("langA", "langAName", langA)
        lang("langB", "langBName", langB)
    }
}

/** Translate: text spoken or typed on the watch, translated by the iPhone (Apple's Translation). */
object Translate {
    data class Result(val id: String, val source: String, val text: String, val ok: Boolean)

    val pending = MutableStateFlow<String?>(null)
    val result = MutableStateFlow<Result?>(null)
    private var lastSource = ""

    /** Returns false if the phone isn't connected. [toB] picks the direction of the pair. */
    fun request(text: String, toB: Boolean): Boolean {
        val clean = WaveD.text(text, 500)
        if (clean.isEmpty()) return false
        val (from, to) = if (toB) ToolsConfig.langA.value.first to ToolsConfig.langB.value.first
        else ToolsConfig.langB.value.first to ToolsConfig.langA.value.first
        val id = UUID.randomUUID().toString()
        val ok = LinkService.sendFeature("translate", JSONObject().put("id", id).put("text", clean).put("from", from).put("to", to))
        if (ok) { pending.value = id; lastSource = clean; result.value = null }
        return ok
    }

    fun result(j: JSONObject) {
        val id = j.optString("id")
        if (id != pending.value) return
        pending.value = null
        result.value = Result(id, lastSource, WaveD.text(j.optString("text"), 1000).ifEmpty { WaveD.text(j.optString("detail"), 120) },
            j.optBoolean("ok", false))
    }
}

/**
 * Two-way checklist: one iPhone Reminders list (incomplete and recently completed items). Ticking
 * on the watch updates Reminders on the iPhone, and changes on the iPhone come straight back.
 * New items can be added from the watch by voice or keyboard.
 */
object Checklist {
    data class Item(val id: String, val title: String, val done: Boolean)

    val list = MutableStateFlow("")
    val items = MutableStateFlow<List<Item>>(emptyList())
    private fun prefs(c: Context) = c.getSharedPreferences("checklist", Context.MODE_PRIVATE)

    fun receive(s: LinkService, j: JSONObject) {
        val arr = j.optJSONArray("items") ?: return
        if (arr.length() > 60) return
        list.value = WaveD.text(j.optString("list"), 30)
        items.value = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").take(120).ifEmpty { return@mapNotNull null }
            Item(id, WaveD.text(o.optString("title"), 80), o.optBoolean("done"))
        }
        prefs(s).edit().putString("json", j.toString()).apply()
        WaveDTiles.refresh(s)
    }

    fun load(c: Context): List<Item> {
        if (items.value.isEmpty()) prefs(c).getString("json", null)?.let { raw ->
            val j = runCatching { JSONObject(raw) }.getOrNull() ?: return@let
            list.value = j.optString("list")
            val arr = j.optJSONArray("items") ?: return@let
            items.value = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Item(o.optString("id"), o.optString("title"), o.optBoolean("done")) } }
        }
        return items.value
    }

    fun toggle(c: Context, id: String) {
        val item = load(c).firstOrNull { it.id == id } ?: return
        items.value = items.value.map { if (it.id == id) it.copy(done = !it.done) else it }
        LinkService.sendFeature("checklistTick", JSONObject().put("id", id).put("done", !item.done))
        WaveDTiles.refresh(c)
    }

    /** Adds a reminder on the iPhone (to the shared list); it comes back with the next update. */
    fun add(text: String): Boolean {
        val t = WaveD.text(text, 80)
        return t.isNotEmpty() && LinkService.sendFeature("reminderAdd", JSONObject().put("title", t).put("list", list.value))
    }
}

/**
 * Which of our tiles are in the watch's tile carousel. Wear OS doesn't let another app add,
 * remove or reorder tiles (Samsung's tile/app-tray editors relay only to the Galaxy Wearable app),
 * so the iPhone can only see this and change our tiles' content.
 */
object TileState {
    val OURS = listOf(
        "CalendarTile" to "Calendar", "RemindersTile" to "Reminders", "WeatherTile" to "Weather",
        "PhoneBatteryTile" to "iPhone battery", "NowPlayingTile" to "Now playing",
        "WorldClockTile" to "World clock", "ToolsTile" to "Tools", "ChecklistTile" to "Checklist",
        "PassesTile" to "Passes", "HomeTile" to "Home",
    )

    fun send(s: LinkService) {
        val future = runCatching { TileService.getActiveTilesAsync(s, s.mainExecutor) }.getOrNull()
        if (future == null) return reply(s, null)
        future.addListener({
            val active = runCatching { future.get().map { it.componentName.className.substringAfterLast('.') }.toSet() }.getOrNull()
            reply(s, active)
        }, s.mainExecutor)
    }

    private fun reply(s: LinkService, active: Set<String>?) {
        val items = JSONArray(OURS.map { (cls, label) ->
            JSONObject().put("cls", cls).put("label", label).apply { if (active != null) put("active", cls in active) }
        })
        s.send(WatchMessages.feature("tileState", JSONObject().put("items", items).put("known", active != null)))
    }
}

// ---------------------------------------------------------------------------------------------
// Tiles
// ---------------------------------------------------------------------------------------------

object WaveDTiles {
    fun refresh(c: Context) {
        for (cls in listOf(WorldClockTile::class.java, ToolsTile::class.java, ChecklistTile::class.java,
            PassesTile::class.java, HomeTile::class.java)) {
            runCatching { TileService.getUpdater(c).requestUpdate(cls) }
        }
    }

    /** A clickable that opens one page of [ExtrasActivity]. */
    fun open(c: Context, page: Int, id: String = "open:$page"): M.Clickable =
        Tiles.launchClickable(c, ExtrasActivity::class.java.name, id, mapOf(ExtrasActivity.EXTRA_PAGE to page))
}

class WorldClockTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        ToolsConfig.load(this)
        val now = Instant.now()
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val today = LocalDate.now()
        val clocks = ToolsConfig.clocks.value.take(4)
        val open = WaveDTiles.open(this, ExtrasActivity.PAGE_HOME)
        return Tiles.materialTile(this, request, freshMs = 60_000L) {
            primaryLayout(
                titleSlot = { text("World clock".layoutString) },
                mainSlot = {
                    if (clocks.isEmpty()) {
                        text(
                            "Add cities in the iPhone app".layoutString,
                            typography = Typography.BODY_SMALL,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    } else {
                        val rows = clocks.map { cl ->
                            val z = now.atZone(ZoneId.of(cl.zone))
                            val dayDiff = z.toLocalDate().toEpochDay() - today.toEpochDay()
                            val day = when {
                                dayDiff > 0 -> " +1"
                                dayDiff < 0 -> " −1"
                                else -> ""
                            }
                            Tiles.row(
                                text(cl.label.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1),
                                Tiles.widthSpace(8f),
                                text((z.format(fmt) + day).layoutString, typography = Typography.NUMERAL_EXTRA_SMALL, color = colorScheme.onSurface),
                            )
                        }
                        card(
                            onClick = open,
                            modifier = LayoutModifier.background(colorScheme.surfaceContainer),
                            width = expand(),
                            height = wrap(),
                            contentPadding = padding(10f),
                        ) {
                            Tiles.spacedColumn(rows, spacing = 3f)
                        }
                    }
                },
                onClick = open,
            )
        }
    }
}

class ToolsTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        val open = WaveDTiles.open(this, ExtrasActivity.PAGE_HOME)
        val goTranslate = WaveDTiles.open(this, ExtrasActivity.PAGE_TRANSLATE, "go:${ExtrasActivity.PAGE_TRANSLATE}")
        val goCalc = WaveDTiles.open(this, ExtrasActivity.PAGE_CALC, "go:${ExtrasActivity.PAGE_CALC}")
        val goConvert = WaveDTiles.open(this, ExtrasActivity.PAGE_CONVERT, "go:${ExtrasActivity.PAGE_CONVERT}")
        return Tiles.materialTile(this, request) {
            primaryLayout(
                titleSlot = { text("Tools".layoutString) },
                mainSlot = {
                    Tiles.spacedColumn(
                        listOf(
                            compactButton(
                                onClick = goTranslate,
                                width = expand(),
                                colors = filledTonalButtonColors(),
                                labelContent = { text("Translate".layoutString, maxLines = 1) },
                            ),
                            compactButton(
                                onClick = goCalc,
                                width = expand(),
                                colors = filledTonalButtonColors(),
                                labelContent = { text("Calculator".layoutString, maxLines = 1) },
                            ),
                            compactButton(
                                onClick = goConvert,
                                width = expand(),
                                colors = filledTonalButtonColors(),
                                labelContent = { text("Convert".layoutString, maxLines = 1) },
                            ),
                        ),
                        spacing = 4f,
                    )
                },
                onClick = open,
            )
        }
    }
}

class ChecklistTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        if (clicked.startsWith("tick:")) Checklist.toggle(this, clicked.removePrefix("tick:"))
        val items = Checklist.load(this).sortedBy { it.done }.take(3)
        val open = WaveDTiles.open(this, ExtrasActivity.PAGE_CHECKLIST)
        return Tiles.materialTile(this, request) {
            primaryLayout(
                titleSlot = { text(Checklist.list.value.ifEmpty { "Checklist" }.layoutString) },
                mainSlot = {
                    if (items.isEmpty()) {
                        text(
                            "Pick a list in the iPhone app".layoutString,
                            typography = Typography.BODY_SMALL,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    } else {
                        Tiles.spacedColumn(
                            items.map { i ->
                                val mark = if (i.done) "●" else "○"
                                compactButton(
                                    onClick = Tiles.loadClickable("tick:${i.id}"),
                                    width = expand(),
                                    colors = filledTonalButtonColors(),
                                    labelContent = {
                                        text(
                                            "$mark  ${i.title.ifEmpty { "Item" }}".layoutString,
                                            color = if (i.done) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                                            maxLines = 1,
                                        )
                                    },
                                )
                            },
                            spacing = 4f,
                        )
                    }
                },
                onClick = open,
            )
        }
    }
}

class HomeTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        if (clicked.startsWith("scene:")) HomeScenes.run(this, clicked.removePrefix("scene:"))
        val scenes = HomeScenes.load(this).take(4)
        val status = HomeScenes.lastResult.value
        val open = WaveDTiles.open(this, ExtrasActivity.PAGE_HOME)
        return Tiles.materialTile(this, request, freshMs = 5 * 60_000L) {
            primaryLayout(
                titleSlot = { text("Home".layoutString) },
                mainSlot = {
                    if (scenes.isEmpty()) {
                        text(
                            "Add shortcuts in the iPhone app".layoutString,
                            typography = Typography.BODY_SMALL,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    } else if (scenes.size == 4) {
                        Tiles.spacedColumn(
                            scenes.chunked(2).map { pair ->
                                buttonGroup(height = wrap()) {
                                    for (s in pair) {
                                        buttonGroupItem {
                                            compactButton(
                                                onClick = Tiles.loadClickable("scene:${s.id}"),
                                                width = expand(),
                                                colors = filledTonalButtonColors(),
                                                labelContent = { text(s.name.layoutString, maxLines = 1) },
                                            )
                                        }
                                    }
                                }
                            },
                            spacing = 4f,
                        )
                    } else {
                        Tiles.spacedColumn(
                            scenes.map { s ->
                                compactButton(
                                    onClick = Tiles.loadClickable("scene:${s.id}"),
                                    width = expand(),
                                    colors = filledTonalButtonColors(),
                                    labelContent = { text(s.name.layoutString, maxLines = 1) },
                                )
                            },
                            spacing = 4f,
                        )
                    }
                },
                bottomSlot = if (status.isNotEmpty()) {
                    { text(status.layoutString, typography = Typography.BODY_EXTRA_SMALL, color = colorScheme.secondary, maxLines = 1) }
                } else null,
                onClick = open,
            )
        }
    }
}

/** Shows the newest pass's image right on the tile; tap for full screen at full brightness. */
class PassesTile : SimpleTile() {
    @Suppress("DEPRECATION")
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        val pass = QrPasses.load(this).firstOrNull()
        val open = WaveDTiles.open(this, ExtrasActivity.PAGE_PASSES)
        if (pass == null) {
            return Tiles.materialTile(this, request) {
                primaryLayout(
                    titleSlot = { text("Passes".layoutString) },
                    mainSlot = {
                        text(
                            "Send a ticket or boarding pass from the iPhone app".layoutString,
                            typography = Typography.BODY_SMALL,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 3,
                        )
                    },
                    onClick = open,
                )
            }
        }
        val img = L.Image.Builder()
            .setResourceId("pass")
            .setWidth(dp(106f))
            .setHeight(dp(106f))
            .setContentScaleMode(L.CONTENT_SCALE_MODE_FIT)
            .build()
        return Tiles.materialTile(this, request, resVersion = pass.id) {
            primaryLayout(
                titleSlot = { text(pass.title.layoutString) },
                mainSlot = {
                    L.Box.Builder()
                        .setModifiers(
                            M.Modifiers.Builder()
                                .setBackground(
                                    M.Background.Builder()
                                        .setColor(protoArgb(0xFFFFFFFF.toInt()))
                                        .setCorner(M.Corner.Builder().setRadius(dp(10f)).build())
                                        .build(),
                                )
                                .setPadding(M.Padding.Builder().setAll(dp(5f)).build())
                                .build(),
                        )
                        .addContent(img)
                        .build()
                },
                bottomSlot = if (pass.sub.isNotEmpty()) {
                    { text(pass.sub.layoutString, typography = Typography.BODY_EXTRA_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1) }
                } else null,
                onClick = open,
            )
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val pass = QrPasses.load(this).firstOrNull()
        val res = ResourceBuilders.Resources.Builder().setVersion(pass?.id ?: "1")
        if (pass != null) runCatching {
            val bytes = QrPasses.imageFile(this, pass.id).readBytes()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            res.addIdToImageMapping(
                "pass",
                ResourceBuilders.ImageResource.Builder().setInlineResource(
                    ResourceBuilders.InlineImageResource.Builder()
                        .setData(bytes)
                        .setWidthPx(opts.outWidth)
                        .setHeightPx(opts.outHeight)
                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                        .build(),
                ).build(),
            )
        }
        val built = res.build()
        return CallbackToFutureAdapter.getFuture { it.set(built); "res" }
    }
}
