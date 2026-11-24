package nz.galaxybridge.watch

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.protolayout.material3.ColorScheme as TileColorScheme
import androidx.wear.protolayout.types.argb

/**
 * Fallback Material 3 Expressive color scheme derived from Galaxy Bridge's indigo/cyan brand
 * palette. Used on Wear OS versions without system dynamic color (below Wear OS 6).
 */
val BridgeFallbackColorScheme = ColorScheme(
    primary = Color(0xFF9EA6FF),
    primaryDim = Color(0xFF7C88FF),
    primaryContainer = Color(0xFF2B3480),
    onPrimary = Color(0xFF0A1042),
    onPrimaryContainer = Color(0xFFE4E7FF),
    secondary = Color(0xFF5CE1E8),
    secondaryDim = Color(0xFF2ED1DB),
    secondaryContainer = Color(0xFF0E4A52),
    onSecondary = Color(0xFF00262A),
    onSecondaryContainer = Color(0xFFCFF8FA),
    tertiary = Color(0xFFD69EFF),
    tertiaryDim = Color(0xFFB04CFF),
    tertiaryContainer = Color(0xFF4A1E6B),
    onTertiary = Color(0xFF2B0047),
    onTertiaryContainer = Color(0xFFF3E2FF),
    surfaceContainerLow = Color(0xFF151830),
    surfaceContainer = Color(0xFF1C2046),
    surfaceContainerHigh = Color(0xFF272C59),
    onSurface = Color(0xFFF2F4FF),
    onSurfaceVariant = Color(0xFFB7BCE0),
    outline = Color(0xFF7A80A6),
    outlineVariant = Color(0xFF3A3F66),
    background = Color.Black,
    onBackground = Color(0xFFF2F4FF),
    error = Color(0xFFFF8A8A),
    errorDim = Color(0xFFE5484D),
    errorContainer = Color(0xFF69121B),
    onError = Color(0xFF4A0008),
    onErrorContainer = Color(0xFFFFDADA),
)

/** Matching ProtoLayout Material 3 fallback palette for Wear Tiles. */
val BridgeTileColorScheme = TileColorScheme(
    primary = 0xFF9EA6FF.toInt().argb,
    primaryDim = 0xFF7C88FF.toInt().argb,
    primaryContainer = 0xFF2B3480.toInt().argb,
    onPrimary = 0xFF0A1042.toInt().argb,
    onPrimaryContainer = 0xFFE4E7FF.toInt().argb,
    secondary = 0xFF5CE1E8.toInt().argb,
    secondaryDim = 0xFF2ED1DB.toInt().argb,
    secondaryContainer = 0xFF0E4A52.toInt().argb,
    onSecondary = 0xFF00262A.toInt().argb,
    onSecondaryContainer = 0xFFCFF8FA.toInt().argb,
    tertiary = 0xFFD69EFF.toInt().argb,
    tertiaryDim = 0xFFB04CFF.toInt().argb,
    tertiaryContainer = 0xFF4A1E6B.toInt().argb,
    onTertiary = 0xFF2B0047.toInt().argb,
    onTertiaryContainer = 0xFFF3E2FF.toInt().argb,
    surfaceContainerLow = 0xFF151830.toInt().argb,
    surfaceContainer = 0xFF1C2046.toInt().argb,
    surfaceContainerHigh = 0xFF272C59.toInt().argb,
    onSurface = 0xFFF2F4FF.toInt().argb,
    onSurfaceVariant = 0xFFB7BCE0.toInt().argb,
    outline = 0xFF7A80A6.toInt().argb,
    outlineVariant = 0xFF3A3F66.toInt().argb,
    background = 0xFF000000.toInt().argb,
    onBackground = 0xFFF2F4FF.toInt().argb,
    error = 0xFFFF8A8A.toInt().argb,
    errorDim = 0xFFE5484D.toInt().argb,
    errorContainer = 0xFF69121B.toInt().argb,
    onError = 0xFF4A0008.toInt().argb,
    onErrorContainer = 0xFFFFDADA.toInt().argb,
)

/**
 * Semantic colors that convey domain meaning (health metrics, call accept/decline, status dots,
 * stress zones) and intentionally stay consistent regardless of dynamic wallpaper color.
 */
object BridgeTokens {
    val Good = Color(0xFF3DDC84)
    val Wait = Color(0xFFFFC542)
    val CallAccept = Color(0xFF30A46C)
    val CallDecline = Color(0xFFE5484D)
    val Energy = Color(0xFFFF5C7A)
    val Active = Color(0xFF2ED1DB)
    val Water = Color(0xFF4DA3FF)
    val Coffee = Color(0xFFFFB347)
    val Period = Color(0xFFFF5C9A)
    val StressZones = listOf(Color(0xFF2E8B8B), Color(0xFF3FA34D), Color(0xFFF2B705), Color(0xFFE0692B))
    val Spo2Tint = Color(0xFF7A2048)
    val Spo2Track = Color(0xFF5E1F3C)
}

/** Wear Compose Material 3 theme using dynamic color when available (Wear OS 6) with brand fallback. */
@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    val colors = dynamicColorScheme(LocalContext.current) ?: BridgeFallbackColorScheme
    MaterialTheme(colorScheme = colors, content = content)
}
