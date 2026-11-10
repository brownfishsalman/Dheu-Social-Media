package com.salmansaadiq.dheu.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Only the columns members may insert (0012 grants exactly these). */
@Serializable
data class MessageInsert(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
    @SerialName("story_id") val storyId: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("audio_path") val audioPath: String? = null,
    @SerialName("audio_duration_ms") val audioDurationMs: Long? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("note_text") val noteText: String? = null,
    /** A view-once photo's file in the private "once" bucket (0023). */
    @SerialName("once_path") val oncePath: String? = null,
)

/** A recorded voice message waiting to be sent: an AAC .m4a in the app's cache. */
class VoiceClip(val file: File, val durationMs: Long)

/** Same limits as the website (src/lib/constants.ts) and the database functions (0011). */
const val VOICE_MAX_SECONDS = 120
const val GROUP_MAX_MEMBERS = 50
const val GROUP_MIN_OTHERS = 2
const val GROUP_TITLE_MAX = 60

sealed interface ChatEvent {
    data class Inserted(val id: String) : ChatEvent
    data class Deleted(val id: String) : ChatEvent
    /** The channel joined again after a gap; reload to catch up. */
    data object Rejoined : ChatEvent
    /** A reaction was added or changed, or (emoji null) taken back. */
    data class Reacted(val messageId: String, val userId: String, val emoji: String?) : ChatEvent
    /** Someone opened a view-once photo. */
    data class Opened(val messageId: String, val userId: String, val openedAt: String) : ChatEvent
}

/** read_receipts_inbox(): [Receipt] for every chat at once (0023). */
@Serializable
data class InboxReceipt(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("last_read_at") val lastReadAt: String,
    @SerialName("joined_at") val joinedAt: String,
) {
    fun toReceipt() = Receipt(userId, lastReadAt, joinedAt)
}

/**
 * read_receipts(): when another member last read the chat, for members who
 * share their receipts (0022). joinedAt lets a group ignore people added
 * after a message was sent.
 */
@Serializable
data class Receipt(
    @SerialName("user_id") val userId: String,
    @SerialName("last_read_at") val lastReadAt: String,
    @SerialName("joined_at") val joinedAt: String,
)

object Chat {
    const val PAGE = 50
    private const val MEMBER_EMBED =
        "members:conversation_members(joined_at, user:profiles!conversation_members_user_id_fkey($PERSON))"
    private val client get() = Backend.client

    /** Members I can see, oldest first; someone who blocked me comes back null and is left out. */
    private fun visible(rows: List<MemberRow>) = rows.sortedBy { it.joinedAt }.mapNotNull { it.user }

    suspend fun inbox(me: String): List<ConversationSummary> {
        val rows = client.from("conversations").select(
            Columns.raw(
                "id, is_group, title, last_message_at, $MEMBER_EMBED, reads:conversation_reads(cleared_at), " +
                    "messages(body, sender_id, created_at, story_id, image_path, audio_path, note_text, kind, view_once, " +
                    "sender:profiles!messages_sender_id_fkey(full_name))",
            ),
        ) {
            order("last_message_at", Order.DESCENDING, nullsFirst = false)
            order("created_at", Order.DESCENDING, referencedTable = "messages")
            limit(1, referencedTable = "messages")
        }.decodeList<ConversationRow>()
        val unread = Backend.db.rpc("unread_counts").decodeList<UnreadRow>().associate { it.conversationId to it.unread.toInt() }
        // "Seen" under my last message; the inbox still works if this fails.
        val receipts = runCatching { Backend.db.rpc("read_receipts_inbox").decodeList<InboxReceipt>() }.getOrDefault(emptyList())
            .groupBy({ it.conversationId }, { it.toReceipt() })

        return rows.mapNotNull { c ->
            val members = visible(c.members)
            val peer = if (c.isGroup) null else members.firstOrNull { it.id != me }
            if (!c.isGroup && peer == null) return@mapNotNull null // DM partner hidden (blocked me) or gone
            val last = c.messages.firstOrNull()
            // I deleted this chat and nothing new has come since (RLS hides older messages).
            if (c.reads.firstOrNull()?.clearedAt != null && last == null) return@mapNotNull null
            ConversationSummary(
                id = c.id,
                isGroup = c.isGroup,
                title = if (c.isGroup) c.title ?: "Group" else peer!!.fullName,
                peer = peer,
                members = members.filter { it.id != me },
                memberCount = c.members.size,
                last = last,
                unread = unread[c.id] ?: 0,
                receipts = receipts[c.id].orEmpty(),
            )
        }
    }

