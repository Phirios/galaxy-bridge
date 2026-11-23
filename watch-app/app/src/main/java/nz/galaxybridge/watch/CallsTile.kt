package nz.galaxybridge.watch

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders as L
import androidx.wear.protolayout.ModifiersBuilders as M
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService

/**
 * "Calls & SOS" tile: up to three favourites, the last call to call back (one tap each), and an
 * SOS chip that opens the cancellable SOS countdown.
 */
class CallsTile : SimpleTile() {
    companion object {
        fun refresh(c: Context) { runCatching { TileService.getUpdater(c).requestUpdate(CallsTile::class.java) } }
        private const val GREEN = 0xFF1E7A4F.toInt()
        private const val RED = 0xFFC9303A.toInt()
        /** A tap reaches us once; the refresh after it must never dial again. */
        private var lastDialAt = 0L
    }

    override fun build(clicked: String): TileBuilders.Tile {
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
        val rows = ArrayList<L.LayoutElement>()
        rows += Tiles.text(status.ifEmpty { "Calls" }, 12f, if (status.isEmpty()) Tiles.DIM else Tiles.ACCENT)
        rows += Tiles.space(4f)
        favs.forEachIndexed { i, f ->
            rows += Tiles.chip(this, Tiles.text(f.name, 13f), id = "fav:$i", color = GREEN)
            rows += Tiles.space(3f)
        }
        if (last != null) {
            rows += Tiles.chip(this, Tiles.text("↩  ${last.label}", 13f), id = "back")
            rows += Tiles.space(3f)
        }
        if (favs.isEmpty() && last == null) {
            rows += Tiles.text("Add favourites on your iPhone", 12f, Tiles.DIM, lines = 2)
            rows += Tiles.space(3f)
        }
        rows += launch(SosActivity::class.java.name, Tiles.text("SOS", 14f, bold = true), RED)
        return Tiles.page(this, Tiles.column(*rows.toTypedArray()), 15 * 60_000L)
    }

    /** A chip that opens one of our activities (Tiles.chip only opens the main app). */
    private fun launch(cls: String, content: L.LayoutElement, color: Int): L.LayoutElement =
        L.Box.Builder().setWidth(dp(110f)).setHeight(wrap())
            .setModifiers(M.Modifiers.Builder()
                .setClickable(M.Clickable.Builder().setId("sos").setOnClick(
                    ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(cls).build()).build()).build())
                .setBackground(M.Background.Builder().setColor(argb(color))
                    .setCorner(M.Corner.Builder().setRadius(dp(18f)).build()).build())
                .setPadding(M.Padding.Builder().setTop(dp(6f)).setBottom(dp(6f)).build())
                .build())
            .addContent(content).build()
}
