package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationClarityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun searchSelectionPersistsAfterRequestAndTypingAndSavedDoesNotOverlap() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        compose.onNodeWithTag("nav-Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("test")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("nav-Search").assertIsSelected()
        compose.onNodeWithTag("nav-Library").assertIsNotSelected()
        val search = compose.onNodeWithTag("nav-Search").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("nav-selection").fetchSemanticsNode().boundsInRoot
        assertTrue("Indicator must travel to Search", kotlin.math.abs(search.center.x - indicator.center.x) < 4f)
        compose.onNodeWithTag("nav-Downloads").performClick()
        compose.onNodeWithTag("nav-Downloads").assertIsSelected()
        val saved = compose.onNodeWithTag("nav-Downloads").fetchSemanticsNode().boundsInRoot
        val library = compose.onNodeWithTag("nav-Library").fetchSemanticsNode().boundsInRoot
        assertTrue("Saved and Library touch targets must not overlap", saved.right <= library.left)
        compose.onNodeWithTag("nav-Library").performClick()
        compose.onNodeWithTag("nav-Library").assertIsSelected()
        compose.onNodeWithTag("nav-Search").assertIsNotSelected()
        val list = compose.onNodeWithTag("music-library-list").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("floating-playback-dock").fetchSemanticsNode().boundsInRoot
        assertTrue("Scrolling content must continue behind the floating controls", list.bottom >= dock.bottom - 1f)
    }
}
