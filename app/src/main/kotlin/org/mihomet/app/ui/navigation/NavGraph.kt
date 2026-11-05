package org.mihomet.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
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

private fun getNavTabOrder(route: String?): Int {
    return when (route) {
        Screen.Dashboard.route -> 0
        Screen.Proxies.route -> 1
        Screen.Profiles.route -> 2
        Screen.Settings.route -> 3
        else -> -1
    }
}

private fun isDetailRoute(route: String?): Boolean {
    return route != null && (
        route == Screen.Routing.route ||
        route == Screen.Connections.route ||
        route == Screen.Logs.route ||
        route.startsWith("yaml_editor")
    )
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    paddingValues: PaddingValues
) {
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
                else -> AppTransitions.tabEnter(toIndex >= fromIndex)
            }
        },
        exitTransition = {
            val targetRoute = targetState.destination.route
            val fromIndex = getNavTabOrder(initialState.destination.route)
            val toIndex = getNavTabOrder(targetRoute)
            when {
                isDetailRoute(targetRoute) -> AppTransitions.detailExit()
                fromIndex == toIndex && fromIndex != -1 -> ExitTransition.None
                else -> AppTransitions.tabExit(toIndex >= fromIndex)
            }
        },
        popEnterTransition = {
            val initialRoute = initialState.destination.route
            val fromIndex = getNavTabOrder(initialRoute)
            val toIndex = getNavTabOrder(targetState.destination.route)
            when {
                isDetailRoute(initialRoute) -> AppTransitions.detailPopEnter()
                fromIndex == toIndex && fromIndex != -1 -> EnterTransition.None
                else -> AppTransitions.tabEnter(fromIndex > toIndex)
            }
        },
        popExitTransition = {
            val initialRoute = initialState.destination.route
            val fromIndex = getNavTabOrder(initialRoute)
            val toIndex = getNavTabOrder(targetState.destination.route)
            when {
                isDetailRoute(initialRoute) -> AppTransitions.detailPopExit()
                fromIndex == toIndex && fromIndex != -1 -> ExitTransition.None
                else -> AppTransitions.tabExit(fromIndex > toIndex)
            }
        }
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToServers = {
                    navController.navigate(Screen.Proxies.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToConnections = { navController.navigate(Screen.Connections.route) },
                onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
            )
        }

        composable(Screen.Proxies.route) {
            ProxiesScreen()
        }

        composable(Screen.Profiles.route) {
            ProfilesScreen(
                onEditProfileYaml = { profileId ->
                    navController.navigate(Screen.YamlEditor.createRoute(profileId))
                }
            )
        }

        composable(Screen.Routing.route) {
            RoutingScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToRouting = { navController.navigate(Screen.Routing.route) },
                onNavigateToSiteRules = { navController.navigate(Screen.SiteRules.route) },
                onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                onNavigateToConnections = { navController.navigate(Screen.Connections.route) }
            )
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
