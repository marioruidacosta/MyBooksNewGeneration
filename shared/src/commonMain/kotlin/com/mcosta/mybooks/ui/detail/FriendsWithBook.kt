package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.FriendWithBook
import com.mcosta.mybooks.ui.social.PersonAvatar
import com.mcosta.mybooks.ui.social.StarColor
import com.mcosta.mybooks.ui.social.friendWithBookText
import com.mcosta.mybooks.ui.social.starsText

/** "Friends who have this book": each friend with their shelf, stars and the start of their review; tap for their profile. */
@Composable
fun FriendsWithBookSection(friends: List<FriendWithBook>, onPerson: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        friends.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { onPerson(f.person.id) }, verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(f.person.avatarUrl, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(f.person.displayName) }
                            append(" ")
                            append(friendWithBookText(f))
                            if (f.status == ReadingStatus.READ) starsText(f.rating)?.let { stars ->
                                append(" · ")
                                withStyle(SpanStyle(color = StarColor)) { append(stars) }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (f.review.isNotBlank()) {
                        Text(
                            "“${f.review}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
