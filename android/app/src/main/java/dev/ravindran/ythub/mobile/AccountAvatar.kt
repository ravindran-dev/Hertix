package dev.ravindran.ythub.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@Composable
internal fun AccountAvatar(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val account by CloudAccount.state.collectAsStateWithLifecycle()
    val initial = (account.displayName.firstOrNull() ?: account.email.firstOrNull() ?: 'Y').uppercaseChar()
    Box(
        modifier.size(size).clip(CircleShape).background(Color(0xFF3677C8))
            .border(.8.dp, Color.White.copy(alpha = .24f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (account.userId == null) "Account, signed out" else "Account for ${account.email}" },
        contentAlignment = Alignment.Center
    ) {
        if (account.avatarUrl.startsWith("https://")) {
            AsyncImage(account.avatarUrl, "Account profile", Modifier.matchParentSize().clip(CircleShape))
        } else {
            Text(initial.toString(), color = Color.White, fontSize = (size.value * .44f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
