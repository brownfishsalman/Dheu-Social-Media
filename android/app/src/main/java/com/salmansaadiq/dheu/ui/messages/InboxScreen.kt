package com.salmansaadiq.dheu.ui.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salmansaadiq.dheu.R
import com.salmansaadiq.dheu.core.expiresIn
import com.salmansaadiq.dheu.core.timeAgo
import java.time.Instant
import com.salmansaadiq.dheu.data.Announcement
import com.salmansaadiq.dheu.data.Chat
import com.salmansaadiq.dheu.data.ConversationSummary
import com.salmansaadiq.dheu.data.NOTE_MAX_LENGTH
import com.salmansaadiq.dheu.data.Note
import com.salmansaadiq.dheu.data.Notes
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.Load
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.Avatar
import com.salmansaadiq.dheu.ui.components.ConfirmDialog
import com.salmansaadiq.dheu.ui.components.CountBadge
import com.salmansaadiq.dheu.ui.components.DheuSheet
import com.salmansaadiq.dheu.ui.components.DheuTextField
import com.salmansaadiq.dheu.ui.components.DheuTopBar
import com.salmansaadiq.dheu.ui.components.EmptyState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.IconAction
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PrimaryButton
import com.salmansaadiq.dheu.ui.components.SecondaryButton
import com.salmansaadiq.dheu.ui.components.SheetAction
import com.salmansaadiq.dheu.ui.components.rememberToast
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.nav.AnnouncementsRoute
import com.salmansaadiq.dheu.ui.nav.LocalNav
import com.salmansaadiq.dheu.ui.nav.NewMessageRoute
import com.salmansaadiq.dheu.ui.rememberLoader
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class InboxData(
    val conversations: List<ConversationSummary>,
    val announcement: Announcement?,
    val announcementsUnread: Int,
    val notes: List<Note>,
)

@Composable
fun InboxScreen() {
    val app = LocalAppState.current
    val nav = LocalNav.current
    val loader = rememberLoader("inbox") {
        coroutineScope {
            val ann = async { Chat.announcementSummary() }
            val notes = async { Notes.visible() }
            val conversations = Chat.inbox(app.userId)
            val (latest, unread) = ann.await()
            InboxData(conversations, latest, unread, notes.await())
        }
    }
    // Coming back from a chat (read, unsent, deleted): refresh the list and badges.
    LifecycleResumeEffect(Unit) {
        if (loader.state is Load.Ready) loader.reload(silent = true)
        app.refreshBadges()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(Dheu.colors.surface)) {
        DheuTopBar("Messages", actions = { IconAction(Lucide.SquarePen, "New message", { nav.to(NewMessageRoute) }) })
        when (val s = loader.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorState(s.message, onRetry = { loader.reload() })
            is Load.Ready -> PullToRefreshBox(isRefreshing = loader.refreshing, onRefresh = { loader.reload(silent = true) }) {
                InboxList(s.value, onChanged = { loader.reload(silent = true); app.refreshBadges() })
            }
        }
    }
}

