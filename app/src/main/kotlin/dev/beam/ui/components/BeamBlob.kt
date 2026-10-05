package dev.beam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * The shape *is* the progress bar. Waiting for a tap, it is a slowly turning seven-point star;
 * searching, it softens; as bytes move it settles point by point into a circle, reaching a
 * perfect one exactly as the transfer completes, where a tick is drawn through it. Two people
 * holding phones together are not reading a percentage — they are glancing at a shape, and it
 * should be obvious at arm's length whether it is still working.
 *
 * Driving the morph from [progress] rather than animating a decorative shape alongside a ring
 * is what makes it legible: there is one thing to look at, and its roundness means something.
 */
@Composable
fun BeamBlob(
    shape: BeamShape,
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    /**
     * A shape this large is a surface, not an accent, so it takes the soft container tone.
     * Filling it with `primary` makes a heavy slab of saturated colour that dominates the
     * screen; the saturated tone is spent on the progress sweep and the tick instead, where
     * it reads as emphasis.
     */
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    errorColor: Color = MaterialTheme.colorScheme.errorContainer,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    tickColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    // Polygons are costly to build and never change, so build them once.
    val shapes = remember { BlobShapes() }
    val clamped = progress.coerceIn(0f, 1f)

    /*
     * One continuous position walks the whole journey:
     *   0.0  seven-point star   waiting
     *   1.0  soft five-point    searching
     *   2.0  circle             complete
     * so a transfer is simply the walk from 1 to 2, paced by the bytes.
     */
    val target = when (shape) {
        BeamShape.Idle -> 0f
        BeamShape.Searching -> 1f
        BeamShape.Transferring -> 1f + clamped
        BeamShape.Done -> 2f
        // Stopped partway: unresolved, and it looks it.
        BeamShape.Failed -> 0.45f
    }

    // Spring, not tween: the overshoot is what makes a state change feel physical.
    val position by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = if (shape == BeamShape.Transferring) {
                // A transfer should glide, not wobble; bytes already make it move.
                Spring.DampingRatioNoBouncy
            } else {
                Spring.DampingRatioMediumBouncy
            },
            stiffness = Spring.StiffnessLow,
        ),
        label = "blobMorph",
    )

    val infinite = rememberInfiniteTransition(label = "blobIdle")

    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_PERIOD_MILLIS, easing = LinearEasing)),
        label = "blobSpin",
    )

    val breath by infinite.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            tween(1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blobBreath",
    )

    /*
     * Rotation decays as the transfer completes, so the shape visibly comes to rest rather
     * than stopping dead. Idle turns slowly, searching turns briskly.
     */
    val spinScale = when (shape) {
        BeamShape.Idle -> 0.25f
        // The pointed shape makes rotation legible, so it no longer needs to spin hard to
        // look busy; it used to be a near-circle turning fast, which just looked unsteady.
        BeamShape.Searching -> 0.7f
        BeamShape.Transferring -> 0.45f * (1f - clamped)
        BeamShape.Done, BeamShape.Failed -> 0f
    }

    val scale = if (shape == BeamShape.Idle || shape == BeamShape.Searching) breath else 1f

    val fill by animateColorAsState(
        targetValue = if (shape == BeamShape.Failed) errorColor else color,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "blobColor",
    )

    val ringAlpha by animateFloatAsState(
        targetValue = if (shape == BeamShape.Transferring) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ringAlpha",
    )

    val tick by animateFloatAsState(
        targetValue = if (shape == BeamShape.Done) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "tick",
    )

    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)

        if (ringAlpha > 0.01f) {
            drawProgressRing(clamped, accentColor, trackColor, radius, ringAlpha)
        }

        // The ring sits outside the shape, so the shape shrinks slightly to make room for it.
        val bodyRadius = radius * (BODY_FRACTION - RING_INSET_FRACTION * ringAlpha) * scale

        rotate(if (spinScale > 0f) spin * spinScale else 0f) {
            drawPath(shapes.pathAt(position, bodyRadius, centre), fill)
        }

        // The bolt gives the shape a subject; it hands over to the tick on completion.
        if (tick < 0.99f) {
            drawBolt(centre, radius * 0.34f, tickColor, alpha = 1f - tick)
        }
        if (tick > 0.01f) {
            drawTick(centre, radius * 0.30f, tick, tickColor)
        }
    }
}

/** Builds the polygon set and the morphs between neighbouring stages once. */
private class BlobShapes {

