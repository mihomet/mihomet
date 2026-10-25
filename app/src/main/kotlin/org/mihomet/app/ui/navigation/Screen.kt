package org.mihomet.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Proxies : Screen("proxies", "Proxies", Icons.AutoMirrored.Filled.AltRoute)
    data object Profiles : Screen("profiles", "Profiles", Icons.Default.Layers)
    data object Routing : Screen("routing", "Routing", Icons.Default.Security)
    data object Connections : Screen("connections", "Connections", Icons.Default.Hub)
    data object Logs : Screen("logs", "Logs", Icons.Default.Terminal)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    data object YamlEditor : Screen("yaml_editor/{profileId}", "YAML Editor", Icons.Default.Edit) {
        fun createRoute(profileId: Long): String = "yaml_editor/$profileId"
    }
}

val bottomNavItems = listOf(
    Screen.Dashboard,
    Screen.Proxies,
    Screen.Profiles,
    Screen.Routing,
    Screen.Settings
)
