package dev.ravindran.ythub.mobile

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Additive v1 migration; unreadable/future data is never overwritten by the repository. */
internal object MusicPersistence {
    private val gson = Gson()
    fun decode(text: String): MusicState {
        val incoming = JsonParser.parseString(text).asJsonObject
        val version = incoming.get("version")?.asInt ?: 1
        require(version in 1..2) { "Unsupported library version $version" }
        val defaults = gson.toJsonTree(MusicState()).asJsonObject
        fun merge(target: JsonObject, source: JsonObject) {
            source.entrySet().forEach { (key, value) ->
                require(!value.isJsonNull) { "Invalid null value for $key" }
                if (value.isJsonObject && target.get(key)?.isJsonObject == true) merge(target.getAsJsonObject(key), value.asJsonObject)
                else target.add(key, value)
            }
        }
        merge(defaults, incoming)
        val state = gson.fromJson(defaults, MusicState::class.java)
        require(state.tracks.all { it.id.isNotBlank() && it.uri.isNotBlank() && it.title.isNotBlank() }) { "Invalid track metadata" }
        require(state.playlists.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Invalid playlist metadata" }
        require(state.playlists.distinctBy { it.id }.size == state.playlists.size) { "Duplicate playlist IDs" }
        val settings = state.settings.copy(
            speed = state.settings.speed.takeIf { it in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f) } ?: 1f,
            bass = state.settings.bass.coerceIn(0, 1000),
            skipForward = state.settings.skipForward.coerceIn(5, 60), skipBackward = state.settings.skipBackward.coerceIn(5, 60),
            libraryLayout = state.settings.libraryLayout.takeIf { it in listOf("Grid", "Compact") } ?: "Grid",
            songsLayout = state.settings.songsLayout.takeIf { it in listOf("Grid", "Compact") } ?: "Compact")
        return state.copy(version = 2, tracks = state.tracks.distinctBy { it.id }.map { track ->
            track.copy(sourceVideoUrl = track.sourceVideoUrl.orEmpty())
        }, queueIndex = state.queueIndex.coerceIn(0, (state.queue.size - 1).coerceAtLeast(0)),
            position = state.position.coerceAtLeast(0), repeat = state.repeat.coerceIn(0, 2), settings = settings)
    }
    fun mergeLocalEdits(local: MusicState, before: MusicState, after: MusicState): MusicState {
        val removed = before.playlists.map { it.id }.toSet() - after.playlists.map { it.id }.toSet()
        val changed = after.playlists.filter { p -> before.playlists.find { it.id == p.id } != p }
        return local.copy(playlists = local.playlists.filterNot { it.id in removed || it.id in changed.map { p -> p.id } } + changed,
            favorites = (local.favorites - (before.favorites - after.favorites)) + (after.favorites - before.favorites))
    }
    /** Disk cache uses oldest-write eviction; metadata/lyrics never depend on cache retention. */
    @Synchronized fun trimArtwork(directory: File, limit: Long = 64L * 1024 * 1024) {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        for (file in files) { if (bytes <= limit) break; val size = file.length(); if (file.delete()) bytes -= size }
    }
}
