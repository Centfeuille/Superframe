package com.example.inbetween

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * Custom icons drawn in the Material glyph style used by the rest of the app: 24 x 24 viewport, one flat colour (the Icon tint is applied on top),
 * rounded 2-unit strokes, no outlines or shading. Used as `Icon(ReshapeIcon, ...)`.
 */

/**
 * Reshape tool: a flat line (faint) pulled up into a smooth bump by a round handle. The handle is the brush circle the tool shows on the canvas.
 */
val ReshapeIcon: ImageVector by lazy {
    ImageVector.Builder("Reshape", 24.dp, 24.dp, 24f, 24f).apply {
        // the original, undeformed line
        path(stroke = SolidColor(Color.Black), strokeAlpha = 0.4f, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 18f); lineTo(21f, 18f)
        }
        // the line pulled into a bump
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 18f)
            curveTo(8.5f, 18f, 8.5f, 7f, 12f, 7f)
            curveTo(15.5f, 7f, 15.5f, 18f, 21f, 18f)
        }
        // the handle
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3.8f)
            arcTo(3.2f, 3.2f, 0f, true, true, 12f, 10.2f)
            arcTo(3.2f, 3.2f, 0f, true, true, 12f, 3.8f)
            close()
        }
    }.build()
}

/**
 * Ghost line button: a ghost silhouette that fades out towards the bottom (the transparency comes from a vertical alpha gradient, so it also works
 * with any tint). Eyes are cut out of the body.
 */
val GhostFadeIcon: ImageVector by lazy {
    ImageVector.Builder("GhostFade", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = ComposeBrush.linearGradient(
                0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.1f),
                start = Offset(0f, 2f), end = Offset(0f, 22f)
            ),
            pathFillType = PathFillType.EvenOdd
        ) {
            // body: round head, straight sides, wavy hem
            moveTo(12f, 2f)
            curveTo(7.6f, 2f, 4f, 5.6f, 4f, 10f)
            lineTo(4f, 21f)
            lineTo(6.67f, 19f)
            lineTo(9.33f, 21f)
            lineTo(12f, 19f)
            lineTo(14.67f, 21f)
            lineTo(17.33f, 19f)
            lineTo(20f, 21f)
            lineTo(20f, 10f)
            curveTo(20f, 5.6f, 16.4f, 2f, 12f, 2f)
            close()
            // left eye
            moveTo(9f, 8.5f)
            arcTo(1.5f, 1.5f, 0f, true, true, 9f, 11.5f)
            arcTo(1.5f, 1.5f, 0f, true, true, 9f, 8.5f)
            close()
            // right eye
            moveTo(15f, 8.5f)
            arcTo(1.5f, 1.5f, 0f, true, true, 15f, 11.5f)
            arcTo(1.5f, 1.5f, 0f, true, true, 15f, 8.5f)
            close()
        }
    }.build()
}

/*
 * Tool sub-option icons (Line tool shapes, Bezier handle count, Reshape modes, Selection operations and toggles). Same art direction as above: 24 x 24,
 * flat, one colour, rounded 2-unit strokes; the faint (40 %) parts show what the option changes or the "before".
 */

private fun ImageVector.Builder.line(alpha: Float = 1f, w: Float = 2f, block: PathBuilder.() -> Unit): ImageVector.Builder =
    path(stroke = SolidColor(Color.Black), strokeAlpha = alpha, strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit): ImageVector.Builder = path(fill = SolidColor(Color.Black), pathBuilder = block)

private fun PathBuilder.dot(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy); arcTo(r, r, 0f, true, true, cx + r, cy); arcTo(r, r, 0f, true, true, cx - r, cy); close()
}

/** Four corner brackets: the selection frame. */
private fun PathBuilder.brackets(l: Float, t: Float, r: Float, b: Float, n: Float) {
    moveTo(l, t + n); lineTo(l, t); lineTo(l + n, t)
    moveTo(r - n, t); lineTo(r, t); lineTo(r, t + n)
    moveTo(r, b - n); lineTo(r, b); lineTo(r - n, b)
    moveTo(l + n, b); lineTo(l, b); lineTo(l, b - n)
}

