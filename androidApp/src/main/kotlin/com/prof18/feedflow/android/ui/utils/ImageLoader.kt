package com.prof18.feedflow.android.ui.utils

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.network.httpHeaders
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.util.DebugLogger
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

private val imageAcceptHeaderInterceptor = Interceptor { chain ->
    val request = chain.request
    if (request.httpHeaders["Accept"] != null) {
        chain.proceed()
    } else {
        val headers = request.httpHeaders.newBuilder()
            .apply { this["Accept"] = "image/*" }
            .build()
        chain.withRequest(
            request.newBuilder()
                .httpHeaders(headers)
                .build(),
        ).proceed()
    }
}

fun coilImageLoader(
    context: PlatformContext,
    debug: Boolean,
    // fork: client with the tailnet setup, so feed icons on the FreshRSS host load through the proxy
    okHttpClient: OkHttpClient? = null,
    // fork (Phase 4a): keeps requests off the network while images are held back on mobile connections
    extraInterceptor: Interceptor? = null,
): ImageLoader {
    return ImageLoader.Builder(context)
        .components {
            if (okHttpClient != null) add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            add(imageAcceptHeaderInterceptor)
            if (extraInterceptor != null) add(extraInterceptor)
            add(SvgDecoder.Factory())
        }
        .memoryCache {
            MemoryCache.Builder()
                // Set the max size to 25% of the app's available memory.
                .maxSizePercent(context, percent = 0.25)
                .build()
        }
        .diskCache {
            newDiskCache(context)
        }
        .crossfade(true)
        .apply {
            if (debug) {
                logger(DebugLogger())
            }
        }
        .build()
}

internal fun newDiskCache(context: PlatformContext): DiskCache {
    return DiskCache.Builder()
        .directory(context.cacheDir.absolutePath.toPath().resolve("images_cache"))
        .build()
}
