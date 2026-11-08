@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.ravindran.ythub.mobile

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin

/** Rounded translucent panels share the menu's material without scaling an entire form on a row press. */
@Composable
internal fun GlassSheet(onDismissRequest: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content: @Composable ColumnScope.() -> Unit) {
    val scene = LocalGlassScene.current
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
        containerColor = Color.Transparent, dragHandle = null, scrimColor = Color.Black.copy(alpha = .14f)) {
        GlassWindowBlur()
        LiquidGlassPanel(Modifier.fillMaxWidth().padding(horizontal = 8.dp), backdrop = scene?.layer, contentOrigin = scene?.origin ?: androidx.compose.ui.geometry.Offset.Zero,
            radius = 32.dp, tintAlpha = .14f) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).size(34.dp, 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .25f)))
                content()
            }
        }
    }
}

@Composable
internal fun GlassConfirmDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null) {
    val scene = LocalGlassScene.current
    Dialog(onDismissRequest = onDismissRequest) {
        GlassWindowBlur()
        val reveal = remember { Animatable(0f) }
        LaunchedEffect(Unit) { reveal.animateTo(1f, spring(dampingRatio = .86f, stiffness = 560f)) }
        LiquidGlassPanel(Modifier.fillMaxWidth().graphicsLayer {
            alpha = reveal.value.coerceIn(0f, 1f)
            scaleX = .96f + .04f * reveal.value
            scaleY = .94f + .06f * reveal.value
            translationY = 10.dp.toPx() * (1f - reveal.value)
            transformOrigin = TransformOrigin(.5f, .6f)
        }, backdrop = scene?.layer, contentOrigin = scene?.origin ?: androidx.compose.ui.geometry.Offset.Zero, radius = 30.dp, tintAlpha = .14f) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .78f).dp).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (icon != null) Box(Modifier.align(Alignment.CenterHorizontally)) { icon() }
                if (title != null) ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                if (text != null) ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton?.invoke(); confirmButton() }
            }
        }
    }
}

@Composable
internal fun GlassMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).glassTouch(onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
    }
}

@Composable
internal fun GlassWindowBlur() {
    val view = LocalView.current
    DisposableEffect(view) {
        var parent: android.view.ViewParent? = view.parent
        var provider: DialogWindowProvider? = null
        while (parent != null && provider == null) {
            provider = parent as? DialogWindowProvider
            parent = parent.parent
        }
        val window = provider?.window
        if (Build.VERSION.SDK_INT >= 31 && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 48 }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 31) window?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }
}
