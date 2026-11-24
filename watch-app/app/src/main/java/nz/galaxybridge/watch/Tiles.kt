package nz.galaxybridge.watch

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders as L
import androidx.wear.protolayout.ModifiersBuilders as M
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.ProgressIndicatorColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.card
import androidx.wear.protolayout.material3.circularProgressIndicator
import androidx.wear.protolayout.material3.compactButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.background
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** iPhone Reminders, as sent by the phone. Ticking one off on the watch tells the phone. */
object Reminders {
    data class Item(val id: String, val title: String, val due: Long, val list: String)

    val items = MutableStateFlow<List<Item>>(emptyList())
    private fun prefs(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun receive(s: LinkService, j: JSONObject) {
        val arr = j.optJSONArray("items") ?: return
        if (arr.length() > 50) return
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").take(120)
            if (id.isEmpty()) return@mapNotNull null
            Item(id, Features.clean(o.optString("title")).take(80), o.optLong("due", 0).coerceAtLeast(0),
                Features.clean(o.optString("list")).take(30))
        }
        items.value = list
        prefs(s).edit().putString("items", arr.toString()).apply()
        Tiles.refresh(s)
    }

    fun load(c: Context): List<Item> {
        if (items.value.isEmpty()) {
            val s = prefs(c).getString("items", null) ?: return emptyList()
            val arr = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
            items.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Item(o.optString("id"), o.optString("title"), o.optLong("due"), o.optString("list"))
            }
        }
        return items.value
    }

    fun complete(c: Context, id: String) {
        val left = load(c).filterNot { it.id == id }
        items.value = left
        prefs(c).edit().putString("items", JSONArray(left.map {
            JSONObject().put("id", it.id).put("title", it.title).put("due", it.due).put("list", it.list)
        }).toString()).apply()
        LinkService.sendFeature("reminderDone", JSONObject().put("id", id))
    }
}

object Tiles {
    fun refresh(c: Context) {
        for (cls in listOf(CalendarTile::class.java, RemindersTile::class.java, WeatherTile::class.java,
            PhoneBatteryTile::class.java, NowPlayingTile::class.java)) {
            runCatching { TileService.getUpdater(c).requestUpdate(cls) }
        }
    }

    fun loadClickable(id: String): M.Clickable =
        M.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()

    fun launchClickable(
        c: Context,
        cls: String = MainActivity::class.java.name,
        id: String = "open",
        intExtras: Map<String, Int> = emptyMap(),
    ): M.Clickable {
        val act = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(c.packageName)
            .setClassName(cls)
            .apply {
                for ((k, v) in intExtras) {
                    addKeyToExtraMapping(k, ActionBuilders.AndroidIntExtra.Builder().setValue(v).build())
                }
            }
            .build()
        return M.Clickable.Builder().setId(id).setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(act).build(),
        ).build()
    }

    fun space(h: Float): L.LayoutElement = L.Spacer.Builder().setHeight(dp(h)).build()

    fun widthSpace(w: Float): L.LayoutElement = L.Spacer.Builder().setWidth(dp(w)).build()

    fun column(vararg items: L.LayoutElement, align: Int = L.HORIZONTAL_ALIGN_CENTER): L.LayoutElement =
        L.Column.Builder().setWidth(expand()).setHorizontalAlignment(align)
            .apply { items.forEach { addContent(it) } }.build()

    fun spacedColumn(items: List<L.LayoutElement>, spacing: Float = 4f, align: Int = L.HORIZONTAL_ALIGN_CENTER): L.LayoutElement =
        L.Column.Builder().setWidth(expand()).setHorizontalAlignment(align).apply {
            items.forEachIndexed { idx, el ->
                if (idx > 0) addContent(space(spacing))
                addContent(el)
            }
        }.build()

    fun row(vararg items: L.LayoutElement): L.LayoutElement =
        L.Row.Builder().setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
            .apply { items.forEach { addContent(it) } }.build()

    fun tile(root: L.LayoutElement, freshMs: Long = 15 * 60_000L, resVersion: String = "1"): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion(resVersion)
            .setFreshnessIntervalMillis(freshMs)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()

    /** Wraps a ProtoLayout Material 3 [materialScope] layout with dynamic color + brand fallback. */
    fun materialTile(
        c: Context,
        request: RequestBuilders.TileRequest,
        freshMs: Long = 15 * 60_000L,
        resVersion: String = "1",
        layout: MaterialScope.() -> L.LayoutElement,
    ): TileBuilders.Tile = tile(
        root = materialScope(
            context = c,
            deviceConfiguration = request.deviceConfiguration,
            allowDynamicTheme = true,
            defaultColorScheme = BridgeTileColorScheme,
            layout = layout,
        ),
        freshMs = freshMs,
        resVersion = resVersion,
    )
}

