package com.moogsynth.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Knob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isLearningSelected: Boolean = false,
    onKnobClick: (() -> Unit)? = null
) {
    // Garante que o Canvas e os gestos usem sempre o valor mais recente vindo do MIDI CC
    val currentValue by rememberUpdatedState(value.coerceIn(0f, 1f))
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnKnobClick by rememberUpdatedState(onKnobClick)

    Box(
        modifier = modifier.size(68.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { currentOnKnobClick?.invoke() }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { currentOnKnobClick?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val delta = -dragAmount.y / 220f
                            val nextVal = (currentValue + delta).coerceIn(0f, 1f)
                            currentOnValueChange(nextVal)
                        }
                    )
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val knobRadius = size.minDimension * 0.36f

            // 1. Efeito Auréola Dourada Suave (Halo Gaussiano irradiando para fora)
            if (isLearningSelected) {
                val outerHaloRadius = size.minDimension * 0.56f
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color.Transparent,
                        0.55f to Color(0xFFF59E0B).copy(alpha = 0.65f),
                        0.78f to Color(0xFFFBBF24).copy(alpha = 0.28f),
                        1.0f to Color.Transparent,
                        center = center,
                        radius = outerHaloRadius
                    ),
                    radius = outerHaloRadius,
                    center = center
                )
                // Anel fino dourado de contorno externo
                drawCircle(
                    color = Color(0xFFFBBF24).copy(alpha = 0.85f),
                    radius = knobRadius + 2.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // 2. Borda Externa Escura do Knob
            drawCircle(
                color = Color(0xFF090A0D),
                radius = knobRadius,
                center = center
            )

            // 3. Corpo Principal do Knob (Alumínio Escovado Escuro)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF262930), Color(0xFF14161A)),
                    center = Offset(center.x - knobRadius * 0.2f, center.y - knobRadius * 0.2f),
                    radius = knobRadius * 1.2f
                ),
                radius = knobRadius * 0.88f,
                center = center
            )

            // 4. Arco sutil de nível ao redor do Knob
            val startAngle = 135f
            val sweepAngle = currentValue * 270f
            drawArc(
                color = if (isLearningSelected) Color(0xFFFBBF24) else Color(0xFFF59E0B).copy(alpha = 0.45f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // 5. Ponteiro Indicador (Gira de -135° a +135°)
            val angleDeg = -135f + (currentValue * 270f)
            val angleRad = Math.toRadians(angleDeg.toDouble() - 90.0)

            val startIndicator = Offset(
                x = center.x + (knobRadius * 0.22f) * cos(angleRad).toFloat(),
                y = center.y + (knobRadius * 0.22f) * sin(angleRad).toFloat()
            )
            val endIndicator = Offset(
                x = center.x + (knobRadius * 0.78f) * cos(angleRad).toFloat(),
                y = center.y + (knobRadius * 0.78f) * sin(angleRad).toFloat()
            )

            drawLine(
                color = if (isLearningSelected) Color(0xFFFDE047) else Color.White,
                start = startIndicator,
                end = endIndicator,
                strokeWidth = 3.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}
