package com.salmansaadiq.dheu.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object Posts {
    private const val FEED_PAGE = 8
    private val client get() = Backend.client

    private fun Post.sorted() = copy(images = images.sortedBy { it.position })

    /** My posts and those of people I follow (the home_feed view), newest first. */
    suspend fun feedPage(before: String? = null): Page<Post> {
        val rows = client.from("home_feed").select(Columns.raw(POST_SELECT)) {
            filter {
                gt("expires_at", nowIso())
                if (before != null) lt("created_at", before)
            }
            order("created_at", Order.DESCENDING)
            limit(FEED_PAGE + 1L)
        }.decodeList<Post>().filter { it.author != null }.map { it.sorted() }
        val hasMore = rows.size > FEED_PAGE
        val items = if (hasMore) rows.take(FEED_PAGE) else rows
        return Page(items, if (hasMore) items.last().createdAt else null)
    }

    suspend fun byId(id: String): Post? =
        client.from("post_feed").select(Columns.raw(POST_SELECT)) { filter { eq("id", id) } }
            .decodeSingleOrNull<Post>()?.takeIf { it.author != null }?.sorted()

    suspend fun comments(postId: String): List<Comment> =
        client.from("comments").select(
            Columns.raw("id, post_id, author_id, body, created_at, parent_id, likes:comment_likes(user_id), author:profiles!comments_author_id_fkey($PERSON)"),
        ) {
            filter { eq("post_id", postId) }
            order("created_at", Order.ASCENDING)
            limit(500)
        }.decodeList<Comment>().filter { it.author != null }

    @Serializable
    private data class CommentInsert(
        @SerialName("post_id") val postId: String,
        @SerialName("author_id") val authorId: String,
        val body: String,
        @SerialName("parent_id") val parentId: String? = null,
    )

    /** [parentId]: the top comment this replies to (one level, 0024). */
    suspend fun addComment(postId: String, me: Person, body: String, parentId: String? = null): Comment =
        client.from("comments").insert(CommentInsert(postId, me.id, body, parentId)) {
            select(Columns.raw("id, post_id, author_id, body, created_at, parent_id"))
        }.decodeSingle<Comment>().copy(author = me)

    /** Its replies go with it (on delete cascade). */
    suspend fun deleteComment(id: String) {
        client.from("comments").delete { filter { eq("id", id) } }
    }

    @Serializable
    private data class CommentLikeInsert(@SerialName("comment_id") val commentId: String, @SerialName("user_id") val userId: String)

    suspend fun likeComment(commentId: String, me: String) {
        try {
            client.from("comment_likes").insert(CommentLikeInsert(commentId, me))
        } catch (e: Exception) {
            if (e.pgCode != "23505") throw e // already liked: fine
        }
    }

    suspend fun unlikeComment(commentId: String, me: String) {
        client.from("comment_likes").delete { filter { eq("comment_id", commentId); eq("user_id", me) } }
    }

    // ---- saved posts (0024): private to me, gone when the post expires ----

    @Serializable
    private data class SaveInsert(@SerialName("user_id") val userId: String, @SerialName("post_id") val postId: String)

    suspend fun save(postId: String, me: String) {
        try {
            client.from("saved_posts").insert(SaveInsert(me, postId))
        } catch (e: Exception) {
            if (e.pgCode != "23505") throw e // already saved: fine
        }
    }

    suspend fun unsave(postId: String, me: String) {
        client.from("saved_posts").delete { filter { eq("user_id", me); eq("post_id", postId) } }
    }

    @Serializable
    private data class SavedRow(val post: GridPost? = null)

    /** Newest save first; RLS leaves out posts that expired or that I can no longer see. */
    suspend fun saved(me: String): List<GridPost> =
        client.from("saved_posts").select(Columns.raw("created_at, post:posts(id, created_at, images:post_images(path, thumb_path, width, height, position))")) {
            filter { eq("user_id", me) }
            order("created_at", Order.DESCENDING)
            limit(200)
        }.decodeList<SavedRow>().mapNotNull { it.post }.map { p -> p.copy(images = p.images.sortedBy { it.position }) }

    suspend fun likers(postId: String): List<Person> =
        client.from("post_likes").select(Columns.raw("profile:profiles!post_likes_user_id_fkey($PERSON)")) {
            filter { eq("post_id", postId) }
            order("created_at", Order.DESCENDING)
            limit(200)
        }.decodeList<PersonRow>().mapNotNull { it.profile }

    @Serializable
    private data class LikeInsert(@SerialName("post_id") val postId: String, @SerialName("user_id") val userId: String)

    suspend fun like(postId: String, me: String) {
        try {
            client.from("post_likes").insert(LikeInsert(postId, me))
        } catch (e: Exception) {
            if (e.pgCode != "23505") throw e // already liked: fine
        }
    }

    suspend fun unlike(postId: String, me: String) {
        client.from("post_likes").delete { filter { eq("post_id", postId); eq("user_id", me) } }
    }

    /** Removes the files (photos and thumbnails), then the row; RLS allows the author or the admin. */
    suspend fun delete(post: Post) {
        val files = post.images.flatMap { listOfNotNull(it.path, it.thumbPath) }
        removeFiles(Buckets.POSTS, files)
        client.from("posts").delete { filter { eq("id", post.id) } }
    }

    @Serializable
    private data class PostInsert(@SerialName("author_id") val authorId: String, val caption: String)

    @Serializable
    private data class ImageInsert(
        @SerialName("post_id") val postId: String,
        val path: String,
        @SerialName("thumb_path") val thumbPath: String,
        val width: Int,
        val height: Int,
        val position: Int,
    )

    /**
     * Same order as the website's NewPostForm: upload every photo and its
     * thumbnail, create the post, then its images. Anything uploaded is
     * removed again if a later step fails. Returns the new post's id.
     */
    suspend fun create(
        context: Context,
        me: String,
        uris: List<Uri>,
        caption: String,
        onProgress: (String) -> Unit,
    ): String {
        val uploaded = mutableListOf<String>()
        try {
            val images = uris.mapIndexed { i, uri ->
                onProgress("Preparing ${i + 1} of ${uris.size}…")
                Images.encodePost(context, uri)
            }
            val files = images.mapIndexed { i, img ->
                onProgress("Uploading ${i + 1} of ${images.size}…")
                val path = Images.newPath(me)
                val thumbPath = Images.thumbPathFor(path)
                uploadFile(Buckets.POSTS, path, img.photo.bytes, ContentType.Image.JPEG, CACHE_YEAR)
                uploaded += path
                uploadFile(Buckets.POSTS, thumbPath, img.thumb.bytes, ContentType.Image.JPEG, CACHE_YEAR)
                uploaded += thumbPath
                path to thumbPath
            }

            onProgress("Publishing…")
            val post = client.from("posts").insert(PostInsert(me, caption.trim())) { select(Columns.raw("id")) }.decodeSingle<IdRow>()
            try {
                client.from("post_images").insert(
                    images.mapIndexed { i, img ->
                        ImageInsert(post.id, files[i].first, files[i].second, img.photo.width, img.photo.height, i)
                    },
                )
            } catch (e: Exception) {
                client.from("posts").delete { filter { eq("id", post.id) } }
                throw e
            }
            return post.id
        } catch (e: Exception) {
            removeFiles(Buckets.POSTS, uploaded)
            throw e
        }
    }
}
