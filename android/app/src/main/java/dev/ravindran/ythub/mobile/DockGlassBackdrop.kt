package dev.ravindran.ythub.mobile

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** A single cropped, half-resolution underlay shared by all three dock surfaces. */
internal class DockGlassState(val layer: GraphicsLayer) {
    var bounds by mutableStateOf(Rect.Zero)
    var contentOrigin by mutableStateOf(Offset.Zero)
    var scrolling by mutableStateOf(false)
    var lastCaptureNanos=0L
    var capturedBounds=Rect.Zero
}

@Composable
internal fun rememberDockGlassState(): DockGlassState {
    val layer = rememberGraphicsLayer()
    return remember(layer) {
        // The glass renderer applies blur and refraction to the sampled layer.
        DockGlassState(layer)
    }
}

/** Capture only the strip behind the dock. The dock itself is outside this draw subtree. */
@Composable
internal fun Modifier.captureDockGlass(state: DockGlassState, atmosphere: GraphicsLayer, enabled: Boolean = true): Modifier {
    val foreground = rememberGraphicsLayer()
    return onGloballyPositioned { state.contentOrigin = it.positionInRoot() }.drawWithContent {
        val bounds = state.bounds
        if (!enabled || bounds.width <= 0f || bounds.height <= 0f) {
            drawContent()
            return@drawWithContent
        }
        // Record Compose content once; both the viewport and cropped dock reuse its render node.
        foreground.record { this@drawWithContent.drawContent() }
        val now=System.nanoTime()
        val refresh = !state.scrolling || state.capturedBounds!=bounds || now-state.lastCaptureNanos>=33_333_333L
        if (enabled && refresh && bounds.width > 0f && bounds.height > 0f) {
            state.lastCaptureNanos=now;state.capturedBounds=bounds
            val crop = bounds.inflate(24.dp.toPx())
            // Use the same live backdrop during idle, drag and fling.
            state.layer.record(size = IntSize(ceil(crop.width / 2f).toInt(), ceil(crop.height / 2f).toInt())) {
                scale(.5f, .5f, Offset.Zero) {
                    translate(-crop.left, -crop.top) { drawLayer(atmosphere) }
                    translate(state.contentOrigin.x - crop.left, state.contentOrigin.y - crop.top) { drawLayer(foreground) }
                }
            }
        }
        drawLayer(foreground)
    }
}

@Composable
internal fun DockGlassBackdrop(state: DockGlassState, position: State<Offset>) {
    Box(Modifier.fillMaxSize().drawBehind {
        if (state.layer.size.width > 0 && state.layer.size.height > 0) {
            val crop = state.bounds.inflate(24.dp.toPx())
            translate(crop.left - position.value.x, crop.top - position.value.y) {
                scale(2f, 2f, Offset.Zero) { drawLayer(state.layer) }
            }
        }
    })
}
