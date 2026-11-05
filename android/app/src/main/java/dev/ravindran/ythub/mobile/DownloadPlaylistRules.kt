package dev.ravindran.ythub.mobile

/** A null choice preserves the global behavior of older download-history records. */
internal fun DownloadItem.createsLibraryPlaylist(default: Boolean): Boolean =
    audio && media.playlist && (createLibraryPlaylist ?: default)

internal fun DownloadItem.exportsIndividualFiles(default: Boolean): Boolean =
    !media.playlist || options.keepFolder || !options.autoZip || createsLibraryPlaylist(default)

internal fun DownloadItem.hasImportableAudio(): Boolean = audio &&
    (state == "Completed" || (state == "Failed" && createLibraryPlaylist == true && uris.isNotEmpty()))

internal fun DownloadItem.orderedAudioUris(): List<String> {
    val order = playlistFileOrder.orEmpty()
    if (order.isEmpty()) return uris
    val byName = exportedNames.zip(uris).toMap()
    return order.mapNotNull { byName[it] }
}

/** Stable download identity makes rescans/retries idempotent and preserves user renames. */
internal fun MusicState.withDownloadPlaylist(job: DownloadItem, orderedIds: List<String>): MusicState {
    if (!job.createsLibraryPlaylist(settings.downloadPlaylists) || orderedIds.isEmpty()) return this
    val id = "download-${job.id}"
    val existing = playlists.find { it.id == id }
    if (existing != null && (existing.sourceTracks == null || existing.sourceTracks == orderedIds)) {
        return copy(playlists = playlists.map { if (it.id == id) it.copy(sourceTracks = orderedIds) else it })
    }
    val previousSource = existing?.sourceTracks.orEmpty().toSet()
    val manuallyRemoved = previousSource - existing?.tracks.orEmpty().toSet()
    val playlist = LocalPlaylist(id, existing?.name ?: job.libraryPlaylistName?.trim()?.takeIf { it.isNotEmpty() } ?: job.media.title,
        orderedIds.filterNot { it in manuallyRemoved } + existing?.tracks.orEmpty().filterNot { it in previousSource }, job.id, orderedIds)
    return copy(playlists = if (existing == null) playlists + playlist else playlists.map { if (it.id == id) playlist else it })
}
