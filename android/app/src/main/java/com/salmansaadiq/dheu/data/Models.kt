package com.salmansaadiq.dheu.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Shapes of the rows the app reads. They mirror the website's queries in
// src/lib/data/*.ts and the columns in supabase/migrations.

/** Name and picture of a member, as embedded everywhere. */
@Serializable
data class Person(
    val id: String,
    val username: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
)

const val PERSON = "id, username, full_name, avatar_path"

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val bio: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("is_private") val isPrivate: Boolean = true,
    @SerialName("privacy_chosen_at") val privacyChosenAt: String? = null,
    /** Whether others see when I've read their messages (0022). */
    @SerialName("read_receipts") val readReceipts: Boolean = true,
) {
    val person get() = Person(id, username, fullName, avatarPath)
}

/** profile_stats(): the header numbers, seen from the signed-in member's side. */
@Serializable
data class ProfileStats(
    val posts: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    @SerialName("is_following") val isFollowing: Boolean = false,
    @SerialName("follows_me") val followsMe: Boolean = false,
    val requested: Boolean = false,
    @SerialName("requested_me") val requestedMe: Boolean = false,
    @SerialName("block_status") val blockStatus: String? = null,
) {
    val blockedByMe get() = blockStatus == "by_me" || blockStatus == "both"
}

enum class FollowState { None, Requested, Following }

// ---- posts -------------------------------------------------------------------

@Serializable
data class PostImage(
    val id: String = "",
    val path: String,
    @SerialName("thumb_path") val thumbPath: String? = null,
    val width: Int,
    val height: Int,
    val position: Int = 0,
)

@Serializable
data class Post(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val caption: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
    /** I bookmarked it (0024). */
    @SerialName("saved_by_me") val savedByMe: Boolean = false,
    val author: Person? = null,
    val images: List<PostImage> = emptyList(),
)

const val POST_SELECT =
    "id, author_id, caption, created_at, expires_at, like_count, comment_count, liked_by_me, saved_by_me, " +
        "author:profiles!posts_author_id_fkey($PERSON), images:post_images(id, path, thumb_path, width, height, position)"

@Serializable
data class GridPost(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val images: List<PostImage> = emptyList(),
)

@Serializable
data class Comment(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    /** A reply hangs off a top comment (one level, 0024). */
    @SerialName("parent_id") val parentId: String? = null,
    val likes: List<UserRef> = emptyList(),
    val author: Person? = null,
) {
    fun likedBy(userId: String) = likes.any { it.userId == userId }
}

@Serializable
data class UserRef(@SerialName("user_id") val userId: String)

// ---- stories -------------------------------------------------------------------

@Serializable
data class StoryRow(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("image_path") val imagePath: String,
    val width: Int,
    val height: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    /** "everyone" or "close_friends" (0024). */
    val audience: String = AUDIENCE_EVERYONE,
    val author: Person? = null,
    val views: List<ViewerId> = emptyList(),
)

@Serializable
data class ViewerId(@SerialName("viewer_id") val viewerId: String)

const val AUDIENCE_EVERYONE = "everyone"
const val AUDIENCE_CLOSE_FRIENDS = "close_friends"

data class Story(
    val id: String,
    val authorId: String,
    val imagePath: String,
    val width: Int,
    val height: Int,
    val createdAt: String,
    val seen: Boolean,
    val audience: String = AUDIENCE_EVERYONE,
) {
    val closeFriends get() = audience == AUDIENCE_CLOSE_FRIENDS
}

/** [closeFriends]: some story here is for close friends, so the ring is green. */
data class StoryGroup(
    val author: Person,
    val stories: List<Story>,
    val allSeen: Boolean,
    val latestAt: String,
    val closeFriends: Boolean = false,
)

data class HighlightSummary(val id: String, val title: String, val coverPath: String?, val count: Int)

data class HighlightDetail(val id: String, val title: String, val owner: Person, val stories: List<Story>)

const val HIGHLIGHT_TITLE_MAX = 30

// ---- activity ---------------------------------------------------------------------

@Serializable
data class NotificationRow(
    val id: String,
    val type: String,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("post_id") val postId: String? = null,
    @SerialName("story_id") val storyId: String? = null,
    // A mention in a group chat (0020): the message and the group's name.
    @SerialName("conversation_id") val conversationId: String? = null,
    val actor: Person? = null,
    val post: NotificationPost? = null,
    val comment: NotificationComment? = null,
    val story: NotificationStory? = null,
    val message: NotificationComment? = null,
    val conversation: NotificationGroup? = null,
)

@Serializable
data class NotificationGroup(val title: String? = null)

@Serializable
data class NotificationPost(val images: List<PostImage> = emptyList())

@Serializable
data class NotificationComment(val body: String = "")

@Serializable
data class NotificationStory(@SerialName("image_path") val imagePath: String? = null)

@Serializable
data class Badges(val messages: Int = 0, val activity: Int = 0)

// ---- messages ------------------------------------------------------------------

@Serializable
data class SystemEvent(
    val type: String,
    val actor: String,
    val targets: List<String> = emptyList(),
    val title: String? = null,
)

/** Enough of a quoted message to label it, even after its media expired. */
@Serializable
data class ReplyPreview(
    val id: String,
    val body: String = "",
    @SerialName("sender_id") val senderId: String,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("image_expires_at") val imageExpiresAt: String? = null,
    @SerialName("audio_path") val audioPath: String? = null,
    @SerialName("audio_expires_at") val audioExpiresAt: String? = null,
)

@Serializable
data class StoryRef(@SerialName("image_path") val imagePath: String, @SerialName("author_id") val authorId: String)

