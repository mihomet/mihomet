package org.mihomet.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.ui.navigation.Screen
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.HapticType

/**
 * Floating M3E navigation bar on the "Edition 2" language: a detached stadium
 * capsule (no border, tone + soft shadow only) where the selected icon sits
 * inside a springy pill indicator that stretches when chosen. Items keep a
 * spring press squeeze; haptics stay tactile.
 */
@Composable
fun ExpressiveNavigationBar(
    items: List<Screen>,
    currentRoute: String?,
    onItemSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp)
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                spotColor = Color.Black.copy(alpha = 0.4f),
                ambientColor = Color.Black.copy(alpha = 0.3f)
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { screen ->
                val isSelected = currentRoute == screen.route
                val screenTitle = stringResource(screen.titleRes)

                // The pill indicator stretches under the selected icon.
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isSelected) 58.dp else 40.dp,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "navIndicatorWidth"
                )

                val indicatorColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "navIndicatorColor"
                )

                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "navIconColor"
                )

                val labelColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "navLabelColor"
                )

                val interactionSource = remember { MutableInteractionSource() }
                val pressScale = rememberPressScale(interactionSource, pressedScale = 0.9f)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .scale(pressScale)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            if (!isSelected) {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                onItemSelected(screen)
                            }
                        }
                        .padding(horizontal = 4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(indicatorWidth)
                            .height(34.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                    ) {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screenTitle,
                            tint = iconColor,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = screenTitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                        letterSpacing = if (isSelected) 0.4.sp else 0.5.sp,
                        color = labelColor
                    )
                }
            }
        }
    }
}
