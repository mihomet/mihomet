package org.mihomet.app.ui.theme

import androidx.compose.ui.graphics.Color

// Core Material Expressive Primary / Secondary / Tertiary
val PrimaryViolet = Color(0xFF8B5CF6)
val PrimaryVioletLight = Color(0xFF6D28D9)
val SecondaryTeal = Color(0xFF06B6D4)
val TertiaryPink = Color(0xFFEC4899)

val PrimaryDark = Color(0xFFC4B5FD)
val OnPrimaryDark = Color(0xFF2E1065)
val PrimaryContainerDark = Color(0xFF4C1D95)
val OnPrimaryContainerDark = Color(0xFFEDE9FE)

val SecondaryDark = Color(0xFF67E8F9)
val OnSecondaryDark = Color(0xFF083344)
val SecondaryContainerDark = Color(0xFF155E75)
val OnSecondaryContainerDark = Color(0xFFCFFAFE)

// Tonal Surface Containers - Dark Mode (Default)
val DarkBackground = Color(0xFF0F0E13)
val DarkSurface = Color(0xFF15141A)
val DarkSurfaceContainerLowest = Color(0xFF0A090D)
val DarkSurfaceContainerLow = Color(0xFF1B1A21)
val DarkSurfaceContainer = Color(0xFF212028)
val DarkSurfaceContainerHigh = Color(0xFF2B2A34)
val DarkSurfaceContainerHighest = Color(0xFF363540)
val DarkOutline = Color(0xFF4A4954)
val DarkOutlineVariant = Color(0xFF2E2D38)

// Tonal Surface Containers - AMOLED True Black / Pure Carbon Neutral Mode (Zero Purple)
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF0C0C0C)
val AmoledSurfaceContainerLowest = Color(0xFF000000)
val AmoledSurfaceContainerLow = Color(0xFF121212)
val AmoledSurfaceContainer = Color(0xFF181818)
val AmoledSurfaceContainerHigh = Color(0xFF222222)
val AmoledSurfaceContainerHighest = Color(0xFF2C2C2C)
val AmoledOutline = Color(0xFF484848)
val AmoledOutlineVariant = Color(0xFF262626)

val AmoledPrimary = Color(0xFFFFFFFF)
val AmoledOnPrimary = Color(0xFF000000)
val AmoledPrimaryContainer = Color(0xFF27272A)
val AmoledOnPrimaryContainer = Color(0xFFFFFFFF)

val AmoledSecondary = Color(0xFFE4E4E7)
val AmoledOnSecondary = Color(0xFF18181B)
val AmoledSecondaryContainer = Color(0xFF27272A)
val AmoledOnSecondaryContainer = Color(0xFFF4F4F5)

val AmoledTertiary = Color(0xFFA1A1AA)
val AmoledOnTertiary = Color(0xFF09090B)
val AmoledTertiaryContainer = Color(0xFF27272A)
val AmoledOnTertiaryContainer = Color(0xFFF4F4F5)

// Tonal Surface Containers - Light Mode
val LightBackground = Color(0xFFFBF8FD)
val LightSurface = Color(0xFFFBF8FD)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF5F2F8)
val LightSurfaceContainer = Color(0xFFEFEBF2)
val LightSurfaceContainerHigh = Color(0xFFE9E5EC)
val LightSurfaceContainerHighest = Color(0xFFE3DFE6)

// Protocol Expressive Color Accents
// NOTE: fixed dark-on-dark pairs — kept ONLY as a fallback palette for static
// previews. Live UI badges must derive colors from MaterialTheme instead
// (see ProtocolBadge in ExpressiveCards.kt).
object ProtocolColors {
    val VlessText = Color(0xFFC084FC)
    val VlessBg = Color(0xFF3B1569)

    val HysteriaText = Color(0xFF38BDF8)
    val HysteriaBg = Color(0xFF0B3A53)

    val TrojanText = Color(0xFFFBBF24)
    val TrojanBg = Color(0xFF4E2D07)

    val ShadowsocksText = Color(0xFF818CF8)
    val ShadowsocksBg = Color(0xFF1E1E5E)

    val TuicText = Color(0xFF34D399)
    val TuicBg = Color(0xFF064734)

    val DirectText = Color(0xFF94A3B8)
    val DirectBg = Color(0xFF1E293B)
}

// Status & Activity Colors
val AccentGreen = Color(0xFF10B981)
val AccentGreenContainer = Color(0xFF064E3B)
val AccentGreenLight = Color(0xFF34D399)
val AccentGreenDeep = Color(0xFF047857)

val AccentOrange = Color(0xFFF59E0B)
val AccentOrangeContainer = Color(0xFF451A03)
val AccentOrangeDeep = Color(0xFFB45309)

val AccentRed = Color(0xFFEF4444)
val AccentRedContainer = Color(0xFF450A0A)
val AccentRedDeep = Color(0xFFB91C1C)

val AccentBlue = Color(0xFF3B82F6)
val AccentIndigo = Color(0xFF6366F1)

val AmoledBlack = Color(0xFF000000)
val DarkSurfaceLegacy = Color(0xFF1E1E24)

// Compatibility aliases
val Purple80 = PrimaryDark
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6D28D9)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
