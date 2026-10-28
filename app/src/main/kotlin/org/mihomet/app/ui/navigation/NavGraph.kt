package org.mihomet.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isDetailRoute(targetRoute)) {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(340, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(280))
            } else {
                val fromIndex = getNavTabOrder(initialRoute)
                val toIndex = getNavTabOrder(targetRoute)
                val direction = if (toIndex >= fromIndex) 1 else -1
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> (fullWidth * 0.22f * direction).toInt() },
                    animationSpec = tween(320, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing))
            }
        },
        exitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isDetailRoute(targetRoute)) {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> (-fullWidth * 0.25f).toInt() },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
            } else {
                val fromIndex = getNavTabOrder(initialRoute)
                val toIndex = getNavTabOrder(targetRoute)
                val direction = if (toIndex >= fromIndex) -1 else 1
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> (fullWidth * 0.22f * direction).toInt() },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing))
            }
        },
        popEnterTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isDetailRoute(initialRoute)) {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> (-fullWidth * 0.25f).toInt() },
                    animationSpec = tween(320, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing))
            } else {
                val fromIndex = getNavTabOrder(initialRoute)
                val toIndex = getNavTabOrder(targetRoute)
                val direction = if (toIndex >= fromIndex) 1 else -1
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> (fullWidth * 0.22f * direction).toInt() },
                    animationSpec = tween(320, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing))
            }
        },
        popExitTransition = {
            val initialRoute = initialState.destination.route
            if (isDetailRoute(initialRoute)) {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing))
            } else {
                val targetRoute = targetState.destination.route
                val fromIndex = getNavTabOrder(initialRoute)
                val toIndex = getNavTabOrder(targetRoute)
                val direction = if (toIndex >= fromIndex) -1 else 1
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> (fullWidth * 0.22f * direction).toInt() },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
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
                onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                onNavigateToConnections = { navController.navigate(Screen.Connections.route) }
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
