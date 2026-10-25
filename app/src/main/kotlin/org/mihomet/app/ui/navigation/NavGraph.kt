package org.mihomet.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import org.mihomet.app.ui.screens.settings.SettingsScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    paddingValues: PaddingValues
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = Modifier.padding(paddingValues)
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
