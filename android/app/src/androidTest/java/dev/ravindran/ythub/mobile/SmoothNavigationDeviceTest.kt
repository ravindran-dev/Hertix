package dev.ravindran.ythub.mobile

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.FrameMetricsAggregator
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Repeatable real-library scrolling/navigation workload; restores the prior player state. */
class SmoothNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun playerFollowsLightAndDarkThemes() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val previous = MusicPlayback.state.value
        val prefs = DownloadStore.prefs.value
        val track = MusicRepository.state.value.tracks.firstOrNull { it.available && it.artwork.isNotBlank() && File(it.artwork).exists() } ?: MusicRepository.state.value.tracks.firstOrNull { it.available }
        org.junit.Assume.assumeTrue("Requires local audio", track != null)
        try {
            compose.runOnUiThread { MusicPlayback.play(listOf(track!!.id)); MusicPlayback.pauseIfConnected() }
            compose.waitUntil(15000) { MusicPlayback.state.value.track?.id == track!!.id }
            val mini = compose.onNodeWithTag("music-mini-player").fetchSemanticsNode().boundsInRoot
            val art = compose.onNodeWithTag("mini-player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Mini-player artwork must stay inside the bar", mini.contains(art.topLeft) && mini.contains(art.bottomRight))
            compose.onNodeWithTag("music-mini-player").performClick()
            listOf("Light", "Dark").forEach { theme ->
                compose.runOnUiThread { DownloadStore.savePrefs(prefs.copy(theme = theme)) }
                compose.waitForIdle()
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                val brightness = pixels[1, pixels.height / 2].luminance()
                assertTrue("Player must follow $theme theme (luminance=$brightness)", if (theme == "Light") brightness > .5f else brightness < .2f)
                compose.onNodeWithTag("primary-playback").assertIsDisplayed()
                compose.onNodeWithTag("player-artwork", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Previous track").assertIsDisplayed()
                compose.onNodeWithText("Lyrics").assertIsDisplayed()
                compose.onNodeWithText("Output").assertIsDisplayed()
                compose.onNodeWithText("Queue").assertIsDisplayed()
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(compose.activity.cacheDir, "smooth-player-$theme.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            compose.runOnUiThread {
                DownloadStore.savePrefs(prefs)
                if (previous.queue.isEmpty()) MusicPlayback.stop() else {
                    MusicPlayback.play(previous.queue, previous.index); MusicPlayback.seek(previous.position)
                    if (!previous.playing) MusicPlayback.pauseIfConnected()
                }
            }
        }
    }
    @Test fun scrollAndNavigateWithoutInterruptingPlayback() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val previous = MusicPlayback.state.value
        val tracks = MusicRepository.state.value.tracks.filter { it.available }.take(2)
        org.junit.Assume.assumeTrue("Requires local audio", tracks.isNotEmpty())
        val frames = FrameMetricsAggregator()
        try {
            compose.runOnUiThread { MusicPlayback.play(tracks.map { it.id }) }
            compose.waitUntil(15000) { MusicPlayback.state.value.playing }
            compose.runOnUiThread { MusicPlayback.pauseIfConnected() }
            compose.waitUntil(5000) { !MusicPlayback.state.value.playing }
            frames.add(compose.activity)
            repeat(4) {
                compose.onNodeWithTag("nav-Home").performClick()
                compose.onNodeWithTag("nav-Library").performClick()
                compose.onNodeWithText("Songs", useUnmergedTree = true).performClick()
                val list = compose.onNodeWithTag("music-library-list")
                repeat(3) {
                    physicalSwipe(list, .72f, .26f)
                    physicalSwipe(list, .26f, .72f)
                }
                compose.onNodeWithTag("music-mini-player").performClick()
                compose.onNodeWithTag("primary-playback").assertIsDisplayed()
                compose.onNodeWithContentDescription("Minimize player").performClick()
                assertFalse("Navigation must preserve paused playback", MusicPlayback.state.value.playing)
                assertTrue(MusicPlayback.state.value.track?.id in tracks.map { it.id })
            }
            val histogram = frames.remove(compose.activity)?.get(FrameMetricsAggregator.TOTAL_INDEX)
            var total = 0; var over16 = 0; var over32 = 0
            if (histogram != null) for (i in 0 until histogram.size()) {
                val count = histogram.valueAt(i); total += count
                if (histogram.keyAt(i) > 16) over16 += count
                if (histogram.keyAt(i) > 32) over32 += count
            }
            assertTrue("Must collect rendered frames", total > 0)
            val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "current"
            File(compose.activity.cacheDir, "smooth-$phase.txt").writeText("frames=$total, over16ms=$over16, over32ms=$over32")
        } finally {
            frames.stop()
            compose.runOnUiThread {
                if (previous.queue.isEmpty()) MusicPlayback.stop() else {
                    MusicPlayback.play(previous.queue, previous.index); MusicPlayback.seek(previous.position)
                    if (!previous.playing) MusicPlayback.pauseIfConnected()
                }
            }
        }
    }
    // Wall-clock input: Compose's synthetic touch clock batches frames and skews frame timings.
    private fun physicalSwipe(node: SemanticsNodeInteraction, from: Float, to: Float) {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val start = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action,
                bounds.center.x, bounds.top + bounds.height * (from + (to - from) * fraction), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..18) {
            android.os.SystemClock.sleep(16)
            send(android.view.MotionEvent.ACTION_MOVE, step / 18f)
        }
        send(android.view.MotionEvent.ACTION_UP, 1f)
        compose.waitForIdle()
    }

}