    suspend fun conversation(id: String, me: String): ConversationInfo? {
        val c = client.from("conversations").select(Columns.raw("id, is_group, title, created_by, $MEMBER_EMBED")) {
            filter { eq("id", id) }
        }.decodeSingleOrNull<ConversationRow>() ?: return null
        val members = visible(c.members)
        if (c.isGroup) return ConversationInfo(c.id, true, c.title ?: "Group", null, members, null)
        val peer = members.firstOrNull { it.id != me } ?: return null
        val block = Backend.db.rpc("block_status", buildJsonObject { put("p_other", peer.id) }).decodeAs<String?>()
        return ConversationInfo(c.id, false, peer.fullName, peer, members, block)
    }

    /** Newest page (or the page before [before]), returned oldest first. */
    suspend fun messages(conversationId: String, before: String? = null): List<Message> =
        client.from("messages").select(Columns.raw(MESSAGE_SELECT)) {
            filter {
                eq("conversation_id", conversationId)
                if (before != null) lt("created_at", before)
            }
            order("created_at", Order.DESCENDING)
            limit(PAGE.toLong())
        }.decodeList<Message>().reversed()

    suspend fun message(id: String): Message? =
        client.from("messages").select(Columns.raw(MESSAGE_SELECT)) { filter { eq("id", id) } }.decodeSingleOrNull()

    suspend fun people(ids: Collection<String>): List<Person> {
        if (ids.isEmpty()) return emptyList()
        return client.from("profiles").select(Columns.raw(PERSON)) { filter { isIn("id", ids.toList()) } }.decodeList()
    }

    suspend fun insertMessage(message: MessageInsert): Message =
        client.from("messages").insert(message) { select(Columns.raw(MESSAGE_SELECT)) }.decodeSingle()

    /**
     * Uploads the photo or voice message first (if any); if the message can't be
     * saved, the file is removed again. A view-once photo goes to the private
     * "once" bucket instead of "chat".
     */
    suspend fun send(
        conversationId: String,
        me: String,
        body: String,
        photo: Images.Encoded?,
        replyToId: String?,
        voice: VoiceClip? = null,
        viewOnce: Boolean = false,
    ): Message {
        val photoPath = photo?.let {
            val path = Images.newPath(me)
            if (viewOnce) uploadFile(Buckets.ONCE, path, it.bytes, ContentType.Image.JPEG, 0)
            else uploadFile(Buckets.CHAT, path, it.bytes, ContentType.Image.JPEG, CACHE_WEEK)
            path
        }
        val imagePath = photoPath.takeUnless { viewOnce }
        val oncePath = photoPath.takeIf { viewOnce }
        val audioPath = voice?.let {
            val path = "$me/${UUID.randomUUID()}.m4a"
            val bytes = withContext(Dispatchers.IO) { it.file.readBytes() }
            uploadFile(Buckets.CHAT, path, bytes, ContentType("audio", "mp4"), CACHE_WEEK)
            path
        }
        return try {
            insertMessage(
                MessageInsert(
                    conversationId, me, body,
                    imagePath = imagePath,
                    audioPath = audioPath,
                    audioDurationMs = voice?.durationMs,
                    replyToId = replyToId,
                    oncePath = oncePath,
                ),
            )
        } catch (e: Exception) {
            removeFiles(Buckets.CHAT, listOfNotNull(imagePath, audioPath))
            removeFiles(Buckets.ONCE, listOfNotNull(oncePath))
            throw e
        }
    }

