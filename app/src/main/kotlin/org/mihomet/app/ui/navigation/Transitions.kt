package org.mihomet.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * M3 Expressive screen motion, ported from the PixelPlayer study:
 *
 * - Detail push: the incoming screen slides over 50% with a scale-up from 0.92
 *   (Emphasized-Decelerate), while the covered screen parallaxes away at -25%
 *   and fades at HALF the duration with Emphasized-Accelerate — the fade must
 *   not "arrive before the slide" (PixelPlayer Transitions.kt comment).
 * - Tab switches: directional parallax slide of 50% + fade, deliberately with
 *   NO scale so tabs do not "jump".
 * - Pop mirrors both.
 */
object AppTransitions {

    private const val DURATION = 450

    /** M3 emphasized-decelerate: fast start, long gentle settle. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** M3 emphasized-accelerate: slow start, decisive exit. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ---- Detail push / pop ----

    fun detailEnter(): EnterTransition = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(DURATION, easing = EmphasizedDecelerate)
    ) + scaleIn(
        initialScale = 0.92f,
        animationSpec = tween(DURATION, easing = EmphasizedDecelerate)
    )

    fun detailExit(coverFraction: Float = 0.25f): ExitTransition = slideOutHorizontally(
        targetOffsetX = { -(it * coverFraction).toInt() },
        animationSpec = tween(DURATION, easing = EmphasizedAccelerate)
    ) + fadeOut(
        // Half duration: the covered screen disappears before the slide settles.
        animationSpec = tween(DURATION / 2, easing = EmphasizedAccelerate)
    )

    fun detailPopEnter(coverFraction: Float = 0.25f): EnterTransition = slideInHorizontally(
        initialOffsetX = { -(it * coverFraction).toInt() },
        animationSpec = tween(DURATION, easing = EmphasizedDecelerate)
    ) + fadeIn(
        animationSpec = tween(DURATION / 2, easing = EmphasizedDecelerate)
    )

    fun detailPopExit(): ExitTransition = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(DURATION, easing = EmphasizedAccelerate)
    ) + scaleOut(
        targetScale = 0.92f,
        animationSpec = tween(DURATION, easing = EmphasizedAccelerate)
    )

    // ---- Bottom-tab switches: parallax only, no scale ----

    fun tabEnter(forward: Boolean): EnterTransition = slideInHorizontally(
        initialOffsetX = { if (forward) it / 2 else -it / 2 },
        animationSpec = tween(DURATION, easing = EmphasizedDecelerate)
    ) + fadeIn(animationSpec = tween(DURATION / 2, easing = EmphasizedDecelerate))

    fun tabExit(forward: Boolean): ExitTransition = slideOutHorizontally(
        targetOffsetX = { if (forward) -it / 2 else it / 2 },
        animationSpec = tween(DURATION, easing = EmphasizedAccelerate)
    ) + fadeOut(animationSpec = tween(DURATION / 2, easing = EmphasizedAccelerate))
}
