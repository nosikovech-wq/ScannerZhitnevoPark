package com.example.russianplatescanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0B0C0E)
val Surface = Color(0xFF14161A)
val Surface2 = Color(0xFF1C1F25)
val Fg = Color(0xFFF1F2F4)
val Muted = Color(0xFF9AA0AA)
val Subtle = Color(0xFF6D7380)
val Accent = Color(0xFFE8EAEE)
val AccentFg = Color(0xFF101114)
val Ok = Color(0xFF7DBA8A)
val Danger = Color(0xFFE23B3B)
val Border = Color(0x1FF1F2F4)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = AccentFg,
    secondary = Surface2,
    onSecondary = Fg,
    background = Bg,
    onBackground = Fg,
    surface = Surface,
    onSurface = Fg,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Border,
    error = Danger,
    onError = Fg
)

private val Type = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.4).sp,
        color = Fg
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.4.sp,
        color = Fg
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = Fg
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        color = Muted
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        color = Subtle
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
        color = Subtle
    )
)

@Composable
fun RussianPlateScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Type,
        content = content
    )
}
