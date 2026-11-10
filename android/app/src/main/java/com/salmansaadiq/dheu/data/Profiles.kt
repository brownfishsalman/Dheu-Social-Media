package com.salmansaadiq.dheu.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.http.ContentType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Who I follow and who I've asked to follow, for lists of people. */
class Relationships(val following: Set<String>, val requested: Set<String>) {
    fun stateOf(id: String) = when (id) {
        in following -> FollowState.Following
        in requested -> FollowState.Requested
        else -> FollowState.None
    }
}

object Profiles {
    private val client get() = Backend.client

    suspend fun byId(id: String): Profile =
        client.from("profiles").select { filter { eq("id", id) } }.decodeSingle()

    /** Null when there's no such member, or they blocked me (RLS hides them). */
    suspend fun byUsername(username: String): Profile? =
        client.from("profiles").select { filter { eq("username", username.lowercase()) } }.decodeSingleOrNull()

    suspend fun stats(profileId: String): ProfileStats =
        Backend.db.rpc("profile_stats", buildJsonObject { put("p_profile", profileId) })
            .decodeList<ProfileStats>().firstOrNull() ?: ProfileStats()

    /** RLS returns posts only to people allowed to see them (empty otherwise). */
    suspend fun gridPosts(profileId: String): List<GridPost> =
        client.from("posts").select(Columns.raw("id, created_at, images:post_images(path, thumb_path, width, height, position)")) {
            filter {
                eq("author_id", profileId)
                gt("expires_at", nowIso())
            }
            order("created_at", Order.DESCENDING)
            limit(200)
        }.decodeList<GridPost>().map { p -> p.copy(images = p.images.sortedBy { it.position }) }

    suspend fun followers(profileId: String): List<Person> =
        client.from("follows").select(Columns.raw("profile:profiles!follows_follower_id_fkey($PERSON)")) {
            filter { eq("following_id", profileId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<PersonRow>().mapNotNull { it.profile }

    suspend fun following(profileId: String): List<Person> =
        client.from("follows").select(Columns.raw("profile:profiles!follows_following_id_fkey($PERSON)")) {
            filter { eq("follower_id", profileId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<PersonRow>().mapNotNull { it.profile }

    suspend fun followingIds(me: String): List<String> =
        client.from("follows").select(Columns.raw("following_id")) { filter { eq("follower_id", me) } }
            .decodeList<FollowingId>().map { it.followingId }

    suspend fun relationships(me: String): Relationships {
        val following = followingIds(me).toSet()
        val requested = client.from("follow_requests").select(Columns.raw("target_id")) { filter { eq("requester_id", me) } }
            .decodeList<TargetId>().map { it.targetId }.toSet()
        return Relationships(following, requested)
    }

    suspend fun search(query: String, limit: Long = 30): List<Person> {
        val q = query.trim().replace(Regex("[%_,()]"), "")
        return client.from("profiles").select(Columns.raw(PERSON)) {
            if (q.isNotEmpty()) {
                filter {
                    or {
                        ilike("username", "%$q%")
                        ilike("full_name", "%$q%")
                    }
                }
            }
            order("username", Order.ASCENDING)
            limit(limit)
        }.decodeList()
    }

    /**
     * "Suggested for you": the admin's picks in their order, leaving out me, people
     * I follow or have asked to follow, and people I blocked (as on the website).
     */
    suspend fun suggestions(me: String, limit: Int = 10): List<Person> = coroutineScope {
        val picks = async {
            client.from("suggested_people").select(Columns.raw("profile:profiles!suggested_people_user_id_fkey($PERSON)")) {
                order("position", Order.ASCENDING)
                limit(50)
            }.decodeList<PersonRow>().mapNotNull { it.profile }
        }
        val rel = async { relationships(me) }
        val blocked = async { blocked(me).map { it.id } }
        val skip = setOf(me) + rel.await().following + rel.await().requested + blocked.await()
        picks.await().filter { it.id !in skip }.take(limit)
    }

    /** Instant for public accounts, a request for private ones (follow_user decides). */
    suspend fun follow(targetId: String): FollowState {
        val result = Backend.db.rpc("follow_user", buildJsonObject { put("p_target", targetId) }).decodeAs<String>()
        return if (result == "following") FollowState.Following else FollowState.Requested
    }

    suspend fun cancelRequest(me: String, targetId: String) {
        client.from("follow_requests").delete { filter { eq("requester_id", me); eq("target_id", targetId) } }
    }

    suspend fun unfollow(me: String, targetId: String) {
        client.from("follows").delete { filter { eq("follower_id", me); eq("following_id", targetId) } }
    }

    suspend fun removeFollower(me: String, followerId: String) {
        client.from("follows").delete { filter { eq("follower_id", followerId); eq("following_id", me) } }
    }

    @Serializable
    private data class BlockInsert(@SerialName("blocker_id") val blockerId: String, @SerialName("blocked_id") val blockedId: String)

    suspend fun block(me: String, targetId: String) {
        client.from("blocks").insert(BlockInsert(me, targetId))
    }

    suspend fun unblock(me: String, targetId: String) {
        client.from("blocks").delete { filter { eq("blocker_id", me); eq("blocked_id", targetId) } }
    }

    suspend fun blocked(me: String): List<Person> =
        client.from("blocks").select(Columns.raw("profile:profiles!blocks_blocked_id_fkey($PERSON)")) {
            filter { eq("blocker_id", me) }
            order("created_at", Order.DESCENDING)
        }.decodeList<PersonRow>().mapNotNull { it.profile }

    suspend fun canViewContentOf(authorId: String): Boolean =
        Backend.db.rpc("can_view_content_of", buildJsonObject { put("p_author", authorId) }).decodeAs<Boolean>()

    suspend fun setPrivacy(private: Boolean) {
        Backend.db.rpc("set_account_privacy", buildJsonObject { put("p_private", private) })
    }

    /** Whether others see "Seen" when I've read their messages (0022). */
    suspend fun setReadReceipts(me: String, on: Boolean) {
        client.from("profiles").update({ set("read_receipts", on) }) { filter { eq("id", me) } }
    }

    suspend fun updateDetails(me: String, fullName: String, username: String, bio: String) {
        client.from("profiles").update({
            set("full_name", fullName)
            set("username", username)
            set("bio", bio)
        }) { filter { eq("id", me) } }
    }

    /** Uploads a new avatar, points the profile at it, then drops the old file. Returns the new path. */
    suspend fun replaceAvatar(context: Context, me: String, uri: Uri, oldPath: String?): String {
        val image = Images.encode(context, uri, Images.AVATAR)
        val path = Images.newPath(me)
        uploadFile(Buckets.AVATARS, path, image.bytes, ContentType.Image.JPEG, CACHE_YEAR)
        client.from("profiles").update({ set("avatar_path", path) }) { filter { eq("id", me) } }
        if (oldPath != null) removeFiles(Buckets.AVATARS, listOf(oldPath))
        return path
    }
}
