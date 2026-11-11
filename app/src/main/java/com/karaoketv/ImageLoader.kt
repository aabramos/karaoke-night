package com.karaoketv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Thumbnail loader sized for a low-memory TV box:
 *  - small 320x180 thumbnails decoded as RGB_565 (half the memory of ARGB)
 *  - a capped memory cache, cleared when Android asks for memory
 *  - newest-first loading, so what's on screen now loads before older requests
 *  - pending work only holds WeakReferences to views, so it can never keep a screen alive
 */
object ImageLoader {
    private val cacheBytes = (Runtime.getRuntime().maxMemory() / 12).toInt().coerceIn(4 shl 20, 10 shl 20)
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val queue = LinkedBlockingDeque<Runnable>()
    private val pool = ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, queue).apply {
        allowCoreThreadTimeOut(true)   // no idle threads left behind
    }
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, into: ImageView) {
        into.tag = url
        val hit = cache.get(url)
        if (hit != null) { into.setImageBitmap(hit); return }
        into.setImageDrawable(null)

        val ref = WeakReference(into)
        val task = Runnable {
            if (ref.get()?.tag != url) return@Runnable             // view gone or reused
            val bmp = cache.get(url) ?: fetch(url)?.also { cache.put(url, it) } ?: return@Runnable
            main.post {
                val v = ref.get()
                if (v != null && v.tag == url) v.setImageBitmap(bmp)
            }
        }
        while (queue.size > 40) queue.pollLast()                  // drop the oldest requests
        if (pool.poolSize < pool.corePoolSize) {
            pool.execute(task)
        } else {
            queue.offerFirst(task)
            // An idle worker may have timed out just now: make sure someone picks the task up
            pool.prestartCoreThread()
        }
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            c.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Forget pending downloads (e.g. when the screen is destroyed). */
    fun cancelAll() = queue.clear()

    fun clear() {
        queue.clear()
        cache.evictAll()
    }
}
