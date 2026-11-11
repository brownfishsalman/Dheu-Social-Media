package com.salmansaadiq.dheu.data

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.toUri
import kotlin.time.Duration.Companion.hours

/**
 * A file in one of Dheu's Storage buckets, as an image model for Coil.
 *
 * It loads through a short-lived signed link made with the member's own
 * session, so the photo access rules (0025) decide who sees what, the same
 * way the website's /media route does. The buckets are still public for older
 * app versions; once they're switched to private, this is the only way in.
 * Photos are cached on disk by their path (they're never rewritten), so one
 * seen before needs no new link.
 */
data class StorageImage(val bucket: String, val path: String) {
    val cacheKey get() = "storage:$bucket/$path"
}

/** An image model for [path] in [bucket], or null when there's no file. */
fun storageImage(bucket: String, path: String?): StorageImage? = path?.let { StorageImage(bucket, it) }

object SignedLinks {
    private val LIFETIME = 1.hours

    /** A link to the file that works for about an hour (voice messages, images). */
    suspend fun of(bucket: String, path: String): String = Backend.storage.from(bucket).createSignedUrl(path, LIFETIME)
}

class StorageImageKeyer : Keyer<StorageImage> {
    override fun key(data: StorageImage, options: Options): String = data.cacheKey
}

class StorageImageFetcher(
    private val data: StorageImage,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val key = data.cacheKey
        // Seen before: straight from the disk cache, no link needed.
        val diskCache = imageLoader.diskCache
        if (diskCache != null && options.diskCachePolicy.readEnabled) {
            diskCache.openSnapshot(key)?.let { snapshot ->
                return SourceFetchResult(
                    source = ImageSource(file = snapshot.data, fileSystem = diskCache.fileSystem, diskCacheKey = key, closeable = snapshot),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }
        // Otherwise a signed link, fetched by the usual network fetcher and kept on disk under the path.
        val url = SignedLinks.of(data.bucket, data.path)
        val network = imageLoader.components.newFetcher(url.toUri(), options.copy(diskCacheKey = key), imageLoader)?.first
            ?: error("No network fetcher for $url")
        return network.fetch()
    }

    class Factory : Fetcher.Factory<StorageImage> {
        override fun create(data: StorageImage, options: Options, imageLoader: ImageLoader): Fetcher =
            StorageImageFetcher(data, options, imageLoader)
    }
}