/** Shared plumbing: a tile is a function of the request and the last tapped id. */
abstract class SimpleTile : TileService() {
    abstract fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        PhoneInfo.load(this)
        val clicked = runCatching { requestParams.currentState.lastClickableId }.getOrDefault("")
        val tile = build(requestParams, clicked)
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion("1").build()); "res" }
}

class CalendarTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        val now = System.currentTimeMillis()
        val next = LinkService.events.value.filter { it.end > now }.take(3)
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request) {
            primaryLayout(
                titleSlot = { text("Calendar".layoutString) },
                mainSlot = {
                    if (next.isEmpty()) {
                        text("No more events today".layoutString, typography = Typography.BODY_MEDIUM, color = colorScheme.onSurfaceVariant)
                    } else {
                        val rows = next.map { e ->
                            Tiles.column(
                                text(
                                    (if (e.allDay) "All day" else fmt.format(Date(e.start))).layoutString,
                                    typography = Typography.LABEL_SMALL,
                                    color = colorScheme.secondary,
                                ),
                                text(
                                    e.title.ifEmpty { "Event" }.layoutString,
                                    typography = Typography.TITLE_SMALL,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                ),
                                align = L.HORIZONTAL_ALIGN_START,
                            )
                        }
                        card(
                            onClick = open,
                            modifier = LayoutModifier.background(colorScheme.surfaceContainer),
                            width = expand(),
                            height = wrap(),
                            contentPadding = padding(10f),
                        ) {
                            Tiles.spacedColumn(rows, spacing = 4f, align = L.HORIZONTAL_ALIGN_START)
                        }
                    }
                },
                onClick = open,
            )
        }
    }
}

class RemindersTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        if (clicked.startsWith("done:")) Reminders.complete(this, clicked.removePrefix("done:"))
        val items = Reminders.load(this).take(3)
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request) {
            primaryLayout(
                titleSlot = { text("Reminders".layoutString) },
                mainSlot = {
                    if (items.isEmpty()) {
                        text("All done".layoutString, typography = Typography.BODY_MEDIUM, color = colorScheme.onSurfaceVariant)
                    } else {
                        Tiles.spacedColumn(
                            items.map { r ->
                                compactButton(
                                    onClick = Tiles.loadClickable("done:${r.id}"),
                                    width = expand(),
                                    colors = filledTonalButtonColors(),
                                    labelContent = { text("○  ${r.title.ifEmpty { "Reminder" }}".layoutString, maxLines = 1) },
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

class WeatherTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        val w = LinkService.weather.value
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request) {
            if (w == null) {
                primaryLayout(
                    titleSlot = { text("Weather".layoutString) },
                    mainSlot = {
                        text("Waiting for iPhone".layoutString, typography = Typography.BODY_MEDIUM, color = colorScheme.onSurfaceVariant)
                    },
                    onClick = open,
                )
            } else {
                primaryLayout(
                    titleSlot = { text(w.place.ifEmpty { "Weather" }.layoutString) },
                    mainSlot = {
                        Tiles.column(
                            text(weatherSymbol(w.code).layoutString, typography = Typography.DISPLAY_SMALL),
                            text("${w.tempC.toInt()}°".layoutString, typography = Typography.DISPLAY_LARGE, color = colorScheme.onBackground),
                        )
                    },
                    bottomSlot = {
                        text(
                            "H ${w.highC.toInt()}°  L ${w.lowC.toInt()}°".layoutString,
                            typography = Typography.LABEL_SMALL,
                            color = colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = open,
                )
            }
        }
    }
}

class PhoneBatteryTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        val b = PhoneInfo.battery(this)
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request, freshMs = 5 * 60_000L) {
            val pct = b?.first
            val sub = when {
                b == null -> "Not connected"
                b.second -> "Charging"
                else -> "Battery"
            }
            if (pct != null) {
                L.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
                    .setModifiers(M.Modifiers.Builder().setClickable(open).build())
                    .addContent(
                        circularProgressIndicator(
                            staticProgress = (pct / 100f).coerceIn(0f, 1f),
                            strokeWidth = 8f,
                            colors = ProgressIndicatorColors(
                                indicatorColor = colorScheme.secondary,
                                trackColor = colorScheme.surfaceContainerHigh,
                            ),
                        ),
                    )
                    .addContent(
                        Tiles.column(
                            text("iPhone".layoutString, typography = Typography.LABEL_MEDIUM, color = colorScheme.onSurfaceVariant),
                            text("$pct%".layoutString, typography = Typography.NUMERAL_LARGE, color = colorScheme.onBackground),
                            text(sub.layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.secondary),
                        ),
                    )
                    .build()
            } else {
                primaryLayout(
                    titleSlot = { text("iPhone".layoutString) },
                    mainSlot = {
                        text("--".layoutString, typography = Typography.NUMERAL_LARGE, color = colorScheme.onBackground)
                    },
                    bottomSlot = {
                        text(sub.layoutString, typography = Typography.LABEL_MEDIUM, color = colorScheme.onSurfaceVariant)
                    },
                    onClick = open,
                )
            }
        }
    }
}

class NowPlayingTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        when (clicked) {
            "prev" -> LinkService.media(Ams.CMD_PREVIOUS)
            "toggle" -> LinkService.media(Ams.CMD_TOGGLE)
            "next" -> LinkService.media(Ams.CMD_NEXT)
        }
        val np = AppleClient.nowPlaying.value
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request, freshMs = 60_000L) {
            primaryLayout(
                titleSlot = { text("Now playing".layoutString) },
                mainSlot = {
                    Tiles.column(
                        text(
                            np.title.ifEmpty { "Nothing playing" }.layoutString,
                            typography = Typography.TITLE_SMALL,
                            color = colorScheme.onBackground,
                            maxLines = 2,
                        ),
                        if (np.artist.isNotEmpty()) {
                            text(np.artist.layoutString, typography = Typography.BODY_EXTRA_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1)
                        } else {
                            Tiles.space(2f)
                        },
                        Tiles.space(6f),
                        buttonGroup(height = dp(48f)) {
                            buttonGroupItem {
                                textButton(
                                    onClick = Tiles.loadClickable("prev"),
                                    modifier = LayoutModifier.contentDescription("Previous"),
                                    width = expand(),
                                    height = expand(),
                                    colors = filledTonalButtonColors(),
                                    labelContent = { text("⏮".layoutString) },
                                )
                            }
                            buttonGroupItem {
                                textButton(
                                    onClick = Tiles.loadClickable("toggle"),
                                    modifier = LayoutModifier.contentDescription(if (np.playing) "Pause" else "Play"),
                                    width = expand(),
                                    height = expand(),
                                    colors = filledButtonColors(),
                                    labelContent = { text((if (np.playing) "❚❚" else "▶").layoutString) },
                                )
                            }
                            buttonGroupItem {
                                textButton(
                                    onClick = Tiles.loadClickable("next"),
                                    modifier = LayoutModifier.contentDescription("Next"),
                                    width = expand(),
                                    height = expand(),
                                    colors = filledTonalButtonColors(),
                                    labelContent = { text("⏭".layoutString) },
                                )
                            }
                        },
                    )
                },
                onClick = open,
            )
        }
    }
}
