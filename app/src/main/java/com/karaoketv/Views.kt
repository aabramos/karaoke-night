package com.karaoketv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

val PARTY_COLORS = intArrayOf(
    0xFFFF3DA5.toInt(), 0xFF2EE6FF.toInt(), 0xFFFFD23F.toInt(),
    0xFF7CFF6B.toInt(), 0xFFB36BFF.toInt(), 0xFFFF8A3D.toInt()
)

fun colorFor(name: String): Int = PARTY_COLORS[abs(name.lowercase().hashCode() % PARTY_COLORS.size)]

/** Turns a TextView into a round avatar with the singer's initial in their colour. */
fun TextView.avatar(name: String) {
    text = name.trim().take(1).uppercase()
    background = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(colorFor(name))
    }
}

/**
 * Animated nightclub stage behind the menus: sweeping spotlight beams, a glowing floor and
 * twinkling lights. Pure Canvas drawing at ~30 fps, no bitmaps, and it stops completely
 * while a video is playing.
 */
class StageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val bgPaint = Paint()
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPath = Path()
    private val beamShaders = arrayOfNulls<Shader>(3)
    private val beamColors = intArrayOf(0xFFFF3DA5.toInt(), 0xFF2EE6FF.toInt(), 0xFFB36BFF.toInt())
    private val origins = floatArrayOf(0.18f, 0.5f, 0.82f)   // allocated once, not every frame
    private val dots = Array(36) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.75f, Random.nextFloat() * 6.28f, 1f + Random.nextFloat() * 2f) }
    private var start = System.nanoTime()

    var animating = true
        set(v) {
            field = v
            if (v) invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF1B0B33.toInt(), 0xFF0E0620.toInt(), 0xFF07030F.toInt()), null, Shader.TileMode.CLAMP)
        floorPaint.shader = RadialGradient(w / 2f, h * 1.05f, w * 0.6f,
            intArrayOf(0x66FF3DA5, 0x22B36BFF, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val len = h * 1.25f
        for (i in beamShaders.indices) {
            val c = beamColors[i]
            beamShaders[i] = LinearGradient(0f, 0f, 0f, len,
                intArrayOf((c and 0x00FFFFFF) or 0x55000000, (c and 0x00FFFFFF) or 0x14000000, c and 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        beamPath.reset()
        val spread = len * 0.22f
        beamPath.moveTo(-6f, 0f); beamPath.lineTo(6f, 0f)
        beamPath.lineTo(spread, len); beamPath.lineTo(-spread, len); beamPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val t = (System.nanoTime() - start) / 1e9f

        // Spotlight beams from the top, sweeping slowly
        for (i in 0 until 3) {
            val sweep = sin(t * (0.35f + i * 0.11f) + i * 2.1f) * 28f
            canvas.save()
            canvas.translate(w * origins[i], -10f)
            canvas.rotate(sweep + (i - 1) * 12f)
            beamPaint.shader = beamShaders[i]
            canvas.drawPath(beamPath, beamPaint)
            canvas.restore()
        }

        canvas.drawRect(0f, h * 0.45f, w, h, floorPaint)

        // Twinkling lights
        for (d in dots) {
            val a = (0.5f + 0.5f * sin(t * d[3] + d[2]))
            dotPaint.color = Color.argb((a * 170).toInt(), 255, 255, 255)
            canvas.drawCircle(d[0] * w, d[1] * h, 1.2f + a * 1.8f, dotPaint)
        }

        if (animating && isShown) postInvalidateDelayed(33)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && animating) invalidate()
    }
}

/** Confetti burst drawn on a Canvas (capped number of pieces for low-end devices). */
class ConfettiView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Piece(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, val vrot: Float, val w: Float, val h: Float, val color: Int, val wobble: Float
    )

    private val pieces = ArrayList<Piece>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastFrame = 0L
    private var running = false

    fun burst(count: Int = 200) {
        if (width == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        val n = min(count, 320 - pieces.size)
        repeat(max(0, n)) { i ->
            val fromTop = i % 3 == 0
            val x: Float; val y: Float; val angle: Float; val speed: Float
            if (fromTop) {
                x = Random.nextFloat() * w; y = -20f; angle = (Math.PI / 2).toFloat(); speed = Random.nextFloat() * 150f * d
            } else {
                val left = i % 3 == 1
                x = if (left) 0f else w; y = h
                angle = if (left) (-Math.PI / 3 + Random.nextDouble(-0.35, 0.35)).toFloat()
                        else (-2 * Math.PI / 3 + Random.nextDouble(-0.35, 0.35)).toFloat()
                speed = (900f + Random.nextFloat() * 700f) * d / 2f
            }
            pieces += Piece(
                x, y, cos(angle) * speed, sin(angle) * speed,
                Random.nextFloat() * 360f, Random.nextFloat() * 600f - 300f,
                (6 + Random.nextFloat() * 6) * d, (10 + Random.nextFloat() * 8) * d,
                PARTY_COLORS[Random.nextInt(PARTY_COLORS.size)], Random.nextFloat() * 6f
            )
        }
        if (!running) {
            running = true
            lastFrame = System.nanoTime()
            postInvalidateOnAnimation()
        }
    }

    fun clear() {
        pieces.clear()
        running = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        val d = resources.displayMetrics.density
        val gravity = 260f * d
        val iter = pieces.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.vy += gravity * dt
            p.vx *= 0.99f; p.vy *= 0.99f
            p.x += (p.vx + sin(p.y / 40f + p.wobble) * 30f * d) * dt
            p.y += p.vy * dt
            p.rot += p.vrot * dt
            if (p.y > height + 60) { iter.remove(); continue }
            paint.color = p.color
            canvas.save()
            canvas.rotate(p.rot, p.x, p.y)
            val flip = abs(cos(Math.toRadians(p.rot.toDouble() * 2)).toFloat())
            canvas.drawRect(p.x - p.w / 2 * flip, p.y - p.h / 2, p.x + p.w / 2 * flip, p.y + p.h / 2, paint)
            canvas.restore()
        }
        if (pieces.isEmpty()) {
            running = false
        } else {
            postInvalidateOnAnimation()
        }
    }
}

/** Draws a QR code (white card, dark modules). */
class QrView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var qr: QrCode? = null
    private val dark = Paint().apply { color = 0xFF0B0614.toInt() }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    fun setText(text: String?) {
        qr = text?.let { QrCode.encode(it) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val q = qr ?: return
        val size = min(width, height).toFloat()
        val quiet = 3
        val cell = size / (q.size + quiet * 2)
        val r = cell * 1.5f
        rect.set(0f, 0f, size, size)
        canvas.drawRoundRect(rect, r, r, light)
        for (y in 0 until q.size) {
            for (x in 0 until q.size) {
                if (q.get(x, y)) {
                    val left = (x + quiet) * cell
                    val top = (y + quiet) * cell
                    canvas.drawRect(left, top, left + cell + 0.5f, top + cell + 0.5f, dark)
                }
            }
        }
    }
}

/**
 * Makes the whole interface scale with the screen. Every layout is designed on a 960 x 540 "dp"
 * canvas (the Android TV standard); here the density is chosen so that canvas fills the TV exactly,
 * whatever its resolution (720p, 1080p, 4K) or size. Text stays proportional too, so it reads the
 * same from the couch on a 43" or a 75" TV. Odd aspect ratios are letter-fitted, never cut off.
 */
object TvScale {
    private const val BASE_W = 960f
    private const val BASE_H = 540f

    fun configuration(base: android.content.Context): android.content.res.Configuration {
        // The app runs full screen (system bars hidden), so size the canvas from the whole physical
        // screen. The plain display metrics leave out a phone's navigation bar, and edge-to-edge
        // drawing would then push the right edge of the interface under it.
        val dm = base.resources.displayMetrics
        val dmW = max(dm.widthPixels, dm.heightPixels)
        val dmH = min(dm.widthPixels, dm.heightPixels)
        val real = android.graphics.Point()
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // The largest window this app can have, in the app's own pixels
                val b = base.getSystemService(android.view.WindowManager::class.java)!!.maximumWindowMetrics.bounds
                real.set(b.width(), b.height())
            } else {
                val dmgr = base.getSystemService(android.hardware.display.DisplayManager::class.java)
                @Suppress("DEPRECATION")
                dmgr?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealSize(real)
            }
        }
        val rw = max(real.x, real.y)
        val rh = min(real.x, real.y)
        // Use the full size only when it's the display metrics plus system bars. A much bigger value
        // means the panel's physical resolution (a TV sending 4K while the interface runs at 1080p).
        val plausible = rw in dmW..(dmW * 13 / 10) && rh in dmH..(dmH * 13 / 10)
        val w = (if (plausible) rw else dmW).toFloat()
        val h = (if (plausible) rh else dmH).toFloat()
        val density = min(w / BASE_W, h / BASE_H).coerceAtLeast(0.75f)
        return android.content.res.Configuration(base.resources.configuration).apply {
            densityDpi = (density * 160f).toInt()
            screenWidthDp = (w / density).toInt()
            screenHeightDp = (h / density).toInt()
            smallestScreenWidthDp = screenHeightDp
            // Respect a bigger system font a little, but not so much that layouts overflow
            fontScale = fontScale.coerceIn(0.9f, 1.1f)
        }
    }
}

