package org.mihomet.app.ui.design2

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Edition 2" design language — tokens.
 *
 * The language (wave A of the clean-sheet MD3E rebuild):
 *  - FILLED tonal containers ([androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow]
 *    and up), never outlined cards. Separation comes from tone steps and
 *    spacing; borders are reserved for selected/focused state only.
 *  - Loud typography: screen titles as big black display text with tight
 *    tracking, section labels as small uppercase labels with wide tracking.
 *  - Expressive corner family: hero 38dp, containers 26dp, inner elements
 *    16dp, pills (full stadium) for chips/controls.
 *  - Generous spacing rhythm: wide gutters, large section gaps.
 */
object D2 {

    // Corner family
    val Hero: CornerBasedShape = RoundedCornerShape(38.dp)
    val Container: CornerBasedShape = RoundedCornerShape(26.dp)
    val Inner: CornerBasedShape = RoundedCornerShape(16.dp)
    val Pill: Shape = CircleShape

    // Layout rhythm
    /** Horizontal screen gutter. */
    val ScreenH: Dp = 20.dp

    /** Vertical gap between major screen sections. */
    val SectionGap: Dp = 28.dp

    /** Gap between a section label and its content block. */
    val LabelGap: Dp = 12.dp

    /** Gap between sibling blocks inside one section. */
    val BlockGap: Dp = 12.dp

    /** Default content padding inside a filled panel. */
    val PanelPad: Dp = 18.dp

    /** Standard squircle icon-container size inside rows. */
    val IconBox: Dp = 46.dp
}
