package nz.galaxybridge.watch

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders as L
import androidx.wear.protolayout.ModifiersBuilders as M
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService

/** Goal rings, quick-log and stress tiles (Wave C). Built with the shared [Tiles] kit. */
object HealthDepthTiles {
    const val STEPS = 0xFF3DDC84.toInt()
    const val ENERGY = 0xFFFF5C7A.toInt()
    const val ACTIVE = 0xFF2ED1DB.toInt()
    const val WATER = 0xFF4DA3FF.toInt()
    private const val TRACK = 0x33FFFFFF

    fun refresh(c: Context) {
        for (cls in listOf(GoalsTile::class.java, QuickLogTile::class.java, StressTile::class.java)) {
            runCatching { TileService.getUpdater(c).requestUpdate(cls) }
        }
    }

    /** A full-circle ring showing [fraction] (capped at one lap) inside a box padded by [inset]. */
    fun ring(fraction: Double, color: Int, inset: Float, content: L.LayoutElement? = null): L.LayoutElement {
        val deg = (fraction.coerceIn(0.0, 1.0) * 360.0).toFloat().coerceAtLeast(0.5f)
        val arc = L.Arc.Builder()
            .setAnchorAngle(degrees(0f)).setAnchorType(L.ARC_ANCHOR_START)
            .addContent(L.ArcLine.Builder().setLength(degrees(deg)).setThickness(dp(9f)).setColor(argb(color)).build())
            .addContent(L.ArcLine.Builder().setLength(degrees((360f - deg).coerceAtLeast(0f))).setThickness(dp(9f))
                .setColor(argb(TRACK)).build())
            .build()
        return L.Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(M.Modifiers.Builder().setPadding(M.Padding.Builder().setAll(dp(inset)).build()).build())
            .addContent(arc)
            .apply { content?.let { addContent(it) } }
            .build()
    }
}

class GoalsTile : SimpleTile() {
    override fun build(clicked: String): TileBuilders.Tile {
        val t = Goals.today(this)
        val (steps, kcal, active) = Goals.progress(this)
        val center = Tiles.column(
            Tiles.text("%,d".format(t.steps), 20f, bold = true),
            Tiles.text("steps", 11f, Tiles.DIM),
            Tiles.space(2f),
            Tiles.text("${t.kcal.toInt()} kcal · ${t.activeMin} min", 11f, Tiles.DIM))
        val rings = HealthDepthTiles.ring(steps, HealthDepthTiles.STEPS, 2f,
            HealthDepthTiles.ring(kcal, HealthDepthTiles.ENERGY, 13f,
                HealthDepthTiles.ring(active, HealthDepthTiles.ACTIVE, 13f,
                    L.Box.Builder().setWidth(expand()).setHeight(expand())
                        .setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
                        .addContent(center).build())))
        return Tiles.page(this, rings, 10 * 60_000L)
    }
}

class QuickLogTile : SimpleTile() {
    override fun build(clicked: String): TileBuilders.Tile {
        when (clicked) {
            "water" -> Intake.logWater(this)
            "coffee" -> Intake.logCaffeine(this)
        }
        val t = Goals.today(this)
        val cup = HealthDepth.get(this, "cupMl")
        val mg = HealthDepth.get(this, "coffeeMg")
        return Tiles.page(this, Tiles.column(
            Tiles.text("Quick log", 12f, Tiles.DIM),
            Tiles.space(6f),
            Tiles.chip(this, Tiles.row(Tiles.text("💧  ", 14f), Tiles.text("Water +$cup ml", 14f, bold = true)), "water",
                color = 0x334DA3FF),
            Tiles.space(4f),
            Tiles.chip(this, Tiles.row(Tiles.text("☕  ", 14f), Tiles.text("Coffee +$mg mg", 14f, bold = true)), "coffee",
                color = 0x33FFB347),
            Tiles.space(6f),
            Tiles.text("Today ${t.waterMl} ml · ${t.caffeineMg} mg caffeine", 11f, Tiles.DIM)), 30 * 60_000L)
    }
}

class StressTile : SimpleTile() {
    override fun build(clicked: String): TileBuilders.Tile {
        val r = Hrv.latest(this)
        if (r == null) {
            return Tiles.page(this, Tiles.column(
                Tiles.text("Stress", 12f, Tiles.DIM), Tiles.space(4f),
                Tiles.text("No reading yet", 14f, bold = true),
                Tiles.text("Tap to measure (1 min)", 11f, Tiles.DIM)))
        }
        val ago = ((System.currentTimeMillis() - r.endMs) / 60_000L).coerceAtLeast(0)
        val color = when { r.stress < 34 -> HealthDepthTiles.STEPS; r.stress < 67 -> 0xFFFFC542.toInt(); else -> HealthDepthTiles.ENERGY }
        return Tiles.page(this, HealthDepthTiles.ring(r.stress / 100.0, color, 2f,
            L.Box.Builder().setWidth(expand()).setHeight(expand())
                .setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
                .addContent(Tiles.column(
                    Tiles.text("Stress (estimate)", 11f, Tiles.DIM),
                    Tiles.text("${r.stress}", 34f, bold = true),
                    Tiles.text(Hrv.label(r.stress), 13f, color),
                    Tiles.text("HRV ${r.sdnn.toInt()} ms · ${if (ago < 60) "${ago}m" else "${ago / 60}h"} ago", 11f, Tiles.DIM)))
                .build()), 30 * 60_000L)
    }
}
