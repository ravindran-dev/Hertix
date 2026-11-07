package dev.ravindran.ythub.mobile

import android.view.View
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection

internal class GlassPopoverEntry(val key: Any, val anchor: State<Rect>, val dismiss: () -> Unit,
    val content: @Composable () -> Unit)

/** Reuses the app window for menus; dialogs/sheets can still use their own native popup window. */
internal class GlassPopoverHostState(val view: View) {
    var entry by mutableStateOf<GlassPopoverEntry?>(null)
    var triggerBounds by mutableStateOf(Rect.Zero)
    var triggerTime by mutableLongStateOf(0L)
    fun recordTrigger(bounds: Rect) { triggerBounds = bounds; triggerTime = SystemClock.elapsedRealtime() }
    fun consumeTrigger(): Rect = triggerBounds.takeIf { !it.isEmpty && SystemClock.elapsedRealtime() - triggerTime < 1_200L }
        ?.also { triggerBounds = Rect.Zero } ?: Rect.Zero
}

internal val LocalGlassPopoverHost = staticCompositionLocalOf<GlassPopoverHostState?> { null }

@Composable
internal fun rememberGlassPopoverHost(): GlassPopoverHostState {
    val view = LocalView.current
    return remember(view) { GlassPopoverHostState(view) }
}

@Composable
internal fun HostedGlassPopover(host: GlassPopoverHostState, dismiss: () -> Unit, content: @Composable () -> Unit) {
    val marker = remember { mutableStateOf(Rect.Zero) }
    val anchor = remember { mutableStateOf(host.consumeTrigger()) }
    val key = remember { Any() }
    val currentContent = rememberUpdatedState(content)
    val currentDismiss = rememberUpdatedState(dismiss)
    // This zero-size marker uses its parent's bounds without changing the existing toolbar layout.
    Box(Modifier.onGloballyPositioned { coordinates ->
        if (anchor.value.isEmpty) marker.value = coordinates.parentLayoutCoordinates?.boundsInRoot() ?: coordinates.boundsInRoot()
        if (anchor.value.isEmpty && !marker.value.isEmpty) anchor.value = marker.value
    })
    DisposableEffect(host, key) {
        host.entry = GlassPopoverEntry(key, anchor, { currentDismiss.value() }, { currentContent.value() })
        onDispose { if (host.entry?.key === key) host.entry = null }
    }
}

@Composable
internal fun GlassPopoverOverlay(host: GlassPopoverHostState) {
    val entry = host.entry ?: return
    val focus = remember(entry.key) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val safe = WindowInsets.safeDrawing
    BackHandler { entry.dismiss() }
    LaunchedEffect(entry.key) { withFrameNanos { }; focus.requestFocus(); keyboard?.hide() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { entry.dismiss() })
        key(entry.key) { Layout(content = {
            Box(Modifier.focusRequester(focus).focusable().semantics { paneTitle = "Actions" }) { entry.content() }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val margin = 8.dp.roundToPx()
            val anchor = entry.anchor.value
            val minX = safe.getLeft(this, layoutDirection) + margin
            val minY = safe.getTop(this) + margin
            val maxX = (constraints.maxWidth - safe.getRight(this, layoutDirection) - menu.width - margin).coerceAtLeast(minX)
            val maxY = (constraints.maxHeight - safe.getBottom(this) - menu.height - margin).coerceAtLeast(minY)
            val anchorX = if (layoutDirection == LayoutDirection.Ltr) anchor.right.toInt() - menu.width else anchor.left.toInt()
            val x = anchorX.coerceIn(minX, maxX)
            val below = anchor.bottom.toInt() + margin
            val above = anchor.top.toInt() - menu.height - margin
            val safeBottom = constraints.maxHeight - safe.getBottom(this)
            val y = when {
                below + menu.height <= safeBottom -> below.coerceAtLeast(minY)
                above >= minY -> above
                else -> below.coerceIn(minY, maxY)
            }
            layout(constraints.maxWidth, constraints.maxHeight) { menu.place(x, y) }
        } }
    }
}
