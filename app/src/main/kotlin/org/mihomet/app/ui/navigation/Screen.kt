package org.mihomet.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import org.mihomet.app.R

sealed class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Default.Shield)
    data object Proxies : Screen("proxies", R.string.nav_proxies, Icons.Default.Dns)
    data object Profiles : Screen("profiles", R.string.nav_profiles, Icons.Default.Bookmarks)
    data object Routing : Screen("routing", R.string.nav_routing, Icons.Default.Apps)
    data object Connections : Screen("connections", R.string.nav_connections, Icons.Default.Hub)
    data object Logs : Screen("logs", R.string.nav_logs, Icons.Default.Terminal)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)

    data object YamlEditor : Screen("yaml_editor/{profileId}", R.string.nav_yaml_editor, Icons.Default.Edit) {
        fun createRoute(profileId: Long): String = "yaml_editor/$profileId"
    }
}

val bottomNavItems = listOf(
    Screen.Dashboard,
    Screen.Proxies,
    Screen.Profiles,
    Screen.Settings
)
