package ro.davinci.checker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Grafic minimal, desenat direct pe Canvas.
 *
 * De ce nu o librărie de grafice: pentru două linii care se mișcă la 2 secunde,
 * orice dependență în plus înseamnă sute de kilobyte și un API de învățat.
 * Aici sunt ~60 de puncte, o linie și o umplere sub ea.
 *
 * @param values eșantioanele, cele mai vechi primele
 * @param max valoarea de sus a scalei (100 pentru procente)
 * @param color culoarea liniei
 */
@Composable
fun Sparkline(
    values: List<Float>,
    max: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Int = 44,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
    ) {
        val safeMax = if (max <= 0f) 1f else max
        val stepX = if (values.size <= 1) size.width else size.width / (values.size - 1)
        val baseline = size.height - 1f

        // Linie de referință la jumătatea scalei: dă senzația de scară, nu de doodle.
        drawLine(
            color = color.copy(alpha = 0.14f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1f,
        )

        if (values.size < 2) return@Canvas

        val line = Path()
        values.forEachIndexed { index, value ->
            val ratio = (value / safeMax).coerceIn(0f, 1f)
            val x = index * stepX
            val y = baseline - ratio * baseline
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }

        // Umplerea se închide pe baseline, ca aria să arate ca un grafic de consum.
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, baseline)
            lineTo(0f, baseline)
            close()
        }

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)),
            ),
        )
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 2f),
        )
    }
}
