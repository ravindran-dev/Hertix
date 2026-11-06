package dev.ravindran.ythub.mobile

import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

internal data class YouTubeResult(val id:String,val type:String,val title:String,val channel:String,val thumbnail:String,val url:String)
internal suspend fun searchYouTube(query:String,type:String="all"):List<YouTubeResult> {
    delay(350)
    val user=CloudAccount.state.value.userId ?: error("Sign in to search YouTube")
    val result=CloudAccount.api("/functions/v1/youtube-discovery","POST",JsonObject().apply {addProperty("q",query);addProperty("type",type)},user).asJsonObject
    return result.getAsJsonArray("items").map { item -> item.asJsonObject.let { YouTubeResult(it.get("id").asString,it.get("type").asString,it.get("title").asString,it.get("channel").asString,it.get("thumbnail").asString,it.get("source_url").asString) } }
}
internal fun openYouTubeLink(context:android.content.Context,url:String) {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
