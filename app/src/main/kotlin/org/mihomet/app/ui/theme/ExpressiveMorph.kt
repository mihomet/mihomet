package org.mihomet.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.MutableCubic
import androidx.graphics.shapes.RoundedPolygon
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Material 3 Expressive shape-morph helpers — the "cookie morph" signature look,
 * built on standalone androidx.graphics:graphics-shapes (material3 1.5.x carries
 * MaterialShapes presets but requires AGP 9.1, so the two presets we need are
 * reproduced here with the same visual recipe: a rounded square that morphs into
 * a 12-lobed cookie).
 *
 * Polygons live in a normalized coordinate space, so every path is re-fitted
 * (uniformly scaled + centered) to the layout [Size]. [Morph] construction is
 * expensive — instances are cached per polygon pair; the wrappers handed out by
 * [shape] are cheap and fresh per frame so the graphicsLayer recomputes the
 * outline while the progress animates.
 */
object ExpressiveMorph {

    /** Rounded-square rest state. rectangle() spans -w/2..w/2, so with a 2-unit
     *  side the absolute corner radius 0.62 ≈ 31% of the side — the M3E squircle
     *  look. (A 1-unit rectangle clamps any radius > 0.5 into a circle.) */
    val SQUARE: RoundedPolygon by lazy {
        RoundedPolygon.rectangle(2f, 2f, CornerRounding(0.62f))
    }

    /** 12-lobed cookie — the "protected" state of the hero button. */
    val COOKIE_12: RoundedPolygon by lazy {
        RoundedPolygon.star(12, 1f, 0.87f, CornerRounding(0.28f), CornerRounding(0.28f))
    }

    private val morphCache = ConcurrentHashMap<Pair<RoundedPolygon, RoundedPolygon>, Morph>()

    fun morphFor(start: RoundedPolygon, end: RoundedPolygon): Morph =
        morphCache.getOrPut(start to end) { Morph(start, end) }

    /** Any [RoundedPolygon] rendered as a compose [Path], fitted to [size]. */
    fun path(polygon: RoundedPolygon, size: Size, out: Path): Path {
        out.rewind()
        var first = true
        for (c in polygon.cubics) {
            if (first) {
                out.moveTo(c.anchor0X, c.anchor0Y)
                first = false
            }
            out.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
        }
        out.close()
        return fitToSize(out, size)
    }

    /** [Morph] path at [progress], fitted (uniformly scaled + centered) into [size]. */
    fun path(morph: Morph, progress: Float, size: Size, out: Path): Path {
        out.rewind()
        var first = true
        val scratch = MutableCubic()
        morph.forEachCubic(progress.coerceIn(0f, 1f), scratch) { c ->
            if (first) {
                out.moveTo(c.anchor0X, c.anchor0Y)
                first = false
            }
            out.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
        }
        out.close()
        return fitToSize(out, size)
    }

    private fun fitToSize(path: Path, size: Size): Path {
        val bounds = path.getBounds()
        val scale = min(size.width, size.height) /
                max(bounds.width, bounds.height).coerceAtLeast(0.001f)
        val matrix = Matrix().apply {
            translate(size.width / 2f, size.height / 2f)
            scale(scale, scale)
            translate(-bounds.center.x, -bounds.center.y)
        }
        path.transform(matrix)
        return path
    }

    /**
     * A [Shape] driven by a live progress provider. Intended to be created
     * inside a `graphicsLayer { shape = ExpressiveMorph.shape(...); clip = true }`
     * block: the block re-executes while the progress state animates, and the
     * fresh wrapper forces the outline to be rebuilt.
     */
    fun shape(start: RoundedPolygon, end: RoundedPolygon, progress: () -> Float): Shape {
        val morph = morphFor(start, end)
        val scratch = Path()
        return object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density
            ): Outline = Outline.Generic(path(morph, progress(), size, scratch))
        }
    }

    /** Animated 0..1 morph progress with the M3E bouncy spring. */
    @Composable
    fun progress(target: Float): State<Float> =
        animateFloatAsState(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "morphProgress"
        )
}
