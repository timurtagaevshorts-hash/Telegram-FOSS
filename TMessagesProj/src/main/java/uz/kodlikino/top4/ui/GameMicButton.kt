package uz.kodlikino.top4.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import org.telegram.messenger.R
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin

/**
 * "Bosib gapir" tugmasi (push-to-talk).
 *
 * Bosib turganda mikrofon yozadi, qo‘yib yuborilganda klip yuboriladi. Holatlar
 * vizual ajralib turadi: tinch (ko‘k), yozuv (qizil puls + sekundomer), yuklash
 * (aylanuvchi yoy), ijro (ko‘k to‘lqin).
 */
class GameMicButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, RECORDING, BUSY, PLAYING }

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: ((Long) -> Unit)? = null
    var onHoldCancel: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val action = context.getColor(R.color.yuz_action)
    private val danger = context.getColor(R.color.yuz_danger)
    private val surface = context.getColor(R.color.yuz_surface_raised)
    private val stroke = context.getColor(R.color.yuz_stroke_strong)
    private val onAction = context.getColor(R.color.yuz_on_action)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 11f * density
    }

    private var mode = Mode.IDLE
    private var elapsed = 0L
    private var phase = 0f
    private var spin = 0f
    private var pressed = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = (it.animatedValue as Float + 0f) % 1f
            spin = (spin + 0.06f) % 1f
            invalidate()
        }
    }

    fun setMode(next: Mode) {
        if (mode == next) return
        mode = next
        if (next == Mode.RECORDING || next == Mode.BUSY) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            phase = 0f
            invalidate()
        }
    }

    fun setElapsed(ms: Long) {
        elapsed = ms
        if (mode == Mode.RECORDING) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(w.coerceAtMost((68 * density).toInt()), if (h > 0) h else w)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 6f * density

        if (mode == Mode.RECORDING) {
            // Ikki puls to‘lqini
            for (k in 0..1) {
                val p = (phase + k * 0.5f) % 1f
                pulsePaint.color = withAlpha(danger, (150 * (1f - p)).toInt())
                pulsePaint.strokeWidth = 2f * density
                canvas.drawCircle(cx, cy, radius * (1f + p * 0.45f), pulsePaint)
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = when (mode) {
            Mode.RECORDING -> danger
            Mode.BUSY -> surface
            Mode.PLAYING -> action
            Mode.IDLE -> if (pressed) action else surface
        }
        canvas.drawCircle(cx, cy, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = when (mode) {
            Mode.RECORDING -> withAlpha(danger, 200)
            Mode.IDLE -> if (pressed) withAlpha(action, 220) else stroke
            else -> withAlpha(action, 160)
        }
        canvas.drawCircle(cx, cy, radius, paint)

        when (mode) {
            Mode.BUSY -> drawSpinner(canvas, cx, cy, radius)
            Mode.PLAYING -> drawWave(canvas, cx, cy, radius)
            else -> drawMic(canvas, cx, cy, radius)
        }

        if (mode == Mode.RECORDING) {
            val seconds = elapsed / 1000f
            textPaint.color = 0xFFFFFFFF.toInt()
            val label = String.format(Locale.US, "%.1f", seconds)
            canvas.drawText(label, cx, cy + radius * 0.92f, textPaint)
        }
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val iconColor = when (mode) {
            Mode.RECORDING -> 0xFFFFFFFF.toInt()
            Mode.IDLE -> if (pressed) onAction else action
            else -> onAction
        }
        paint.style = Paint.Style.FILL
        paint.color = iconColor
        val capsuleW = radius * 0.30f
        val capsuleH = radius * 0.58f
        val top = cy - radius * 0.62f
        val rect = RectF(cx - capsuleW / 2f, top, cx + capsuleW / 2f, top + capsuleH)
        canvas.drawRoundRect(rect, capsuleW / 2f, capsuleW / 2f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.strokeCap = Paint.Cap.ROUND
        val cradle = RectF(
            cx - radius * 0.34f,
            top + capsuleH * 0.42f,
            cx + radius * 0.34f,
            top + capsuleH * 0.42f + radius * 0.52f
        )
        canvas.drawArc(cradle, 0f, 180f, false, paint)
        canvas.drawLine(cx, cradle.bottom, cx, cradle.bottom + radius * 0.18f, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawWave(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = onAction
        val bars = 5
        val gap = radius * 0.26f
        val startX = cx - gap * (bars - 1) / 2f
        for (i in 0 until bars) {
            val strength = 0.35f + 0.65f * ((sin((phase * 6.28f) + i * 0.9f) + 1f) / 2f)
            val h = radius * 0.30f * strength + radius * 0.12f
            val x = startX + gap * i
            val rect = RectF(x - 2f * density, cy - h, x + 2f * density, cy + h)
            canvas.drawRoundRect(rect, 2f * density, 2f * density, paint)
        }
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = withAlpha(action, 60)
        canvas.drawCircle(cx, cy, radius * 0.62f, paint)
        paint.color = action
        val start = spin * 360f
        canvas.drawArc(
            RectF(cx - radius * 0.62f, cy - radius * 0.62f, cx + radius * 0.62f, cy + radius * 0.62f),
            start, 96f, false, paint
        )
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.BUSY || mode == Mode.PLAYING) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                setMode(Mode.RECORDING)
                elapsed = 0L
                onHoldStart?.invoke()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                if (mode == Mode.RECORDING) {
                    val inside = event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height
                    if (inside) onHoldEnd?.invoke(elapsed) else onHoldCancel?.invoke()
                    setMode(Mode.IDLE)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                if (mode == Mode.RECORDING) {
                    onHoldCancel?.invoke()
                    setMode(Mode.IDLE)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    init {
        isClickable = true
        setWillNotDraw(false)
        contentDescription = context.getString(R.string.games_voice_hint)
    }
}
