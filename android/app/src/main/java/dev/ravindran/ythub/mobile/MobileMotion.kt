package dev.ravindran.ythub.mobile

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Finite UI transitions use Compose's system duration scale, including disabled animations. */
internal object MobileMotion {
    const val quick = 140
    const val standard = 220
    const val navigation = 220
    fun navigation(from: String, to: String, back: Boolean = false): ContentTransform {
        val tabs = listOf("Home", "Downloads", "Library", "Settings")
        return when {
            from in tabs && to in tabs -> EnterTransition.None togetherWith ExitTransition.None
            to=="Player" -> (fadeIn(tween(140))+slideInVertically(tween(160)) {it/12}) togetherWith fadeOut(tween(90))
            from=="Player" -> fadeIn(tween(110)) togetherWith (fadeOut(tween(110))+slideOutVertically(tween(150)){it/12})
            else -> fadeIn(tween(100)) togetherWith fadeOut(tween(90))
        }
    }
}

@Composable internal fun DownloadMotionProgress(item: DownloadItem, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState((item.progress / 100f).coerceIn(0f, 1f), tween(320, easing = LinearOutSlowInEasing), label = "Download byte progress")
    val processing = item.state in listOf("Processing", "Exporting", "Zipping")
    if (processing) LinearProgressIndicator(modifier = modifier, color = MobileUi.accent)
    else LinearProgressIndicator(progress = { progress }, modifier = modifier, color = MobileUi.accent)
}

@Composable internal fun DownloadMotionStatus(state: String, color: Color) {
    AnimatedContent(state, transitionSpec = {
        (fadeIn(tween(MobileMotion.standard)) + scaleIn(tween(MobileMotion.standard), initialScale = .85f)) togetherWith fadeOut(tween(MobileMotion.quick))
    }, label = "Download stage") { status ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            val icon = when (status) {
                "Completed" -> Icons.Rounded.CheckCircle
                "Failed", "Cancelled" -> Icons.Rounded.ErrorOutline
                "Paused", "Interrupted" -> Icons.Rounded.PauseCircleOutline
                "Processing", "Exporting", "Zipping" -> Icons.Rounded.AutoFixHigh
                else -> Icons.Rounded.Downloading
            }
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Text(status, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Stable occurrence keys allow repeated songs without tying row identity to its position. */
internal fun motionTrackKeys(ids: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return ids.map { id ->
        val occurrence = occurrences[id] ?: 0
        occurrences[id] = occurrence + 1
        "$id:$occurrence"
    }
}
