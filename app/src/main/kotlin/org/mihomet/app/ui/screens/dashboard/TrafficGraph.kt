package org.mihomet.app.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.mihomet.app.data.api.TrafficSample
import org.mihomet.app.ui.theme.AccentBlue
import org.mihomet.app.ui.theme.AccentGreen
import kotlin.math.max

@Composable
fun TrafficGraph(
    history: List<TrafficSample>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(140.dp)
) {
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val uploadColor = AccentGreen
    val downloadColor = AccentBlue

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // Draw horizontal grid lines
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = height * (i.toFloat() / gridLines)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (history.size < 2) return@Canvas

        val maxVal = max(
            history.maxOfOrNull { max(it.up, it.down) } ?: 1024L,
            1024L // Min scale: 1 KB/s
        ).toFloat()

        val stepX = width / (history.size - 1)

        val upPath = Path()
        val downPath = Path()

        history.forEachIndexed { index, sample ->
            val x = index * stepX
            val upY = height - (sample.up.toFloat() / maxVal * (height - 10f)) - 5f
            val downY = height - (sample.down.toFloat() / maxVal * (height - 10f)) - 5f

            if (index == 0) {
                upPath.moveTo(x, upY)
                downPath.moveTo(x, downY)
            } else {
                upPath.lineTo(x, upY)
                downPath.lineTo(x, downY)
            }
        }

        drawPath(
            path = downPath,
            color = downloadColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        drawPath(
            path = upPath,
            color = uploadColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
