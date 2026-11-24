package nz.galaxybridge.watch

import android.content.Context
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders as L
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.compactButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService

/**
 * "Calls & SOS" tile: up to three favourites, the last call to call back (one tap each), and an
 * SOS edge button that opens the cancellable SOS countdown.
 */
class CallsTile : SimpleTile() {
    companion object {
        fun refresh(c: Context) { runCatching { TileService.getUpdater(c).requestUpdate(CallsTile::class.java) } }
        private const val GREEN = 0xFF1E7A4F.toInt()
        private const val RED = 0xFFC9303A.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        /** A tap reaches us once; the refresh after it must never dial again. */
        private var lastDialAt = 0L
    }

    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        CallsSafety.load(this)
        val favs = CallsSafety.favourites.value.take(3)
        val last = CallLog.load(this).firstOrNull { it.number.isNotEmpty() }
        var status = ""
        val now = System.currentTimeMillis()
        val tapped = (clicked.startsWith("fav:") || clicked == "back") && now - lastDialAt > 5_000
        if (tapped) lastDialAt = now
        if (tapped) when {
            clicked.startsWith("fav:") -> favs.getOrNull(clicked.removePrefix("fav:").toIntOrNull() ?: -1)?.let {
                status = if (CallLog.dial(this, it.number, it.name)) "Calling ${it.name}…" else "iPhone not connected"
            }
            clicked == "back" && last != null ->
                status = if (CallLog.dial(this, last.number, last.name)) "Calling ${last.label}…" else "iPhone not connected"
        }

        val favColors = ButtonColors(containerColor = GREEN.argb, labelColor = WHITE.argb)
        val sosColors = ButtonColors(containerColor = RED.argb, labelColor = WHITE.argb)
        val open = Tiles.launchClickable(this)
        val sosClick = Tiles.launchClickable(this, SosActivity::class.java.name, id = "sos")

        return Tiles.materialTile(this, request) {
            primaryLayout(
                titleSlot = {
                    text(
                        status.ifEmpty { "Calls" }.layoutString,
                        color = if (status.isEmpty()) colorScheme.onBackground else colorScheme.secondary,
                    )
                },
                mainSlot = {
                    if (favs.isEmpty() && last == null) {
                        text(
                            "Add favourites on your iPhone".layoutString,
                            typography = Typography.BODY_SMALL,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    } else {
                        val rows = ArrayList<L.LayoutElement>()
                        if (favs.size >= 2) {
                            rows += buttonGroup(height = androidx.wear.protolayout.DimensionBuilders.wrap()) {
                                favs.forEachIndexed { i, f ->
                                    buttonGroupItem {
                                        compactButton(
                                            onClick = Tiles.loadClickable("fav:$i"),
                                            width = expand(),
                                            colors = favColors,
                                            labelContent = { text(f.name.layoutString, maxLines = 1) },
                                        )
                                    }
                                }
                            }
                        } else if (favs.size == 1) {
                            rows += compactButton(
                                onClick = Tiles.loadClickable("fav:0"),
                                width = expand(),
                                colors = favColors,
                                labelContent = { text(favs[0].name.layoutString, maxLines = 1) },
                            )
                        }
                        if (last != null) {
                            rows += compactButton(
                                onClick = Tiles.loadClickable("back"),
                                width = expand(),
                                colors = filledTonalButtonColors(),
                                labelContent = { text("↩  ${last.label}".layoutString, maxLines = 1) },
                            )
                        }
                        Tiles.spacedColumn(rows, spacing = 4f)
                    }
                },
                bottomSlot = {
                    textEdgeButton(
                        onClick = sosClick,
                        modifier = LayoutModifier.contentDescription("Start SOS countdown"),
                        colors = sosColors,
                        labelContent = { text("SOS".layoutString) },
                    )
                },
                onClick = open,
            )
        }
    }
}
