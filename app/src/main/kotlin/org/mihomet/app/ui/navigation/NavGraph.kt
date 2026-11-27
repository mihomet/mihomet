package org.mihomet.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import org.mihomet.app.ui.screens.connections.ConnectionsScreen
import org.mihomet.app.ui.screens.dashboard.DashboardScreen
import org.mihomet.app.ui.screens.logs.LogsScreen
import org.mihomet.app.ui.screens.profiles.ProfilesScreen
import org.mihomet.app.ui.screens.profiles.YamlEditorScreen
import org.mihomet.app.ui.screens.proxies.ProxiesScreen
import org.mihomet.app.ui.screens.routing.RoutingScreen
import org.mihomet.app.ui.screens.routing.SiteRulesScreen
import org.mihomet.app.ui.screens.settings.SettingsScreen
import org.mihomet.app.core.DeepLinkBus

internal fun getNavTabOrder(route: String?): Int = bottomNavItems.indexOfFirst { it.route == route }

internal fun tabMovesForward(fromRoute: String?, toRoute: String?): Boolean =
    getNavTabOrder(toRoute) > getNavTabOrder(fromRoute)

private fun isDetailRoute(route: String?): Boolean {
    return route != null && (
        route == Screen.Routing.route ||
        route == Screen.SiteRules.route ||
        route == Screen.Connections.route ||
        route == Screen.Logs.route ||
        route.startsWith("yaml_editor")
    )
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    paddingValues: PaddingValues,
    bottomBarPadding: Dp = 0.dp
) {
    val importNavigation by DeepLinkBus.navigationRequest.collectAsStateWithLifecycle()
    LaunchedEffect(importNavigation) {
        if (importNavigation != 0L) {
            navController.navigate(Screen.Profiles.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            DeepLinkBus.acknowledgeNavigation(importNavigation)
        }
    }
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = Modifier.padding(paddingValues),
        enterTransition = {
            val targetRoute = targetState.destination.route
            val fromIndex = getNavTabOrder(initialState.destination.route)
            val toIndex = getNavTabOrder(targetRoute)
            when {
                isDetailRoute(targetRoute) -> AppTransitions.detailEnter()
                fromIndex == toIndex && fromIndex != -1 -> EnterTransition.None
                else -> AppTransitions.tabEnter(tabMovesForward(initialState.destination.route, targetState.destination.route))
            }
        },
        exitTransition = {
            val targetRoute = targetState.destination.route
            val fromIndex = getNavTabOrder(initialState.destination.route)
            val toIndex = getNavTabOrder(targetRoute)
            when {
                isDetailRoute(targetRoute) -> AppTransitions.detailExit()
                fromIndex == toIndex && fromIndex != -1 -> ExitTransition.None
                else -> AppTransitions.tabExit(tabMovesForward(initialState.destination.route, targetState.destination.route))
            }
        },
        popEnterTransition = {
            val initialRoute = initialState.destination.route
            val fromIndex = getNavTabOrder(initialRoute)
            val toIndex = getNavTabOrder(targetState.destination.route)
            when {
                isDetailRoute(initialRoute) -> AppTransitions.detailPopEnter()
                fromIndex == toIndex && fromIndex != -1 -> EnterTransition.None
                else -> AppTransitions.tabEnter(tabMovesForward(initialState.destination.route, targetState.destination.route))
            }
        },
        popExitTransition = {
            val initialRoute = initialState.destination.route
            val fromIndex = getNavTabOrder(initialRoute)
            val toIndex = getNavTabOrder(targetState.destination.route)
            when {
                isDetailRoute(initialRoute) -> AppTransitions.detailPopExit()
                fromIndex == toIndex && fromIndex != -1 -> ExitTransition.None
                else -> AppTransitions.tabExit(tabMovesForward(initialState.destination.route, targetState.destination.route))
            }
        }
    ) {
        composable(Screen.Dashboard.route) {
            Box(Modifier.fillMaxSize().padding(bottom = bottomBarPadding)) {
                DashboardScreen(
                    onNavigateToServers = {
                        navController.navigate(Screen.Proxies.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToConnections = { navController.navigate(Screen.Connections.route) },
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
                )
            }
        }

        composable(Screen.Proxies.route) {
            Box(Modifier.fillMaxSize().padding(bottom = bottomBarPadding)) {
                ProxiesScreen()
            }
        }

        composable(Screen.Profiles.route) {
            Box(Modifier.fillMaxSize().padding(bottom = bottomBarPadding)) {
                ProfilesScreen(
                    onEditProfileYaml = { profileId ->
                        navController.navigate(Screen.YamlEditor.createRoute(profileId))
                    }
                )
            }
        }

        composable(Screen.Routing.route) {
            RoutingScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            Box(Modifier.fillMaxSize().padding(bottom = bottomBarPadding)) {
                SettingsScreen(
                    onNavigateToRouting = { navController.navigate(Screen.Routing.route) },
                    onNavigateToSiteRules = { navController.navigate(Screen.SiteRules.route) },
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                    onNavigateToConnections = { navController.navigate(Screen.Connections.route) }
                )
            }
        }

        composable(Screen.SiteRules.route) {
            SiteRulesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Connections.route) {
            ConnectionsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Logs.route) {
            LogsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.YamlEditor.route,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType })
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: 0L
            YamlEditorScreen(
                profileId = profileId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
