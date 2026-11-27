package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.ui.navigation.bottomNavItems
import org.mihomet.app.ui.navigation.tabMovesForward

class NavigationDirectionTest {
    @Test fun `all tab transitions follow their visible dock order including return to dashboard`() {
        bottomNavItems.forEachIndexed { from, source ->
            bottomNavItems.forEachIndexed { to, target ->
                assertEquals("${source.route} -> ${target.route}", to > from, tabMovesForward(source.route, target.route))
            }
        }
    }
}
