package dev.ravindran.ythub.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Draw-only motion for the active song; paused tracks do not run an animation. */
@Composable
internal fun PlayingNotation(playing:Boolean,modifier:Modifier=Modifier,tint:Color=MobileUi.accent) {
    if(!playing) {Icon(Icons.Rounded.Pause,"Playback paused",modifier,tint=tint);return}
    val motion=rememberInfiniteTransition(label="Playing equalizer")
    val bars=List(3) {index -> motion.animateFloat(.25f,1f,infiniteRepeatable(tween(440+index*130,easing=LinearEasing),RepeatMode.Reverse,StartOffset(index*120)),label="Equalizer bar $index")}
    Canvas(modifier.semantics {contentDescription="Now playing"}) {
        val width=2.5.dp.toPx()
        bars.forEachIndexed {i,value ->
            val height=size.height*value.value
            drawRoundRect(tint,Offset(size.width*(i+.5f)/3-width/2,(size.height-height)/2),Size(width,height),CornerRadius(width/2))
        }
    }
}
