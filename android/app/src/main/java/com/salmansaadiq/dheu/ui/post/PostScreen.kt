package com.salmansaadiq.dheu.ui.post

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salmansaadiq.dheu.core.timeAgo
import com.salmansaadiq.dheu.data.Comment
import com.salmansaadiq.dheu.data.Post
import com.salmansaadiq.dheu.data.Posts
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.UserRef
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.MentionSuggestions
import com.salmansaadiq.dheu.ui.components.MentionText
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PeopleList
import com.salmansaadiq.dheu.ui.components.fieldColors
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class PostWithComments(val post: Post?, val comments: List<Comment>)

@Composable
fun PostScreen(id: String) {
    val nav = LocalNav.current
    val loader = rememberLoader("post-$id") {
        coroutineScope {
            val comments = async { Posts.comments(id) } // RLS only returns them if the post is visible
            PostWithComments(Posts.byId(id), comments.await())
        }
    }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        val state = loader.state
        val author = (state as? Load.Ready)?.value?.post?.author
        DheuTopBar("Post", subtitle = author?.let { "@${it.username}" }, onBack = nav::back)
        when (state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(state.message, onRetry = { loader.reload() })
            is Load.Ready -> {
                val post = state.value.post
                if (post == null) ErrorState("This post isn't available. It may have expired or be private.", onRetry = null)
                else PostWithCommentsView(post, state.value.comments, onDeleted = nav::back)
            }
        }
    }
}

/** The top comment a reply hangs off, and whose name the reply starts with. */
private data class ReplyTarget(val commentId: String, val username: String)

/**
 * Comments with one level of replies and likes, as on the website (0024).
 * A reply starts with "@name", so its author hears about it as a mention.
 */