    /*
     * Every stage has the same vertex count, and that is the whole trick.
     *
     * `star(numVerticesPerRadius = n)` produces 2n vertices. The first version of this used
     * star(7) → star(5) → circle(16), i.e. 14 → 10 → 16, so each morph had to interpolate
     * between outlines whose points did not correspond. graphics-shapes will do it, but the
     * in-between shapes bulge asymmetrically — which is only visible in the states that *are*
     * in-between, namely searching and mid-transfer. The endpoints looked fine, which is why
     * idle and done always did.
     *
     * With a shared count of 14 the correspondence is one-to-one and the interpolation is
     * clean the whole way through.
     */
    private val star = RoundedPolygon.star(
        numVerticesPerRadius = POINTS,
        innerRadius = 0.80f,
        rounding = CornerRounding(0.35f),
    )

    /** Pointier than idle, not softer: searching should look like reaching out. */
    private val soft = RoundedPolygon.star(
        numVerticesPerRadius = POINTS,
        innerRadius = 0.62f,
        rounding = CornerRounding(0.26f),
    )

    private val circle = RoundedPolygon.circle(numVertices = POINTS * 2)

    private val stages = listOf(star, soft, circle)
    private val morphs = stages.zipWithNext().map { (from, to) -> Morph(from, to) }

    private val scratch = android.graphics.Path()
    private val matrix = android.graphics.Matrix()

    private companion object {
        /** 7 points per radius = 14 vertices, shared by every stage. */
        const val POINTS = 7
    }

    /**
     * [position] walks 0..2 across the three stages, morphing between the two it sits between.
     *
     * graphics-shapes works in a unit square around the origin, so the result is scaled and
     * centred here. The scratch path and matrix are reused: this runs every frame.
     */
    fun pathAt(position: Float, radius: Float, centre: Offset): Path {
        val clamped = position.coerceIn(0f, (stages.size - 1).toFloat())
        val index = clamped.toInt().coerceAtMost(morphs.size - 1)
        val fraction = clamped - index

        scratch.rewind()
        morphs[index].toPath(progress = fraction, path = scratch)
        matrix.setScale(radius, radius)
        matrix.postTranslate(centre.x, centre.y)
        scratch.transform(matrix)
        return scratch.asComposePath()
    }
}

/** A hairline track with a rounded sweep over it. Thin, so the shape stays the subject. */
private fun DrawScope.drawProgressRing(
    progress: Float,
    color: Color,
    trackColor: Color,
    radius: Float,
    alpha: Float,
) {
    val stroke = radius * 0.055f
    val inset = stroke / 2f + radius * 0.01f
    val diameter = (radius - inset) * 2f

    drawCircle(
        color = trackColor,
        radius = radius - inset,
        alpha = alpha * 0.5f,
        style = Stroke(width = stroke),
    )
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(diameter, diameter),
        alpha = alpha,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

/**
 * A tick drawn through the finished circle, its two strokes appearing in sequence so it reads
 * as being written rather than appearing.
 */
private fun DrawScope.drawTick(centre: Offset, radius: Float, progress: Float, color: Color) {
    val start = Offset(centre.x - radius * 0.75f, centre.y + radius * 0.05f)
    val elbow = Offset(centre.x - radius * 0.2f, centre.y + radius * 0.6f)
    val end = Offset(centre.x + radius * 0.8f, centre.y - radius * 0.55f)
    val stroke = radius * 0.22f

    // The short stroke is drawn over the first 40% of the animation, the long one after.
    val first = (progress / 0.4f).coerceIn(0f, 1f)
    drawLine(color, start, lerp(start, elbow, first), stroke, StrokeCap.Round)

    if (progress > 0.4f) {
        val second = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
        drawLine(color, elbow, lerp(elbow, end, second), stroke, StrokeCap.Round)
    }
}

/**
 * Beam's bolt, the same outline as the launcher and notification icons, so the shape is
 * recognisably this app rather than a generic blob. Coordinates are the icon's own 24dp
 * viewport, remapped around the centre.
 */
private fun DrawScope.drawBolt(centre: Offset, radius: Float, color: Color, alpha: Float) {
    val points = listOf(
        13f to 2f,
        4.5f to 13.5f,
        9.7f to 13.5f,
        9f to 22f,
        17.5f to 10.5f,
        12.3f to 10.5f,
    )

    val path = Path().apply {
        points.forEachIndexed { index, (x, y) ->
            val px = centre.x + (x - 12f) / 12f * radius
            val py = centre.y + (y - 12f) / 12f * radius
            if (index == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path, color, alpha = alpha)
}

private fun lerp(from: Offset, to: Offset, fraction: Float): Offset =
    Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)

/** How much of the canvas the shape itself fills, before the ring claims its margin. */
private const val BODY_FRACTION = 0.76f
private const val RING_INSET_FRACTION = 0.04f
private const val SPIN_PERIOD_MILLIS = 6_000
