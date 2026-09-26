package com.abhinavxt.debforge.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = EmberPrimaryDark,
    onPrimary = EmberOnPrimaryDark,
    primaryContainer = EmberPrimaryContainerDark,
    onPrimaryContainer = EmberOnPrimaryContainerDark,
    secondary = SlateSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = SlateOnSecondaryContainerDark,
    tertiary = SteelTertiaryDark,
    tertiaryContainer = SteelTertiaryContainerDark,
    onTertiaryContainer = SteelOnTertiaryContainerDark,
    background = ForgeBackgroundDark,
    surface = ForgeBackgroundDark,
    surfaceVariant = ForgeSurfaceVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = EmberPrimary,
    onPrimary = EmberOnPrimary,
    primaryContainer = EmberPrimaryContainer,
    onPrimaryContainer = EmberOnPrimaryContainer,
    secondary = SlateSecondary,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = SlateOnSecondaryContainer,
    tertiary = SteelTertiary,
    tertiaryContainer = SteelTertiaryContainer,
    onTertiaryContainer = SteelOnTertiaryContainer,
    background = WarmBackground,
    surface = WarmBackground,
    surfaceVariant = WarmSurfaceVariant
)

@Composable
fun DebforgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You (wallpaper colours), Android 12+. User can turn it off in
    // Settings to get the Forge brand palette instead.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}