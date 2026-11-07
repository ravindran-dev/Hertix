package dev.ravindran.ythub.mobile

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Animates the visual glyph; the accessible hit target never shrinks. */
@Composable internal fun PlayerControl(icon: ImageVector, description: String, onClick: () -> Unit, active: Boolean = false, enabled: Boolean = true, size: Dp = 48.dp, glyph: Dp = 24.dp, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = 1f, stiffness = 500f), label = "Player control press")
    val color by animateColorAsState(if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .3f) else if (active) MobileUi.accent else MaterialTheme.colorScheme.onSurface, tween(140), label = "Player control state")
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = modifier.size(size)) {
        Crossfade(icon, animationSpec = tween(140), label = "Player glyph") { vector ->
            Icon(vector, description, Modifier.size(glyph).graphicsLayer { scaleX = scale; scaleY = scale }, tint = color)
        }
    }
}

internal enum class CurvedPlaybackGlyph { Shuffle, Loop, LoopOne }

/** Lightweight, round-stroked transport glyphs sized and animated like the player controls. */
@Composable internal fun CurvedPlaybackControl(
    description: String,
    onClick: () -> Unit,
    active: Boolean,
    size: Dp,
    glyph: Dp,
    style: CurvedPlaybackGlyph,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) .94f else 1f, spring(dampingRatio = 1f, stiffness = 520f), label = "Curved transport press")
    val tint by animateColorAsState(if (active) MobileUi.accent else MaterialTheme.colorScheme.onSurface, tween(130), label = "Curved transport tint")
    IconButton(onClick = onClick, interactionSource = interaction, modifier = Modifier.size(size).semantics { contentDescription = description }) {
        Box(Modifier.size(glyph).graphicsLayer { scaleX = pressScale; scaleY = pressScale }) {
            Canvas(Modifier.fillMaxSize()) {
                val unit = this.size.minDimension / 24f
                val line = 2f
                val stroke = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round)
                scale(unit, unit, Offset.Zero) {
                    when (style) {
                        CurvedPlaybackGlyph.Shuffle -> {
                            val first = Path().apply {
                                moveTo(3f, 6f); cubicTo(8f, 6f, 11f, 18f, 17f, 18f); lineTo(20f, 18f)
                                moveTo(16.5f, 15f); lineTo(20f, 18f); lineTo(16.5f, 21f)
                            }
                            val second = Path().apply {
                                moveTo(3f, 18f); cubicTo(8f, 18f, 11f, 6f, 17f, 6f); lineTo(20f, 6f)
                                moveTo(16.5f, 3f); lineTo(20f, 6f); lineTo(16.5f, 9f)
                            }
                            drawPath(first, tint, style = stroke)
                            drawPath(second, tint, style = stroke)
                        }
                        CurvedPlaybackGlyph.Loop, CurvedPlaybackGlyph.LoopOne -> {
                            val upper = Path().apply {
                                moveTo(5f, 11f); cubicTo(5f, 7.5f, 8f, 5f, 12f, 5f); cubicTo(16f, 5f, 19f, 7.5f, 19f, 11f)
                                moveTo(16.5f, 9f); lineTo(20f, 12f); lineTo(16.5f, 15f)
                            }
                            val lower = Path().apply {
                                moveTo(19f, 13f); cubicTo(19f, 16.5f, 16f, 19f, 12f, 19f); cubicTo(8f, 19f, 5f, 16.5f, 5f, 13f)
                                moveTo(7.5f, 9f); lineTo(4f, 12f); lineTo(7.5f, 15f)
                            }
                            drawPath(upper, tint, style = stroke)
                            drawPath(lower, tint, style = stroke)
                        }
                    }
                }
            }
            if (style == CurvedPlaybackGlyph.LoopOne) Text("1", Modifier.align(Alignment.Center), color = tint, fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Circular repeat mark from the app icon set, with the one-track state set into its open centre. */
@Composable internal fun LoopModeControl(description: String, onClick: () -> Unit, active: Boolean, repeatOne: Boolean, size: Dp, glyph: Dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) .94f else 1f, spring(dampingRatio = 1f, stiffness = 520f), label = "Loop control press")
    val tint by animateColorAsState(if (active) MobileUi.accent else MaterialTheme.colorScheme.onSurface, tween(130), label = "Loop control tint")
    IconButton(onClick = onClick, interactionSource = interaction, modifier = Modifier.size(size).semantics { contentDescription = description }) {
        Box(Modifier.size(glyph).graphicsLayer { scaleX = pressScale; scaleY = pressScale }) {
            Icon(Icons.Rounded.Cached, null, Modifier.fillMaxSize(), tint = tint)
            if (repeatOne) Text("1", Modifier.align(Alignment.Center), color = tint, fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** System media volume, shared with the hardware buttons and the active Android audio route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PlayerVolume(showOutputAction: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var maximum by remember { mutableIntStateOf(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }
    var minimum by remember { mutableIntStateOf(if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0) }
    var fixed by remember { mutableStateOf(audio.isVolumeFixed) }
    LaunchedEffect(lifecycle, audio) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
                fixed = audio.isVolumeFixed
                delay(500)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.VolumeDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        GlassPlayerSlider(value = volume.toFloat(), onValueChange = { requested ->
            val next = requested.roundToInt().coerceIn(minimum, maximum.coerceAtLeast(minimum))
            if (next != volume) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0); volume = next }
                .onFailure { MusicPlayback.error.value = "Android could not change media volume: ${it.message}" }
        }, valueRange = minimum.toFloat()..maximum.coerceAtLeast(minimum + 1).toFloat(), enabled = !fixed && maximum > minimum,
            description = "Media volume", modifier = Modifier.weight(1f), ink = MaterialTheme.colorScheme.onSurface, showThumb = false)
        Icon(Icons.Rounded.VolumeUp, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
    }
    if (fixed) Text("Volume is controlled by the connected device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
    if (showOutputAction) TextButton(onClick = {
        runCatching { context.startActivity(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            .onFailure { MusicPlayback.error.value = "Android audio settings are unavailable: ${it.message}" }
    }) {
        Icon(Icons.Rounded.DevicesOther, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
        Text("Audio output", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
    }
}

/** A single centered translucent capsule, with native Slider gestures and accessibility semantics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GlassPlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    ink: Color = MobileUi.accent,
    showThumb: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val engaged = enabled && (dragging || pressed || focused)
    val thickness by animateFloatAsState(if (engaged) 8f else 4f, spring(dampingRatio = 1f, stiffness = 500f), label = "Slider thickness")
    val opacity by animateFloatAsState(if (!enabled) .25f else if (engaged) .9f else .6f, tween(160), label = "Slider opacity")
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
    val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
    val progress by animateFloatAsState(fraction, if (engaged) snap() else tween(220, easing = LinearOutSlowInEasing), label = "Slider progress")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sliderInk = ink
    val remainingInk = MaterialTheme.colorScheme.onSurface
    Slider(value = value.coerceIn(valueRange), onValueChange = onValueChange, valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished, enabled = enabled, interactionSource = interaction,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        thumb = { Box(Modifier.size(if(showThumb) 10.dp else 0.dp).background(Color.White, CircleShape)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(thickness.dp)) {
                val height = size.height
                val top = (size.height - height) / 2f
                val radius = CornerRadius(height / 2f)
                drawRoundRect(remainingInk.copy(alpha = if (enabled) .18f else .06f), Offset(0f, top), Size(size.width, height), radius)
                val width = size.width * progress
                if (width > 0f) drawRoundRect(sliderInk.copy(alpha = opacity), Offset(if (rtl) size.width - width else 0f, top), Size(width, height), radius)
            }
        })
}
