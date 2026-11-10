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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull

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

    init {
        load()
        viewModelScope.launch {
            Chat.events(conversationId).catch { }.collect { event ->
                when (event) {
                    is ChatEvent.Inserted -> onInserted(event.id)
                    is ChatEvent.Deleted -> removeLocally(event.id)
                    ChatEvent.Rejoined -> catchUp()
                }
            }
        }
        viewModelScope.launch {
            Chat.readNudges(conversationId).catch { }.collect { refreshReceipts() }
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
    fun send(context: Context, body: String, photo: Uri?, replyTo: Message?, voice: VoiceClip?, onDone: (Boolean) -> Unit) {
        if (sending) return
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val encoded = photo?.let { Images.encode(context, it, Images.POST) }
                val sent = Chat.send(conversationId, me, body.trim(), encoded, replyTo?.id, voice)
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
