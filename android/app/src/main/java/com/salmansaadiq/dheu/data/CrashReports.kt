package com.salmansaadiq.dheu.data

import android.content.Context
import android.os.Build
import com.salmansaadiq.dheu.BuildConfig
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant

/**
 * Crash reports (0026). When the app crashes, the details go to a small file
 * on the phone, and the app then crashes as it always would. On the next
 * launch, once signed in, the files are sent to app_crashes, where the admin
 * reads them (Admin → Crashes on the website). Nothing leaves the phone but
 * the error, its stack trace, the app version, the Android version and the
 * phone model.
 */
object CrashReports {
    private const val DIR = "crashes"
    private const val MAX_WAITING = 5 // a crash loop: the first few say it all
    private const val STACK_MAX = 20_000

    @Serializable
    private data class Report(
        @SerialName("user_id") val userId: String? = null,
        @SerialName("app_version") val appVersion: String,
        @SerialName("version_code") val versionCode: Int,
        val android: String,
        val device: String,
        val thread: String,
        val exception: String,
        val stack: String,
        @SerialName("happened_at") val happenedAt: String,
    )

    private fun dir(context: Context) = File(context.noBackupFilesDir, DIR)

    /** Called first thing in DheuApp.onCreate. */
    fun install(context: Context) {
        val dir = dir(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(dir, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(dir: File, thread: Thread, error: Throwable) {
        dir.mkdirs()
        if ((dir.listFiles()?.size ?: 0) >= MAX_WAITING) return
        val report = Report(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})".take(40),
            device = "${Build.MANUFACTURER} ${Build.MODEL}".take(120),
            thread = thread.name.take(120),
            exception = error.toString().take(1000).ifEmpty { error.javaClass.name },
            stack = error.stackTraceToString().take(STACK_MAX),
            happenedAt = Instant.now().toString(),
        )
        File(dir, "${System.currentTimeMillis()}.json").writeText(Backend.json.encodeToString(report))
    }

    /**
     * Sends what earlier runs saved, oldest first. A file goes once it's in,
     * or once the server has refused it for good; if the phone is offline, or
     * the table isn't there yet, it waits for the next launch.
     */
    suspend fun sendWaiting(context: Context, me: String) = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles()?.sortedBy { it.name }.orEmpty()
        for (file in files) {
            val report = runCatching { Backend.json.decodeFromString(Report.serializer(), file.readText()) }.getOrNull()
            if (report == null) {
                file.delete()
                continue
            }
            try {
                Backend.client.from("app_crashes").insert(report.copy(userId = me))
                file.delete()
            } catch (e: PostgrestRestException) {
                if (e.code?.startsWith("PGRST2") == true) break // no such table (yet): try next time
                file.delete()
            } catch (e: Exception) {
                break // offline: try next time
            }
        }
    }
}