/** Line tool: a straight segment between two end points. */
val OptLineIcon: ImageVector by lazy {
    ImageVector.Builder("OptLine", 24.dp, 24.dp, 24f, 24f).apply {
        line { moveTo(6f, 18f); lineTo(18f, 6f) }
        solid { dot(5f, 19f, 2.2f); dot(19f, 5f, 2.2f) }
    }.build()
}

val OptRectIcon: ImageVector by lazy {
    ImageVector.Builder("OptRect", 24.dp, 24.dp, 24f, 24f).apply {
        line { moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 18f); lineTo(4f, 18f); close() }
    }.build()
}

val OptCircleIcon: ImageVector by lazy {
    ImageVector.Builder("OptCircle", 24.dp, 24.dp, 24f, 24f).apply {
        line { moveTo(4f, 12f); arcTo(8f, 8f, 0f, true, true, 20f, 12f); arcTo(8f, 8f, 0f, true, true, 4f, 12f); close() }
    }.build()
}

val OptEllipseIcon: ImageVector by lazy {
    ImageVector.Builder("OptEllipse", 24.dp, 24.dp, 24f, 24f).apply {
        line { moveTo(2.5f, 12f); arcTo(9.5f, 5.5f, 0f, true, true, 21.5f, 12f); arcTo(9.5f, 5.5f, 0f, true, true, 2.5f, 12f); close() }
    }.build()
}

/** Bezier curve with two handles (cubic): an S curve, both end points pulled by a handle. */
val OptBezierTwoIcon: ImageVector by lazy {
    ImageVector.Builder("OptBezierTwo", 24.dp, 24.dp, 24f, 24f).apply {
        line(alpha = 0.4f, w = 1.5f) { moveTo(4f, 19f); lineTo(4f, 7f); moveTo(20f, 5f); lineTo(20f, 17f) }
        line { moveTo(4f, 19f); curveTo(4f, 7f, 20f, 17f, 20f, 5f) }
        solid { dot(4f, 19f, 2f); dot(20f, 5f, 2f); dot(4f, 7f, 1.7f); dot(20f, 17f, 1.7f) }
    }.build()
}

/** Bezier curve with one handle (quadratic): an arch, both end points pulled by the same handle. */
val OptBezierOneIcon: ImageVector by lazy {
    ImageVector.Builder("OptBezierOne", 24.dp, 24.dp, 24f, 24f).apply {
        line(alpha = 0.4f, w = 1.5f) { moveTo(4f, 19f); lineTo(12f, 3f); lineTo(20f, 19f) }
        line { moveTo(4f, 19f); quadTo(12f, 3f, 20f, 19f) }
        solid { dot(4f, 19f, 2f); dot(20f, 19f, 2f); dot(12f, 3f, 1.7f) }
    }.build()
}

/** Reshape / Shift: the brush circle pushing along (arrow). */
val OptShiftIcon: ImageVector by lazy {
    ImageVector.Builder("OptShift", 24.dp, 24.dp, 24f, 24f).apply {
        solid { dot(7f, 12f, 3.6f) }
        line { moveTo(13.5f, 12f); lineTo(21f, 12f); moveTo(17.5f, 8f); lineTo(21.5f, 12f); lineTo(17.5f, 16f) }
    }.build()
}

/** Reshape / Smoothen: a jagged line (faint) relaxed into a smooth wave. */
val OptSmoothenIcon: ImageVector by lazy {
    ImageVector.Builder("OptSmoothen", 24.dp, 24.dp, 24f, 24f).apply {
        line(alpha = 0.4f) { moveTo(3f, 9f); lineTo(7f, 4f); lineTo(11f, 9f); lineTo(15f, 4f); lineTo(21f, 9f) }
        line { moveTo(3f, 17f); curveTo(6f, 11f, 9f, 11f, 12f, 17f); curveTo(15f, 23f, 18f, 23f, 21f, 17f) }
    }.build()
}

