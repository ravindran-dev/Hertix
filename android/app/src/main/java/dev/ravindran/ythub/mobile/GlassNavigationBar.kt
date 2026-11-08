package dev.ravindran.ythub.mobile

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One interaction source per glass surface lets a capsule flex as one continuous object.
internal data class GlassScene(val layer: GraphicsLayer, val origin: Offset)
internal val LocalDockGlass = staticCompositionLocalOf<DockGlassState?> { null }
internal val LocalAccountAction = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalGlassScene = staticCompositionLocalOf<GlassScene?> { null }
internal val LocalPopoverGlassBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }
private val LocalGlassInteraction = staticCompositionLocalOf<MutableInteractionSource?> { null }

/** Bounded feedback without the rectangular Material ripple. Haptics respect system settings. */
@Composable
internal fun Modifier.glassTouch(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val shared = LocalGlassInteraction.current
    val interaction = shared ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pressed) { if (shared == null && pressed && enabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val feedback by animateFloatAsState(if (shared == null && pressed) 1f else 0f, spring(dampingRatio = .8f, stiffness = 450f), label = "Row press")
    val feedbackColor = MaterialTheme.colorScheme.onSurface
    return graphicsLayer { scaleX = 1f - .015f * feedback; scaleY = 1f - .025f * feedback }
        .drawBehind { drawRect(feedbackColor.copy(alpha = .045f * feedback.coerceIn(0f, 1f))) }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** Samples the shared, already frosted ambient image without filtering each moving panel again. */
@Composable
internal fun GlassBackdrop(layer: GraphicsLayer, origin: Offset, position: State<Offset>, magnification: Float = 1.035f) {
    val backing = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(backing)
        if (layer.size.width > 0 && layer.size.height > 0) {
            // Only the ambient image is sampled. Scrolling rows stay on their own rendering path.
            with(drawContext.canvas) {
                save()
                val centre = Offset(size.width / 2f, size.height / 2f)
                translate(centre.x, centre.y)
                scale(magnification, magnification)
                translate(-centre.x, -centre.y)
                translate(origin.x - position.value.x, origin.y - position.value.y)
                drawLayer(layer)
                restore()
            }
        }
    })
}

/** One frosted image layer with a restrained reflective rim; labels stay sharp. */
@Composable
internal fun GlassControlSurface(modifier: Modifier = Modifier, dark: Boolean = MaterialTheme.colorScheme.background.luminance() < .5f, backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50), screenPosition: Boolean = false, flexible: Boolean = true, tintAlpha: Float? = null, onPressedChange: (Boolean) -> Unit = {}, dockBackdrop: DockGlassState? = null, magnification: Float = 1.035f, content: @Composable BoxScope.() -> Unit) {
    val ambientBackdrop = LocalAtmosphereLayer.current
    val origin = remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pressed) { onPressedChange(pressed); if (pressed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val compression by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = .72f, stiffness = 450f), label = "Glass compression")
    val highlight by animateFloatAsState(if (pressed) .10f else if (hovered) .04f else 0f, spring(stiffness = 600f), label = "Glass highlight")
    val highlightColor = MaterialTheme.colorScheme.onSurface
    val glassLayer = backdrop ?: LocalGlassScene.current?.layer ?: ambientBackdrop
    val useReference = Build.VERSION.SDK_INT >= 31 && (dockBackdrop != null || backdrop != null)
    val fill = if (dark) Color(0xFF121212) else Color(0xFFFAFAFA)
    val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark).24f else .75f),Color.White.copy(alpha=.035f),Color.White.copy(alpha=.12f)))
    Box(modifier.graphicsLayer { scaleX = 1f - .018f * compression; scaleY = 1f - .035f * compression }
        .then(if (useReference) Modifier.clip(shape).referenceGlass(shape, dark, glassLayer, if (backdrop != null) contentOrigin else LocalGlassScene.current?.origin ?: Offset.Zero, dockBackdrop, tintAlpha ?: .20f)
            else Modifier.shadow(3.dp,shape).clip(shape).border(.5.dp,rim,shape))
        .onGloballyPositioned { origin.value = if (screenPosition) it.positionOnScreen() else it.positionInRoot() }) {
        if(!useReference) Box(Modifier.matchParentSize()) {
            if(glassLayer!=null)GlassBackdrop(glassLayer,if(backdrop!=null)contentOrigin else Offset.Zero,origin,magnification=1f)
            // Use the same restrained translucent tint on older Android releases as well.
            Box(Modifier.matchParentSize().background(fill.copy(alpha=(tintAlpha ?: .20f).coerceIn(.12f, .48f))))
        }
        Box(Modifier.matchParentSize().drawBehind { drawRect(highlightColor.copy(alpha = highlight)) })
        CompositionLocalProvider(LocalGlassInteraction provides if (flexible) interaction else null) { content() }
    }
}

