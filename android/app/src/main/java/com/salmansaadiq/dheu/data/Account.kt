package com.salmansaadiq.dheu.data

import com.salmansaadiq.dheu.BuildConfig
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class SignupRequest(
    val fullName: String,
    val username: String,
    val email: String,
    val password: String,
    val inviteCode: String,
    val accountType: String, // "public" | "private"
)

@Serializable
private data class SiteResult(val ok: Boolean = false, val error: String? = null, val code: String? = null)

object Account {
    suspend fun signIn(email: String, password: String) {
        Backend.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() {
        runCatching { Push.unregister() } // needs the session, so before signing out
        Backend.auth.signOut()
        Push.forgetToken()
    }

    suspend fun changePassword(password: String) {
        Backend.auth.updateUser { this.password = password }
    }

    @Serializable
    private data class SignupSettings(val inviteOnly: Boolean = false)

    /** Whether sign-up needs an invite code right now (0028), or null if the website can't be reached. */
    suspend fun inviteOnly(): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(BuildConfig.SITE_URL + "/api/auth/signup").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                check(connection.responseCode == 200) { "The website answered ${connection.responseCode}" }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                Backend.json.decodeFromString<SignupSettings>(text).inviteOnly
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** The website's sign-up page, for sharing while sign-up is open. */
    val signupLink get() = "${BuildConfig.SITE_URL}/signup"

    /**
     * Creating an account needs the secret key (and, while sign-up is
     * invite-only, the invite check), which only the website's server holds,
     * so the app asks it, then signs in.
     */
    suspend fun signUp(request: SignupRequest) {
        val result = postToSite("/api/auth/signup", Backend.json.encodeToString(request), bearer = null)
        if (!result.ok) throw IllegalArgumentException(result.error ?: "Could not create your account. Please try again.")
        signIn(request.email, request.password)
    }

    suspend fun inviteCodes(me: String): List<InviteCode> =
        Backend.client.from("invite_codes").select {
            filter { eq("created_by", me) }
            order("created_at", Order.DESCENDING)
        }.decodeList()

    /** A single-use code for a friend (the server holds the limits and the secret key). */
    suspend fun createInviteCode(): String {
        val token = Backend.auth.currentAccessTokenOrNull() ?: throw IllegalArgumentException("Not signed in.")
        val result = postToSite("/api/invites", "{}", bearer = token)
        if (!result.ok || result.code == null) throw IllegalArgumentException(result.error ?: "Couldn't create a code.")
        return result.code
    }

    fun inviteLink(code: String) = "${BuildConfig.SITE_URL}/signup?code=$code"

    @Serializable
    private data class DeleteRequest(val password: String)

    /**
     * Settings → Delete account (0025). The website checks the password and
     * deletes the account with its server key (src/lib/account.ts); then the
     * session here is dropped, which takes the app back to the login screen.
     */
    suspend fun deleteAccount(password: String) {
        val token = Backend.auth.currentAccessTokenOrNull() ?: throw IllegalArgumentException("Not signed in.")
        val result = postToSite("/api/account/delete", Backend.json.encodeToString(DeleteRequest(password)), bearer = token)
        if (!result.ok) throw IllegalArgumentException(result.error ?: "Couldn't delete your account. Please try again.")
        // Their push devices went with the account; this phone just forgets its token.
        Push.forgetToken()
        // The server no longer knows the user; signOut still clears the session here.
        runCatching { Backend.auth.signOut() }
        if (Backend.auth.currentSessionOrNull() != null) runCatching { Backend.auth.clearSession() }
    }

    private suspend fun postToSite(path: String, body: String, bearer: String?): SiteResult = withContext(Dispatchers.IO) {
        val connection = (URL(BuildConfig.SITE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            runCatching { Backend.json.decodeFromString<SiteResult>(text) }
                .getOrElse { SiteResult(false, "Unexpected reply from the server (${connection.responseCode}).") }
        } finally {
            connection.disconnect()
        }
    }
}
