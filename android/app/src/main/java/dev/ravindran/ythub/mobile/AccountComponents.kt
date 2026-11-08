package dev.ravindran.ythub.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AccountProfileCard(account: AccountState, onEdit: () -> Unit) {
    LiquidGlassPanel(Modifier.fillMaxWidth(), backdrop = LocalAtmosphereLayer.current, radius = 26.dp, animateOnTouch = false) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AccountAvatar(onEdit, size = 66.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(account.displayName.ifBlank { account.email.substringBefore('@') }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (account.verified) "Verified account" else "Verification pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, "Edit profile", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun AccountModule(title: String, subtitle: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassPanel(Modifier.fillMaxWidth(), backdrop = LocalAtmosphereLayer.current, radius = 24.dp, animateOnTouch = false) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .16f)) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/** Separate inset action cards make account controls read as buttons instead of plain text rows. */
@Composable
internal fun AccountActionButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    forward: Boolean = true,
) {
    val shape = RoundedCornerShape(17.dp)
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .68f))
            .border(BorderStroke(.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .09f)), shape)
            .glassTouch(onClick, enabled)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Surface(shape = RoundedCornerShape(11.dp), color = tint.copy(alpha = if (enabled) .14f else .06f)) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = tint.copy(alpha = if (enabled) 1f else .4f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = if (destructive) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .42f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (forward) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp), tint = Color.White.copy(alpha = .48f))
    }
}
