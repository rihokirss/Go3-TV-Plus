package ee.local.go3tvplus.catalog

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

private val imageWidth = Regex("([?&])dstw=[^&]+")
private val imageHeight = Regex("([?&])dsth=[^&]+")

internal fun catalogPosterRequest(context: Context, url: String?, width: Int, height: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(url?.replace(imageWidth, "$1dstw=$width")?.replace(imageHeight, "$1dsth=$height"))
        .size(width, height)
        .crossfade(120)
        .build()

/** A small, cancellable window beyond the active viewport; visible images keep priority. */
@Composable
internal fun PrefetchCatalogPosters(items: List<CatalogTitle>, model: CatalogViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val width = with(density) { 116.dp.roundToPx() }
    val height = with(density) { 157.dp.roundToPx() }
    val urls = remember(items) { items.mapNotNull { it.poster }.distinct().take(2) }
    LaunchedEffect(urls, width, height) {
        delay(200)
        val loader = SingletonImageLoader.get(context)
        for (url in urls) {
            while (!model.imagePrefetchAllowed()) delay(500)
            loader.execute(catalogPosterRequest(context, url, width, height))
        }
    }
}

fun catalogImageLoader(context: Context): ImageLoader {
    // Interceptor also bounds synchronous Coil calls; OkHttp Dispatcher alone only bounds enqueue().
    val permits = Semaphore(2, true)
    val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).addInterceptor { chain ->
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