@Composable
internal fun FloatingPlaybackDock(destination: String, onExplore: () -> Unit, onHome: () -> Unit, onPlayer: () -> Unit, onLibrary: () -> Unit, onSearch: () -> Unit, dark: Boolean, dockBackdrop: DockGlassState) {
    val dockState = remember { MusicPlayback.state.map { it.track to it.playing }.distinctUntilChanged() }
    val player by dockState.collectAsStateWithLifecycle(initialValue = remember { MusicPlayback.state.value.let { it.track to it.playing } })
    val track = player.first
    val dockHeight = maxOf(56.dp, with(LocalDensity.current) { 30.sp.toDp() } + 20.dp)
    val tint = .40f
    Column(Modifier.fillMaxWidth().onGloballyPositioned { dockBackdrop.bounds = it.boundsInRoot() }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding().padding(horizontal = 19.dp, vertical = 6.dp).testTag("floating-playback-dock"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (track != null) GlassControlSurface(Modifier.fillMaxWidth().height(dockHeight).testTag("dock-glass-player"), dark, dockBackdrop = dockBackdrop, tintAlpha = tint) {
            Row(Modifier.fillMaxSize().clipToBounds().padding(start=12.dp,end=4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).fillMaxHeight().glassTouch(onPlayer).testTag("music-mini-player"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).testTag("mini-player-artwork")) {TrackArtwork(track,Modifier.fillMaxSize())}
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                GlassIconButton(if (player.second) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.second) "Pause" else "Play", MusicPlayback::toggle)
                GlassIconButton(Icons.Rounded.FastForward, "Next track", MusicPlayback::next)
            }
        }
        GlassControlSurface(Modifier.fillMaxWidth().height(62.dp), dark, dockBackdrop = dockBackdrop, tintAlpha = tint, flexible = false) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(2.dp)) {
                val tabs = listOf(Triple("Home", Icons.Rounded.Home, onHome), Triple("Explore", Icons.Rounded.GridView, onExplore), Triple("Library", Icons.Rounded.LibraryMusic, onLibrary), Triple("Search", Icons.Rounded.Search, onSearch))
                val selectedIndex = tabs.indexOfFirst { it.first == destination }
                val width = (maxWidth - 18.dp) / 4
                val target = (width + 6.dp) * selectedIndex.coerceAtLeast(0)
                val position by animateDpAsState(target, spring(dampingRatio = .68f, stiffness = 420f), label = "Liquid tab travel")
                if (selectedIndex >= 0) Box(Modifier.offset(x = position + 3.dp).width((width - 6.dp).coerceAtLeast(1.dp)).fillMaxHeight().padding(vertical = 3.dp)
                    .testTag("nav-selection").clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if(dark) .14f else .5f)).border(.5.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(50)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { (label, icon, action) ->
                        val active = destination == label
                        val ink = (if(dark) Color.White else Color.Black).copy(alpha = if(active) 1f else .65f)
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(50)).semantics { selected = active }.glassTouch(action).padding(vertical = 5.dp).testTag("nav-$label"), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (label == "Library") LibraryTabGlyph(ink) else Icon(icon, label, Modifier.size(28.dp), tint = ink)
                            Text(label, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTabGlyph(tint: Color) {
    Canvas(Modifier.size(28.dp).semantics { contentDescription = "Library" }) {
        scale(size.width / 512f, size.height / 512f, Offset.Zero) { drawPath(HertixLibraryTabPath, tint) }
    }
}

@Composable
internal fun ContextControls(onBack: () -> Unit, onAdd: () -> Unit, onMore: () -> Unit, backLabel: String = "Go back", addLabel: String = "Add to playlist", backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack) {
    val scene = LocalGlassScene.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassControlSurface(Modifier.size(48.dp), backdrop = scene?.layer, contentOrigin = scene?.origin ?: Offset.Zero) {
            GlassIconButton(backIcon, backLabel, onBack)
        }
        Spacer(Modifier.weight(1f))
        PairedGlassActions(onAdd, onMore, addLabel = addLabel)

    }
}

@Composable
internal fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: Dp = 21.dp) {
    val popoverHost = LocalGlassPopoverHost.current
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(modifier.size(48.dp).onGloballyPositioned { bounds = it.boundsInRoot() }.clip(RoundedCornerShape(50)).glassTouch({
        if (enabled && !bounds.isEmpty) popoverHost?.recordTrigger(bounds)
        onClick()
    }, enabled)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(glyph), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .3f))
    }
}

