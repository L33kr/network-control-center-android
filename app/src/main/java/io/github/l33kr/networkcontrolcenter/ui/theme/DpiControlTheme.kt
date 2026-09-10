package io.github.l33kr.networkcontrolcenter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFFA7F3D0),
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF090D12),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0F151D),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF17202B),
    onSurfaceVariant = Color(0xFFB8C4D0),
    outline = Color(0xFF536170),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DpiControlTheme(content: @Composable () -> Unit) {
    // The app is intentionally dark-first. Keeping one stable palette also avoids
    // flashing between light and dark while the VPN foreground service reconnects.
    @Suppress("UNUSED_VARIABLE")
    val systemDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
