package org.mihomet.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * Material 3 Expressive tactile press feedback: returns an animated scale
 * factor that dips to [pressedScale] while [interactionSource] reports a
 * press and springs back on release. Pair with `Modifier.scale(...)` next to
 * a ripple-bearing clickable — the ripple carries the state layer, the scale
 * adds the expressive "squeeze".
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
    stiffness: Float = Spring.StiffnessHigh,
): Float {
    val isPressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = stiffness
        ),
        label = "pressScale"
    ).value
}
