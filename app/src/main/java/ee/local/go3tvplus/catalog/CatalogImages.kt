package ee.local.go3tvplus.catalog

import android.content.Context
import coil3.ImageLoader
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore

fun catalogImageLoader(context: Context): ImageLoader {
    // Interceptor also bounds synchronous Coil calls; OkHttp Dispatcher alone only bounds enqueue().
    val permits = Semaphore(2, true)
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        permits.acquire()
        try {
            val response = chain.proceed(chain.request())
            val body = response.body
            val released = AtomicBoolean(false)
            val source = object : ForwardingSource(body.source()) {
                override fun close() {
                    try { super.close() } finally { if (released.compareAndSet(false, true)) permits.release() }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Throwable) { permits.release(); throw error }
    }.build()
    return ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("catalog_images").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .crossfade(250)
        .build()
}
