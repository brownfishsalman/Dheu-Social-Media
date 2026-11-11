package com.salmansaadiq.dheu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salmansaadiq.dheu.R

// The website's design tokens (src/app/globals.css), sampled from the logo:
// navy #00273D, teal #1589A5, aqua #BDDEE6. Dark mode differs from the
// website's: a soft, faintly cool black instead of navy, easier on the eyes,
// with the same teal accents.
@Immutable
data class DheuColors(
    val canvas: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val brand: Color,
    val brandStrong: Color,
    val brandSoft: Color,
    val brandInk: Color,
    val waveA: Color,
    val waveB: Color,
    val danger: Color,
    val dangerSoft: Color,
    val like: Color,
    val isDark: Boolean,
)

val LightColors = DheuColors(
    canvas = Color(0xFFF3F7F9), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE8F0F4), line = Color(0xFFD5E2E9),
    ink = Color(0xFF00273D), inkMuted = Color(0xFF587687), inkFaint = Color(0xFF8FA6B4),
    brand = Color(0xFF1589A5), brandStrong = Color(0xFF0E5368), brandSoft = Color(0xFFD9EDF2), brandInk = Color(0xFFFFFFFF),
    waveA = Color(0xFF2BA3BF), waveB = Color(0xFF194158),
    danger = Color(0xFFC93B3B), dangerSoft = Color(0xFFFBE4E4), like = Color(0xFFE5484D), isDark = false,
)

val DarkColors = DheuColors(
    canvas = Color(0xFF0A0C0E), surface = Color(0xFF0F1215), surface2 = Color(0xFF1E2327), line = Color(0xFF262C31),
    ink = Color(0xFFE6ECEF), inkMuted = Color(0xFF9AA6AE), inkFaint = Color(0xFF6E7A82),
    brand = Color(0xFF2BA3BF), brandStrong = Color(0xFF5FC3D8), brandSoft = Color(0xFF12313B), brandInk = Color(0xFF03202E),
    waveA = Color(0xFFBDDEE6), waveB = Color(0xFF1589A5),
    danger = Color(0xFFF28B8B), dangerSoft = Color(0xFF3D1A1D), like = Color(0xFFF06B70), isDark = true,
)

/** Close-friends stories: the ring and badge, in both themes (the website's --color-close). */
val CloseFriendsGreen = Color(0xFF1FB35A)

val LocalDheuColors = staticCompositionLocalOf { LightColors }

/** `Dheu.colors.brand` etc. */
object Dheu {
    val colors: DheuColors @Composable get() = LocalDheuColors.current
}

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600), variable(R.font.inter, 700))
val Outfit = FontFamily(variable(R.font.outfit, 500), variable(R.font.outfit, 600), variable(R.font.outfit, 700))

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

enum class ThemeMode { System, Light, Dark }

@Composable
fun isDark(mode: ThemeMode) = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun DheuTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val c = if (isDark(mode)) DarkColors else LightColors
    val scheme = if (c.isDark) {
        darkColorScheme(
            primary = c.brand, onPrimary = c.brandInk, primaryContainer = c.brandSoft, onPrimaryContainer = c.ink,
            secondary = c.brandStrong, background = c.canvas, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.surface2, onSurfaceVariant = c.inkMuted, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerLow = c.surface, outline = c.line, outlineVariant = c.line,
            error = c.danger, onError = c.brandInk, errorContainer = c.dangerSoft,
        )
    } else {
        lightColorScheme(
            primary = c.brand, onPrimary = c.brandInk, primaryContainer = c.brandSoft, onPrimaryContainer = c.ink,
            secondary = c.brandStrong, background = c.canvas, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.surface2, onSurfaceVariant = c.inkMuted, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerLow = c.surface, outline = c.line, outlineVariant = c.line,
            error = c.danger, onError = Color.White, errorContainer = c.dangerSoft,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
    ) {
        CompositionLocalProvider(LocalDheuColors provides c, content = content)
    }
}
