package com.salmansaadiq.dheu.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.salmansaadiq.dheu.data.Backend
import com.salmansaadiq.dheu.data.Buckets
import com.salmansaadiq.dheu.data.StorageImage
import com.salmansaadiq.dheu.data.FollowState
import com.salmansaadiq.dheu.data.GridPost
import com.salmansaadiq.dheu.data.HighlightSummary
import com.salmansaadiq.dheu.data.Posts
import com.salmansaadiq.dheu.data.Profile
import com.salmansaadiq.dheu.data.ProfileStats
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.ReportTarget
import com.salmansaadiq.dheu.data.Stories
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuSheet
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.EmptyState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.FollowButton
import com.salmansaadiq.dheu.ui.components.IconAction
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.components.ReportSheet
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.SheetAction
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.ActivityRoute
import com.salmansaadiq.dheu.ui.nav.CreateRoute
import com.salmansaadiq.dheu.ui.nav.EditProfileRoute
import com.salmansaadiq.dheu.ui.nav.FollowListRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.SettingsRoute
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.stories.HighlightsRow
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class ProfileData(
    val profile: Profile?,
    val stats: ProfileStats,
    val posts: List<GridPost>,
    val highlights: List<HighlightSummary> = emptyList(),
)

@Composable
fun ProfileScreen(username: String, isTab: Boolean = false) {
    val nav = LocalNav.current
    val loader = rememberLoader("profile-$username") {
        val profile = Profiles.byUsername(username) ?: return@rememberLoader ProfileData(null, ProfileStats(), emptyList())
        coroutineScope {
            // Posts and highlights load alongside the stats: RLS returns them only to people allowed to see them.
            val stats = async { Profiles.stats(profile.id) }
            val posts = async { Profiles.gridPosts(profile.id) }
            val highlights = async { Stories.highlights(profile.id) }
            ProfileData(profile, stats.await(), posts.await(), highlights.await())
        }
    }
    // Back on the profile (after a new highlight, a post, a follow elsewhere): catch up quietly.
    LaunchedEffect(Unit) { if (loader.state is Load.Ready) loader.reload(silent = true) }
    val state = loader.state
    val profile = (state as? Load.Ready)?.value?.profile
    val app = LocalAppState.current
    val isMe = profile?.id == app.userId
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar(
            title = profile?.username ?: username,
            onBack = if (isTab) null else nav::back,
            actions = {
                if (isMe) IconAction(Lucide.Settings, "Settings", { nav.to(SettingsRoute) })
                else if (profile != null) IconAction(Lucide.Ellipsis, "More options", { menu = true })
            },
        )
        when (state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(state.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                val data = state.value
                if (data.profile == null) {
                    ErrorState("This member isn't here, or you can't see their profile.", onRetry = null)
                } else {
                    PullToRefreshBox(
                        isRefreshing = loader.refreshing,
                        onRefresh = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            loader.reload(silent = true)
                        },
                    ) {
                        ProfileBody(data, onChanged = { loader.reload(silent = true) })
                    }
                    if (menu) ProfileMenu(data.profile, data.stats, onDismiss = { menu = false }, onChanged = { loader.reload(silent = true) })
                }
            }
        }
    }
}

