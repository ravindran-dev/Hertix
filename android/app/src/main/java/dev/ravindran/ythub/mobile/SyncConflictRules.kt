package dev.ravindran.ythub.mobile

import com.google.gson.JsonObject

/** Version differences alone do not mean the user's values conflict. */
internal object SyncConflictRules {
    fun matches(patch: JsonObject, remote: JsonObject?): Boolean = remote != null &&
        patch.entrySet().all { (key, value) -> (remote.get(key) ?: com.google.gson.JsonNull.INSTANCE) == value }
    fun currentMembership(op:SyncOperation,state:MusicState,playlistMap:Map<String,String>,trackMap:Map<String,String>):JsonObject? {
        if(op.entity_type!="playlist_items" || op.expected_version!=0L)return null
        val playlist=op.patch.get("playlist_id")?.asString ?: return null
        val song=op.patch.get("library_item_id")?.asString ?: return null
        val localPlaylist=playlistMap.entries.find {it.value==playlist}?.key ?: return null
        val localTrack=trackMap.entries.find {it.value==song}?.key ?: return null
        val members=state.playlists.find {it.id==localPlaylist}?.tracks ?: return null
        val position=members.indexOf(localTrack)
        if(position<0)return null
        return json("playlist_id" to playlist,"library_item_id" to song).apply {addProperty("position",position);add("deleted_at",com.google.gson.JsonNull.INSTANCE)}
    }
    fun sameRestoredOrder(op:SyncOperation,state:MusicState,rows:Map<String,List<JsonObject>>,playlistMap:Map<String,String>,trackMap:Map<String,String>):Boolean {
        if(op.entity_type!="playlist_items" || op.expected_version!=0L)return false
        val cloud=op.patch.get("playlist_id")?.asString ?: return false
        val remote=rows["playlist_items"]?.find {it.get("id")?.asString==op.entity_id} ?: return false
        if(remote.get("deleted_at")?.isJsonNull==false || remote.get("playlist_id")!=op.patch.get("playlist_id") || remote.get("library_item_id")!=op.patch.get("library_item_id"))return false
        val localId=playlistMap.entries.find {it.value==cloud}?.key ?: return false
        val members=state.playlists.find {it.id==localId}?.tracks ?: return false
        val reverse=trackMap.entries.associate {it.value to it.key}
        val cloudMembers=rows["playlist_items"].orEmpty().filter {it.get("playlist_id")?.asString==cloud && it.get("deleted_at")?.isJsonNull!=false}
            .sortedWith(compareBy<JsonObject> {it.get("position").asDouble}.thenBy {it.get("id").asString})
            .map {reverse[it.get("library_item_id").asString]}
        return cloudMembers.none {it==null} && members==cloudMembers
    }
    fun playlistName(op: SyncOperation, rows: Map<String,List<JsonObject>>): String? {
        val id = if(op.entity_type == "playlists") op.entity_id else op.patch.get("playlist_id")?.asString ?: op.remote?.get("playlist_id")?.asString
        return rows["playlists"]?.find { it.get("id")?.asString == id }?.get("name")?.asString
    }
    fun title(op: SyncOperation, rows: Map<String,List<JsonObject>>): String {
        val song = op.patch.get("library_item_id")?.asString ?: op.remote?.get("library_item_id")?.asString ?: op.entity_id
        return rows["library_items"]?.find { it.get("id")?.asString == song }?.get("title")?.asString
            ?: op.patch.get("name")?.asString ?: playlistName(op,rows) ?: "Library change"
    }
}