/** Anchored, focusable popover: outside touch and Android Back dismiss it. */
@Composable
internal fun GlassActionPopover(title: String, onDismiss: () -> Unit, backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero,
    panelWidth: Dp = 252.dp, content: @Composable ColumnScope.() -> Unit) {
    val scene = LocalGlassScene.current
    val popoverBackdrop = LocalPopoverGlassBackdrop.current
    GlassPopover(onDismiss, panelWidth = panelWidth) {
        GlassMenuBlock(backdrop ?: popoverBackdrop ?: scene?.layer, if (backdrop == null) scene?.origin ?: contentOrigin else contentOrigin) {
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val menuMaxHeight = minOf(screenHeight - 150.dp, screenHeight * .70f).coerceAtLeast(220.dp)
            Column(Modifier.heightIn(max = menuMaxHeight).verticalScroll(rememberScrollState())) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            content()
            }
        }
    }
}

@Composable
internal fun GlassPopover(onDismiss: () -> Unit, panelWidth: Dp = 252.dp, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val available = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtLeast(180.dp)
    val host = LocalGlassPopoverHost.current
    val view = LocalView.current
    val panel: @Composable () -> Unit = {
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(Unit) { reveal.animateTo(1f, spring(dampingRatio = .9f, stiffness = 650f)) }
        Column(Modifier.widthIn(max = available).width(panelWidth.coerceAtMost(available)).padding(4.dp).graphicsLayer {
            alpha = reveal.value.coerceIn(0f, 1f)
            scaleX = .88f + .12f * reveal.value
            scaleY = .80f + .20f * reveal.value
            translationY = -8.dp.toPx() * (1f - reveal.value)
            transformOrigin = TransformOrigin(1f, 0f)
        }.testTag("glass-popover"), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
    if (host != null && host.view === view) HostedGlassPopover(host, onDismiss, panel)
    else Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, with(density) { 58.dp.roundToPx() }), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = true), content = panel)
}

@Composable
internal fun GlassMenuBlock(backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero, content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassPanel(Modifier.fillMaxWidth(), backdrop = backdrop, contentOrigin = contentOrigin, radius = 28.dp, tintAlpha = .12f) {
        Column(Modifier.fillMaxWidth().padding(6.dp), content = content)
    }
}

@Composable
internal fun PairedGlassActions(onAdd: () -> Unit, onMore: () -> Unit, addLabel: String = "Phone music", moreLabel: String = "More options",
    backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero) {
    val scene = LocalGlassScene.current
    GlassControlSurface(Modifier.height(48.dp).testTag("paired-glass-actions"),
        backdrop = backdrop ?: scene?.layer,
        contentOrigin = if (backdrop == null) scene?.origin ?: contentOrigin else contentOrigin) {
        // Aligned header actions share a single glass capsule and consistent touch targets.
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LocalAccountAction.current?.let { account ->
                AccountAvatar(account, Modifier.testTag("account-avatar"), size = 40.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(.5.dp).height(20.dp).align(Alignment.CenterVertically).background(Color.White.copy(alpha=.10f)))
                Spacer(Modifier.width(8.dp))
            }
            GlassIconButton(Icons.Rounded.MoreHoriz, moreLabel, onMore, glyph = 22.dp)
        }
    }
}
