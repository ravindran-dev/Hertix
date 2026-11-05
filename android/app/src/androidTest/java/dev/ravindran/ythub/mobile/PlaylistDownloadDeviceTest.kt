package dev.ravindran.ythub.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlaylistDownloadDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun playlistNameIsEditableValidatedAndOptional() {
        var requested: Pair<Boolean, String>? = null
        compose.activity.setContent {
            var save by remember { mutableStateOf(true) }
            var name by remember { mutableStateOf("YouTube title") }
            MaterialTheme {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                    MediaScreen(MediaInfo("https://youtube.com/playlist?list=test", "YouTube title", playlist = true), true, {}, "320", {}, Preferences(),
                        { requested = save to name }, save, { save = it }, name, { name = it })
                }
            }
        }
        val name = compose.onNodeWithTag("download-playlist-name")
        name.performScrollTo().assertTextContains("YouTube title")
        name.performTextClearance()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Download playlist").performScrollTo().assertIsNotEnabled()
        name.performScrollTo().performTextInput("Road trip BGM")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Download playlist").performScrollTo().performClick()
        assertEquals(true to "Road trip BGM", requested)
        compose.onNodeWithContentDescription("Create a YTHub playlist").performScrollTo().performClick()
        name.assertDoesNotExist()
        compose.onNodeWithText("Download playlist").performScrollTo().performClick()
        assertEquals(false to "Road trip BGM", requested)
    }

    @Test fun completedAndRetriedDownloadImportsCustomPlaylistEvenWithAutoAddOff() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        val before = MusicRepository.state.value
        val a = MusicTestFixtures.audio(compose.activity, "playlist-a").toString()
        val z = MusicTestFixtures.audio(compose.activity, "playlist-z").toString()
        val job = DownloadItem("playlist-import-audit", MediaInfo("https://youtube.com/playlist?list=test", "Original title", playlist = true), true, "320",
            state = "Failed", uris = listOf(z), exportedNames = listOf("Z.wav"), playlistFileOrder = listOf("Z.wav"),
            createLibraryPlaylist = true, libraryPlaylistName = "My downloaded BGM", options = Preferences(autoZip = true, keepFolder = false))
        try {
            MusicRepository.update { it.copy(settings = it.settings.copy(autoAdd = false, downloadPlaylists = false)) }
            runBlocking { MusicRepository.rescan(downloads = listOf(job)) }
            val partial = MusicRepository.state.value.playlists.single { it.downloadId == job.id }
            assertEquals("My downloaded BGM", partial.name)
            assertEquals(listOf(z), partial.tracks.map { id -> MusicRepository.state.value.tracks.single { it.id == id }.uri })
            val completed = job.copy(state = "Completed", uris = listOf(a, z), exportedNames = listOf("A.wav", "Z.wav"), playlistFileOrder = listOf("Z.wav", "A.wav", "Z.wav"))
            runBlocking { MusicRepository.rescan(downloads = listOf(completed)); MusicRepository.rescan(downloads = listOf(completed)) }
            val library = MusicRepository.state.value
            assertEquals(1, library.playlists.count { it.downloadId == job.id })
            assertEquals(listOf(z, a, z), library.playlists.single { it.downloadId == job.id }.tracks.map { id -> library.tracks.single { it.id == id }.uri })
            assertTrue(library.tracks.filter { it.downloadId == job.id }.all { it.available && MusicRepository.exists(it.uri) })
            assertEquals(before.playlists, library.playlists.filterNot { it.downloadId == job.id })
        } finally {
            MusicRepository.update { before }
            listOf("playlist-a", "playlist-z").forEach { File(compose.activity.filesDir, "music/imports/tests/$it.wav").delete() }
        }
    }

    @Test fun headingAndBackgroundScrollWhileGlassActionsStayFixed() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        compose.onNodeWithTag("nav-Home").performClick()
        val avatar = compose.onNodeWithTag("account-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val scene = compose.onNodeWithTag("scrolling-scenic-background", useUnmergedTree = true).fetchSemanticsNode().positionInRoot
        compose.onNodeWithTag("home-page-scroll").performTouchInput { swipeUp() }
        val movedScene = compose.onNodeWithTag("scrolling-scenic-background", useUnmergedTree = true).fetchSemanticsNode().positionInRoot
        assertTrue("Background must move with page content", movedScene.y < scene.y - 20)
        compose.onNodeWithTag("scrolling-header-title", useUnmergedTree = true).assertIsNotDisplayed()
        assertEquals(avatar, compose.onNodeWithTag("account-avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("nav-Library").performClick()
        compose.onNodeWithText("Songs", useUnmergedTree = true).performClick()
        val fixed = compose.onNodeWithTag("fixed-library-glass-actions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("music-library-list").performTouchInput { swipe(Offset(center.x, height * .7f), Offset(center.x, height * .3f), 300) }
        assertEquals(fixed, compose.onNodeWithTag("fixed-library-glass-actions").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sort: Title").assertIsDisplayed()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "playlist-scroll-fixed-actions.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