@Composable
private fun ProfileBody(data: ProfileData, onChanged: () -> Unit) {
    val profile = data.profile ?: return
    val stats = data.stats
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val c = Dheu.colors
    val isMe = profile.id == app.userId
    // Public accounts show their content to everyone; private ones only to approved followers.
    val canSee = isMe || me?.isAdmin == true || ((!profile.isPrivate || stats.isFollowing) && !stats.blockedByMe)
    val followState = when {
        stats.isFollowing -> FollowState.Following
        stats.requested -> FollowState.Requested
        else -> FollowState.None
    }
    // My own profile has a private Saved tab (0024). It loads when opened, and
    // catches up quietly when I come back to it (I may have unsaved something).
    var savedTab by rememberSaveable { mutableStateOf(false) }
    val showSaved = isMe && savedTab
    val savedLoader = if (showSaved) rememberLoader("saved-${profile.id}") { Posts.saved(profile.id) } else null
    LaunchedEffect(showSaved) { savedLoader?.let { if (it.state is Load.Ready) it.reload(silent = true) } }

    LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile.avatarPath, profile.fullName, 86.dp)
                    Spacer(Modifier.width(20.dp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceAround) {
                        Stat(stats.posts, "posts", null)
                        Stat(stats.followers, "followers", if (canSee) ({ nav.to(FollowListRoute(profile.id, profile.username, true)) }) else null)
                        Stat(stats.following, "following", if (canSee) ({ nav.to(FollowListRoute(profile.id, profile.username, false)) }) else null)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.fullName, fontWeight = FontWeight.SemiBold)
                    if (profile.isPrivate) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Lucide.Lock, "Private account", tint = c.inkMuted, modifier = Modifier.size(14.dp))
                    }
                    if (profile.isAdmin) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Lucide.ShieldCheck, "Admin", tint = c.brand, modifier = Modifier.size(16.dp))
                    }
                }
                if (profile.bio.isNotBlank()) Text(profile.bio)
                if (!isMe && stats.followsMe) Text("Follows you", color = c.inkFaint, style = MaterialTheme.typography.bodySmall)
                if (!isMe && stats.requestedMe) {
                    Text(
                        "Wants to follow you: respond in Activity",
                        color = c.brand,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { nav.to(ActivityRoute) },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        isMe -> {
                            SecondaryButton("Edit profile", { nav.to(EditProfileRoute) }, Modifier.weight(1f), compact = true)
                            SecondaryButton("Settings", { nav.to(SettingsRoute) }, Modifier.weight(1f), compact = true)
                        }
                        stats.blockedByMe -> Text(
                            "Blocked",
                            color = c.danger,
                            modifier = Modifier.clip(CircleShape).background(c.dangerSoft).padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        else -> {
                            Box(Modifier.weight(1f)) { FollowButton(profile.id, followState, onChange = { onChanged() }) }
                            SecondaryButton("Message", { nav.dm(profile.id) }, Modifier.weight(1f), icon = Lucide.MessageCircle, compact = true)
                        }
                    }
                }
            }
        }
        if (canSee) item(span = { GridItemSpan(maxLineSpan) }) { HighlightsRow(data.highlights, isOwner = isMe) }
        item(span = { GridItemSpan(maxLineSpan) }) { HorizontalDivider(color = c.line) }
        if (isMe) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally)) {
                    ProfileTab(Lucide.Grid, "Posts", selected = !savedTab, onClick = { savedTab = false })
                    ProfileTab(Lucide.Bookmark, "Saved", selected = savedTab, onClick = { savedTab = true })
                }
            }
        }

        if (!canSee) {
            item(span = { GridItemSpan(maxLineSpan) }) { PrivatePanel(stats, followState) }
        } else if (showSaved) {
            when (val s = savedLoader?.state ?: Load.Loading) {
                Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.padding(vertical = 48.dp)) }
                is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(s.message, onRetry = { savedLoader?.reload() }) }
                is Load.Ready -> if (s.value.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            "Nothing saved yet",
                            "Tap the bookmark under a post to keep it here until it expires. Only you can see what you've saved.",
                        )
                    }
                } else {
                    items(s.value, key = { "saved-${it.id}" }) { post -> GridTile(post) { nav.post(post.id) } }
                }
            }
        } else if (data.posts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No posts yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isMe) "Share your first photo: posts stay up for 3 months." else "Nothing here right now.",
                        color = c.inkMuted,
                        textAlign = TextAlign.Center,
                    )
                    if (isMe) {
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Create a post", { nav.to(CreateRoute()) })
                    }
                }
            }
        } else {
            items(data.posts, key = { it.id }) { post -> GridTile(post) { nav.post(post.id) } }
        }
    }
}

/** "Posts" / "Saved" under my own header; the open one is marked with a line, as on the website. */
@Composable
private fun ProfileTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Dheu.colors
    val haptics = LocalHapticFeedback.current
    val tint = if (selected) c.ink else c.inkFaint
    Column(
        Modifier.clickable(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(72.dp).height(1.5.dp).background(if (selected) c.ink else Color.Transparent))
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), color = tint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun Stat(count: Int, label: String, onClick: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(count.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, color = Dheu.colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GridTile(post: GridPost, onClick: () -> Unit) {
    val cover = post.images.firstOrNull()
    Box(Modifier.aspectRatio(1f).background(Dheu.colors.surface2).clickable(onClick = onClick)) {
        if (cover != null) {
            AsyncImage(
                model = StorageImage(Buckets.POSTS, cover.thumbPath ?: cover.path), // small thumbnail when there is one
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (post.images.size > 1) {
            Icon(Lucide.Layers, "Multiple photos", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
        }
    }
}

@Composable
private fun PrivatePanel(stats: ProfileStats, followState: FollowState) {
    val c = Dheu.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).border(2.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Lucide.Lock, null, tint = c.inkMuted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(if (stats.blockedByMe) "Blocked" else "This account is private", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                stats.blockedByMe -> "You've blocked this member."
                followState == FollowState.Requested -> "Your follow request is pending. Once they accept, you'll see their photos and stories here."
                else -> "Follow to see their photos and stories. They'll get a request to approve."
            },
            color = c.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfileMenu(profile: Profile, stats: ProfileStats, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val me = LocalAppState.current.userId
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var confirm by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                onChanged()
            } catch (e: Exception) {
                toast(e.friendly())
            }
        }
    }

    if (confirm == null) {
        DheuSheet(profile.username, onDismiss = onDismiss) {
            if (stats.followsMe && !stats.blockedByMe) SheetAction(Lucide.UserMinus, "Remove follower", { confirm = "remove" })
            if (stats.blockedByMe) SheetAction(Lucide.ShieldOff, "Unblock", { onDismiss(); run { Profiles.unblock(me, profile.id) } })
            else SheetAction(Lucide.Ban, "Block", { confirm = "block" }, danger = true)
            SheetAction(Lucide.Flag, "Report ${profile.username}", { confirm = "report" }, danger = true)
        }
    }
    when (confirm) {
        "report" -> ReportSheet(ReportTarget.Profile, profile.id, onDismiss = { confirm = null; onDismiss() })
        "block" -> ConfirmDialog(
            "Block ${profile.username}?",
            "You won't see each other's posts, stories or messages, and any follows between you are removed.",
            "Block",
            onConfirm = { run { Profiles.block(me, profile.id) } },
            onDismiss = { confirm = null; onDismiss() },
        )
        "remove" -> ConfirmDialog(
            "Remove ${profile.username}?",
            "They'll be removed from your followers. If your account is private, they'll have to request again.",
            "Remove",
            onConfirm = { run { Profiles.removeFollower(me, profile.id) } },
            onDismiss = { confirm = null; onDismiss() },
        )
    }
}
