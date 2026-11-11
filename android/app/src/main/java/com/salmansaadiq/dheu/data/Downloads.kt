package com.salmansaadiq.dheu.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves my own post's photos, full size, to the phone's gallery
 * (Pictures/Dheu) before the post expires. Android 10 and newer need no
 * permission for this; Android 9 needs WRITE_EXTERNAL_STORAGE, which the
 * screen asks for first.
 */
object Downloads {
    val needsPermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Returns how many photos were saved. */
    suspend fun savePost(context: Context, post: Post): Int = withContext(Dispatchers.IO) {
        val day = post.createdAt.take(10)
        var saved = 0
        post.images.sortedBy { it.position }.forEachIndexed { i, image ->
            // With my own session, so it works once the buckets are private too (0025).
            val bytes = Backend.storage.from(Buckets.POSTS).downloadAuthenticated(image.path)
            write(context, "dheu-$day-${i + 1}-${post.id.take(6)}.jpg", bytes)
            saved++
        }
        saved
    }

    private fun write(context: Context, name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Dheu")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create the file.")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't write the file.")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Dheu").apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        }
    }
}
