package dev.beam.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/** The stages the blob morphs between. One shape per thing the app is doing. */
enum class BeamShape { Idle, Searching, Transferring, Done, Failed }

/**
 * The single piece of UI that carries the whole interaction.
 *
 * Rather than a progress bar and a status string, Beam shows one large shape that morphs
 * between states: a slowly breathing star while it waits for a tap, a tightening circle while
 * it searches, a filling ring during transfer, a settled circle when done. Shape morphing plus
 * spring motion is the most legible way to say "something is happening between these two
 * phones" at arm's length, with two people holding phones and nobody reading labels.
 */
@Composable
fun BeamBlob(
    shape: BeamShape,
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    trackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
) {
    // Polygons are expensive to build; they never change, so build them once.
    val shapes = remember { BlobShapes() }

    val target = when (shape) {
        BeamShape.Idle -> 0f
        BeamShape.Searching -> 1f
        BeamShape.Transferring -> 2f
        BeamShape.Done, BeamShape.Failed -> 3f
    }

    // Spring, not tween: the overshoot is what makes the state change feel physical.
    val morphPosition by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "blobMorph",
    )

    val infinite = rememberInfiniteTransition(label = "blobIdle")

    // Idle and searching rotate; a transferring or finished blob holds still so the progress
    // ring is readable.
    val spinSpeed = when (shape) {
        BeamShape.Idle -> 18_000
        BeamShape.Searching -> 4_000
        else -> 0
    }
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(spinSpeed.coerceAtLeast(1), easing = androidx.compose.animation.core.LinearEasing)),
        label = "blobSpin",
    )

    val pulse by infinite.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            tween(1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blobPulse",
    )

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "blobProgress",
    )

    val scale = if (shape == BeamShape.Idle || shape == BeamShape.Searching) pulse else 1f

    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        if (shape == BeamShape.Transferring) {
            drawProgressRing(animatedProgress, color, trackColor, radius)
        }

        rotate(if (spinSpeed > 0) rotation else 0f) {
            drawPath(shapes.pathAt(morphPosition, radius * 0.72f * scale, center), color)
        }
    }
}

/** Builds the polygon set and the morphs between neighbouring stages once. */
private class BlobShapes {

    private val star = RoundedPolygon.star(
        numVerticesPerRadius = 7,
        innerRadius = 0.78f,
        rounding = CornerRounding(0.35f),
    )
    private val soft = RoundedPolygon.star(
        numVerticesPerRadius = 5,
        innerRadius = 0.88f,
        rounding = CornerRounding(0.5f),
    )
    private val pill = RoundedPolygon(numVertices = 4, rounding = CornerRounding(0.6f))
    private val circle = RoundedPolygon.circle(numVertices = 12)

    private val stages = listOf(star, soft, pill, circle)
    private val morphs = stages.zipWithNext().map { (from, to) -> Morph(from, to) }

    /**
     * [position] walks 0..3 across the four stages, morphing between the two it sits between.
     *
     * graphics-shapes works in a unit square around the origin, so the result is scaled and
     * centred onto the canvas here. The scratch path is reused: this runs every frame.
     */
    fun pathAt(position: Float, radius: Float, center: Offset): Path {
        val clamped = position.coerceIn(0f, (stages.size - 1).toFloat())
        val index = clamped.toInt().coerceAtMost(morphs.size - 1)
        val fraction = clamped - index

        scratch.rewind()
        morphs[index].toPath(progress = fraction, path = scratch)
        matrix.setScale(radius, radius)
        matrix.postTranslate(center.x, center.y)
        scratch.transform(matrix)
        return scratch.asComposePath()
    }

    private val scratch = android.graphics.Path()
    private val matrix = android.graphics.Matrix()
}

private fun DrawScope.drawProgressRing(
    progress: Float,
    color: Color,
    trackColor: Color,
    radius: Float,
) {
    val stroke = radius * 0.08f
    val inset = radius * 0.06f
    drawCircle(
        color = trackColor,
        radius = radius - inset,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
    )
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(
            (radius - inset) * 2f,
            (radius - inset) * 2f,
        ),
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        ),
    )
}
