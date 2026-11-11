package com.salmansaadiq.dheu.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.core.parseInstant
import com.salmansaadiq.dheu.core.timeAgo
import com.salmansaadiq.dheu.data.Activity
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.StorageImage
import com.salmansaadiq.dheu.data.storageImage
import com.salmansaadiq.dheu.data.NotificationRow
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.Relationships
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.EmptyState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.FollowButton
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.SectionTitle
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private class ActivityData(val items: List<NotificationRow>, val relationships: Relationships)

@Composable
fun ActivityScreen() {
    val nav = LocalNav.current
    val app = LocalAppState.current
    val loader = rememberLoader("activity") {
        coroutineScope {
            val rel = async { Profiles.relationships(app.userId) }
            ActivityData(Activity.notifications(app.userId), rel.await())
        }
    }
    // Opening the page clears the badge; this visit still shows what was new.
    val state = loader.state
    LaunchedEffect(state is Load.Ready) {
        val data = (state as? Load.Ready)?.value ?: return@LaunchedEffect
        if (data.items.any { it.readAt == null }) {
            runCatching { Activity.markAllRead(app.userId) }
            app.refreshBadges()
        }
    }

    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("Activity", onBack = nav::back)
        when (state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(state.message, onRetry = { loader.reload() })
            is Load.Ready -> PullToRefreshBox(isRefreshing = loader.refreshing, onRefresh = { loader.reload(silent = true) }) {
                ActivityList(state.value, onChanged = { loader.reload(silent = true) })
            }
        }
    }
}

private fun bucket(iso: String, now: Instant): String {
    val age = Duration.between(parseInstant(iso), now)
    return when {
        age.toDays() < 1 -> "Today"
        age.toDays() < 7 -> "This week"
        else -> "Earlier"
    }
}

@Composable
private fun ActivityList(data: ActivityData, onChanged: () -> Unit) {
    if (data.items.isEmpty()) {
        EmptyState("Nothing yet", "Follow requests, likes, comments, mentions and story reactions will show up here.")
        return
    }
    val now = remember { Instant.now() }
    // Pending follow requests always sit at the top, whatever their age.
    val requests = data.items.filter { it.type == "follow_request" }
    val rest = data.items.filter { it.type != "follow_request" }
    val groups = buildList {
        if (requests.isNotEmpty()) add("Follow requests" to requests)
        rest.filter { it.readAt == null }.takeIf { it.isNotEmpty() }?.let { add("New" to it) }
        for (title in listOf("Today", "This week", "Earlier")) {
            rest.filter { it.readAt != null && bucket(it.createdAt, now) == title }.takeIf { it.isNotEmpty() }?.let { add(title to it) }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (title, rows) ->
            item(key = "h-$title") { SectionTitle(title, Modifier.padding(top = 8.dp)) }
            items(rows, key = { it.id }) { NotificationItem(it, data.relationships, onChanged) }
        }
    }
}

@Composable
private fun NotificationItem(n: NotificationRow, relationships: Relationships, onChanged: () -> Unit) {
    val actor = n.actor ?: return
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val c = Dheu.colors
    val chat = n.type == "mention" && n.conversationId != null
    val text = when (n.type) {
        "like" -> "liked your post."
        "follow" -> "started following you."
        "follow_request" -> "wants to follow you."
        "follow_accepted" -> "accepted your follow request."
        "comment" -> "commented: ${n.comment?.body ?: "…"}"
        "mention" -> when {
            chat -> "mentioned you in ${n.conversation?.title ?: "a group"}" + (n.message?.body?.let { ": $it" } ?: ".")
            n.comment != null -> "mentioned you in a comment: ${n.comment.body}"
            else -> "mentioned you in a post."
        }
        else -> "reacted to your story."
    }
    val social = n.type in setOf("follow", "follow_request", "follow_accepted")
    val thumb = if (n.type == "story_reaction") storageImage(Buckets.STORIES, n.story?.imagePath)
    else n.post?.images?.minByOrNull { it.position }?.let { StorageImage(Buckets.POSTS, it.thumbPath ?: it.path) }

    Row(
        Modifier.fillMaxWidth().background(if (n.readAt == null) c.brandSoft.copy(alpha = 0.4f) else c.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(actor.avatarPath, actor.fullName, 44.dp, Modifier.clickable { nav.profile(actor.username) })
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(actor.username) }
                append(" $text ")
                withStyle(SpanStyle(color = c.inkFaint)) { append(timeAgo(n.createdAt)) }
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { nav.profile(actor.username) },
        )
        Spacer(Modifier.width(8.dp))
        when {
            n.type == "follow_request" -> RequestButtons(actor.id, me, onChanged)
            social -> FollowButton(actor.id, relationships.stateOf(actor.id))
            chat -> Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2).clickable { nav.chat(n.conversationId) },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.MessagesSquare, "Open chat", tint = c.inkMuted, modifier = Modifier.size(20.dp)) }
            thumb != null -> AsyncImage(
                model = thumb,
                contentDescription = "Open",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2).clickable {
                    if (n.type == "story_reaction") nav.story(me) else n.postId?.let(nav::post)
                },
            )
            else -> Text("no longer available", color = c.inkFaint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
        }
    }
}

@Composable
private fun RequestButtons(requesterId: String, me: String, onChanged: () -> Unit) {
    var busy by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    fun go(kind: String) {
        busy = kind
        scope.launch {
            try {
                if (kind == "accept") Activity.accept(requesterId) else Activity.decline(me, requesterId)
                done = if (kind == "accept") "Accepted" else "Declined"
                onChanged()
            } catch (e: Exception) {
                toast(e.friendly())
            } finally {
                busy = null
            }
        }
    }

    val shown = done
    if (shown != null) {
        Text(shown, color = Dheu.colors.inkFaint, style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Accept", { go("accept") }, loading = busy == "accept", enabled = busy == null, compact = true)
        Box {
            SecondaryButton("", { go("decline") }, loading = busy == "decline", enabled = busy == null, icon = Lucide.X, compact = true)
        }
    }
}
