package org.mihomet.app.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.data.api.TrafficSample
import org.mihomet.app.ui.theme.AccentBlue
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.AccentGreenLight
import kotlin.math.max

@Composable
fun TrafficGraph(
    history: List<TrafficSample>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(140.dp)
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val uploadColor = AccentBlue
    val downloadColor = AccentGreen

    Column(modifier = modifier) {
        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(downloadColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Входящий",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(uploadColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Исходящий",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val width = size.width
            val height = size.height

            // Horizontal grid lines
            val gridLines = 3
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
                1024L
            ).toFloat()

            val stepX = width / (history.size - 1)

            val downPath = Path()
            val downFillPath = Path()
            val upPath = Path()

            history.forEachIndexed { index, sample ->
                val x = index * stepX
                val downY = height - (sample.down.toFloat() / maxVal * (height - 12f)) - 6f
                val upY = height - (sample.up.toFloat() / maxVal * (height - 12f)) - 6f

                if (index == 0) {
                    downPath.moveTo(x, downY)
                    downFillPath.moveTo(x, height)
                    downFillPath.lineTo(x, downY)
                    upPath.moveTo(x, upY)
                } else {
                    val prevX = (index - 1) * stepX
                    val prevDownY = height - (history[index - 1].down.toFloat() / maxVal * (height - 12f)) - 6f
                    val prevUpY = height - (history[index - 1].up.toFloat() / maxVal * (height - 12f)) - 6f

                    val cX = (prevX + x) / 2f
                    downPath.cubicTo(cX, prevDownY, cX, downY, x, downY)
                    downFillPath.cubicTo(cX, prevDownY, cX, downY, x, downY)
                    upPath.cubicTo(cX, prevUpY, cX, upY, x, upY)
                }

                if (index == history.size - 1) {
                    downFillPath.lineTo(x, height)
                    downFillPath.close()
                }
            }

            // Draw download gradient fill
            drawPath(
                path = downFillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        downloadColor.copy(alpha = 0.28f),
                        downloadColor.copy(alpha = 0.02f)
                    ),
                    startY = 0f,
                    endY = height
                )
            )

            // Draw smooth strokes
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
}
