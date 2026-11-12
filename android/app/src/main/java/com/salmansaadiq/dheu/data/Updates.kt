package com.salmansaadiq.dheu.data

import android.content.Context
import com.salmansaadiq.dheu.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL

/**
 * The "new version" banner. Releases are published on GitHub with the APK
 * attached (see android/README.md), so the app asks GitHub for the latest
 * one, at most twice a day, and offers it when it's newer than this build.
 */
object Updates {
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val page: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

    /** [url] is the APK itself when the release has one, else its page. */
    data class Available(val version: String, val url: String)

    /**
     * A newer release this member hasn't closed the banner for, or null. GitHub
     * is asked at most every 12 hours; in between, the last answer is used.
     */
    suspend fun check(context: Context): Available? {
        if (System.currentTimeMillis() - UpdatePrefs.checkedAt(context) >= CHECK_EVERY_MS) {
            runCatching { latest() }.getOrNull()?.takeIf { !it.draft && !it.prerelease }?.let { release ->
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }?.url
                // Tags are "v" + the version; a capital V, easy to type by mistake, counts too.
                UpdatePrefs.saveLatest(context, release.tag.trimStart('v', 'V'), apk ?: release.page)
            }
        }
        val (version, url) = UpdatePrefs.latest(context) ?: return null
        if (!isNewer(version, BuildConfig.VERSION_NAME) || UpdatePrefs.dismissed(context) == version) return null
        return Available(version, url)
    }

    suspend fun dismiss(context: Context, version: String) = UpdatePrefs.dismiss(context, version)

    private suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val connection = (URL("https://api.github.com/repos/${BuildConfig.RELEASES_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Dheu-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode == 200) { "GitHub answered ${connection.responseCode}" }
            Backend.json.decodeFromString(Release.serializer(), connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** "0.10.0" is newer than "0.9.2": compared number by number. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
