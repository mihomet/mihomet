package org.mihomet.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** One shared axis and timing for tabs, details and settings categories. */
object AppTransitions {
    private const val DURATION = 300

    fun detailEnter(): EnterTransition = tabEnter(forward = true)
    fun detailExit(): ExitTransition = tabExit(forward = true)
    fun detailPopEnter(): EnterTransition = tabEnter(forward = false)
    fun detailPopExit(): ExitTransition = tabExit(forward = false)

    fun tabEnter(forward: Boolean): EnterTransition = slideInHorizontally(
        initialOffsetX = { if (forward) it / 6 else -it / 6 },
        animationSpec = tween(DURATION, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(DURATION, easing = FastOutSlowInEasing))

    fun tabExit(forward: Boolean): ExitTransition = slideOutHorizontally(
        targetOffsetX = { if (forward) -it / 6 else it / 6 },
        animationSpec = tween(DURATION, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(DURATION, easing = FastOutSlowInEasing))
}
