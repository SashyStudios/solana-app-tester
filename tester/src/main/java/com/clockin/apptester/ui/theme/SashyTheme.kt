package com.clockin.apptester.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Sashy Studios brand palette - see CLAUDE.md "Branding" section for the source of truth.
object SashyColors {
    val StudioBlack = Color(0xFF1A1A1A)
    val PureBlack = Color(0xFF000000)
    val CardBlack = Color(0xFF111111)
    val BorderGray = Color(0xFF333333)
    val ElectricGreen = Color(0xFF00FF88)
    val DeepGreen = Color(0xFF006633)
    val SolanaPurple = Color(0xFF9945FF)
    val ErrorRed = Color(0xFFFF4444)
    val White = Color(0xFFFFFFFF)
    val DimWhite = Color(0xFF888888)
}

private val SashyColorScheme = darkColorScheme(
    background = SashyColors.StudioBlack,
    surface = SashyColors.StudioBlack,
    surfaceVariant = SashyColors.CardBlack,
    primary = SashyColors.ElectricGreen,
    onPrimary = SashyColors.PureBlack,
    secondary = SashyColors.BorderGray,
    onBackground = SashyColors.White,
    onSurface = SashyColors.White,
    error = SashyColors.ErrorRed,
    onError = SashyColors.White
)

@Composable
fun SashyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SashyColorScheme, content = content)
}
