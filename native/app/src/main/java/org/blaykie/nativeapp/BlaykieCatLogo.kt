package org.blaykie.nativeapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/** The transparent, theme-aware cat mark, drawn in the original 64 by 64 coordinate space. */
@Composable
internal fun BlaykieCatLogo(size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    // Follow the app's selected theme, not just the system theme.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val line = if (dark) Color(0xFF9BB6FF) else Color(0xFF2F5BEA)
    val eyes = Color(0xFFF4C430)
    val small = size.value <= 24f
    val accessibility = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    }
    Canvas(modifier = modifier.then(accessibility).size(size)) {
        val unit = this.size.minDimension / 64f
        scale(unit, pivot = Offset.Zero) {
            val head = Path().apply {
                moveTo(15f, 30f); lineTo(15f, 13f); lineTo(27f, 21f)
                cubicTo(30f, 20f, 34f, 20f, 37f, 21f)
                lineTo(49f, 13f); lineTo(49f, 30f)
                cubicTo(49f, 42f, 41f, 50f, 32f, 50f)
                cubicTo(23f, 50f, 15f, 42f, 15f, 30f); close()
            }
            drawPath(head, line, style = Stroke(width = 3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(eyes, 2.6f, Offset(25.5f, 31f))
            drawCircle(eyes, 2.6f, Offset(38.5f, 31f))
            val nose = Path().apply { moveTo(30f, 37f); lineTo(34f, 37f); lineTo(32f, 39.4f); close() }
            drawPath(nose, line)
            drawPath(nose, line, style = Stroke(width = if (small) 2.2f else 1.4f, join = StrokeJoin.Round))
            val whiskerWidth = if (small) 3.2f else 2.6f
            for ((start, end) in listOf(
                Offset(9f, 34f) to Offset(18f, 36f), Offset(9f, 41f) to Offset(18f, 39f),
                Offset(55f, 34f) to Offset(46f, 36f), Offset(55f, 41f) to Offset(46f, 39f),
            )) drawLine(line, start, end, strokeWidth = whiskerWidth, cap = StrokeCap.Round)
        }
    }
}
