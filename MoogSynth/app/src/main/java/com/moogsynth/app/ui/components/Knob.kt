package com.moogsynth.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Knob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var angle by remember { mutableStateOf(value * 270f - 135f) }

    Canvas(modifier = modifier
        .size(60.dp)
        .pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                angle -= dragAmount.y // Movimento vertical altera o ângulo
                angle = angle.coerceIn(-135f, 135f)
                val normalizedValue = (angle + 135f) / 270f
                onValueChange(normalizedValue)
            }
        }
    ) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        
        // Fundo do Knob
        drawCircle(color = Color(0xFF1E1E1E), radius = radius)
        drawCircle(color = Color.Black, radius = radius, style = Stroke(width = 4f))
        
        // Indicador
        val rad = Math.toRadians((angle - 90).toDouble())
        val lineEndX = center.x + (radius * 0.8f) * cos(rad).toFloat()
        val lineEndY = center.y + (radius * 0.8f) * sin(rad).toFloat()
        
        drawLine(
            color = Color.White,
            start = center,
            end = Offset(lineEndX, lineEndY),
            strokeWidth = 5f
        )
    }
}
