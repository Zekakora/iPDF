package com.doge.ipdf

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

@Composable
private fun resolvedTint(tint: Color): Color =
    if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint

@Composable
fun ChevronGlyph(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "chevronRotation",
    )
    Canvas(modifier) {
        rotate(rotation) {
            val w = size.width
            val h = size.height
            val stroke = 1.7.dp.toPx()
            drawLine(color, Offset(w * 0.22f, h * 0.38f), Offset(w * 0.5f, h * 0.64f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.64f), Offset(w * 0.78f, h * 0.38f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
fun FolderGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.28f)
            lineTo(w * 0.10f, h * 0.80f)
            lineTo(w * 0.90f, h * 0.80f)
            lineTo(w * 0.90f, h * 0.38f)
            lineTo(w * 0.46f, h * 0.38f)
            lineTo(w * 0.37f, h * 0.28f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun FolderPlusGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.26f)
            lineTo(w * 0.08f, h * 0.78f)
            lineTo(w * 0.92f, h * 0.78f)
            lineTo(w * 0.92f, h * 0.36f)
            lineTo(w * 0.47f, h * 0.36f)
            lineTo(w * 0.38f, h * 0.26f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(color, Offset(w * 0.5f, h * 0.48f), Offset(w * 0.5f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.58f), Offset(w * 0.60f, h * 0.58f), stroke, StrokeCap.Round)
    }
}

@Composable
fun FileGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.27f, h * 0.08f)
            lineTo(w * 0.60f, h * 0.08f)
            lineTo(w * 0.79f, h * 0.27f)
            lineTo(w * 0.79f, h * 0.92f)
            lineTo(w * 0.27f, h * 0.92f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(color, Offset(w * 0.60f, h * 0.08f), Offset(w * 0.60f, h * 0.27f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.60f, h * 0.27f), Offset(w * 0.79f, h * 0.27f), stroke, StrokeCap.Round)
    }
}

@Composable
fun SearchGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        drawCircle(
            color = color,
            radius = w * 0.28f,
            center = Offset(w * 0.42f, h * 0.42f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color,
            Offset(w * 0.63f, h * 0.63f),
            Offset(w * 0.87f, h * 0.87f),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
fun RefreshGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        val arcSize = Size(w * 0.68f, h * 0.68f)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 275f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.16f),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Arrow head at the arc start (upper right).
        drawLine(color, Offset(w * 0.755f, h * 0.13f), Offset(w * 0.755f, h * 0.32f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.755f, h * 0.13f), Offset(w * 0.575f, h * 0.17f), stroke, StrokeCap.Round)
    }
}

@Composable
fun PlusGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), stroke, StrokeCap.Round)
    }
}

@Composable
fun CheckGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.52f)
            lineTo(w * 0.43f, h * 0.76f)
            lineTo(w * 0.84f, h * 0.28f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun CloseGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), stroke, StrokeCap.Round)
    }
}

@Composable
fun HistoryGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        drawCircle(
            color = color,
            radius = w * 0.33f,
            center = Offset(w * 0.5f, h * 0.5f),
            style = Stroke(width = stroke),
        )
        drawLine(color, Offset(w * 0.5f, h * 0.30f), Offset(w * 0.5f, h * 0.52f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.52f), Offset(w * 0.66f, h * 0.62f), stroke, StrokeCap.Round)
    }
}

@Composable
fun CalendarGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.13f, h * 0.20f),
            size = Size(w * 0.74f, h * 0.66f),
            cornerRadius = CornerRadius(w * 0.09f, h * 0.09f),
            style = Stroke(width = stroke),
        )
        drawLine(color, Offset(w * 0.13f, h * 0.36f), Offset(w * 0.87f, h * 0.36f), stroke)
        drawLine(color, Offset(w * 0.32f, h * 0.12f), Offset(w * 0.32f, h * 0.27f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, h * 0.12f), Offset(w * 0.68f, h * 0.27f), stroke, StrokeCap.Round)
        val dot = w * 0.07f
        for (row in 0..1) {
            for (column in 0..2) {
                drawCircle(
                    color = color,
                    radius = dot,
                    center = Offset(w * (0.31f + column * 0.19f), h * (0.53f + row * 0.18f)),
                )
            }
        }
    }
}

@Composable
fun RoundedSquareGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val color = resolvedTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val radius = w * 0.18f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.14f, h * 0.14f),
            size = Size(w * 0.72f, h * 0.72f),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
    }
}
