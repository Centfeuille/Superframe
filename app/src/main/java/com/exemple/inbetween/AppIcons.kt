package com.example.inbetween

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
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
