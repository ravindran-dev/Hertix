package dev.ravindran.ythub.mobile

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.UUID

data class SyncOperation(val operation_id: String = UUID.randomUUID().toString(), val user_id: String, val device_id: String, val entity_type: String, val entity_id: String, val expected_version: Long, val patch: JsonObject, val created_at: String = Instant.now().toString(), var attempt_count: Int = 0, var last_error: String? = null, var status: String = "pending", var remote: JsonObject? = null)
data class CloudCache(var enabled: Boolean = false, var playlists: Boolean = true, var favorites: Boolean = true, var preferences: Boolean = false, var lastSync: String? = null, val queue: MutableList<SyncOperation> = mutableListOf(), val rows: MutableMap<String,List<JsonObject>> = mutableMapOf(), val trackMap: MutableMap<String,String> = mutableMapOf(), val playlistMap: MutableMap<String,String> = mutableMapOf(), val deferred: MutableMap<String,JsonObject> = mutableMapOf())
data class SyncView(val status: String = "Paused", val enabled: Boolean = false, val lastSync: String? = null, val queue: List<SyncOperation> = emptyList(), val rows: Map<String,List<JsonObject>> = emptyMap(), val playlists: Boolean = true, val favorites: Boolean = true, val preferences: Boolean = false)
object CloudSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val gson = Gson()
    private lateinit var context: Context
    private var owner: String? = null
    private var cache = CloudCache()
    private var baseline = MusicState()
    // Offline data is authoritative and survives sign-out. Online records live in the account cache.
    fun localSnapshot(state: MusicState): MusicState = state.copy(tracks=state.tracks.filterNot { it.uri.startsWith("cloud://") })
    private var nextTry = 0L
    private var failures = 0
    private val mutable = MutableStateFlow(SyncView())
    val state = mutable.asStateFlow()
    private val entities = listOf("library_items","playlists","playlist_items","favorites","playback_preferences")
    private val device by lazy { context.getSharedPreferences("cloud-device",0).let { prefs -> prefs.getString("id",null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id",it).commit() } } }
    private fun file() = AtomicFile(File(context.noBackupFilesDir, "cloud-${owner ?: "guest"}.json"))
    private fun save(status: String = mutable.value.status) {
        if(owner != null) {
            val target = file(); val output = target.startWrite()
            try { output.write(gson.toJson(cache).toByteArray()); target.finishWrite(output) } catch(e: Exception) { target.failWrite(output); throw e }
        }
        mutable.value = SyncView(status,cache.enabled,cache.lastSync,cache.queue.map { it.copy() },cache.rows.toMap(),cache.playlists,cache.favorites,cache.preferences)
    }
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun init(ctx: Context) {
        context = ctx.applicationContext
        scope.launch {
            MusicRepository.ready.first { it }
            CloudAccount.state.map { it.userId }.distinctUntilChanged().collect { id -> lock.withLock {
                if(owner != id) {
                    owner = id; cache = CloudCache()
                    if(id != null) {
                        val target = file()
                        if(target.baseFile.exists() || File(target.baseFile.path+".bak").exists()) cache = gson.fromJson(target.openRead().bufferedReader().use { it.readText() },CloudCache::class.java)
                        cache.queue.forEach { if(it.status == "in-progress") it.status = "pending" }
                        reconcileIdentical()
                    }
                    baseline = MusicRepository.state.value
                    save(if(cache.enabled) "Waiting to sync" else "Paused")
                }
            }
                if(id!=null) DownloadStore.items.value.filter {it.cloudRecord!=null && it.accountId==id && it.state=="Interrupted"}.forEach {runCatching {DownloadStore.resume(it.id)}}
            }
        }
        scope.launch {
            MusicRepository.ready.first { it }
            MusicRepository.state.debounce(800).collect {
                lock.withLock { if(owner != null && cache.enabled) {
                    val current=MusicRepository.state.value
                    if(current.tracks!=baseline.tracks || current.playlists!=baseline.playlists || current.favorites!=baseline.favorites || current.settings.speed!=baseline.settings.speed || current.repeat!=baseline.repeat || current.shuffle!=baseline.shuffle) {
                        capture(current);save(if(cache.queue.isEmpty()) mutable.value.status else "Waiting to sync")
                    }
                } }
            }
        }
    }
    fun configure(enabled: Boolean = state.value.enabled, playlists: Boolean = state.value.playlists, favorites: Boolean = state.value.favorites, preferences: Boolean = state.value.preferences) { scope.launch { lock.withLock {
        check(owner != null) { "Sign in first" }
        val first = enabled && (!cache.enabled || (playlists && !cache.playlists) || (favorites && !cache.favorites) || (preferences && !cache.preferences))
        cache.enabled = enabled; cache.playlists=playlists;cache.favorites=favorites;cache.preferences=preferences
        if(first) capture(MusicRepository.state.value, true)
        save(if(enabled) "Waiting to sync" else "Paused")
    }; synchronize(true) } }
    private fun id(entity: String,row: JsonObject) = row.get(if(entity=="favorites") "library_item_id" else if(entity=="playback_preferences") "user_id" else "id").asString
    private fun add(entity: String, id: String, patch: JsonObject) {
        val pending=cache.queue.find { it.entity_type==entity && it.entity_id==id }
        if(pending!=null) {
            if(pending.status=="pending" && pending.attempt_count==0) patch.entrySet().forEach { (key,value) -> pending.patch.add(key,value) }
            else cache.deferred.getOrPut("$entity:$id") { JsonObject() }.let { target -> patch.entrySet().forEach { (key,value) -> target.add(key,value) } }
            return
        }
        val remote = cache.rows[entity]?.find { id(entity,it)==id }
        val changed = JsonObject().apply { patch.entrySet().forEach { (key,value) -> if(remote?.get(key)!=value) add(key,value) } }
        if(changed.size()==0) return
        cache.queue += SyncOperation(user_id=owner!!,device_id=device,entity_type=entity,entity_id=id,expected_version=remote?.get("version")?.asLong ?: 0,patch=changed)
    }
    private fun trackId(local: String) = cache.trackMap.getOrPut(local) { UUID.randomUUID().toString() }
    private fun capture(current: MusicState, initial: Boolean = false) {
        for(track in current.tracks.filterNot { it.uri.startsWith("cloud://") }) {
            if(initial || baseline.tracks.find { it.id==track.id } != track) add("library_items",trackId(track.id),json("source_type" to "local","title" to track.title,"artist" to track.artist,"album" to track.album,"media_format" to track.format).apply { addProperty("duration_ms",track.durationMs) })
        }
        if(cache.favorites) for(local in (current.favorites + baseline.favorites)) {
            if(initial || (local in current.favorites)!=(local in baseline.favorites)) cache.trackMap[local]?.let { add("favorites",it,JsonObject().apply { addProperty("active",local in current.favorites) }) }
        }
        if(cache.playlists) {
            for(p in current.playlists) if(initial || baseline.playlists.find { it.id==p.id } != p) {
                val cloudId = cache.playlistMap.getOrPut(p.id) { UUID.randomUUID().toString() }
                add("playlists",cloudId,json("name" to p.name))
                p.tracks.forEachIndexed { index, local -> cache.trackMap[local]?.let { track ->
                    val itemId = UUID.nameUUIDFromBytes("$cloudId:$track".toByteArray()).toString()
                    add("playlist_items",itemId,json("playlist_id" to cloudId,"library_item_id" to track).apply { addProperty("position",index); add("deleted_at",com.google.gson.JsonNull.INSTANCE) })
                } }
                cache.rows["playlist_items"].orEmpty().filter { it.get("playlist_id").asString==cloudId && cache.trackMap.entries.find { entry -> entry.value==it.get("library_item_id").asString }?.key !in p.tracks }.forEach { add("playlist_items",id("playlist_items",it),json("deleted_at" to Instant.now().toString())) }
            }
            for(p in baseline.playlists.filter { old -> current.playlists.none { it.id==old.id } }) cache.playlistMap[p.id]?.let { add("playlists",it,json("deleted_at" to Instant.now().toString())) }
        }
        if(cache.preferences && (initial || current.settings.speed!=baseline.settings.speed || current.repeat!=baseline.repeat || current.shuffle!=baseline.shuffle)) add("playback_preferences",owner!!,JsonObject().apply { addProperty("speed",current.settings.speed);addProperty("repeat_mode",current.repeat);addProperty("shuffle",current.shuffle) })
        baseline = current
    }
    private fun reconcileIdentical() {
        cache.queue.removeAll { op ->
            val remote=cache.rows[op.entity_type]?.find { id(op.entity_type,it)==op.entity_id } ?: op.remote
            if(op.status=="conflict" && remote!=null)op.remote=remote.deepCopy()
            val restored=SyncConflictRules.currentMembership(op,MusicRepository.state.value,cache.playlistMap,cache.trackMap)
            val deferred=cache.deferred["${op.entity_type}:${op.entity_id}"]
            val restoredOrder=SyncConflictRules.sameRestoredOrder(op,MusicRepository.state.value,cache.rows,cache.playlistMap,cache.trackMap)
            val same=SyncConflictRules.matches(op.patch,remote) || (restored!=null && SyncConflictRules.matches(restored,remote))
            // A restored offline playlist supersedes queued old edits only when its full membership/order equals cloud.
            val resolved=restoredOrder || (same && (deferred==null || SyncConflictRules.matches(deferred,remote)))
            if(resolved)cache.deferred.remove("${op.entity_type}:${op.entity_id}")
            resolved
        }
    }
    suspend fun synchronize(force: Boolean = false) { lock.withLock {
        if(!cache.enabled || owner==null || CloudAccount.state.value.userId!=owner || (!force && System.currentTimeMillis()<nextTry)) return
        if(!CloudBackupPolicy.permitsMetadata(context)) { save("Backup permission required"); return }
        if(!CloudBackupPolicy.networkAllowed(context)) { save("Waiting for allowed network"); return }
        try {
            reconcileIdentical()
            save("Syncing")
            val iterator=cache.queue.iterator()
            while(iterator.hasNext()) {
                check(CloudBackupPolicy.permitsMetadata(context) && CloudBackupPolicy.networkAllowed(context)) { "Backup paused by permission or network" }
                val op=iterator.next(); if(op.status=="conflict") continue
                op.status="in-progress";op.attempt_count++;save("Syncing")
                val response=CloudAccount.api("/rest/v1/rpc/apply_sync_operation","POST",JsonObject().apply { addProperty("operation_id",op.operation_id);addProperty("entity_type",op.entity_type);addProperty("entity_id",op.entity_id);addProperty("expected_version",op.expected_version);add("patch",op.patch) },owner).asJsonObject
                if(response.get("status").asString=="conflict" && SyncConflictRules.matches(op.patch,response.get("remote")?.takeUnless {it.isJsonNull}?.asJsonObject)) iterator.remove()
                else if(response.get("status").asString=="conflict") { op.status="conflict";op.remote=response.get("remote")?.takeUnless { it.isJsonNull }?.asJsonObject;op.last_error="Changed on another device" } else if(response.get("status").asString=="applied") iterator.remove() else error("Invalid response")
                save("Syncing")
            }
            val rows=mutableMapOf<String,List<JsonObject>>()
            for(entity in entities) {
                val result=mutableListOf<JsonObject>();var offset=0
                while(true) {
                    check(CloudBackupPolicy.permitsMetadata(context) && CloudBackupPolicy.networkAllowed(context)) { "Backup paused by permission or network" }
                    val key=if(entity=="favorites") "library_item_id" else if(entity=="playback_preferences") "user_id" else "id"
                    val page=CloudAccount.api("/rest/v1/$entity?user_id=eq.$owner&select=*&order=$key&limit=500&offset=$offset",expectedOwner=owner).asJsonArray.map { it.asJsonObject }
                    result.addAll(page);if(page.size<500) break;offset+=500
                };rows[entity]=result
            }
            check(CloudAccount.state.value.userId==owner) { "Account changed" }
            // Capture changes made during network I/O before applying remote state.
            capture(MusicRepository.state.value)
            cache.rows.putAll(rows)
            for((key,patch) in cache.deferred.toMap()) {
                val entity=key.substringBefore(':');val item=key.substringAfter(':')
                if(cache.queue.none { it.entity_type==entity && it.entity_id==item }) {cache.deferred.remove(key);add(entity,item,patch)}
            }
            reconcileIdentical();baseline=MusicRepository.state.value;cache.lastSync=Instant.now().toString();failures=0;nextTry=0
            save(if(cache.queue.any {it.status=="conflict"}) "Needs review" else if(cache.queue.isEmpty()) "Synced" else "Waiting to sync")
        } catch(e: CancellationException) {
            cache.queue.filter {it.status=="in-progress"}.forEach {it.status="pending"}
            runCatching {save("Backup paused")}
            throw e
        } catch(_: Exception) {
            cache.queue.filter { it.status=="in-progress" }.forEach { it.status="failed";it.last_error="Request failed; retry available" }
            nextTry=System.currentTimeMillis()+(2000L shl (failures++).coerceAtMost(7)).coerceAtMost(300000)
            runCatching { save("Sync failed") }
        }
    } }
    suspend fun ensureTrack(track: LocalTrack): String = lock.withLock {
        val actor=owner ?: error("Sign in first")
        check(CloudAccount.state.value.userId==actor) { "Account changed" }
        val cloudId=trackId(track.id)
        if(cache.rows["library_items"].orEmpty().none { id("library_items",it)==cloudId }) {
            val result=CloudAccount.api("/rest/v1/rpc/apply_sync_operation","POST",json("operation_id" to UUID.randomUUID().toString(),"entity_type" to "library_items","entity_id" to cloudId).apply {
                addProperty("expected_version",0);add("patch",json("source_type" to "local","title" to track.title,"artist" to track.artist,"album" to track.album,"media_format" to track.format).apply { addProperty("duration_ms",track.durationMs) })
            },actor).asJsonObject
            if(result.get("status").asString=="applied") cache.rows["library_items"]=cache.rows["library_items"].orEmpty()+result.getAsJsonObject("record")
            else if(result.get("remote")?.isJsonNull==false) cache.rows["library_items"]=cache.rows["library_items"].orEmpty()+result.getAsJsonObject("remote")
            else error("Library entry unavailable")
        };save();cloudId
    }
    suspend fun localTrackId(cloudId: String): String? = lock.withLock { cache.trackMap.entries.find { it.value==cloudId }?.key }
    suspend fun attachLocal(cloudId: String,localId:String) = lock.withLock {
        val old=cache.trackMap.entries.find { it.value==cloudId }?.key
        if(old!=null) cache.trackMap.remove(old)
        cache.trackMap[localId]=cloudId
        MusicRepository.update { current -> current.copy(tracks=current.tracks.filterNot { it.id==old && it.uri.startsWith("cloud://") },
            favorites=if(old!=null && old in current.favorites) (current.favorites-old)+localId else current.favorites,
            playlists=current.playlists.map { it.copy(tracks=it.tracks.map { id -> if(id==old) localId else id }) }) }
        baseline=MusicRepository.state.value;save()
    }
    suspend fun refreshOnline() {
        val actor=CloudAccount.state.value.userId ?: error("Sign in first")
        awaitAccount(actor)
        lock.withLock {
            check(owner==actor && CloudBackupPolicy.networkAllowed(context)) {"Waiting for the allowed network"}
            val rows=mutableMapOf<String,List<JsonObject>>()
            for(entity in entities) {
                val result=mutableListOf<JsonObject>();var offset=0
                val key=if(entity=="favorites")"library_item_id" else if(entity=="playback_preferences")"user_id" else "id"
                while(true) {
                    check(CloudBackupPolicy.networkAllowed(context)) {"Waiting for the allowed network"}
                    val page=CloudAccount.api("/rest/v1/$entity?user_id=eq.$actor&select=*&order=$key&limit=500&offset=$offset",expectedOwner=actor).asJsonArray.map {it.asJsonObject}
                    result+=page;if(page.size<500)break;offset+=500
                }
                rows[entity]=result
            }
            cache.rows.putAll(rows);reconcileIdentical();save("Online library refreshed")
        }
    }
    suspend fun awaitAccount(id:String) {
        withTimeout(30_000) {while(!lock.withLock {owner==id})delay(100)}
    }
    suspend fun backupNow() {
        lock.withLock {
            check(owner != null && owner==CloudAccount.state.value.userId) { "Sign in first" }
            cache.enabled=true;cache.playlists=true;cache.favorites=true;cache.preferences=true; capture(MusicRepository.state.value,true); save("Waiting to sync")
        }
        synchronize(true)
    }
    private fun acceptCloudChoice(op: SyncOperation) {
        val remote=op.remote ?: return
        MusicRepository.editLocal { current ->
            when(op.entity_type) {
                "playlist_items" -> {
                    val playlist=cache.playlistMap.entries.find {it.value==remote.get("playlist_id")?.asString}?.key
                    val song=cache.trackMap.entries.find {it.value==remote.get("library_item_id")?.asString}?.key
                    current.copy(playlists=current.playlists.map { p -> if(p.id!=playlist || song==null) p else {
                        val songs=p.tracks.filterNot {it==song}.toMutableList()
                        if(remote.get("deleted_at")?.isJsonNull!=false) songs.add((remote.get("position")?.asInt ?: songs.size).coerceIn(0,songs.size),song)
                        p.copy(tracks=songs)
                    } })
                }
                "playlists" -> { val local=cache.playlistMap.entries.find {it.value==op.entity_id}?.key
                    current.copy(playlists=if(remote.get("deleted_at")?.isJsonNull==false)current.playlists.filterNot {it.id==local} else current.playlists.map {if(it.id==local)it.copy(name=remote.get("name").asString) else it}) }
                "library_items" -> {val local=cache.trackMap.entries.find {it.value==op.entity_id}?.key
                    current.copy(tracks=current.tracks.map {if(it.id==local)it.copy(title=remote.get("title").asString,artist=remote.get("artist").asString,album=remote.get("album").asString,durationMs=remote.get("duration_ms").asLong) else it})}
                "favorites" -> {val local=cache.trackMap.entries.find {it.value==op.entity_id}?.key
                    if(local==null)current else current.copy(favorites=if(remote.get("active").asBoolean)current.favorites+local else current.favorites-local)}
                "playback_preferences" -> current.copy(settings=current.settings.copy(speed=remote.get("speed").asFloat),repeat=remote.get("repeat_mode").asInt,shuffle=remote.get("shuffle").asBoolean)
                else -> current
            }
        }
        baseline=MusicRepository.state.value
    }
    fun retry() { scope.launch { synchronize(true) } }
    fun resolve(operation: String, local: Boolean) { scope.launch { lock.withLock {
        val op=cache.queue.find { it.operation_id==operation } ?: return@withLock
        cache.queue.remove(op)
        val deferred=cache.deferred.remove("${op.entity_type}:${op.entity_id}")
        if(local && deferred!=null) deferred.entrySet().forEach { (key,value) -> op.patch.add(key,value) }
        if(!local) acceptCloudChoice(op)
        if(local) cache.queue+=op.copy(operation_id=UUID.randomUUID().toString(),expected_version=op.remote?.get("version")?.asLong ?: 0,status="pending",last_error=null)
        save("Waiting to sync")
    };synchronize(true) } }
}
