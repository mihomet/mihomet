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

/** AMOLED changes surfaces only; Material You keeps its expressive control colors. */
fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black,
    surfaceDim = Color.Black, surfaceBright = Color(0xFF242424),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF101010), surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF242424), surfaceVariant = Color(0xFF242424),
    outline = Color(0xFF666666), outlineVariant = Color(0xFF303030)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MihometTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoledBlack: Boolean = false,
    accentColorId: String = "",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> if (darkTheme) ExpressiveDarkColorScheme else ExpressiveLightColorScheme
    }
    val preset = AccentPresets.byId(accentColorId)
    val selected = if (!dynamicColor && preset != null) {
        val accent = if (darkTheme) preset.dark else preset.light
        base.copy(primary = accent.primary, onPrimary = accent.onPrimary,
            primaryContainer = accent.primaryContainer, onPrimaryContainer = accent.onPrimaryContainer)
    } else base
    val scheme = if (darkTheme && amoledBlack) selected.withAmoledSurfaces() else selected
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        shapes = MihometShapes,
        content = content
    )
}
