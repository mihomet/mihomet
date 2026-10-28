package org.mihomet.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Shape tokens.
 * Characterized by playful, generous corner radii, organic squircles, and tactile grouping.
 */
object ExpressiveShapes {
    val ExtraSmall: CornerBasedShape = RoundedCornerShape(8.dp)
    val Small: CornerBasedShape = RoundedCornerShape(12.dp)
    val Medium: CornerBasedShape = RoundedCornerShape(18.dp)
    val Large: CornerBasedShape = RoundedCornerShape(26.dp)
    val ExtraLarge: CornerBasedShape = RoundedCornerShape(32.dp)
    val Hero: CornerBasedShape = RoundedCornerShape(38.dp)
    val Pill: Shape = CircleShape

    /**
     * Produces cohesive grouped container shapes for lists (e.g. Settings rows, Server groups).
     * Connected items have soft inner radii (4.dp) and rounded outer bounds (24.dp).
     */
    fun groupedShape(
        index: Int,
        total: Int,
        outerCorner: Dp = 24.dp,
        innerCorner: Dp = 4.dp
    ): CornerBasedShape {
        return when {
            total <= 1 -> RoundedCornerShape(outerCorner)
            index == 0 -> RoundedCornerShape(
                topStart = outerCorner,
                topEnd = outerCorner,
                bottomStart = innerCorner,
                bottomEnd = innerCorner
            )
            index == total - 1 -> RoundedCornerShape(
                topStart = innerCorner,
                topEnd = innerCorner,
                bottomStart = outerCorner,
                bottomEnd = outerCorner
            )
            else -> RoundedCornerShape(innerCorner)
        }
    }
}

val MihometShapes = Shapes(
    extraSmall = ExpressiveShapes.ExtraSmall,
    small = ExpressiveShapes.Small,
    medium = ExpressiveShapes.Medium,
    large = ExpressiveShapes.Large,
    extraLarge = ExpressiveShapes.ExtraLarge
)
