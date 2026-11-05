package dev.ravindran.ythub.mobile

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun WaveformProgress(trackId: String, value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, valueRange: ClosedFloatingPointRange<Float>, enabled: Boolean, description: String, modifier: Modifier = Modifier) {
    val bars = remember(trackId) { val random = kotlin.random.Random(trackId.hashCode()); List(64) { .2f + random.nextFloat() * .8f } }
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)).coerceIn(0f, 1f)
    Box(modifier.height(48.dp)) {
        Canvas(Modifier.fillMaxWidth().height(28.dp).offset(y = (-4).dp)) {
            val step = size.width / bars.size
            bars.forEachIndexed { i, height -> drawLine(if (i.toFloat() / bars.size <= fraction) MobileUi.accent else Color(0xFF55565B), Offset(step * (i + .5f), size.height), Offset(step * (i + .5f), size.height * (1f - height)), 1.dp.toPx(), StrokeCap.Round) }
        }
        GlassPlayerSlider(value, onValueChange, valueRange, description, Modifier.fillMaxWidth(), enabled, onValueChangeFinished)
    }
}

@Composable internal fun PlayerActionRow(onLyrics: () -> Unit, onQueue: () -> Unit, onOutput: () -> Unit) {
    val actions = listOf(Triple("Lyrics", Icons.Rounded.Lyrics, onLyrics), Triple("Output", Icons.Rounded.DevicesOther, onOutput), Triple("Queue", Icons.Rounded.QueueMusic, onQueue))
    Row(Modifier.fillMaxWidth()) {
        actions.forEach { (label, icon, action) ->
            Column(Modifier.weight(1f).height(60.dp).glassTouch(action), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, label, Modifier.size(23.dp))
                Text(label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable internal fun AudioOutputPanel() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
        Text("Audio output", style = MaterialTheme.typography.headlineSmall)
        Text("Playback follows Android’s selected media device. Connect speakers or headphones using system settings.", Modifier.padding(vertical = 12.dp))
        MusicActionRow("Media volume and output", Icons.Rounded.VolumeUp, {
            runCatching { context.startActivity(Intent(if (android.os.Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)) }.onFailure { MusicPlayback.error.value = "Audio controls are unavailable on this device" }
        })
        MusicActionRow("Bluetooth devices", Icons.Rounded.Bluetooth, {
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.onFailure { MusicPlayback.error.value = "Bluetooth settings are unavailable on this device" }
        })
    }
}
