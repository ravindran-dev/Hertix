package dev.ravindran.ythub.mobile

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BackupRulesTest {
    private fun row(text:String)=JsonParser.parseString(text).asJsonObject
    @Test fun identicalDuplicateCreateIsNotAConflict() {
        assertTrue(SyncConflictRules.matches(row("""{"position":1,"deleted_at":null}"""),row("""{"version":7,"position":1.0,"deleted_at":null}""")))
    }
    @Test fun differentOrderOrRemovedMembershipNeedsReview() {
        assertFalse(SyncConflictRules.matches(row("""{"position":2}"""),row("""{"position":1}""")))
        assertFalse(SyncConflictRules.matches(row("""{"deleted_at":null}"""),row("""{"deleted_at":"2026-11-01"}""")))
        assertFalse(SyncConflictRules.matches(row("{}"),null))
    }
    @Test fun restoredOfflineOrderCanSupersedeAnOldDuplicateCreate() {
        val op=SyncOperation(user_id="owner",device_id="device",entity_type="playlist_items",entity_id="entry",expected_version=0,patch=row("""{"playlist_id":"cloud-playlist","library_item_id":"cloud-song","position":9}"""))
        val state=MusicState(playlists=listOf(LocalPlaylist("local-playlist","bgm",listOf("local-song"))))
        val latest=SyncConflictRules.currentMembership(op,state,mapOf("local-playlist" to "cloud-playlist"),mapOf("local-song" to "cloud-song"))!!
        assertTrue(SyncConflictRules.matches(latest,row("""{"playlist_id":"cloud-playlist","library_item_id":"cloud-song","position":0,"deleted_at":null}""")))
        assertFalse(SyncConflictRules.matches(latest,row("""{"playlist_id":"cloud-playlist","library_item_id":"cloud-song","position":2,"deleted_at":null}""")))
        assertNull(SyncConflictRules.currentMembership(op.copy(expected_version=1),state,mapOf("local-playlist" to "cloud-playlist"),mapOf("local-song" to "cloud-song")))
    }
    @Test fun restoredOrderAllowsGapsInCloudPositionsButRejectsDifferentOrders() {
        val op=SyncOperation(user_id="owner",device_id="device",entity_type="playlist_items",entity_id="a",expected_version=0,patch=row("""{"playlist_id":"cp","library_item_id":"cs1","position":0}"""))
        val state=MusicState(playlists=listOf(LocalPlaylist("p","bgm",listOf("s1","s2"))))
        val rows=mapOf("playlist_items" to listOf(row("""{"id":"a","playlist_id":"cp","library_item_id":"cs1","position":10,"deleted_at":null}"""),row("""{"id":"b","playlist_id":"cp","library_item_id":"cs2","position":30,"deleted_at":null}""")))
        val mapping=mapOf("s1" to "cs1","s2" to "cs2")
        assertTrue(SyncConflictRules.sameRestoredOrder(op,state,rows,mapOf("p" to "cp"),mapping))
        assertFalse(SyncConflictRules.sameRestoredOrder(op,state.copy(playlists=listOf(LocalPlaylist("p","bgm",listOf("s2","s1")))),rows,mapOf("p" to "cp"),mapping))
    }
    @Test fun defaultConsentDoesNotUploadAnything() {
        val c=BackupConsent();assertFalse(c.data);assertFalse(c.music);assertFalse(c.automatic);assertFalse(c.mobile)
    }
    @Test fun networkPermissionSeparatesWifiMobileAndNoInternet() {
        val wifi=BackupConsent(data=true,music=true)
        assertTrue(wifi.allowsNetwork(wifi=true,validated=true))
        assertFalse(wifi.allowsNetwork(wifi=false,validated=true))
        assertFalse(wifi.allowsNetwork(wifi=true,validated=false))
        assertTrue(wifi.copy(mobile=true).allowsNetwork(wifi=false,validated=true))
        assertFalse(wifi.copy(mobile=true).allowsNetwork(wifi=false,validated=false))
    }
    @Test fun offlineBgmSurvivesSnapshotWithoutCloudProjection() {
        val bgm=LocalPlaylist("bgm","bgm",listOf("song"))
        val local=MusicState(tracks=listOf(LocalTrack("song","content://music/1","Song")),playlists=listOf(bgm))
        assertEquals(local,CloudSync.localSnapshot(local))
        assertEquals(listOf(bgm),CloudSync.localSnapshot(local.copy(tracks=local.tracks+LocalTrack("remote","cloud://other/2","Remote"))).playlists)
    }
}
