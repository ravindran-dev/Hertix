package dev.ravindran.ythub.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Observe consumed scrolling without taking motion away from lists or fling physics. */
internal class PageScrollState : NestedScrollConnection {
    var pixels by mutableFloatStateOf(0f)
        private set
    fun reset() { pixels = 0f }
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        pixels = (pixels - consumed.y).coerceAtLeast(0f)
        return Offset.Zero
    }
}
internal val LocalPageScroll = staticCompositionLocalOf<PageScrollState?> { null }

/** Only the glass actions stay pinned; the title leaves with the scrolling page. */
@Composable internal fun ScrollingHeaderTitle(title: String, modifier: Modifier = Modifier, brand: Boolean = false) {
    val scroll = LocalPageScroll.current
    Box(modifier.height(48.dp).clipToBounds(), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.graphicsLayer { translationY = -(scroll?.pixels ?: 0f) }.testTag("scrolling-header-title"), verticalAlignment = Alignment.CenterVertically) {
            if (brand) Image(painterResource(R.drawable.hertix_wordmark), "Hertix", Modifier.width(126.dp).height(42.dp))
            else Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
