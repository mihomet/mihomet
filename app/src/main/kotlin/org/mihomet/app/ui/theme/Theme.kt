package org.mihomet.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.mihomet.app.data.preferences.PaletteStyles

private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = Color(0xFFF472B6),
    onTertiary = Color(0xFF500724),
    tertiaryContainer = Color(0xFF831843),
    onTertiaryContainer = Color(0xFFFCE7F3),
    background = DarkBackground,
    onBackground = Color(0xFFE5E1E9),
    surface = DarkSurface,
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = DarkSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceDim = DarkSurfaceContainerLowest,
    surfaceBright = DarkSurfaceContainerHighest,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = AccentRed,
    onError = Color.White,
    errorContainer = AccentRedContainer,
    onErrorContainer = Color(0xFFFFDAD6)
)

val ExpressiveAmoledColorScheme = darkColorScheme(
    primary = AmoledPrimary,
    onPrimary = AmoledOnPrimary,
    primaryContainer = AmoledPrimaryContainer,
    onPrimaryContainer = AmoledOnPrimaryContainer,
    secondary = AmoledSecondary,
    onSecondary = AmoledOnSecondary,
    secondaryContainer = AmoledSecondaryContainer,
    onSecondaryContainer = AmoledOnSecondaryContainer,
    tertiary = AmoledTertiary,
    onTertiary = AmoledOnTertiary,
    tertiaryContainer = AmoledTertiaryContainer,
    onTertiaryContainer = AmoledOnTertiaryContainer,
    background = AmoledBackground,
    onBackground = Color(0xFFF4F4F5),
    surface = AmoledSurface,
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = AmoledSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceDim = AmoledSurfaceContainerLowest,
    surfaceBright = AmoledSurfaceContainerHighest,
    surfaceContainerLowest = AmoledSurfaceContainerLowest,
    surfaceContainerLow = AmoledSurfaceContainerLow,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceContainerHigh,
    surfaceContainerHighest = AmoledSurfaceContainerHighest,
    outline = AmoledOutline,
    outlineVariant = AmoledOutlineVariant,
    error = AccentRed,
    onError = Color.White,
    errorContainer = AccentRedContainer,
    onErrorContainer = Color(0xFFFFDAD6)
)

private val ExpressiveLightColorScheme = lightColorScheme(
    primary = PrimaryVioletLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF083344),
    tertiary = Color(0xFFDB2777),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCE7F3),
    onTertiaryContainer = Color(0xFF500724),
    background = LightBackground,
    onBackground = Color(0xFF1B1A20),
    surface = LightSurface,
    onSurface = Color(0xFF1B1A20),
    surfaceVariant = LightSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFF48454E),
    surfaceDim = LightSurfaceContainerLowest,
    surfaceBright = Color.White,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFC9C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun MihometTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoledBlack: Boolean = false,
    accentColorId: String = "",
    paletteStyleId: String = PaletteStyles.DEFAULT,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Material You palette styles (PixelPlayer pattern): when system dynamic
    // colors are off, the whole scheme is GENERATED from the accent seed using
    // the chosen palette style — not just the 4 hand-picked primary roles.
    val style = runCatching { PaletteStyle.valueOf(paletteStyleId) }
        .getOrDefault(PaletteStyle.TonalSpot)

    val finalColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val systemScheme =
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            AccentPresets.byId(accentColorId)?.let { preset ->
                val accent = if (darkTheme) preset.dark else preset.light
                systemScheme.copy(
                    primary = accent.primary,
                    onPrimary = accent.onPrimary,
                    primaryContainer = accent.primaryContainer,
                    onPrimaryContainer = accent.onPrimaryContainer
                )
            } ?: systemScheme
        }
        else -> {
            val preset = AccentPresets.byId(accentColorId)
            val seed = when {
                preset != null -> if (darkTheme) preset.dark.primary else preset.light.primary
                darkTheme -> PrimaryDark
                else -> PrimaryVioletLight
            }
            dynamicColorScheme(
                seedColor = seed,
                isDark = darkTheme,
                isAmoled = darkTheme && amoledBlack,
                style = style
            )
        }
    }

    // Note: material3 1.4.0 ships MotionScheme/MaterialExpressiveTheme as internal —
    // the expressive motionScheme parameter arrives with the 1.5/AGP-9.1 upgrade.
    // The Expressive feel comes from our own morphs/transitions meanwhile.
    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = Typography,
        shapes = MihometShapes,
        content = content
    )
}