@Composable
private fun InboxList(data: InboxData, onChanged: () -> Unit) {
    val nav = LocalNav.current
    val app = LocalAppState.current
    val c = Dheu.colors
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    var menuFor by remember { mutableStateOf<ConversationSummary?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "notes") { NotesTray(data.notes, onChanged) }
        item(key = "announcements") {
            Row(
                Modifier.fillMaxWidth().background(c.brandSoft.copy(alpha = 0.4f)).clickable { nav.to(AnnouncementsRoute) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.dheu_mark), null, Modifier.height(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Megaphone, null, tint = c.brand, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Dheu announcements", fontWeight = if (data.announcementsUnread > 0) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        data.announcement?.let { Text(timeAgo(it.createdAt), color = c.inkFaint, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(
                        data.announcement?.body?.lineSequence()?.firstOrNull() ?: "News and new features will appear here.",
                        color = if (data.announcementsUnread > 0) c.ink else c.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (data.announcementsUnread > 0) {
                    Spacer(Modifier.width(8.dp))
                    CountBadge(data.announcementsUnread)
                }
            }
            HorizontalDivider(color = c.line)
        }
        if (data.conversations.isEmpty()) {
            item(key = "empty") {
                EmptyState("No messages yet", "Start a conversation with someone.") {
                    PrimaryButton("New message", { nav.to(NewMessageRoute) }, icon = Lucide.SquarePen)
                }
            }
        }
        items(data.conversations, key = { it.id }) { conv ->
            ConversationRow(conv, app.userId, onOpen = { nav.chat(conv.id) }, onMenu = { menuFor = conv })
            HorizontalDivider(color = c.line.copy(alpha = 0.6f))
        }
    }

    menuFor?.let { conv ->
        DheuSheet(conv.title, onDismiss = { menuFor = null }) {
            SheetAction(Lucide.Trash, "Delete chat", { menuFor = null; deleting = conv }, danger = true)
        }
    }
    deleting?.let { conv ->
        ConfirmDialog(
            title = if (conv.isGroup) "Delete \"${conv.title}\"?" else "Delete your chat with ${conv.title}?",
            text = if (conv.isGroup) "Its messages are cleared for you and it leaves your inbox until someone writes again. You stay in the group."
            else "Its messages are cleared for you only; ${conv.title} keeps their copy.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    try {
                        Chat.deleteForMe(conv.id)
                        onChanged()
                    } catch (e: Exception) {
                        toast(e.friendly("Couldn't delete the chat."))
                    }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, me: String, onOpen: () -> Unit, onMenu: () -> Unit) {
    val colors = Dheu.colors
    val last = c.last
    // In a group, say who sent the last message; in a DM only mark my own.
    val who = when {
        last == null -> ""
        last.senderId == me -> "You: "
        c.isGroup -> "${(last.sender?.fullName ?: "Someone").split(" ").first()}: "
        else -> ""
    }
    // My message was the last one and someone has seen it: say so, as on Instagram.
    val seen = if (last != null && last.kind != "system" && last.senderId == me) {
        receiptLabel(
            last.createdAt, c.receipts, c.isGroup, if (c.isGroup) c.memberCount - 1 else 1,
            { id -> c.members.firstOrNull { it.id == id }?.fullName?.substringBefore(' ') ?: "Someone" },
            Instant.now(),
        )
    } else null
    val preview = when {
        last == null -> if (c.isGroup) "${c.memberCount} members · No messages yet" else "No messages yet"
        last.kind == "system" -> last.body
        seen != null && seen != "Sent" -> if (c.isGroup) seen else "Seen"
        else -> buildString {
            append(who)
            if (last.storyId != null) append("Replied to a story: ")
            if (last.noteText != null) append("Replied to a note: ")
            if (last.imagePath != null) append("📷 Photo ")
            if (last.viewOnce) append("📷 View-once photo ")
            if (last.audioPath != null) append("🎤 Voice message ")
            append(last.body)
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (c.isGroup) GroupAvatar(c.members, 52.dp) else Avatar(c.peer?.avatarPath, c.peer?.fullName ?: c.title, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.title,
                    fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                last?.let { Text(timeAgo(it.createdAt), color = colors.inkFaint, style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                preview,
                color = if (c.unread > 0) colors.ink else colors.inkMuted,
                fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (c.unread > 0) {
            Spacer(Modifier.width(8.dp))
            CountBadge(c.unread)
        }
        IconButton(onClick = onMenu) { Icon(Lucide.Ellipsis, "Options for ${c.title}", tint = colors.inkMuted) }
    }
}

/** Two overlapping avatars for a group (or one if only one other member is visible). */
@Composable
fun GroupAvatar(members: List<Person>, size: Dp) {
    val shown = members.take(2)
    if (shown.size < 2) {
        val p = shown.firstOrNull()
        Avatar(p?.avatarPath, p?.fullName ?: "Group", size)
        return
    }
    Box(Modifier.size(size)) {
        val small = size * 0.68f
        Avatar(shown[0].avatarPath, shown[0].fullName, small, Modifier.align(Alignment.TopStart))
        Box(Modifier.align(Alignment.BottomEnd).border(2.dp, Dheu.colors.surface, CircleShape)) {
            Avatar(shown[1].avatarPath, shown[1].fullName, small)
        }
    }
}

// ---- notes -------------------------------------------------------------------

@Composable
private fun NotesTray(notes: List<Note>, onChanged: () -> Unit) {
    val app = LocalAppState.current
    val me by app.me.collectAsStateWithLifecycle()
    val profile = me ?: return
    val mine = notes.firstOrNull { it.author?.id == profile.id }
    val others = notes.filter { it.author?.id != profile.id }
    var editing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Note?>(null) }

    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 36.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "mine") { NoteBubble(profile.person, "Your note", mine?.body) { editing = true } }
        items(others, key = { it.author!!.id }) { note -> NoteBubble(note.author!!, note.author.username, note.body) { open = note } }
    }
    HorizontalDivider(color = Dheu.colors.line)

    if (editing) NoteEditor(mine, profile.id, onDismiss = { editing = false }, onChanged = onChanged)
    open?.let { NoteReply(it, profile.id, onDismiss = { open = null }, onChanged = onChanged) }
}

@Composable
private fun NoteBubble(person: Person, label: String, text: String?, onClick: () -> Unit) {
    val c = Dheu.colors
    Column(Modifier.width(78.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopCenter) {
            Avatar(person.avatarPath, person.fullName, 64.dp)
            Text(
                text ?: "Share a note",
                color = if (text != null) c.ink else c.inkFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(y = (-30).dp)
                    .widthIn(max = 96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = c.inkMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NoteEditor(current: Note?, me: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current?.body ?: "") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(kind: String, action: suspend () -> Unit) {
        busy = kind
        error = null
        scope.launch {
            try {
                action()
                onChanged()
                onDismiss()
            } catch (e: Exception) {
                error = e.friendly(if (kind == "share") "Couldn't share your note." else "Couldn't delete your note.")
            } finally {
                busy = null
            }
        }
    }

    DheuSheet(if (current != null) "Your note" else "New note", onDismiss) {
        DheuTextField(text, { text = it }, "Share a thought…", singleLine = false, minLines = 2, maxLength = NOTE_MAX_LENGTH)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Your mutual follows can see it for 24 hours.", color = c.inkFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${text.length}/$NOTE_MAX_LENGTH", color = c.inkFaint, style = MaterialTheme.typography.bodySmall)
        }
        current?.let { Text("Shared ${timeAgo(it.createdAt)} ago · ${expiresIn(it.expiresAt)}", color = c.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        error?.let { Text(it, color = c.danger, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current != null) {
                SecondaryButton("Delete", { run("delete") { Notes.delete(me) } }, loading = busy == "delete", enabled = busy == null, icon = Lucide.Trash)
            }
            PrimaryButton(
                if (current != null) "Update" else "Share",
                { run("share") { Notes.set(text.trim()) } },
                Modifier.weight(1f),
                enabled = busy == null && text.isNotBlank() && text.trim() != current?.body,
                loading = busy == "share",
            )
        }
    }
}

@Composable
private fun NoteReply(note: Note, me: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val author = note.author ?: return
    val nav = LocalNav.current
    val c = Dheu.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DheuSheet("@${author.username}", onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(author.avatarPath, author.fullName, 72.dp)
            Spacer(Modifier.height(10.dp))
            Text(note.body, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(horizontal = 16.dp, vertical = 10.dp))
            Spacer(Modifier.height(6.dp))
            Text("${author.fullName} · ${timeAgo(note.createdAt)}", color = c.inkFaint, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        val sent = sentTo
        if (sent != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Reply sent.", color = c.inkMuted)
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Open chat", { onDismiss(); nav.chat(sent) }, compact = true)
            }
        } else {
            DheuTextField(text, { text = it }, "Reply to ${author.fullName.split(" ").first()}…", maxLength = 2000)
            error?.let { Text(it, color = c.danger, modifier = Modifier.padding(top = 6.dp)) }
            PrimaryButton(
                "Send",
                {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            sentTo = Notes.reply(note, me, text.trim())
                            onChanged()
                        } catch (e: Exception) {
                            error = e.friendly("Couldn't send. Check your connection.")
                        } finally {
                            busy = false
                        }
                    }
                },
                Modifier.fillMaxWidth().padding(top = 10.dp),
                enabled = text.isNotBlank(),
                loading = busy,
                icon = Lucide.Send,
            )
        }
    }
}
