package com.salmansaadiq.dheu.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object Stories {
    private val client get() = Backend.client

    /** Active stories from me and people I follow, grouped per author: mine, then unseen, then seen. */
    suspend fun feed(me: String, followingIds: List<String>): List<StoryGroup> {
        val rows = client.from("stories").select(
            Columns.raw(
                "id, author_id, image_path, width, height, created_at, expires_at, audience, " +
                    "author:profiles!stories_author_id_fkey($PERSON), views:story_views(viewer_id)",
            ),
        ) {
            filter {
                isIn("author_id", listOf(me) + followingIds)
                gt("expires_at", nowIso())
            }
            order("created_at", Order.ASCENDING)
        }.decodeList<StoryRow>()

        val groups = LinkedHashMap<String, StoryGroup>()
        for (s in rows) {
            val author = s.author ?: continue
            val seen = s.views.any { it.viewerId == me }
            val story = Story(s.id, s.authorId, s.imagePath, s.width, s.height, s.createdAt, seen, s.audience)
            val g = groups[s.authorId]
            groups[s.authorId] = if (g == null) StoryGroup(author, listOf(story), seen, s.createdAt, story.closeFriends)
            else g.copy(
                stories = g.stories + story,
                allSeen = g.allSeen && seen,
                latestAt = s.createdAt,
                closeFriends = g.closeFriends || story.closeFriends,
            )
        }
        return groups.values.sortedWith(
            compareBy<StoryGroup> { it.author.id != me }.thenBy { it.allSeen }.thenByDescending { it.latestAt },
        )
    }

    suspend fun ofAuthor(authorId: String, me: String): StoryGroup? =
        feed(me, listOf(authorId)).firstOrNull { it.author.id == authorId }

    @Serializable
    private data class ViewInsert(@SerialName("story_id") val storyId: String, @SerialName("viewer_id") val viewerId: String)

    suspend fun markViewed(storyId: String, me: String) {
        client.from("story_views").upsert(ViewInsert(storyId, me)) {
            onConflict = "story_id,viewer_id"
            ignoreDuplicates = true
        }
    }

    @Serializable
    private data class ReactionUpsert(
        @SerialName("story_id") val storyId: String,
        @SerialName("user_id") val userId: String,
        val emoji: String,
    )

    suspend fun react(storyId: String, me: String, emoji: String) {
        client.from("story_reactions").upsert(ReactionUpsert(storyId, me, emoji)) { onConflict = "story_id,user_id" }
    }

    /** A text reply to a story lands in the author's DMs, with the story attached. */
    suspend fun reply(story: Story, me: String, text: String) {
        val conversationId = Chat.directConversation(story.authorId)
        Chat.insertMessage(MessageInsert(conversationId, me, text, storyId = story.id))
    }

    @Serializable
    private data class StoryInsert(
        @SerialName("author_id") val authorId: String,
        @SerialName("image_path") val imagePath: String,
        val width: Int,
        val height: Int,
        val audience: String,
    )

    /** [audience]: everyone who can see my stories, or only my close friends (0024). */
    suspend fun create(context: Context, me: String, uri: Uri, audience: String = AUDIENCE_EVERYONE) {
        val image = Images.encode(context, uri, Images.STORY)
        val path = Images.newPath(me)
        uploadFile(Buckets.STORIES, path, image.bytes, ContentType.Image.JPEG, CACHE_DAY)
        try {
            client.from("stories").insert(StoryInsert(me, path, image.width, image.height, audience))
        } catch (e: Exception) {
            removeFiles(Buckets.STORIES, listOf(path))
            throw e
        }
    }

    suspend fun delete(story: Story) {
        client.from("stories").delete { filter { eq("id", story.id) } }
        removeFiles(Buckets.STORIES, listOf(story.imagePath))
    }

    @Serializable
    private data class ViewerRow(val viewer: Person? = null)

    /** Who has seen one of my stories (RLS: only the author can read others' views). */
    suspend fun seenBy(storyId: String): List<Person> =
        client.from("story_views").select(Columns.raw("viewer:profiles!story_views_viewer_id_fkey($PERSON)")) {
            filter { eq("story_id", storyId) }
            order("viewed_at", Order.DESCENDING)
        }.decodeList<ViewerRow>().mapNotNull { it.viewer }

    // ---- highlights: stories kept on a profile after they expire (same tables as the website) ----

    @Serializable
    private data class ImagePath(@SerialName("image_path") val imagePath: String)

    @Serializable
    private data class HighlightItemRow(val story: ImagePath? = null)

    @Serializable
    private data class HighlightRow(
        val id: String,
        val title: String,
        val cover: ImagePath? = null,
        val items: List<HighlightItemRow> = emptyList(),
    )

    /** A profile's highlights in order; the cover is the chosen story, else the first one. */
    suspend fun highlights(ownerId: String): List<HighlightSummary> =
        client.from("story_highlights").select(
            Columns.raw(
                "id, title, cover:stories!story_highlights_cover_story_id_fkey(image_path), items:highlight_items(story:stories(image_path))",
            ),
        ) {
            filter { eq("owner_id", ownerId) }
            order("position", Order.ASCENDING)
            order("created_at", Order.ASCENDING)
        }.decodeList<HighlightRow>().map { h ->
            // Close-friends stories come back empty for everyone else (RLS); don't count them.
            HighlightSummary(h.id, h.title, h.cover?.imagePath ?: h.items.firstNotNullOfOrNull { it.story?.imagePath }, h.items.count { it.story != null })
        }

    @Serializable
    private data class HighlightStoryRow(val story: StoryRow? = null)

    @Serializable
    private data class HighlightDetailRow(
        val id: String,
        val title: String,
        val owner: Person? = null,
        val items: List<HighlightStoryRow> = emptyList(),
    )

    suspend fun highlight(id: String): HighlightDetail? {
        val row = client.from("story_highlights").select(
            Columns.raw(
                "id, title, owner:profiles!story_highlights_owner_id_fkey($PERSON), " +
                    "items:highlight_items(story:stories(id, author_id, image_path, width, height, created_at, expires_at, audience))",
            ),
        ) { filter { eq("id", id) } }.decodeSingleOrNull<HighlightDetailRow>() ?: return null
        val owner = row.owner ?: return null
        val stories = row.items.mapNotNull { it.story }.sortedBy { it.createdAt }
            .map { Story(it.id, it.authorId, it.imagePath, it.width, it.height, it.createdAt, seen = true, audience = it.audience) }
        return HighlightDetail(row.id, row.title, owner, stories)
    }

    /** My stories that can go into a highlight: active ones, and ones already in a highlight. */
    suspend fun storiesForHighlights(me: String): List<StoryRow> =
        client.from("stories").select(Columns.raw("id, author_id, image_path, width, height, created_at, expires_at")) {
            filter { eq("author_id", me) }
            order("created_at", Order.DESCENDING)
            limit(200)
        }.decodeList()

    @Serializable
    private data class HighlightInsert(
        @SerialName("owner_id") val ownerId: String,
        val title: String,
        @SerialName("cover_story_id") val coverStoryId: String,
        val position: Int,
    )

    @Serializable
    private data class HighlightItemInsert(@SerialName("highlight_id") val highlightId: String, @SerialName("story_id") val storyId: String)

    /** Creates the highlight with these stories (the first is the cover) and returns its id. */
    suspend fun createHighlight(me: String, title: String, storyIds: List<String>, position: Int): String {
        val id = client.from("story_highlights").insert(HighlightInsert(me, title.trim(), storyIds.first(), position)) {
            select(Columns.raw("id"))
        }.decodeSingle<IdRow>().id
        try {
            client.from("highlight_items").insert(storyIds.map { HighlightItemInsert(id, it) })
        } catch (e: Exception) {
            runCatching { deleteHighlight(id) }
            throw e
        }
        return id
    }

    suspend fun addToHighlight(highlightId: String, storyId: String) {
        client.from("highlight_items").upsert(HighlightItemInsert(highlightId, storyId)) {
            onConflict = "highlight_id,story_id"
            ignoreDuplicates = true
        }
    }

    suspend fun removeFromHighlight(highlightId: String, storyId: String) {
        client.from("highlight_items").delete { filter { eq("highlight_id", highlightId); eq("story_id", storyId) } }
    }

    /** The highlight goes; its stories are kept (and expire as usual if they're in no other highlight). */
    suspend fun deleteHighlight(id: String) {
        client.from("story_highlights").delete { filter { eq("id", id) } }
    }
}
