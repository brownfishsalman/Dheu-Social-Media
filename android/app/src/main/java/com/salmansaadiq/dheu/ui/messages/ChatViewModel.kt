package com.salmansaadiq.dheu.ui.messages

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salmansaadiq.dheu.core.parseInstant
import com.salmansaadiq.dheu.data.Chat
import com.salmansaadiq.dheu.data.ChatEvent
import com.salmansaadiq.dheu.data.ConversationInfo
import com.salmansaadiq.dheu.data.Images
import com.salmansaadiq.dheu.data.Message
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Receipt
import com.salmansaadiq.dheu.data.VoiceClip
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.data.Typing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import java.time.Instant

private const val TYPING_SHOWN_MS = 5_000L // how long "typing…" stays after the last signal

class ChatViewModel(private val conversationId: String, private val me: String) : ViewModel() {
    var info by mutableStateOf<ConversationInfo?>(null)
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    /** Names and pictures by id; grows when someone new (e.g. just added) appears. */
    var people by mutableStateOf<Map<String, Person>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var missing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var hasOlder by mutableStateOf(false)
        private set
    var loadingOlder by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    /** When the others last read this chat, for "Seen" under my last message. */
    var receipts by mutableStateOf<List<Receipt>>(emptyList())
        private set
    /** Who is typing right now: user id -> shown until (ms). */
    var typing by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** The view-once photo being opened, if any. */
    var opening by mutableStateOf<String?>(null)
        private set

    private val typingLink = Typing.open(conversationId)

