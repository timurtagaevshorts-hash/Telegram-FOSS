package uz.kodlikino.top4.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max

/**
 * YuzGram #98: qidiruv/yuklanish skeleti — kelgusi qatorlarning o'rnini
 * bosuvchi kulrang bloklar ustidan chapdan o'ngga yorug'lik oqib o'tadi.
 * Matn "Yuklanmoqda…" o'rniga ko'zga tabiiyroq: nima kelishi aniq, faqat
 * hali kelmagan.
 */
class YuzShimmer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private var band: LinearGradient? = null
    private var t = 0f

    /** Ko'rinadigan skelet qatorlari soni. */
    var rowCount = 6

    private val rowH get() = dp(72f)

    private val sweeper = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1250L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedFraction
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (rowCount * rowH).toInt()
        setMeasuredDimension(
            resolveSize(dp(240f).toInt(), widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bw = max(dp(120f), w * 0.45f)
        band = if (w > 0) {
            LinearGradient(
                0f, 0f, bw, 0f,
                intArrayOf(0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) return
        val pad = dp(16f)
        val cover = dp(48f)
        val bw = max(dp(120f), w * 0.45f)
        val sh = band
        if (sh != null) {
            shaderMatrix.setTranslate(-bw + t * (w + bw), 0f)
            sh.setLocalMatrix(shaderMatrix)
            glow.shader = sh
        }
        for (i in 0 until rowCount) {
            val top = i * rowH + (rowH - cover) / 2f
            // muqova
            drawRound(canvas, pad, top, pad + cover, top + cover, dp(10f))
            // sarlavha + ijrochi qatorlari
            val tx = pad + cover + dp(12f)
            drawRound(canvas, tx, top + dp(6f), tx + (w - tx - pad) * 0.78f, top + dp(19f), dp(6f))
            drawRound(canvas, tx, top + dp(27f), tx + (w - tx - pad) * 0.42f, top + dp(37f), dp(5f))
            // o'ngdagi davomiylik belgisi
            drawRound(canvas, w - pad - dp(34f), top + dp(15f), w - pad, top + dp(26f), dp(5f))
        }
        glow.shader = null
    }

    private fun drawRound(canvas: Canvas, l: Float, tp: Float, r: Float, bt: Float, rad: Float) {
        rect.set(l, tp, r, bt)
        base.color = 0x14FFFFFF
        canvas.drawRoundRect(rect, rad, rad, base)
        if (glow.shader != null) canvas.drawRoundRect(rect, rad, rad, glow)
    }

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
        if (!sweeper.isRunning) sweeper.start()
    }

    private fun stop() {
        if (sweeper.isRunning) sweeper.cancel()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
