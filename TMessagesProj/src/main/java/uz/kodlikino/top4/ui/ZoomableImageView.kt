package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Fullscreen rasm ko'ruvchidagi rasm: barmoq bilan masshtab (pinch), ikki marta
 * bosishda yaqinlashtirish/uzoqlashtirish va yaqinlashtirilganda surgish.
 *
 * Nima uchun tayyor klass yo'q: bu fork (Telegram-FOSS) manba kodida
 * `GalleryImageView`/`PinchImageView` mavjud emas, tashqi kutubxona qo'shish esa
 * Material 1.6.1 pinini buzish xavfini beradi (AGP 7.0.3 resource merge oldin
 * 1.7.0/1.8.0'da `<macro>` tufayli sinadi). Shuning uchun standard
 * [ScaleType.MATRIX] + ikkita Android gesture detector'i kifoya.
 *
 * Xatti oldini olishdagi muhim nuqta: masshtablanmagan holida (fitScale) biz
 * touch'ni "yemaymiz" — aks holda parent ViewPager2 gorizontal surganini
 * bosa olmaslik edi. Yaqinlashtirilgandagina `requestDisallowInterceptTouchEvent`
 * so'raymiz va [onTouchEvent] `true` qaytaramiz.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val work = Matrix()
    private val values = FloatArray(9)

    /** Rasm ekranga sig'adigan eng kichik masshtab (fitCenter ekvivalenti). */
    private var fitScale = 1f

    /** Eng katta ruxsat etilgan masshtab — fitScale'dan 3 barobar katta. */
    private var maxScale = 3f

    /** Sig'ma holatidagi markazlashtirilgan ofsetlar (reset uchun kerak). */
    private var fitLeft = 0f
    private var fitTop = 0f

    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var target = currentScale * detector.scaleFactor
                target = target.coerceIn(fitScale, maxScale)
                applyScale(target, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val tapper = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > fitScale * 1.02f) resetFit() else applyScale(fitScale * 2.5f, e.x, e.y)
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    private val currentScale: Float
        get() {
            work.set(imageMatrix)
            work.getValues(values)
            return if (values[Matrix.MSCALE_X] <= 0f) fitScale else values[Matrix.MSCALE_X]
        }

    private val isZoomed: Boolean
        get() = currentScale > fitScale * 1.02f

    override fun setImageDrawable(d: Drawable?) {
        super.setImageDrawable(d)
        if (d != null) fitToBounds()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && drawable != null) fitToBounds()
    }

    /** Rasm to'liq ko'rinadigan qilib masshtablanadi va markazga qo'yiladi. */
    private fun fitToBounds() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) return
        val s = minOf(vw / dw, vh / dh)
        fitScale = s
        maxScale = s * 3f
        fitLeft = (vw - dw * s) / 2f
        fitTop = (vh - dh * s) / 2f
        work.reset()
        work.postScale(s, s)
        work.postTranslate(fitLeft, fitTop)
        imageMatrix = work
    }

    /** [pivot] atroda [target] masshtabgacha yaqinlashtiramiz, so'ng chegaralaymiz. */
    private fun applyScale(target: Float, pivotX: Float, pivotY: Float) {
        val s = currentScale
        if (s <= 0f) return
        val f = target / s
        work.set(imageMatrix)
        work.postScale(f, f, pivotX, pivotY)
        imageMatrix = work
        clampToBounds()
        disallowIntercept(isZoomed)
    }

    private fun resetFit() {
        fitToBounds()
        disallowIntercept(false)
    }

    /**
     * Yaqinlashtirilganda rasm chetlari ekran bo'shlig'iga surilib qolmasin:
     * kichikro'k o'lcham markazda, kattaro'gi esa chegaralari bilan tekislanadi.
     */
    private fun clampToBounds() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        work.set(imageMatrix)
        work.getValues(values)
        val s = values[Matrix.MSCALE_X]
        val tx = values[Matrix.MTRANS_X]
        val ty = values[Matrix.MTRANS_Y]
        val scaledW = d.intrinsicWidth * s
        val scaledH = d.intrinsicHeight * s
        var dx = 0f
        var dy = 0f
        if (scaledW <= vw) {
            dx = (vw - scaledW) / 2f - tx
        } else {
            if (tx > 0f) dx = -tx else if (tx + scaledW < vw) dx = vw - (tx + scaledW)
        }
        if (scaledH <= vh) {
            dy = (vh - scaledH) / 2f - ty
        } else {
            if (ty > 0f) dy = -ty else if (ty + scaledH < vh) dy = vh - (ty + scaledH)
        }
        if (dx != 0f || dy != 0f) {
            work.postTranslate(dx, dy)
            imageMatrix = work
        }
    }

    private fun disallowIntercept(disallow: Boolean) {
        try {
            parent?.requestDisallowInterceptTouchEvent(disallow)
        } catch (_: Exception) {
            // Parent hali biriktirilmagan bo'lishi mumkin — zararsiz.
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val zoomed = isZoomed
        if (event.pointerCount > 1 && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            disallowIntercept(true)
        }
        tapper.onTouchEvent(event)
        scaler.onTouchEvent(event)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                // Yaqinlashtirilgan rasmni surganda parent pager swip'ni o'g'irlamasin.
                if (zoomed) disallowIntercept(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoomed && event.pointerCount == 1 && !scaler.isInProgress) {
                    disallowIntercept(true)
                    work.set(imageMatrix)
                    work.postTranslate(event.x - lastX, event.y - lastY)
                    imageMatrix = work
                    clampToBounds()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disallowIntercept(false)
        }
        lastX = event.x
        lastY = event.y
        // Har doim `true`: ACTION_DOWN'ni yemesak ikkinchi barmoq eventlarini
        // olmay qo'yamiz va pinch boshlanmaydi. Gorizontal sip'ni esa parent
        // ViewPager2 (RecyclerView) o'zi onInterceptTouchEvent orqali tortib
        // oladi — masshtablanmagan holatda bu bizga toxunmaydi.
        return true
    }
}
