package dev.ravindran.ythub.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object MobileUi {
    val background = Color(0xFF0B0B0D)
    val surface = Color(0xFF18181B)
    val elevated = Color(0xFF242427)
    val accent = Color(0xFFFF1838)
    val page = 16.dp
    val card = RoundedCornerShape(10.dp)
}
internal val PlayerCrimson = MobileUi.accent
internal fun musicTypography(): Typography {
    val family = FontFamily(Font(R.font.inter), Font(R.font.inter, FontWeight.Bold), Font(R.font.inter, FontWeight.Medium), Font(R.font.inter, FontWeight.SemiBold))
    val t = Typography()
    return t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = t.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        headlineMedium = t.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = t.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
        titleMedium = t.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
        titleSmall = t.titleSmall.copy(fontFamily = family,fontWeight = FontWeight.Medium,fontSize = 15.sp,lineHeight = 21.sp), bodyLarge = t.bodyLarge.copy(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = family,fontSize = 14.sp,lineHeight = 20.sp), bodySmall = t.bodySmall.copy(fontFamily = family,fontSize = 12.sp,lineHeight = 17.sp),
        labelLarge = t.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontFamily = family), labelSmall = t.labelSmall.copy(fontFamily = family)
    )
}
@Composable internal fun MusicSectionTitle(title: String, action: String = "See all", onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action); Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp)) }
    }
}
@Composable internal fun MusicArtworkCard(track: LocalTrack, subtitle: String = track.artist, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    val playerFlow=remember {MusicPlayback.state.map {it.track?.id to it.playing}.distinctUntilChanged()}
    val player by playerFlow.collectAsStateWithLifecycle(initialValue=remember {MusicPlayback.state.value.let {it.track?.id to it.playing}})
    val active=player.first==track.id
    Column(modifier.clip(RoundedCornerShape(10.dp)).glassTouch(onClick,track.available).padding(bottom=6.dp)) {
        Box {
            TrackArtwork(track,Modifier.fillMaxWidth().aspectRatio(1f))
            if(active)Surface(Modifier.align(Alignment.BottomStart).padding(8.dp),shape=RoundedCornerShape(6.dp),color=Color.Black.copy(alpha=.68f)) {
                Row(Modifier.padding(horizontal=7.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    PlayingNotation(player.second,Modifier.size(13.dp),Color.White)
                    Text(if(player.second) "Playing" else "Paused",fontSize=10.sp,lineHeight=13.sp,color=Color.White)
                }
            }
            if(onMore!=null)ArtworkMoreButton(track.title, onMore, Modifier.align(Alignment.TopEnd).padding(7.dp))
        }
        Text(track.title,Modifier.fillMaxWidth().padding(top=9.dp),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,color=if(active)MobileUi.accent else MaterialTheme.colorScheme.onSurface)
        Text(if(track.available)subtitle else "File unavailable",Modifier.fillMaxWidth().padding(top=2.dp),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ArtworkMoreButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassControlSurface(modifier.size(38.dp), shape = RoundedCornerShape(50), tintAlpha = .48f, flexible = false) {
        Box(Modifier.fillMaxSize().glassTouch(onClick).semantics { contentDescription = "More options for $title" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MoreHoriz, null, Modifier.size(20.dp), tint = Color.White)
        }
    }
}
@Composable internal fun PlaylistCover(playlist:LocalPlaylist,tracks:List<LocalTrack>,modifier:Modifier=Modifier) {
    val covers=remember(playlist.tracks,tracks) {val byId=tracks.associateBy {it.id};playlist.tracks.mapNotNull {byId[it]}.filter {it.artwork.isNotBlank()}.distinctBy {it.artwork}.take(4)}
    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        if(covers.size==4) Column(Modifier.fillMaxSize()) {
            covers.chunked(2).forEach {row ->Row(Modifier.weight(1f)) {row.forEach {track ->TrackArtwork(track,Modifier.weight(1f).fillMaxHeight(),RoundedCornerShape(0.dp))}}}
        } else TrackArtwork(covers.firstOrNull() ?: LocalTrack(playlist.id,"playlist://${playlist.id}",playlist.name,album=playlist.name),Modifier.fillMaxSize())
    }
}
@Composable internal fun MusicShelf(title: String, tracks: List<LocalTrack>, empty: String, onAll: () -> Unit, onTrack: (LocalTrack) -> Unit) {
    MusicSectionTitle(title, onAction = onAll)
    if (tracks.isEmpty()) Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Text(empty, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tracks.take(8), key = { it.id }) { track -> MusicArtworkCard(track, modifier = Modifier.width(150.dp)) { onTrack(track) } }
    }
}
@Composable internal fun PlaylistArtworkRow(playlist: LocalPlaylist, tracks: List<LocalTrack>, onClick: () -> Unit) {
    val ordered=remember(playlist.tracks,tracks) {val byId=tracks.associateBy {it.id};playlist.tracks.mapNotNull {byId[it]}}
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).glassTouch(onClick).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            PlaylistCover(playlist,tracks,Modifier.size(62.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(playlist.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold)
                Text("${playlist.tracks.size} songs · ${durationLabel(ordered.sumOf {it.durationMs}/1000)}",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight,"Open playlist",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(start=76.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
    }
}
@Composable internal fun MusicSettingsSection(title: String, summary: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(MobileMotion.standard), label = "Settings disclosure")
    LiquidGlassPanel(Modifier.fillMaxWidth(), backdrop = LocalAtmosphereLayer.current, radius = 26.dp, animateOnTouch = false) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
                Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }, tint = PlayerCrimson)
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(220)) + fadeIn(tween(160)), exit = shrinkVertically(tween(180)) + fadeOut(tween(100))) { Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
        }
    }
}

/** Shared compact sheet action; destructive actions are visually distinct from normal navigation. */
@Composable internal fun MusicActionRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    forward: Boolean = false
) {
    val tint = if (destructive) MobileUi.accent else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).glassTouch(onClick, enabled)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint.copy(alpha = if (enabled) .85f else .3f))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = tint.copy(alpha = if (enabled) 1f else .4f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (forward) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact real-content rows keep Home's primary download controls near the top. */
@Composable internal fun MusicRecentList(title: String, tracks: List<LocalTrack>, empty: String, onAll: () -> Unit, onTrack: (LocalTrack) -> Unit) {
    MusicSectionTitle(title, onAction = onAll)
    if (tracks.isEmpty()) {
        Text(empty, Modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Column {
        tracks.take(3).forEach { track ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).glassTouch({ onTrack(track) }, track.available).padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrackArtwork(track, Modifier.size(46.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${track.artist} · ${track.format}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(durationLabel(track.durationMs / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (track != tracks.take(3).last()) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), modifier = Modifier.padding(start = 62.dp))
        }
    }
}

@Composable
internal fun MusicPillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit,
    enabled: Boolean = true, primary: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val foreground = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val fill = if (primary) MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .30f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .09f else .035f)
    Box(modifier.heightIn(min = 48.dp).clip(shape).glassTouch(onClick, enabled), contentAlignment = Alignment.Center) {
        Surface(Modifier.height(36.dp), shape = shape, color = fill,
            border = if (primary) null else BorderStroke(.65.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .20f))) {
            Row(Modifier.padding(horizontal = 15.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(17.dp), tint = foreground.copy(alpha = if (enabled) 1f else .5f))
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = foreground.copy(alpha = if (enabled) 1f else .5f), maxLines = 1)
            }
        }
    }
}
