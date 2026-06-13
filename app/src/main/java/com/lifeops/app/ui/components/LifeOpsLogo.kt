package com.lifeops.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * LifeOps brand logo — colours track the active MaterialTheme automatically.
 *
 *   bg    → colorScheme.surfaceVariant   (rounded-rect background)
 *   dial  → colorScheme.secondary        (ring + cardinal tick marks)
 *   check → colorScheme.tertiary         (checkmark needle)
 *
 * All geometry is defined in a 120×120 design space and scaled to [size].
 */
@Composable
fun LifeOpsLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    val bg    = MaterialTheme.colorScheme.surfaceVariant
    val dial  = MaterialTheme.colorScheme.secondary
    val check = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier.size(size)) {
        val s = this.size.width / 120f

        // Background rounded rect
        drawRoundRect(
            color = bg,
            cornerRadius = CornerRadius(24 * s),
        )

        // Dial ring — subtle structural guide
        drawCircle(
            color = dial.copy(alpha = 0.35f),
            radius = 38 * s,
            center = Offset(60 * s, 60 * s),
            style = Stroke(width = 2.5f * s),
        )

        // Cardinal tick marks (12 / 3 / 6 / 9 o'clock)
        val tick = 4f * s
        drawLine(dial, Offset(60 * s, 22 * s), Offset(60 * s, 32 * s), tick, StrokeCap.Round)
        drawLine(dial, Offset(98 * s, 60 * s), Offset(88 * s, 60 * s), tick, StrokeCap.Round)
        drawLine(dial, Offset(60 * s, 98 * s), Offset(60 * s, 88 * s), tick, StrokeCap.Round)
        drawLine(dial, Offset(22 * s, 60 * s), Offset(32 * s, 60 * s), tick, StrokeCap.Round)

        // Checkmark needle
        drawPath(
            path = Path().apply {
                moveTo(44 * s, 62 * s)
                lineTo(56 * s, 73 * s)
                lineTo(79 * s, 47 * s)
            },
            color = check,
            style = Stroke(width = 6.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
