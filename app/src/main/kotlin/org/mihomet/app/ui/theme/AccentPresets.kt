package org.mihomet.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Theme accent color presets. Each preset overrides primary/primaryContainer/
 * onPrimary/onPrimaryContainer in the active color scheme. The default (empty)
 * keeps the brand violet.
 */
data class AccentPreset(
    val id: String,
    val label: String,
    val light: AccentColors,
    val dark: AccentColors
)

data class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color
)

object AccentPresets {
    val DEFAULT = ""  // brand violet

    val all = listOf(
        AccentPreset("violet", "Violet",
            AccentColors(Color(0xFF6D28D9), Color.White, Color(0xFFEDE9FE), Color(0xFF2E1065)),
            AccentColors(Color(0xFFA78BFA), Color(0xFF1E1B4B), Color(0xFF4C1D95), Color(0xFFEDE9FE))),
        AccentPreset("blue", "Blue",
            AccentColors(Color(0xFF1D4ED8), Color.White, Color(0xFFDBEAFE), Color(0xFF1E3A8A)),
            AccentColors(Color(0xFF60A5FA), Color(0xFF0C1445), Color(0xFF1E3A8A), Color(0xFFDBEAFE))),
        AccentPreset("teal", "Teal",
            AccentColors(Color(0xFF0D9488), Color.White, Color(0xFFCCFBF1), Color(0xFF042F2E)),
            AccentColors(Color(0xFF2DD4BF), Color(0xFF042F2E), Color(0xFF115E59), Color(0xFFCCFBF1))),
        AccentPreset("green", "Green",
            AccentColors(Color(0xFF16A34A), Color.White, Color(0xFFDCFCE7), Color(0xFF14532D)),
            AccentColors(Color(0xFF4ADE80), Color(0xFF052E16), Color(0xFF166534), Color(0xFFDCFCE7))),
        AccentPreset("amber", "Amber",
            AccentColors(Color(0xFFD97706), Color.White, Color(0xFFFEF3C7), Color(0xFF78350F)),
            AccentColors(Color(0xFFFBBF24), Color(0xFF451A03), Color(0xFF92400E), Color(0xFFFEF3C7))),
        AccentPreset("orange", "Orange",
            AccentColors(Color(0xFFEA580C), Color.White, Color(0xFFFFEDD5), Color(0xFF7C2D12)),
            AccentColors(Color(0xFFFB923C), Color(0xFF431407), Color(0xFF9A3412), Color(0xFFFFEDD5))),
        AccentPreset("red", "Red",
            AccentColors(Color(0xFFDC2626), Color.White, Color(0xFFFEE2E2), Color(0xFF7F1D1D)),
            AccentColors(Color(0xFFF87171), Color(0xFF450A0A), Color(0xFF991B1B), Color(0xFFFEE2E2))),
        AccentPreset("pink", "Pink",
            AccentColors(Color(0xFFDB2777), Color.White, Color(0xFFFCE7F3), Color(0xFF500724)),
            AccentColors(Color(0xFFF472B6), Color(0xFF500724), Color(0xFF9D174D), Color(0xFFFCE7F3))),
        AccentPreset("indigo", "Indigo",
            AccentColors(Color(0xFF4F46E5), Color.White, Color(0xFFE0E7FF), Color(0xFF1E1B4B)),
            AccentColors(Color(0xFF818CF8), Color(0xFF1E1B4B), Color(0xFF3730A3), Color(0xFFE0E7FF))),
    )

    fun byId(id: String): AccentPreset? = all.firstOrNull { it.id == id }
}
