package uz.kodlikino.top4.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * YuzGram #98: markazdagi yuklanish animatsiyasi — aylanuvchi neon yoy +
 * nafas oluvchi halqa + ekvalayzer tayoqchalari. Musiqa qidirilganda,
 * ommabop xitlar yuklanayotganda va qo'shiq buffering holatida ishlatiladi.
 *
 * Faqat ko'rinib turganda animatsiya ishlaydi — fon rejimida batareya
 * sarflanmaydi.
 */
class YuzPulseLoader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private var sweep: SweepGradient? = null
    private var t = 0f

    private val spinner = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedFraction
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = dp(88f).toInt()
        setMeasuredDimension(resolveSize(want, widthMeasureSpec), resolveSize(want, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        sweep = if (w > 0 && h > 0) {
            SweepGradient(cx, cy, intArrayOf(0x002AABEE, 0xFF2AABEE.toInt(), 0xFF5B6CF9.toInt(), 0x005B6CF9), null)
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val unit = minOf(w, h) / 2f
        val breath = abs(sin((t * 2.0 * PI).toFloat()))

        // 1) nafas oluvchi halqa — kengayib xiralashadi
        stroke.color = fade(0x662AABEE, 1f - breath)
        stroke.strokeWidth = dp(1.4f)
        canvas.drawCircle(cx, cy, unit * (0.58f + 0.34f * breath), stroke)

        // 2) ekvalayzer tayoqchalari
        fill.color = 0xFF2AABEE.toInt()
        val bars = 5
        val barW = dp(3.2f)
        val gap = dp(2.4f)
        var x = cx - (bars * barW + (bars - 1) * gap) / 2f
        val baseY = cy + unit * 0.12f
        for (i in 0 until bars) {
            val ph = (t * 2f + i * 0.19f) % 1f
            val hh = unit * (0.16f + 0.30f * abs(sin((ph * PI * 2f).toFloat())))
            rect.set(x, baseY - hh, x + barW, baseY)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, fill)
            x += barW + gap
        }

        // 3) aylanuvchi neon yoy (shaffof boshlanish → to'q oxir)
        val sh = sweep
        if (sh != null) {
            shaderMatrix.setRotate(t * 360f, cx, cy)
            sh.setLocalMatrix(shaderMatrix)
            arc.strokeWidth = dp(3f)
            arc.shader = sh
            val r = unit * 0.82f
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, 0f, 300f, false, arc)
            arc.shader = null
        }
    }

    /** Base kolorning alpha'sini [keep] ulushiga qadar pasaytiradi. */
    private fun fade(color: Int, keep: Float): Int {
        val a = (((color ushr 24) and 0xFF) * keep.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (!spinner.isRunning) spinner.start()
    }

    private fun stop() {
        if (spinner.isRunning) spinner.cancel()
    }
}
