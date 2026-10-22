package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * #134: Gemini uslubidagi jonli orb — AI yordamchi ekranining markazi.
 * To'rtta rangli "bulut" sekin aylanadi; mikrofon darajasi (RMS) bilan nafas
 * oladi. Rejim (tinch/tinglayapti/o'ylayapti/gapiryapti) tezlik va yorqinlikni
 * o'zgartiradi. Bitmap sprite + tint: har kadrda gradient yaratmaydi.
 */
class YuzAiOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 0..1 — mikrofon RMS darajasi; silliqlanib radiusga ta'sir qiladi. */
    var level: Float = 0f
        set(value) {
            targetLevel = value.coerceIn(0f, 1f)
        }

    private var targetLevel = 0f
    private var smoothLevel = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private var ticking = false

    private val sprite: Bitmap = makeSprite()
    private val blobs: List<Paint> = BLOB_COLORS.map { color ->
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }
    private val halo: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.parseColor("#2AABEE"), PorterDuff.Mode.SRC_IN)
    }
    private val core: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val frame = object : Runnable {
        override fun run() {
            if (!ticking || !isAttachedToWindow) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else ((now - lastFrame).coerceIn(0L, 64L) / 1000f)
            lastFrame = now
            phase += dt * speedFor(mode)
            smoothLevel += (targetLevel - smoothLevel) * min(1f, dt * 9f)
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicking()
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startTicking() else stopTicking()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        if (visibility == VISIBLE) startTicking() else stopTicking()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastFrame = 0L
        postOnAnimation(frame)
    }

    private fun stopTicking() {
        ticking = false
        removeCallbacks(frame)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.92f
        val lvl = smoothLevel

        val pulse = if (mode == Mode.SPEAKING) 0.055f * sin(phase * 5.2f) else 0f
        val breath = 1f + 0.03f * sin(phase * 1.15f) + pulse

        // Tashqi yumshoq halo
        halo.alpha = (30 + (lvl * 34).toInt()).coerceAtMost(74)
        drawSprite(canvas, halo, cx, cy, r * (1.22f + 0.12f * lvl) * breath / HALF)

        // Rangli bulutlar — turli orbita va tezliklarda aylanadi
        for (i in blobs.indices) {
            val paint = blobs[i]
            val dir = if (i % 2 == 0) 1f else -1f
            val angle = phase * dir * (0.72f + i * 0.34f) + i * 1.9f
            val orbit = r * (
                0.12f + 0.05f * i +
                    0.07f * sin(phase * 0.9f + i * 2.0f) +
                    0.10f * lvl
                )
            val bx = cx + cos(angle) * orbit
            val by = cy + sin(angle) * orbit * 0.9f
            val base = 0.94f - i * 0.11f
            val size = r * (
                base +
                    0.07f * sin(phase * 1.2f + i) +
                    0.20f * lvl
                ) * breath / HALF
            paint.alpha = (alphaFor(mode) + (lvl * 26).toInt()).coerceAtMost(238)
            drawSprite(canvas, paint, bx, by, size)
        }

        // Yadro — markazdagi issiq oq nur
        core.alpha = (76 + (lvl * 62).toInt()).coerceAtMost(168)
        drawSprite(canvas, core, cx, cy, r * (0.44f + 0.09f * lvl) * breath / HALF)
    }

    private fun drawSprite(canvas: Canvas, paint: Paint, cx: Float, cy: Float, scale: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(sprite, null, SPRITE_RECT, paint)
        canvas.restore()
    }

    private fun speedFor(mode: Mode): Float = when (mode) {
        Mode.IDLE -> 0.5f
        Mode.LISTENING -> 1.35f
        Mode.THINKING -> 2.5f
        Mode.SPEAKING -> 1.9f
    }

    private fun alphaFor(mode: Mode): Int = when (mode) {
        Mode.IDLE -> 118
        Mode.LISTENING -> 198
        Mode.THINKING -> 166
        Mode.SPEAKING -> 184
    }

    private fun makeSprite(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val shader = RadialGradient(
            r, r, r,
            intArrayOf(
                Color.argb(255, 255, 255, 255),
                Color.argb(168, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        c.drawCircle(r, r, r, p)
        return bmp
    }

    companion object {
        private const val HALF = 128f
        private val SPRITE_RECT = RectF(-HALF, -HALF, HALF, HALF)
        private val BLOB_COLORS = intArrayOf(
            0xFF2AABEE.toInt(), // brend ko'k
            0xFF7B5CFF.toInt(), // binafsha
            0xFF00D5FF.toInt(), // moviy
            0xFFFF5C8A.toInt()  // pushti
        )
    }
}
