package com.salmansaadiq.dheu

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.salmansaadiq.dheu.data.CrashReports
import com.salmansaadiq.dheu.data.LiveConnection
import com.salmansaadiq.dheu.data.Push
import com.salmansaadiq.dheu.data.StorageImageFetcher
import com.salmansaadiq.dheu.data.StorageImageKeyer
import com.salmansaadiq.dheu.push.Notifications

class DheuApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is written down (data/CrashReports.kt).
        CrashReports.install(this)
        LiveConnection.install()
        Push.init(this)
        Notifications.createChannels(this)
    }

    // Photos sit at unique, never-rewritten paths, so a generous disk cache is safe.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Registered outright rather than found through ServiceLoader, which R8 can strip.
            .components {
                add(OkHttpNetworkFetcherFactory())
                // Storage files load through signed links, cached by path (data/StorageImage.kt).
                add(StorageImageKeyer())
                add(StorageImageFetcher.Factory())
            }
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .build()
}