/** A row of public.messages, plus the joins the chat screen shows. */
@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String = "",
    @SerialName("story_id") val storyId: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("image_expires_at") val imageExpiresAt: String? = null,
    @SerialName("audio_path") val audioPath: String? = null,
    @SerialName("audio_duration_ms") val audioDurationMs: Long? = null,
    @SerialName("audio_expires_at") val audioExpiresAt: String? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    val kind: String = "text",
    val event: SystemEvent? = null,
    @SerialName("note_text") val noteText: String? = null,
    /** A photo in the private "once" bucket (0023); [oncePath] is null once it's gone. */
    @SerialName("view_once") val viewOnce: Boolean = false,
    @SerialName("once_path") val oncePath: String? = null,
    @SerialName("created_at") val createdAt: String,
    val story: StoryRef? = null,
    // PostgREST returns this self-join as an object or a one-item array; see [replyPreview].
    @SerialName("reply") val replyRaw: JsonElement? = null,
    val reactions: List<Reaction> = emptyList(),
    /** View-once: my own open, or who opened the photo I sent. */
    val opens: List<Open> = emptyList(),
) {
    val isSystem get() = kind == "system"

    /** The emoji I reacted with, if any. */
    fun reactionOf(userId: String): String? = reactions.firstOrNull { it.userId == userId }?.emoji

    fun withReaction(userId: String, emoji: String?): Message =
        copy(reactions = reactions.filter { it.userId != userId } + listOfNotNull(emoji?.let { Reaction(userId, it) }))

    fun withOpen(userId: String, openedAt: String): Message =
        if (opens.any { it.userId == userId }) this else copy(opens = opens + Open(userId, openedAt))

    val replyPreview: ReplyPreview?
        get() {
            val obj = when (val r = replyRaw) {
                is JsonObject -> r
                is JsonArray -> r.firstOrNull() as? JsonObject
                else -> null
            } ?: return null
            return runCatching { Backend.json.decodeFromJsonElement(ReplyPreview.serializer(), obj) }.getOrNull()
        }

    fun toReplyPreview() = ReplyPreview(id, body, senderId, imagePath, imageExpiresAt, audioPath, audioExpiresAt)
}

@Serializable
data class Reaction(@SerialName("user_id") val userId: String, val emoji: String)

@Serializable
data class Open(@SerialName("user_id") val userId: String, @SerialName("opened_at") val openedAt: String)

/** The quick reactions offered on a message (same as the website). */
val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "😡", "👍")

const val MESSAGE_COLUMNS =
    "id, conversation_id, sender_id, body, story_id, image_path, image_expires_at, audio_path, audio_duration_ms, " +
        "audio_expires_at, reply_to_id, kind, event, note_text, view_once, once_path, created_at"
const val MESSAGE_SELECT = "$MESSAGE_COLUMNS, story:stories(image_path, author_id), " +
    "reply:reply_to_id(id, body, sender_id, image_path, image_expires_at, audio_path, audio_expires_at), " +
    "reactions:message_reactions(user_id, emoji), opens:message_opens(user_id, opened_at)"

/** "📷 Photo", "🎤 Voice message", or the text: valid even after media expired. */
fun replyLabel(r: ReplyPreview): String = when {
    r.audioPath != null || r.audioExpiresAt != null -> "🎤 Voice message"
    r.imagePath != null || r.imageExpiresAt != null -> "📷 Photo"
    else -> r.body
}

@Serializable
data class MemberRow(@SerialName("joined_at") val joinedAt: String, val user: Person? = null)

@Serializable
data class ReadRow(@SerialName("cleared_at") val clearedAt: String? = null)

@Serializable
data class LastMessage(
    val body: String = "",
    @SerialName("sender_id") val senderId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("story_id") val storyId: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("audio_path") val audioPath: String? = null,
    @SerialName("note_text") val noteText: String? = null,
    val kind: String = "text",
    @SerialName("view_once") val viewOnce: Boolean = false,
    val sender: SenderName? = null,
)

@Serializable
data class SenderName(@SerialName("full_name") val fullName: String? = null)

@Serializable
data class ConversationRow(
    val id: String,
    @SerialName("is_group") val isGroup: Boolean = false,
    val title: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    val members: List<MemberRow> = emptyList(),
    val reads: List<ReadRow> = emptyList(),
    val messages: List<LastMessage> = emptyList(),
)

data class ConversationSummary(
    val id: String,
    val isGroup: Boolean,
    val title: String,
    val peer: Person?,
    val members: List<Person>,
    val memberCount: Int,
    val last: LastMessage?,
    val unread: Int,
    /** When the others last read it, for "Seen" under my last message (0023). */
    val receipts: List<Receipt> = emptyList(),
)

data class ConversationInfo(
    val id: String,
    val isGroup: Boolean,
    val title: String,
    val peer: Person?,
    val members: List<Person>,
    val blockStatus: String?,
)

@Serializable
data class UnreadRow(@SerialName("conversation_id") val conversationId: String, val unread: Long = 0)

@Serializable
data class Announcement(
    val id: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("author_id") val authorId: String? = null,
)

// ---- notes -------------------------------------------------------------------

@Serializable
data class Note(
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val author: Person? = null,
)

const val NOTE_MAX_LENGTH = 60

// ---- settings -------------------------------------------------------------------

@Serializable
data class InviteCode(
    val id: String,
    val code: String,
    @SerialName("max_uses") val maxUses: Int = 1,
    @SerialName("use_count") val useCount: Int = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
    val disabled: Boolean = false,
    @SerialName("created_at") val createdAt: String,
)

/** Read a string out of a realtime payload's record. */
fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
