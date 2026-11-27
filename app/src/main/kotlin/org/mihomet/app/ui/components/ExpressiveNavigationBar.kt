package org.mihomet.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mihomet.app.ui.navigation.Screen
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.HapticType

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveNavigationBar(items: List<Screen>, currentRoute: String?, onItemSelected: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    Surface(modifier.padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        ShortNavigationBar(modifier = Modifier.padding(horizontal = 8.dp), containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
            items.forEach { screen ->
                ShortNavigationBarItem(
                    selected = currentRoute == screen.route,
                    onClick = {
                        if (currentRoute != screen.route) { AppHaptics.perform(context, HapticType.CLICK, view); onItemSelected(screen) }
                    },
                    icon = { Icon(screen.icon, contentDescription = null) },
                    label = { Text(stringResource(screen.titleRes), modifier = Modifier.padding(horizontal = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                )
            }
        }
    }
}