    /** Unsend my message for everyone, then its photo / voice file. False if nothing was deleted. */
    suspend fun unsend(m: Message): Boolean {
        val deleted = client.from("messages").delete {
            select(Columns.raw("id"))
            filter { eq("id", m.id) }
        }.decodeList<IdRow>()
        if (deleted.isEmpty()) return false
        removeFiles(Buckets.CHAT, listOfNotNull(m.imagePath, m.audioPath))
        removeFiles(Buckets.ONCE, listOfNotNull(m.oncePath))
        return true
    }

    /** React, change the emoji, or (null) take the reaction back (0023). */
    suspend fun react(messageId: String, emoji: String?) {
        Backend.db.rpc(
            "react_to_message",
            buildJsonObject {
                put("p_message_id", messageId)
                put("p_emoji", emoji)
            },
        )
    }

    /**
     * Open a view-once photo sent to me: the database records the open (once
     * only; a retry within two minutes is allowed), and the file can then be
     * downloaded for two minutes. Throws with ALREADY_OPENED / EXPIRED.
     */
    suspend fun openViewOnce(messageId: String): ByteArray {
        val path = Backend.db.rpc("open_view_once", buildJsonObject { put("p_message_id", messageId) }).decodeAs<String>()
        return Backend.storage.from(Buckets.ONCE).downloadAuthenticated(path)
    }

    suspend fun markRead(conversationId: String) {
        Backend.db.rpc("mark_conversation_read", buildJsonObject { put("p_conversation_id", conversationId) })
    }

    /** Who else has read this chat, and when (only members who share receipts). */
    suspend fun receipts(conversationId: String): List<Receipt> =
        Backend.db.rpc("read_receipts", buildJsonObject { put("p_conversation_id", conversationId) }).decodeList()

    private val nudgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nudges = mutableMapOf<String, SharedFlow<Unit>>()

