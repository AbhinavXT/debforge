package com.abhinavxt.debforge.ui.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.ThemeMode
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.SwitchSettingRow
import com.abhinavxt.debforge.ui.theme.AppTheme
import com.abhinavxt.debforge.ui.theme.swatch

/**
 * Theme picker (DebForge palettes + wallpaper colours), light/dark mode and
 * pure black. Lives in a SectionCard on the Settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceContent(
    appTheme: AppTheme,
    dynamicColor: Boolean,
    mode: ThemeMode,
    pureBlack: Boolean,
    onSelectTheme: (AppTheme) -> Unit,
    onSelectWallpaper: () -> Unit,
    onMode: (ThemeMode) -> Unit,
    onPureBlack: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val wallpaperOn = wallpaperAvailable && dynamicColor

    Text(
        stringResource(R.string.set_theme),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (wallpaperAvailable) {
            item(key = "wallpaper") {
                val colors = remember(dark) {
                    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    Triple(scheme.primary, scheme.secondaryContainer, scheme.tertiary)
                }
                Swatch(
                    label = stringResource(R.string.theme_wallpaper),
                    colors = colors,
                    selected = wallpaperOn,
                    badge = true,
                    onClick = onSelectWallpaper
                )
            }
        }
        items(AppTheme.entries, key = { it.name }) { theme ->
            Swatch(
                label = theme.label(),
                colors = remember(theme, dark) { theme.swatch(dark) },
                selected = !wallpaperOn && theme == appTheme,
                onClick = { onSelectTheme(theme) }
            )
        }
    }

    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
    ) {
        val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
        modes.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == mode,
                onClick = { onMode(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                // Mode icon instead of the default check, so labels keep their room.
                icon = {
                    Icon(
                        when (option) {
                            ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Rounded.LightMode
                            ThemeMode.DARK -> Icons.Rounded.DarkMode
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            ) {
                Text(option.label(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    RowDivider()
    SwitchSettingRow(
        icon = Icons.Rounded.Contrast,
        title = stringResource(R.string.set_pure_black),
        subtitle = stringResource(R.string.set_pure_black_detail),
        checked = pureBlack,
        onChange = onPureBlack
    )
}

/**
 * A round colour sample split like Android's own wallpaper picker: primary on
 * top, secondary container and tertiary below. Ring and check when selected.
 */
@Composable
private fun Swatch(
    label: String,
    colors: Triple<Color, Color, Color>,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Boolean = false
) {
    val ring = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(60.dp)
                .then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
                .padding(if (selected) 6.dp else 3.dp),
            contentAlignment = Alignment.Center
        ) {
            val (top, left, right) = colors
            Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
                drawRect(top, size = Size(size.width, size.height / 2))
                drawRect(left, topLeft = Offset(0f, size.height / 2), size = Size(size.width / 2, size.height / 2))
                drawRect(
                    right,
                    topLeft = Offset(size.width / 2, size.height / 2),
                    size = Size(size.width / 2, size.height / 2)
                )
            }
            when {
                selected -> CheckDot()
                // Marks the wallpaper option, which has no name colour of its own.
                badge -> Icon(
                    Icons.Rounded.Wallpaper,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CheckDot() {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AppTheme.label(): String = stringResource(
    when (this) {
        AppTheme.EMBER -> R.string.theme_ember
        AppTheme.OCEAN -> R.string.theme_ocean
        AppTheme.AURORA -> R.string.theme_aurora
        AppTheme.FOREST -> R.string.theme_forest
        AppTheme.AMETHYST -> R.string.theme_amethyst
        AppTheme.ROSE -> R.string.theme_rose
        AppTheme.GRAPHITE -> R.string.theme_graphite
    }
)

@Composable
internal fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)
