package com.dataroute.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** DataRoute's palette: soft lavender ground, one grape brand colour, and a colour per network. */
object DR {
    val Bg = Color(0xFFF6F4FF)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF23213A)
    val Sub = Color(0xFF625F7D)
    val Line = Color(0xFFE4E0F7)

    val Grape = Color(0xFF6C5CE7)
    val GrapeInk = Color(0xFF5341D6)
    val GrapeSoft = Color(0xFFECE9FF)
    val Pink = Color(0xFFFF6FB5)
    val PinkSoft = Color(0xFFFFE4F2)

    /** Wi-Fi / home network */
    val Sky = Color(0xFF2D8CFF)
    val SkyInk = Color(0xFF1460C9)
    val SkySoft = Color(0xFFE0EEFF)

    /** Cellular */
    val Tangerine = Color(0xFFFF8A3D)
    val TangerineInk = Color(0xFFB4500E)
    val TangerineSoft = Color(0xFFFFEBDD)

    val Mint = Color(0xFF12B886)
    val MintInk = Color(0xFF0B7A58)
    val MintSoft = Color(0xFFD9F7EC)
    val Coral = Color(0xFFE5484D)
    val CoralInk = Color(0xFFC0262C)
    val CoralSoft = Color(0xFFFFE3E3)
    val Sunny = Color(0xFFFFC53D)
    val SunnySoft = Color(0xFFFFF4D6)
    val SunnyInk = Color(0xFF8A5A00)

    val heroOn = Brush.linearGradient(listOf(Grape, Color(0xFF9B6CF2), Pink))
    val heroOff = Brush.linearGradient(listOf(GrapeSoft, PinkSoft))
}

val Fredoka = FontFamily(
    Font(R.font.fredoka, FontWeight.Medium),
    Font(R.font.fredoka, FontWeight.SemiBold),
    Font(R.font.fredoka, FontWeight.Bold),
)
val Nunito = FontFamily(
    Font(R.font.nunito, FontWeight.Normal),
    Font(R.font.nunito, FontWeight.SemiBold),
    Font(R.font.nunito, FontWeight.Bold),
    Font(R.font.nunito, FontWeight.ExtraBold),
)

private val scheme = lightColorScheme(
    primary = DR.Grape, onPrimary = Color.White,
    primaryContainer = DR.GrapeSoft, onPrimaryContainer = DR.Ink,
    secondary = DR.Sky, onSecondary = Color.White,
    secondaryContainer = DR.SkySoft, onSecondaryContainer = DR.Ink,
    tertiary = DR.Tangerine, onTertiary = DR.Ink,
    background = DR.Bg, onBackground = DR.Ink,
    surface = DR.Card, onSurface = DR.Ink,
    surfaceVariant = DR.GrapeSoft, onSurfaceVariant = DR.Sub,
    surfaceContainer = DR.Card, surfaceContainerLow = DR.Card, surfaceContainerHigh = DR.Card,
    surfaceContainerHighest = DR.GrapeSoft,
    outline = Color(0xFFB9B3DD), outlineVariant = DR.Line,
    error = DR.CoralInk, onError = Color.White, errorContainer = DR.CoralSoft,
)

private val type = Typography(
    displaySmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, lineHeight = 15.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Always the light, friendly look: the brand is the same in a dark room. */
@Composable
fun DataRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}