    init {
        load()
        viewModelScope.launch {
            Chat.events(conversationId).catch { }.collect { event ->
                when (event) {
                    is ChatEvent.Inserted -> onInserted(event.id)
                    is ChatEvent.Deleted -> removeLocally(event.id)
                    ChatEvent.Rejoined -> catchUp()
                    is ChatEvent.Reacted -> update(event.messageId) { it.withReaction(event.userId, event.emoji) }
                    is ChatEvent.Opened -> update(event.messageId) { it.withOpen(event.userId, event.openedAt) }
                }
            }
        }
        viewModelScope.launch {
            Chat.readNudges(conversationId).catch { }.collect { refreshReceipts() }
        }
        viewModelScope.launch {
            typingLink.typing.collect { id ->
                if (id != me) typing = typing + (id to System.currentTimeMillis() + TYPING_SHOWN_MS)
            }
        }
        // "typing…" fades out on its own.
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (typing.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val live = typing.filterValues { it > now }
                    if (live.size != typing.size) typing = live
                }
            }
        }
    }

    override fun onCleared() {
        typingLink.close()
    }

    private fun update(messageId: String, change: (Message) -> Message) {
        if (messages.none { it.id == messageId }) return
        messages = messages.map { if (it.id == messageId) change(it) else it }
    }

    fun announceTyping() = typingLink.announce(me)

    /** React, change the emoji, or (null) take it back. Shown at once; undone if it fails. */
    fun react(m: Message, emoji: String?) {
        val before = messages.firstOrNull { it.id == m.id }?.reactionOf(me)
        update(m.id) { it.withReaction(me, emoji) }
        viewModelScope.launch {
            try {
                Chat.react(m.id, emoji)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(m.id) { it.withReaction(me, before) }
                error = e.friendly("Couldn't react to that message.")
            }
        }
    }

    /** Double-tap: ❤️, or take a ❤️ back. */
    fun toggleHeart(m: Message) = react(m, if (m.reactionOf(me) == "❤️") null else "❤️")

    /** A view-once photo sent to me: record the open, then hand over the file to show. */
    fun openViewOnce(m: Message, show: (ByteArray) -> Unit) {
        if (opening != null) return
        opening = m.id
        viewModelScope.launch {
            try {
                val bytes = Chat.openViewOnce(m.id)
                update(m.id) { it.withOpen(me, Instant.now().toString()) }
                show(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = e.message.orEmpty()
                if ("ALREADY_OPENED" in text) update(m.id) { it.withOpen(me, Instant.now().toString()) }
                error = when {
                    "ALREADY_OPENED" in text -> "You've already opened this photo."
                    "EXPIRED" in text -> "This photo has expired."
                    else -> e.friendly("Couldn't load the photo. Try again within two minutes.")
                }
            } finally {
                opening = null
            }
        }
    }

    /** Quietly keeps the last receipts if this fails (say, before 0022 is applied). */
    private suspend fun refreshReceipts() {
        runCatching { Chat.receipts(conversationId) }.onSuccess { receipts = it }
    }

    private fun load() {
        viewModelScope.launch {
            loading = true
            try {
                // Messages load alongside the conversation: RLS only returns them to members.
                val msgs = async { Chat.messages(conversationId) }
                val conversation = Chat.conversation(conversationId, me)
                if (conversation == null) {
                    missing = true
                    return@launch
                }
                info = conversation
                messages = msgs.await()
                hasOlder = messages.size >= Chat.PAGE
                people = conversation.members.associateBy { it.id }
                learnNames(messages)
                markRead()
                refreshReceipts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly("Couldn't open this chat.")
            } finally {
                loading = false
            }
        }
    }

    /** People who left a group but are still named here (as senders or in system lines). */
    private suspend fun learnNames(list: List<Message>) {
        val ids = list.flatMap { listOf(it.senderId) + (it.event?.let { e -> listOf(e.actor) + e.targets } ?: emptyList()) }
            .filter { it != me && it !in people }.toSet()
        if (ids.isEmpty()) return
        runCatching { Chat.people(ids) }.onSuccess { found -> people = people + found.associateBy { it.id } }
    }

    private suspend fun onInserted(id: String) {
        if (messages.any { it.id == id }) return
        val full = runCatching { Chat.message(id) }.getOrNull() ?: return
        learnNames(listOf(full))
        if (messages.none { it.id == id }) messages = messages + full
        // Their message arrived, so they've stopped typing.
        if (full.senderId in typing) typing = typing - full.senderId
        if (full.senderId != me) markRead()
        // "Rafi renamed the group", "Tania added Salman": refresh the name and the members.
        if (full.isSystem) refreshInfo()
    }

    private suspend fun refreshInfo() {
        runCatching { Chat.conversation(conversationId, me) }.getOrNull()?.let { fresh ->
            info = fresh
            people = people + fresh.members.associateBy { it.id }
        }
    }

    /**
     * Realtime was away (dropped network, app in the background): reload the
     * newest page, which brings in new messages and drops unsent ones, and keep
     * any older history already loaded above it.
     */
    private suspend fun catchUp() {
        if (loading) return
        val latest = runCatching { Chat.messages(conversationId) }.getOrNull() ?: return
        val complete = latest.size < Chat.PAGE
        val from = latest.firstOrNull()?.let { parseInstant(it.createdAt) }
        val older = if (complete || from == null) emptyList() else messages.filter { parseInstant(it.createdAt) < from }
        val newFromOthers = latest.any { m -> m.senderId != me && messages.none { it.id == m.id } }
        learnNames(latest)
        messages = older + latest
        if (complete) hasOlder = false
        if (newFromOthers) markRead()
        refreshReceipts()
    }

    private fun removeLocally(id: String) {
        if (messages.none { it.id == id || it.replyPreview?.id == id }) return
        messages = messages.filter { it.id != id }.map { m -> if (m.replyPreview?.id == id) m.copy(replyRaw = JsonNull) else m }
    }

    fun markRead() {
        viewModelScope.launch { runCatching { Chat.markRead(conversationId) } }
    }

    fun loadOlder() {
        val first = messages.firstOrNull() ?: return
        if (loadingOlder || !hasOlder) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                val older = Chat.messages(conversationId, before = first.createdAt)
                learnNames(older)
                messages = older + messages
                hasOlder = older.size >= Chat.PAGE
            } catch (_: Exception) {
            } finally {
                loadingOlder = false
            }
        }
    }

    /** Returns true when sent (so the composer can clear). */
    fun send(
        context: Context,
        body: String,
        photo: Uri?,
        replyTo: Message?,
        voice: VoiceClip?,
        viewOnce: Boolean = false,
        onDone: (Boolean) -> Unit,
    ) {
        if (sending) return
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val encoded = photo?.let { Images.encode(context, it, Images.POST) }
                val sent = Chat.send(conversationId, me, body.trim(), encoded, replyTo?.id, voice, viewOnce = viewOnce && encoded != null)
                if (messages.none { it.id == sent.id }) messages = messages + sent
                markRead()
                onDone(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = e.message.orEmpty()
                error = if ("blocked" in text || "violates" in text) "You can't message this person." else e.friendly("Couldn't send. Check your connection.")
                onDone(false)
            } finally {
                sending = false
            }
        }
    }

    fun unsend(m: Message) {
        viewModelScope.launch {
            try {
                if (Chat.unsend(m)) removeLocally(m.id) else error = "Couldn't unsend that message."
            } catch (e: Exception) {
                error = e.friendly("Couldn't unsend that message.")
            }
        }
    }
}
