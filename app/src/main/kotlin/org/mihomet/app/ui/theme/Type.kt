package org.mihomet.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

private val materialType = Typography()
val Typography = Typography(
    displayLarge = materialType.displayLarge.copy(fontWeight = FontWeight.Bold),
    displayMedium = materialType.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = materialType.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = materialType.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = materialType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = materialType.titleLarge.copy(fontWeight = FontWeight.SemiBold)
)
