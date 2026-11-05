package dev.ravindran.ythub.mobile

import android.os.Build
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

/** The same scenic material under every browsing route, including local search. */
@Composable
private fun ScenicAtmosphere() {
    val base = MaterialTheme.colorScheme.background
    val dark = base.luminance() < .5f
    val scroll = LocalPageScroll.current
    Box(Modifier.fillMaxSize().clipToBounds()) {
      Box(Modifier.fillMaxSize().graphicsLayer {
          translationY = -(scroll?.pixels ?: 0f)
      }.testTag("scrolling-scenic-background")) {
        Image(painterResource(R.drawable.home_sunset), null, Modifier.fillMaxSize().testTag("scenic-screen-background"),
            contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to base.copy(alpha = if (dark) .28f else .66f),
            .32f to base.copy(alpha = if (dark) .40f else .74f), .72f to base.copy(alpha = if (dark) .90f else .94f), 1f to base.copy(alpha = .97f))))
      }
    }
}

@Composable
internal fun ArtworkAtmosphere(track: LocalTrack?, modifier: Modifier = Modifier, strong: Boolean = false, hero: Boolean = false) {
    val base = MaterialTheme.colorScheme.background
    val dark = base.luminance() < .5f
    Box(modifier) {
        if (!track?.artwork.isNullOrBlank()) {
            AsyncImage(File(track!!.artwork), null, (if(hero) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxSize()).graphicsLayer {
                alpha = if (strong) (if (dark) .86f else .30f) else (if (dark) .24f else .09f)
                if (!strong && Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(48f, 48f, TileMode.Clamp)
                scaleX = if(hero) 1f else 1.08f; scaleY = if(hero) 1f else 1.08f
            }, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(
                Color(0xFF8D142A).copy(alpha = if (strong) .8f else .50f), base))))
        }
        if (hero) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .25f to base.copy(alpha = .05f), .50f to base.copy(alpha = .95f), .65f to base)))
        else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to base.copy(alpha = if (strong) .48f else .35f),
            .30f to base.copy(alpha = if (strong) .18f else .65f), .68f to base.copy(alpha = .89f), 1f to base)))
    }
}
