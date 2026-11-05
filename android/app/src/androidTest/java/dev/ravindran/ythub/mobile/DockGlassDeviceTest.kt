package dev.ravindran.ythub.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Regression for opaque/stale dock glass: actual pixels beneath it must change the material. */
class DockGlassDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun darkGlassReflectsTheContentBehindTheDock() = verifyGlass(dark = true)
    @Test fun lightGlassReflectsTheContentBehindTheDock() = verifyGlass(dark = false)

    private fun verifyGlass(dark: Boolean) {
        var tone by mutableStateOf(Color(0xFFB64032))
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val capture = rememberDockGlassState()
                val atmosphere = rememberGraphicsLayer()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().captureDockGlass(capture, atmosphere).background(tone))
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        FloatingPlaybackDock("Home", {}, {}, {}, {}, {}, dark, capture)
                    }
                }
            }
        }
        val red = sample()
        compose.runOnIdle { tone = Color(0xFF315FBD) }
        val blue = sample()
        assertTrue("The dock must reveal the red underlay, not a solid theme fill", red.red - blue.red > .15f)
        assertTrue("Glass must refresh when underlying content changes", blue.blue - red.blue > .15f)
        compose.onNodeWithTag("nav-Home").assertHasClickAction()
        compose.onNodeWithTag("music-mini-player").assertHasClickAction()
        compose.onNodeWithTag("nav-Search").assertHasClickAction()
    }

    private fun sample(): Color {
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("dock-glass-player").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height - 12]
    }
}
