package org.mihomet.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

private val ExpressiveAmoledColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = Color(0xFF3B1578),
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = Color(0xFFF472B6),
    onTertiary = Color(0xFF500724),
    tertiaryContainer = Color(0xFF831843),
    onTertiaryContainer = Color(0xFFFCE7F3),
    background = AmoledBackground,
    onBackground = Color(0xFFEDEDF2),
    surface = AmoledSurface,
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = AmoledSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceDim = AmoledSurfaceContainerLowest,
    surfaceBright = AmoledSurfaceContainerHighest,
    surfaceContainerLowest = AmoledSurfaceContainerLowest,
    surfaceContainerLow = AmoledSurfaceContainerLow,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceContainerHigh,
    surfaceContainerHighest = AmoledSurfaceContainerHighest,
    outline = Color(0xFF43424E),
    outlineVariant = Color(0xFF282830),
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
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val baseColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ExpressiveDarkColorScheme
        else -> ExpressiveLightColorScheme
    }

    val finalColorScheme = if (darkTheme && amoledBlack) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            baseColorScheme.copy(
                background = AmoledBackground,
                surface = AmoledSurface,
                surfaceContainerLowest = AmoledSurfaceContainerLowest,
                surfaceContainerLow = AmoledSurfaceContainerLow,
                surfaceContainer = AmoledSurfaceContainer,
                surfaceContainerHigh = AmoledSurfaceContainerHigh,
                surfaceContainerHighest = AmoledSurfaceContainerHighest
            )
        } else {
            ExpressiveAmoledColorScheme
        }
    } else {
        baseColorScheme
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = Typography,
        shapes = MihometShapes,
        content = content
    )
}
