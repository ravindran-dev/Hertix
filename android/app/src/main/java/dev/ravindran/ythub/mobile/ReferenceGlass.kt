package dev.ravindran.ythub.mobile

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ravindran.ythub.mobile.backdrop.Backdrop
import dev.ravindran.ythub.mobile.backdrop.drawBackdrop
import dev.ravindran.ythub.mobile.backdrop.effects.*
import dev.ravindran.ythub.mobile.backdrop.highlight.Highlight
import dev.ravindran.ythub.mobile.backdrop.shadow.Shadow

/** Adapter for YTHub's existing cropped live dock and ambient scene recordings. */
private class RecordedGlassBackdrop(val layer: GraphicsLayer, val origin: () -> Offset, val scale: Float) : Backdrop {
    override val isCoordinatesDependent = true
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val position = coordinates?.positionInRoot() ?: return
        val offset = origin() - position
        translate(offset.x, offset.y) { scale(scale, scale, Offset.Zero) { drawLayer(layer) } }
    }
}

/** Material settings from BitChord's LiquidGlass.kt (GPL-3.0), backed by Kyant0/backdrop. */
@Composable
internal fun Modifier.referenceGlass(shape: Shape, dark: Boolean, layer: GraphicsLayer?, origin: Offset, dock: DockGlassState?, tint: Float?): Modifier {
    if (Build.VERSION.SDK_INT < 31 || (layer == null && dock == null)) return this
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 24.dp.toPx() }
    val backdrop = remember(layer, origin, margin, dock) {
        if (dock != null) RecordedGlassBackdrop(dock.layer, { dock.bounds.topLeft - Offset(margin, margin) }, 2f) else RecordedGlassBackdrop(layer!!, { origin }, 1f)
    }
    val moving=dock?.scrolling==true
    val resolution=.25f
    val blur = with(density) { 8.dp.toPx() } * resolution
    val lens = with(density) { 24.dp.toPx() } * resolution
    return drawBackdrop(backdrop, shape = { shape }, effects = {
        colorControls(saturation = 1.5f)
        blur(blur)
        if (Build.VERSION.SDK_INT >= 33 && !moving) lens(refractionHeight = lens, refractionAmount = lens, depthEffect = true, chromaticAberration = false)
    }, highlight = { Highlight.Default }, shadow = { Shadow.Default },
        onDrawSurface = { drawRect((if(dark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha = tint ?: .4f)) }, backdropScale = resolution)
}
