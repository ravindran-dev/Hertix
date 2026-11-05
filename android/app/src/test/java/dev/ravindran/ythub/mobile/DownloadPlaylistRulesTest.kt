package dev.ravindran.ythub.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class DownloadPlaylistRulesTest {
    private val job = DownloadItem("yt-list", MediaInfo("https://youtube.com/playlist?list=test", "YouTube title", playlist = true), true, "320",
        createLibraryPlaylist = true, libraryPlaylistName = "  My BGM  ")

    @Test fun customNameAndOriginalOrderSurviveAlphabeticExportsAndDuplicateSongs() {
        val saved = job.copy(uris = listOf("uri-a", "uri-z"), exportedNames = listOf("A.mp3", "Z.mp3"), playlistFileOrder = listOf("Z.mp3", "A.mp3", "Z.mp3"))
        assertEquals(listOf("uri-z", "uri-a", "uri-z"), saved.orderedAudioUris())
        val state = MusicState().withDownloadPlaylist(saved, saved.orderedAudioUris())
        assertEquals("My BGM", state.playlists.single().name)
        assertEquals(saved.orderedAudioUris(), state.playlists.single().tracks)
        assertEquals(state, state.withDownloadPlaylist(saved, saved.orderedAudioUris()))
    }
    @Test fun perDownloadChoiceOverridesGlobalSettingsAndRequiresAudioPlaylist() {
        assertFalse(job.copy(createLibraryPlaylist = false).createsLibraryPlaylist(true))
        assertTrue(job.createsLibraryPlaylist(false))
        assertFalse(job.copy(audio = false).createsLibraryPlaylist(true))
        assertFalse(job.copy(media = job.media.copy(playlist = false)).createsLibraryPlaylist(true))
        assertEquals(emptyList<LocalPlaylist>(), MusicState().withDownloadPlaylist(job.copy(createLibraryPlaylist = false), listOf("one")).playlists)
    }
    @Test fun retriesFillMissingSongsWithoutDuplicatingPlaylistOrLosingUserEdits() {
        val partial = MusicState().withDownloadPlaylist(job, listOf("z", "b"))
        val edited = partial.copy(playlists = listOf(partial.playlists.single().copy(name = "Renamed", tracks = listOf("z", "manual"))))
        assertEquals(edited, edited.withDownloadPlaylist(job, listOf("z", "b")))
        val retried = edited.withDownloadPlaylist(job, listOf("z", "a", "b"))
        assertEquals("Renamed", retried.playlists.single().name)
        assertEquals(listOf("z", "a", "manual"), retried.playlists.single().tracks)
        assertEquals(1, retried.playlists.size)
    }
    @Test fun oldHistoryKeepsGlobalDefaultAndOldPlaylistName() {
        val json = """{"id":"old","media":{"url":"https://youtube.com/playlist?list=old","title":"Old title","playlist":true},"audio":true,"quality":"320","uris":["audio"],"exportedNames":["song.mp3"]}"""
        val old = Gson().fromJson(json, DownloadItem::class.java)
        assertNull(old.createLibraryPlaylist)
        assertTrue(old.createsLibraryPlaylist(true))
        assertFalse(old.createsLibraryPlaylist(false))
        assertEquals(listOf("audio"), old.orderedAudioUris())
        assertEquals("Old title", MusicState().withDownloadPlaylist(old, listOf("song")).playlists.single().name)
    }
    @Test fun playlistOptInKeepsPlayableFilesWhenZipOnlyIsSelected() {
        val zip = job.copy(options = Preferences(autoZip = true, keepFolder = false))
        assertTrue(zip.exportsIndividualFiles(false))
        assertFalse(zip.copy(createLibraryPlaylist = false).exportsIndividualFiles(true))
    }
    @Test fun partialFailuresImportOnlySavedSongsAndPausedJobsDoNotImport() {
        assertTrue(job.copy(state = "Failed", uris = listOf("saved")).hasImportableAudio())
        assertFalse(job.copy(state = "Failed").hasImportableAudio())
        assertFalse(job.copy(state = "Paused", uris = listOf("saved")).hasImportableAudio())
        assertFalse(job.copy(state = "Failed", uris = listOf("saved"), createLibraryPlaylist = false).hasImportableAudio())
    }
}