/** Reshape / Distort: a straight line (faint) bent into an irregular wobble. */
val OptDistortIcon: ImageVector by lazy {
    ImageVector.Builder("OptDistort", 24.dp, 24.dp, 24f, 24f).apply {
        line(alpha = 0.4f) { moveTo(3f, 12f); lineTo(21f, 12f) }
        line { moveTo(3f, 12f); curveTo(4.5f, 6f, 7.5f, 6f, 9f, 12f); curveTo(10f, 15f, 11.5f, 17f, 13f, 13f); curveTo(14f, 9f, 16f, 8f, 17f, 11f); curveTo(18f, 13.5f, 19.5f, 13f, 21f, 12f) }
    }.build()
}

/** Selection operation New: a frame with a solid centre (the selection is replaced). */
val OptSelNewIcon: ImageVector by lazy {
    ImageVector.Builder("OptSelNew", 24.dp, 24.dp, 24f, 24f).apply {
        line { brackets(3f, 3f, 21f, 21f, 6f) }
        solid { dot(12f, 12f, 2.4f) }
    }.build()
}

/** Selection operation Add: a frame with a plus. */
val OptSelAddIcon: ImageVector by lazy {
    ImageVector.Builder("OptSelAdd", 24.dp, 24.dp, 24f, 24f).apply {
        line { brackets(3f, 3f, 16f, 16f, 5f) }
        line { moveTo(14.5f, 19f); lineTo(21f, 19f); moveTo(17.75f, 15.75f); lineTo(17.75f, 22.25f) }
    }.build()
}

/** Selection operation Erase: a frame with a minus. */
val OptSelEraseIcon: ImageVector by lazy {
    ImageVector.Builder("OptSelErase", 24.dp, 24.dp, 24f, 24f).apply {
        line { brackets(3f, 3f, 16f, 16f, 5f) }
        line { moveTo(14.5f, 19f); lineTo(21f, 19f) }
    }.build()
}

/** Stroke-aware selection: a wavy stroke with a faint outline hugging it. */
val OptStrokeAwareIcon: ImageVector by lazy {
    ImageVector.Builder("OptStrokeAware", 24.dp, 24.dp, 24f, 24f).apply {
        line(alpha = 0.45f, w = 1.5f) {
            moveTo(3f, 10f); curveTo(8f, 2f, 14f, 14f, 21f, 6f); lineTo(21f, 14f); curveTo(14f, 22f, 8f, 10f, 3f, 18f); close()
        }
        line { moveTo(3f, 14f); curveTo(8f, 6f, 14f, 18f, 21f, 10f) }
    }.build()
}

/** Auto fill the selection: a frame with a filled body. */
val OptAutoFillIcon: ImageVector by lazy {
    ImageVector.Builder("OptAutoFill", 24.dp, 24.dp, 24f, 24f).apply {
        line { brackets(3f, 3f, 21f, 21f, 6f) }
        path(fill = SolidColor(Color.Black)) { moveTo(8f, 8f); lineTo(16f, 8f); lineTo(16f, 16f); lineTo(8f, 16f); close() }
    }.build()
}

/** Draw and paint inside the selection only: a frame with a brush stroke inside (the faint tails are what is not painted). */
val OptPaintInSelIcon: ImageVector by lazy {
    ImageVector.Builder("OptPaintInSel", 24.dp, 24.dp, 24f, 24f).apply {
        line { brackets(3f, 3f, 21f, 21f, 6f) }
        line(w = 3f) { moveTo(8.5f, 15.5f); lineTo(15.5f, 8.5f) }
    }.build()
}

/** Auto gap closing (Fill): a ring with a gap that is bridged (faint). */
val OptGapCloseIcon: ImageVector by lazy {
    ImageVector.Builder("OptGapClose", 24.dp, 24.dp, 24f, 24f).apply {
        line { moveTo(18.93f, 8f); arcTo(8f, 8f, 0f, true, true, 16f, 5.07f) }
        line(alpha = 0.4f) { moveTo(16f, 5.07f); lineTo(18.93f, 8f) }
    }.build()
}
