package com.salmansaadiq.dheu.data

import com.salmansaadiq.dheu.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json

// The one Supabase client the app talks through. Same project, publishable
// key and Row Level Security as the website: the database decides what each
// member may read or change; the app only ever holds the member's own session.
object Backend {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            // Named outright rather than found through ServiceLoader, which R8 can strip.
            httpEngine = OkHttp.create()
            defaultSerializer = KotlinXSerializer(json)
            install(Auth)
            install(Postgrest)
            install(Storage)
            install(Realtime)
        }
    }

    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    val auth get() = client.auth
    val db get() = client.postgrest
    val storage get() = client.storage

    /** Signed-in member's id, or null. */
    val userId: String? get() = auth.currentUserOrNull()?.id
}

object Buckets {
    const val AVATARS = "avatars"
    const val POSTS = "posts"
    const val STORIES = "stories"
    const val CHAT = "chat"
    /** View-once photos: private, downloadable only right after opening (0023). */
    const val ONCE = "once"
}