@Composable
private fun PostWithCommentsView(post: Post, initial: List<Comment>, onDeleted: () -> Unit) {
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val myId = app.userId
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var comments by remember(post.id) { mutableStateOf(initial) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember { mutableStateOf<ReplyTarget?>(null) }
    var sending by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Comment?>(null) }

    val tops = comments.filter { it.parentId == null }
    val replies = comments.filter { it.parentId != null }.groupBy { it.parentId }

    fun startReply(comment: Comment) {
        val author = comment.author ?: return
        // Replies hang off the top comment; replying to a reply names its author.
        replyTo = ReplyTarget(comment.parentId ?: comment.id, author.username)
        val text = "@${author.username} "
        draft = TextFieldValue(text, TextRange(text.length))
        focus.requestFocus()
        keyboard?.show()
    }

    fun toggleLike(comment: Comment) {
        val liked = comment.likedBy(myId)
        fun flip(on: Boolean) {
            comments = comments.map { x ->
                if (x.id != comment.id) x
                else x.copy(likes = x.likes.filter { it.userId != myId } + if (on) listOf(UserRef(myId)) else emptyList())
            }
        }
        flip(!liked)
        scope.launch {
            try {
                if (liked) Posts.unlikeComment(comment.id, myId) else Posts.likeComment(comment.id, myId)
            } catch (e: Exception) {
                flip(liked)
                toast(e.friendly())
            }
        }
    }

    val commentRow: @Composable (Comment, Boolean) -> Unit = { comment, reply ->
        CommentRow(
            comment = comment,
            reply = reply,
            liked = comment.likedBy(myId),
            canDelete = comment.authorId == myId || post.authorId == myId || me?.isAdmin == true,
            onReply = { startReply(comment) },
            onLike = { toggleLike(comment) },
            onDelete = { deleting = comment },
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f)) {
            item(key = "post") { PostCard(post, onDeleted = onDeleted, detail = true) }
            if (tops.isEmpty()) {
                item(key = "none") { Text("No comments yet. Say something nice.", color = c.inkMuted, modifier = Modifier.padding(24.dp)) }
            }
            tops.forEach { top ->
                item(key = top.id) { commentRow(top, false) }
                items(replies[top.id].orEmpty(), key = { it.id }) { commentRow(it, true) }
            }
        }
        MentionSuggestions(draft, onPick = { draft = it })
        HorizontalDivider(color = c.line)
        replyTo?.let { target ->
            Row(
                Modifier.fillMaxWidth().background(c.surface2).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Replying to @${target.username}", color = c.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { replyTo = null; draft = TextFieldValue("") }, modifier = Modifier.size(34.dp)) {
                    Icon(Lucide.X, "Cancel reply", tint = c.inkMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            me?.let { Avatar(it.avatarPath, it.fullName, 30.dp) }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.text.length <= 1000) draft = it },
                placeholder = { Text(replyTo?.let { "Reply to @${it.username}…" } ?: "Add a comment…") },
                shape = RoundedCornerShape(24.dp),
                colors = fieldColors(),
                maxLines = 4,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(
                enabled = draft.text.isNotBlank() && !sending,
                onClick = {
                    val author = me?.person ?: return@IconButton
                    val parent = replyTo?.commentId
                    sending = true
                    scope.launch {
                        try {
                            comments = comments + Posts.addComment(post.id, author, draft.text.trim(), parent)
                            draft = TextFieldValue("")
                            replyTo = null
                        } catch (e: Exception) {
                            toast(e.friendly("Couldn't post your comment."))
                        } finally {
                            sending = false
                        }
                    }
                },
            ) { Icon(Lucide.Send, "Post comment", tint = if (draft.text.isNotBlank()) c.brand else c.inkFaint) }
        }
    }

    deleting?.let { target ->
        val replyCount = replies[target.id].orEmpty().size
        ConfirmDialog(
            title = when (replyCount) {
                0 -> "Delete this comment?"
                1 -> "Delete this comment and its reply?"
                else -> "Delete this comment and its $replyCount replies?"
            },
            text = "It will be removed for everyone.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    try {
                        Posts.deleteComment(target.id)
                        // Its replies go with it (on delete cascade).
                        comments = comments.filter { it.id != target.id && it.parentId != target.id }
                        if (replyTo?.commentId == target.id) {
                            replyTo = null
                            draft = TextFieldValue("")
                        }
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't delete the comment."))
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

/** One comment; a reply is indented under its top comment. */
@Composable
private fun CommentRow(
    comment: Comment,
    reply: Boolean,
    liked: Boolean,
    canDelete: Boolean,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Dheu.colors
    val nav = LocalNav.current
    val author = comment.author ?: return
    val meta = MaterialTheme.typography.bodySmall
    Row(
        Modifier.fillMaxWidth().padding(start = if (reply) 58.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(author.avatarPath, author.fullName, if (reply) 26.dp else 32.dp, Modifier.clickable { nav.profile(author.username) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MentionText(comment.body, prefix = author.username)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(timeAgo(comment.createdAt), color = c.inkFaint, style = meta)
                if (comment.likes.isNotEmpty()) {
                    val n = comment.likes.size
                    Text(if (n == 1) "1 like" else "$n likes", color = c.inkFaint, style = meta, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Reply",
                    color = c.inkFaint,
                    style = meta,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onReply).padding(vertical = 6.dp, horizontal = 2.dp),
                )
                if (canDelete) {
                    Icon(
                        Lucide.Trash,
                        "Delete comment",
                        tint = c.inkFaint,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(6.dp).size(14.dp),
                    )
                }
            }
        }
        IconButton(onClick = onLike, modifier = Modifier.size(36.dp)) {
            Icon(
                if (liked) Lucide.HeartFilled else Lucide.Heart,
                if (liked) "Unlike comment" else "Like comment",
                tint = if (liked) c.like else c.inkFaint,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
fun LikesScreen(postId: String) {
    val nav = LocalNav.current
    val me = LocalAppState.current.userId
    val loader = rememberLoader("likes-$postId") {
        coroutineScope {
            val rel = async { Profiles.relationships(me) }
            Posts.likers(postId) to rel.await()
        }
    }
    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("Likes", onBack = nav::back)
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> PeopleList(s.value.first, s.value.second, "No likes yet.")
        }
    }
}
