package org.mihomet.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Главная", Icons.Default.Shield)
    data object Proxies : Screen("proxies", "Серверы", Icons.Default.Dns)
    data object Profiles : Screen("profiles", "Подписки", Icons.Default.Bookmarks)
    data object Routing : Screen("routing", "Приложения", Icons.Default.Apps)
    data object Connections : Screen("connections", "Соединения", Icons.Default.Hub)
    data object Logs : Screen("logs", "Журнал", Icons.Default.Terminal)
    data object Settings : Screen("settings", "Настройки", Icons.Default.Settings)

    data object YamlEditor : Screen("yaml_editor/{profileId}", "Редактор YAML", Icons.Default.Edit) {
        fun createRoute(profileId: Long): String = "yaml_editor/$profileId"
    }
}

val bottomNavItems = listOf(
    Screen.Dashboard,
    Screen.Proxies,
    Screen.Profiles,
    Screen.Settings
)
