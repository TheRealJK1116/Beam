package dev.beam.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Beam leans on the user's own wallpaper colours wherever it can — dynamic colour is the most
 * expressive thing available and it costs nothing. The static scheme below is the fallback for
 * devices without it: a warm amber, because a beam should feel like a spark rather than a file
 * manager.
 */
private val BeamAmber = Color(0xFFFFB31A)
private val BeamDeep = Color(0xFF2A1A00)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB3),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF725A42),
    secondaryContainer = Color(0xFFFFDDB3),
    tertiary = Color(0xFF58633A),
    surface = Color(0xFFFFF8F4),
    surfaceContainer = Color(0xFFFCEDE1),
)

private val DarkScheme = darkColorScheme(
    primary = BeamAmber,
    onPrimary = BeamDeep,
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFFE1C1A4),
    secondaryContainer = Color(0xFF59422B),
    tertiary = Color(0xFFBFCB94),
    surface = Color(0xFF17120D),
    surfaceContainer = Color(0xFF231E18),
)

@Composable
fun BeamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = BeamTypography,
        content = content,
    )
}