/**
 * Emoji reactions floating up both sides of the score screen, like a live stream's audience.
 * Each emoji is drawn once into a small bitmap, so a frame is just a few bitmap draws.
 * Stops and frees everything by itself when the score screen is hidden.
 */
class ReactionsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Bubble(
        val x: Float, var y: Float, val vy: Float, val sway: Float, val phase: Float,
        val size: Float, val bmp: Bitmap, var age: Float, val life: Float
    )

    private val bubbles = ArrayList<Bubble>()
    private val cache = HashMap<String, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private var emojis: List<String> = emptyList()
    private var perSecond = 0f
    private var owed = 0f
    private var lastFrame = 0L
    private var running = false
    private var left = true

    /** Starts a stream of [emojis], about [rate] a second (replaces any earlier stream). */
    fun start(emojis: List<String>, rate: Float) {
        bubbles.clear()
        this.emojis = emojis.filter { it.isNotEmpty() }
        perSecond = if (this.emojis.isEmpty()) 0f else rate
        owed = 1.5f                          // a couple straight away
        if (!running) {
            running = true
            lastFrame = System.nanoTime()
            postInvalidateOnAnimation()
        }
    }

    fun clear() {
        bubbles.clear()
        perSecond = 0f
        running = false
        invalidate()
    }

    // Hidden (the score screen closed): stop and free the emoji pictures (about 1 MB on a 4K TV)
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) freeAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        freeAll()
    }

    private fun freeAll() {
        clear()
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    private fun bitmapFor(emoji: String): Bitmap = cache.getOrPut(emoji) {
        val side = (44 * resources.displayMetrics.density).toInt().coerceIn(16, 200)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = side * 0.78f; textAlign = Paint.Align.CENTER }
        val fm = p.fontMetrics
        Canvas(bmp).drawText(emoji, side / 2f, side / 2f - (fm.ascent + fm.descent) / 2f, p)
        bmp
    }

    private fun spawn() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f || bubbles.size >= MAX) return
        val d = resources.displayMetrics.density
        val size = (30 + Random.nextFloat() * 22) * d
        // Alternate sides, so the score in the middle stays the centre of attention
        left = !left
        val edge = w * (0.015f + Random.nextFloat() * 0.11f)
        bubbles += Bubble(
            x = if (left) edge else w - edge, y = h + size,
            vy = (70 + Random.nextFloat() * 60) * d, sway = (8 + Random.nextFloat() * 14) * d,
            phase = Random.nextFloat() * 6.28f, size = size, bmp = bitmapFor(emojis[Random.nextInt(emojis.size)]),
            age = 0f, life = 3.6f + Random.nextFloat() * 1.6f
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        owed += perSecond * dt
        while (owed >= 1f) { owed -= 1f; spawn() }
        val iter = bubbles.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            b.age += dt
            b.y -= b.vy * dt
            if (b.age >= b.life || b.y < -b.size) { iter.remove(); continue }
            val t = b.age / b.life
            // pop in, float, fade out near the top of its path
            val grow = min(1f, b.age / 0.25f)
            paint.alpha = (255 * min(1f, (1f - t) / 0.35f)).toInt().coerceIn(0, 255)
            val s = b.size * (0.6f + 0.4f * grow)
            val x = b.x + sin(b.phase + b.age * 2.4f) * b.sway
            box.set(x - s / 2, b.y - s / 2, x + s / 2, b.y + s / 2)
            canvas.drawBitmap(b.bmp, null, box, paint)
        }
        if (perSecond > 0f || bubbles.isNotEmpty()) postInvalidateOnAnimation() else running = false
    }

    private companion object { const val MAX = 40 }
}
