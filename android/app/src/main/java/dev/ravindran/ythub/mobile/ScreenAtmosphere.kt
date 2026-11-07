package dev.ravindran.ythub.mobile

import android.os.Build
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalPlayerPullOffset = staticCompositionLocalOf { mutableFloatStateOf(0f) }
internal val LocalAtmosphereLayer = staticCompositionLocalOf<GraphicsLayer?> { null }

/** A separate background layer allows the glass to sample imagery without capturing itself. */
@Composable
internal fun ScreenAtmosphere(destination: String, layer: GraphicsLayer, frostedLayer: GraphicsLayer) {
    val playerPull = LocalPlayerPullOffset.current
    val trackFlow = remember { MusicPlayback.state.map { it.track }.distinctUntilChanged() }
    val track by trackFlow.collectAsStateWithLifecycle(initialValue = remember { MusicPlayback.state.value.track })
    // Retain the static atmosphere independently of scrolling foreground invalidations.
    Box(Modifier.fillMaxSize().graphicsLayer { }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        // Filter the ambient image once at quarter pixel count. Moving panels only sample it.
        frostedLayer.record(size = IntSize(ceil(size.width / 2f).toInt(), ceil(size.height / 2f).toInt())) {
            scale(.5f, .5f, androidx.compose.ui.geometry.Offset.Zero) { drawLayer(layer) }
        }
        drawLayer(layer)
    }) {
        Crossfade((if(destination=="Player") "Player" else "Browse") to (if(destination=="Player") track else null), animationSpec = tween(200), label = "Screen atmosphere") { (page, artwork) ->
            val base = MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxSize().background(base)) {
                when (page) {
                    "Player" -> ArtworkAtmosphere(artwork, Modifier.fillMaxSize().graphicsLayer {
                        val displacement = playerPull.floatValue
                        translationY = displacement
                        scaleX = 1f - .035f * (displacement / 300f).coerceIn(0f, 1f)
                        shape = RoundedCornerShape(if (displacement > 0f) 24.dp else 0.dp)
                        clip = displacement > 0f
                    }, strong = false, hero = false)
                    else -> ScenicAtmosphere()
                }
            }
        }
    }
}

/** Quiet black surfaces keep content and album covers in the foreground. */
@Composable
private fun ScenicAtmosphere() {
    val base = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base, Color(0xFF080809), Color(0xFF050505)))).testTag("black-screen-background")) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = .018f), Color.Transparent), radius = 1050f)))
    }
}

@Composable
internal fun ArtworkAtmosphere(track: LocalTrack?, modifier: Modifier = Modifier, strong: Boolean = false, hero: Boolean = false) {
    val base = MaterialTheme.colorScheme.background
    val dark = base.luminance() < .5f
    val accent by produceState(Color(0xFF481722), track?.artwork) {
        value = sampleArtworkAccent(track?.artwork.orEmpty())
    }
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base, accent.copy(alpha = if (strong) .22f else .12f), base))))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = if (strong) .12f else .06f), Color.Transparent), radius = 850f)))
    }
}

private suspend fun sampleArtworkAccent(path: String): Color = withContext(Dispatchers.IO) {
    if (path.isBlank()) return@withContext Color(0xFF31131A)
    val options = BitmapFactory.Options().apply { inSampleSize = 32; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
    val bitmap = runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull() ?: return@withContext Color(0xFF31131A)
    try {
        val x = bitmap.width / 2
        val y = bitmap.height / 2
        val pixel = bitmap.getPixel(x, y)
        val r = android.graphics.Color.red(pixel) / 255f
        val g = android.graphics.Color.green(pixel) / 255f
        val b = android.graphics.Color.blue(pixel) / 255f
        Color(r * .28f, g * .28f, b * .28f)
    } finally { bitmap.recycle() }
}
