package nz.galaxybridge.watch

import android.content.Context
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders as L
import androidx.wear.protolayout.ModifiersBuilders as M
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.ProgressIndicatorColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.button
import androidx.wear.protolayout.material3.circularProgressIndicator
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService

/** Goal rings, quick-log and stress tiles (Wave C). Built with ProtoLayout Material 3. */
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

    /** A full-circle M3 progress ring padded by [inset] dp, optionally nesting [content]. */
    fun MaterialScope.ring(fraction: Double, color: Int, inset: Float, content: L.LayoutElement? = null): L.LayoutElement =
        L.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
            .setModifiers(M.Modifiers.Builder().setPadding(M.Padding.Builder().setAll(dp(inset)).build()).build())
            .addContent(
                circularProgressIndicator(
                    staticProgress = fraction.coerceIn(0.0, 1.0).toFloat(),
                    strokeWidth = 7f,
                    colors = ProgressIndicatorColors(indicatorColor = color.argb, trackColor = TRACK.argb),
                ),
            )
            .apply { content?.let { addContent(it) } }
            .build()
}

class GoalsTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        val t = Goals.today(this)
        val (steps, kcal, active) = Goals.progress(this)
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request, freshMs = 10 * 60_000L) {
            val center = Tiles.column(
                text("%,d".format(t.steps).layoutString, typography = Typography.NUMERAL_MEDIUM, color = colorScheme.onBackground),
                text("steps".layoutString, typography = Typography.BODY_EXTRA_SMALL, color = colorScheme.onSurfaceVariant),
                Tiles.space(2f),
                text(
                    "${t.kcal.toInt()} kcal · ${t.activeMin} min".layoutString,
                    typography = Typography.BODY_EXTRA_SMALL,
                    color = colorScheme.onSurfaceVariant,
                ),
            )
            val rings = with(HealthDepthTiles) {
                ring(
                    steps,
                    HealthDepthTiles.STEPS,
                    2f,
                    ring(
                        kcal,
                        HealthDepthTiles.ENERGY,
                        10f,
                        ring(active, HealthDepthTiles.ACTIVE, 10f, center),
                    ),
                )
            }
            L.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(M.Modifiers.Builder().setClickable(open).build())
                .addContent(rings)
                .build()
        }
    }
}

class QuickLogTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        when (clicked) {
            "water" -> Intake.logWater(this)
            "coffee" -> Intake.logCaffeine(this)
        }
        val t = Goals.today(this)
        val cup = HealthDepth.get(this, "cupMl")
        val mg = HealthDepth.get(this, "coffeeMg")
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request, freshMs = 30 * 60_000L) {
            val waterColors = ButtonColors(
                containerColor = 0xFF153657.toInt().argb,
                labelColor = 0xFFD6EAFF.toInt().argb,
                secondaryLabelColor = 0xFF9AC9FF.toInt().argb,
            )
            val coffeeColors = ButtonColors(
                containerColor = 0xFF473114.toInt().argb,
                labelColor = 0xFFFFE4BF.toInt().argb,
                secondaryLabelColor = 0xFFFFCB85.toInt().argb,
            )
            primaryLayout(
                titleSlot = { text("Quick log".layoutString) },
                mainSlot = {
                    Tiles.spacedColumn(
                        listOf(
                            button(
                                onClick = Tiles.loadClickable("water"),
                                width = expand(),
                                colors = waterColors,
                                labelContent = { text("💧 Water +$cup ml".layoutString, maxLines = 1) },
                            ),
                            button(
                                onClick = Tiles.loadClickable("coffee"),
                                width = expand(),
                                colors = coffeeColors,
                                labelContent = { text("☕ Coffee +$mg mg".layoutString, maxLines = 1) },
                            ),
                        ),
                        spacing = 4f,
                    )
                },
                bottomSlot = {
                    text(
                        "Today ${t.waterMl} ml · ${t.caffeineMg} mg".layoutString,
                        typography = Typography.BODY_EXTRA_SMALL,
                        color = colorScheme.onSurfaceVariant,
                    )
                },
                onClick = open,
            )
        }
    }
}

class StressTile : SimpleTile() {
    override fun build(request: RequestBuilders.TileRequest, clicked: String): TileBuilders.Tile {
        val r = Hrv.latest(this)
        val open = Tiles.launchClickable(this)
        return Tiles.materialTile(this, request, freshMs = 30 * 60_000L) {
            if (r == null) {
                primaryLayout(
                    titleSlot = { text("Stress".layoutString) },
                    mainSlot = {
                        Tiles.column(
                            text("No reading yet".layoutString, typography = Typography.TITLE_SMALL, color = colorScheme.onBackground),
                            text("Tap to measure (1 min)".layoutString, typography = Typography.BODY_EXTRA_SMALL, color = colorScheme.onSurfaceVariant),
                        )
                    },
                    bottomSlot = {
                        textEdgeButton(onClick = open) { text("Measure".layoutString) }
                    },
                    onClick = open,
                )
            } else {
                val ago = ((System.currentTimeMillis() - r.endMs) / 60_000L).coerceAtLeast(0)
                val color = when {
                    r.stress < 34 -> HealthDepthTiles.STEPS
                    r.stress < 67 -> 0xFFFFC542.toInt()
                    else -> HealthDepthTiles.ENERGY
                }
                L.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setHorizontalAlignment(L.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(L.VERTICAL_ALIGN_CENTER)
                    .setModifiers(M.Modifiers.Builder().setClickable(open).build())
                    .addContent(
                        circularProgressIndicator(
                            staticProgress = (r.stress / 100f).coerceIn(0f, 1f),
                            modifier = LayoutModifier.padding(2f),
                            strokeWidth = 8f,
                            colors = ProgressIndicatorColors(indicatorColor = color.argb, trackColor = 0x33FFFFFF.argb),
                        ),
                    )
                    .addContent(
                        Tiles.column(
                            text("Stress (estimate)".layoutString, typography = Typography.BODY_EXTRA_SMALL, color = colorScheme.onSurfaceVariant),
                            text("${r.stress}".layoutString, typography = Typography.NUMERAL_LARGE, color = colorScheme.onBackground),
                            text(Hrv.label(r.stress).layoutString, typography = Typography.LABEL_MEDIUM, color = color.argb),
                            text(
                                "HRV ${r.sdnn.toInt()} ms · ${if (ago < 60) "${ago}m" else "${ago / 60}h"} ago".layoutString,
                                typography = Typography.BODY_EXTRA_SMALL,
                                color = colorScheme.onSurfaceVariant,
                            ),
                        ),
                    )
                    .build()
            }
        }
    }
}