    /**
     * Fires when someone reads this chat (0022 broadcasts an empty "read" on
     * "reads:<id>"), and when the channel rejoins after a gap. The topic is
     * fixed, so one channel per chat is shared by every screen showing it and
     * kept for a moment after the last one leaves, rather than opened twice.
     */
    fun readNudges(conversationId: String): Flow<Unit> = synchronized(nudges) {
        nudges.getOrPut(conversationId) {
            channelFlow {
                val channel = client.channel("reads:$conversationId")
                launch { channel.broadcastFlow("read").collect { send(Unit) } }
                launch { rejoins(channel).collect { send(Unit) } }
                channel.subscribe()
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { client.realtime.removeChannel(channel) }
                }
            }.shareIn(nudgeScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000))
        }
    }

    /** "Delete chat": for me only; the others keep theirs (delete_chat_for_me). */
    suspend fun deleteForMe(conversationId: String) {
        Backend.db.rpc("delete_chat_for_me", buildJsonObject { put("p_conversation_id", conversationId) })
    }

    /** Find or create my 1-to-1 conversation with someone. */
    suspend fun directConversation(otherId: String): String =
        Backend.db.rpc("get_or_create_conversation", buildJsonObject { put("p_other_id", otherId) }).decodeAs<String>()

    // ---- groups: every rule lives in the database functions (0011), as on the website ----

    /** Returns the new group's conversation id. */
    suspend fun createGroup(title: String, memberIds: List<String>): String =
        Backend.db.rpc(
            "create_group_conversation",
            buildJsonObject {
                put("p_title", title.trim())
                putJsonArray("p_member_ids") { memberIds.forEach { add(it) } }
            },
        ).decodeAs<String>()

    suspend fun addMembers(conversationId: String, memberIds: List<String>) {
        Backend.db.rpc(
            "add_group_members",
            buildJsonObject {
                put("p_conversation_id", conversationId)
                putJsonArray("p_member_ids") { memberIds.forEach { add(it) } }
            },
        )
    }

    suspend fun rename(conversationId: String, title: String) {
        Backend.db.rpc("rename_group", buildJsonObject { put("p_conversation_id", conversationId); put("p_title", title.trim()) })
    }

    suspend fun removeMember(conversationId: String, userId: String) {
        Backend.db.rpc("remove_group_member", buildJsonObject { put("p_conversation_id", conversationId); put("p_user_id", userId) })
    }

    suspend fun leave(conversationId: String) {
        Backend.db.rpc("leave_group", buildJsonObject { put("p_conversation_id", conversationId) })
    }

    /** The creator while still a member, otherwise the longest-standing member. */
    suspend fun owner(conversationId: String): String? =
        Backend.db.rpc("group_owner", buildJsonObject { put("p_conversation_id", conversationId) }).decodeAs<String?>()

    /** The database's group error codes as sentences (same wording as the website). */
    fun groupError(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "GROUP_TOO_SMALL" in m -> "Pick at least two other people."
            "GROUP_TOO_BIG" in m -> "A group can have up to $GROUP_MAX_MEMBERS people."
            "GROUP_TITLE" in m -> "Give the group a name (up to $GROUP_TITLE_MAX characters)."
            "NOT_OWNER" in m -> "Only the group's owner can remove people."
            "not a member" in m -> "You're not in this group."
            else -> e.friendly()
        }
    }

    suspend fun announcementSummary(): Pair<Announcement?, Int> {
        val latest = client.from("announcements").select(Columns.raw("id, body, created_at, author_id")) {
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull<Announcement>()
        val unread = Backend.db.rpc("unread_announcement_count").decodeAs<Int>()
        return latest to unread
    }

    suspend fun announcements(): List<Announcement> =
        client.from("announcements").select(Columns.raw("id, body, created_at, author_id")) {
            order("created_at", Order.DESCENDING)
            limit(100)
        }.decodeList<Announcement>().reversed()

    suspend fun markAnnouncementsRead() {
        Backend.db.rpc("mark_announcements_read")
    }

    private fun reacted(r: JsonObject, removed: Boolean): ChatEvent.Reacted? {
        val messageId = r.string("message_id") ?: return null
        val userId = r.string("user_id") ?: return null
        val emoji = if (removed) null else r.string("emoji") ?: return null
        return ChatEvent.Reacted(messageId, userId, emoji)
    }

    /**
     * New and unsent messages in one conversation. Inserts are filtered to this
     * conversation; Realtime can't filter DELETE events (they carry only the id),
     * so every unsend arrives and the screen keeps the ones it holds.
     */
    fun events(conversationId: String): Flow<ChatEvent> = channelFlow {
        val channel = client.channel("conversation:$conversationId:${Random.nextInt(1_000_000)}")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
            table = "messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val deletes = channel.postgresChangeFlow<PostgresAction.Delete>("public") { table = "messages" }
        // Reactions and view-once opens (0023). A removed reaction carries only
        // its key, so (as with unsent messages) every chat hears it.
        val reactionAdds = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
            table = "message_reactions"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val reactionChanges = channel.postgresChangeFlow<PostgresAction.Update>("public") {
            table = "message_reactions"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val reactionRemovals = channel.postgresChangeFlow<PostgresAction.Delete>("public") { table = "message_reactions" }
        val opens = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
            table = "message_opens"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        launch { inserts.collect { a -> a.record.string("id")?.let { send(ChatEvent.Inserted(it)) } } }
        launch { deletes.collect { a -> a.oldRecord.string("id")?.let { send(ChatEvent.Deleted(it)) } } }
        launch { reactionAdds.collect { a -> reacted(a.record, removed = false)?.let { send(it) } } }
        launch { reactionChanges.collect { a -> reacted(a.record, removed = false)?.let { send(it) } } }
        launch { reactionRemovals.collect { a -> reacted(a.oldRecord, removed = true)?.let { send(it) } } }
        launch {
            opens.collect { a ->
                val r = a.record
                val messageId = r.string("message_id")
                val userId = r.string("user_id")
                val at = r.string("opened_at")
                if (messageId != null && userId != null && at != null) send(ChatEvent.Opened(messageId, userId, at))
            }
        }
        launch { rejoins(channel).collect { send(ChatEvent.Rejoined) } }
        channel.subscribe()
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { client.realtime.removeChannel(channel) }
        }
    }
}
