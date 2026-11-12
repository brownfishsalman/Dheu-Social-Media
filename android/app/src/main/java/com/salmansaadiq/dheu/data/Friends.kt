package com.salmansaadiq.dheu.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Find friends, the flame tab (0027), as the website's src/lib/friends.ts.
 * Members with a swipe profile swipe through each other's: right to like,
 * left to pass. Likes stay secret until they're mutual; a match opens a chat.
 */
object Friends {
    const val PHOTOS_MAX = 6
    const val BIO_MAX = 300
    const val INTERESTS_MAX = 5
    const val INTEREST_LENGTH = 24
    const val AGE_MIN = 13
    const val AGE_MAX = 99

    /** Portrait cards; the photo is cropped to fit when shown (the website's SWIPE_MAX). */
    val PHOTO = Images.Spec(1080, 1440, 88)

    @Serializable
    data class MyProfile(
        val bio: String = "",
        val age: Int? = null,
        val interests: List<String> = emptyList(),
        val photos: List<String> = emptyList(),
        val active: Boolean = true,
    )

    /** One card in the deck. [looped]: the deck ran out and started over with the people passed on. */
    @Serializable
    data class Card(
        @SerialName("user_id") val userId: String,
        @SerialName("full_name") val fullName: String,
        val bio: String = "",
        val age: Int? = null,
        val interests: List<String> = emptyList(),
        val photos: List<String> = emptyList(),
        val looped: Boolean = false,
    )

    @Serializable
    data class Match(
        @SerialName("user_id") val userId: String,
        val username: String,
        @SerialName("full_name") val fullName: String,
        @SerialName("avatar_path") val avatarPath: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("matched_at") val matchedAt: String,
        /** Has had the "It's a match" screen. */
        val seen: Boolean = true,
    )

    @Serializable
    data class SwipeResult(val matched: Boolean = false, @SerialName("conversation_id") val conversationId: String? = null)

    /** A photo on the profile being edited: already saved, or just picked. */
    sealed interface Photo {
        data class Saved(val path: String) : Photo
        data class Picked(val uri: Uri) : Photo
    }

    suspend fun myProfile(me: String): MyProfile? =
        Backend.client.from("swipe_profiles").select(Columns.raw("bio, age, interests, photos, active")) {
            filter { eq("user_id", me) }
        }.decodeSingleOrNull()

    /** The next cards; [exclude] is what's already on screen. Starts the deck over once everyone's been swiped. */
    suspend fun deck(exclude: List<String> = emptyList(), limit: Int = 10): List<Card> =
        Backend.db.rpc(
            "swipe_deck",
            buildJsonObject {
                putJsonArray("p_exclude") { exclude.forEach { add(it) } }
                put("p_limit", limit)
            },
        ).decodeList()

    suspend fun swipe(target: String, like: Boolean): SwipeResult =
        Backend.db.rpc("swipe", buildJsonObject { put("p_target", target); put("p_like", like) })
            .decodeList<SwipeResult>().firstOrNull() ?: SwipeResult()

    /** Takes back the last swipe, if it was on [target] and didn't make a match. */
    suspend fun undo(target: String): Boolean =
        Backend.db.rpc("swipe_undo", buildJsonObject { put("p_target", target) }).decodeAs<Boolean>()

    /** Neither sees the other in the deck again; the chat stays. */
    suspend fun unmatch(other: String) {
        Backend.db.rpc("swipe_unmatch", buildJsonObject { put("p_other", other) })
    }

    suspend fun matches(): List<Match> = Backend.db.rpc("my_swipe_matches").decodeList()

    suspend fun markMatchesSeen() {
        Backend.db.rpc("mark_swipe_matches_seen")
    }

    /** Same tidying as the database (prepare_swipe_profile), so what's typed is what's saved. */
    fun cleanInterest(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(INTEREST_LENGTH)

    // Built by hand so a cleared age is sent as null (the app's JSON leaves nulls out).
    private fun fields(bio: String, age: Int?, interests: List<String>, photos: List<String>, me: String? = null): JsonObject =
        buildJsonObject {
            if (me != null) put("user_id", me)
            put("bio", bio.trim())
            put("age", age)
            putJsonArray("interests") { interests.forEach { add(it) } }
            putJsonArray("photos") { photos.forEach { add(it) } }
        }

    /**
     * Uploads the newly picked photos, then makes or updates the profile. Photos
     * taken off it leave Storage; if saving fails, the new uploads go again.
     */
    suspend fun save(
        context: Context,
        me: String,
        existing: MyProfile?,
        photos: List<Photo>,
        bio: String,
        age: Int?,
        interests: List<String>,
        onProgress: (String) -> Unit,
    ) {
        val uploaded = mutableListOf<String>()
        val paths = mutableListOf<String>()
        try {
            photos.forEachIndexed { i, photo ->
                when (photo) {
                    is Photo.Saved -> paths += photo.path
                    is Photo.Picked -> {
                        onProgress("Uploading photo ${i + 1} of ${photos.size}…")
                        val image = Images.encode(context, photo.uri, PHOTO)
                        val path = Images.newPath(me)
                        uploadFile(Buckets.SWIPE, path, image.bytes, ContentType.Image.JPEG, CACHE_DAY)
                        uploaded += path
                        paths += path
                    }
                }
            }
            onProgress("Saving…")
            if (existing == null) {
                Backend.client.from("swipe_profiles").insert(fields(bio, age, interests, paths, me = me))
            } else {
                Backend.client.from("swipe_profiles").update(fields(bio, age, interests, paths)) { filter { eq("user_id", me) } }
            }
        } catch (e: Exception) {
            removeFiles(Buckets.SWIPE, uploaded)
            throw e
        }
        removeFiles(Buckets.SWIPE, existing?.photos.orEmpty().filter { it !in paths })
    }

    /** Paused: nobody sees the profile and its owner can't swipe. */
    suspend fun setActive(me: String, active: Boolean) {
        Backend.client.from("swipe_profiles").update(buildJsonObject { put("active", active) }) { filter { eq("user_id", me) } }
    }

    /** Leaves Find friends: the profile and its photos go; matches and chats stay. */
    suspend fun delete(me: String) {
        Backend.client.from("swipe_profiles").delete { filter { eq("user_id", me) } }
        val files = runCatching { Backend.storage.from(Buckets.SWIPE).list(me) }.getOrDefault(emptyList())
        removeFiles(Buckets.SWIPE, files.map { "$me/${it.name}" })
    }
}

/** The swipe functions' error codes, in words; anything else as usual. */
fun Throwable.swipeMessage(fallback: String): String {
    val text = message.orEmpty()
    return when {
        "BAD_PHOTOS" in text -> "One of the photos didn't upload. Remove it and add it again."
        "NO_PROFILE" in text -> "Make your swipe profile first, or resume it if it's paused."
        "NOT_AVAILABLE" in text -> "That profile isn't available any more."
        else -> friendly(fallback)
    }
}
