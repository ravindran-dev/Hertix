package dev.ravindran.ythub.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared liquid material. The gesture observer never consumes taps, drags or scroll events. */
@Composable
internal fun LiquidGlassPanel(modifier: Modifier = Modifier, backdrop: GraphicsLayer? = null,
    contentOrigin: Offset = Offset.Zero, radius: Dp = 28.dp, animateOnTouch: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val scene = LocalGlassScene.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var pressed by remember { mutableStateOf(false) }
    val touch = remember { mutableStateOf(Offset.Unspecified) }
    val pressure by animateFloatAsState(if (pressed) 1f else 0f,
        spring(dampingRatio = .8f, stiffness = 520f), label = "Liquid panel pressure")
    GlassControlSurface(modifier.graphicsLayer {
        val force = if(animateOnTouch) pressure.coerceIn(-.15f, 1f) else 0f
        val point = touch.value
        val bias = if (point.isSpecified && size.width > 0f) (point.x / size.width - .5f).coerceIn(-.5f, .5f) else 0f
        scaleX = 1f - .008f * force
        scaleY = 1f - .018f * force
        translationX = bias * 2.dp.toPx() * force
        translationY = 1.5.dp.toPx() * force
        rotationZ = bias * .18f * force
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touch.value = down.position
            pressed = animateOnTouch
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    event.changes.firstOrNull { it.id == down.id }?.let { touch.value = it.position }
                } while (event.changes.any { it.pressed })
            } finally { pressed = false }
        }
    }, backdrop = backdrop ?: scene?.layer,
        contentOrigin = if (backdrop == null) scene?.origin ?: contentOrigin else contentOrigin,
        shape = RoundedCornerShape(radius), screenPosition = true, flexible = false,
        tintAlpha = .4f, magnification = 1.065f) {
        content()
    }
}
