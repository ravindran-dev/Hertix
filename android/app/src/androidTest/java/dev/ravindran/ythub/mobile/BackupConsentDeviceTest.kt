package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class BackupConsentDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun cancelledPermissionDoesNotEnableUploadsOrMobileData() {
        compose.waitUntil(30000) {MusicRepository.ready.value && CloudAccount.state.value.status!="Initializing"}
        assumeTrue("Requires an existing signed-in account",CloudAccount.state.value.userId!=null)
        val before=CloudBackupPolicy.settings(compose.activity)
        compose.onNodeWithTag("account-avatar").performClick()
        compose.onNode(hasText("Choose what to back up") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("backup-consent-music").performClick()
        compose.onNodeWithTag("backup-network-mobile").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Cancelling must preserve prior permission",before,CloudBackupPolicy.settings(compose.activity))
        compose.onNodeWithTag("nav-Library").performClick()
    }
    @Test fun playlistBackdropContinuesBehindFloatingBars() {
        compose.waitUntil(30000) {MusicRepository.ready.value}
        assumeTrue("Requires recovered playlist",MusicRepository.state.value.playlists.any {it.name=="bgm"})
        compose.onNodeWithTag("nav-Library").performClick()
        compose.onNodeWithText("Playlists").performClick()
        compose.onNodeWithText("bgm").performClick()
        compose.waitForIdle()
        val dock=compose.onNodeWithTag("floating-playback-dock").fetchSemanticsNode().boundsInRoot
        val list=compose.onNodeWithTag("music-library-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Playlist viewport must continue underneath floating bars",list.bottom>=dock.bottom-1f)
        // Scroll to the actual end; trailing padding must let the final track clear the controls.
        repeat(12) {compose.onNodeWithTag("music-library-list").performTouchInput {swipeUp()}}
        compose.waitForIdle()
        val lastRow=compose.onNodeWithText("46",useUnmergedTree=true).onParent().fetchSemanticsNode().boundsInRoot
        assertTrue("Last playlist row must scroll clear of both floating bars",lastRow.bottom<=dock.top+1f)
        compose.onNodeWithTag("nav-Home").performClick()
    }
    @Test fun recoveredBgmIsDurableOfflineData() {
        compose.waitUntil(30000) {MusicRepository.ready.value}
        val bgm=MusicRepository.state.value.playlists.find {it.name=="bgm"}
        assumeTrue("Requires recovered user playlist",bgm!=null)
        assertEquals(46,bgm!!.tracks.size)
        val saved=MusicPersistence.decode(java.io.File(compose.activity.filesDir,"music/library-v1.json").readText())
        assertEquals(bgm,saved.playlists.find {it.id==bgm.id})
        assertTrue(bgm.tracks.all {id -> saved.tracks.any {it.id==id && !it.uri.startsWith("cloud://")}})
    }
}
