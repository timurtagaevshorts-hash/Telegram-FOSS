package uz.kodlikino.top4.utils

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import org.telegram.messenger.R

/**
 * YuzGram #128: musiqa UI uchun silliq harakat yordamchilari — play/pause
 * almashinuvi "pop", panellar siljib kirib-chiqadi, tugmalar jonli javob beradi.
 */
object YuzAnim {

    /** "Pop": tugma bosilgandek siqilib, orqaga qaytadi (spring). */
    fun pop(v: View?, from: Float = 0.78f) {
        val t = v ?: return
        t.animate().cancel()
        t.scaleX = from
        t.scaleY = from
        t.animate().scaleX(1f).scaleY(1f)
            .setDuration(240L)
            .setInterpolator(OvershootInterpolator(2.4f))
            .start()
    }

    /** Ikonni uzluksiz almashtirish: 90 ms so'ndirish -> yangi ikon -> pop. */
    fun swapIcon(img: ImageView?, res: Int) {
        val t = img ?: return
        t.animate().alpha(0f).setDuration(90L).withEndAction {
            t.setImageResource(res)
            t.alpha = 1f
            pop(t, 0.72f)
        }.start()
    }

    // Yuklanish halqalari tugma bo'yicha yuritiladi — aynan shu view'da
    // animator ishlayotganini bilish uchun (qayta boshlash "sakrash" beradi).
    private val playSpinners = java.util.WeakHashMap<View, ObjectAnimator>()

    /**
     * #213: qo'shiq ochilayotganda (resolve + birinchi bufer) play tugmasi
     * o'rnida aylanadigan halqa. Tugma rangi (imageTintList) o'zgarmaydi.
     */
    fun setPlayLoading(img: ImageView?, loading: Boolean) {
        val v = img ?: return
        val running = playSpinners[v]
        if (loading) {
            if (running != null) return
            v.animate().cancel()
            v.rotation = 0f
            v.alpha = 1f
            v.setImageResource(R.drawable.ic_yuz_spinner)
            val anim = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f).apply {
                duration = 850L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            playSpinners[v] = anim
            anim.start()
            return
        }
        if (running == null) return
        playSpinners.remove(v)
        running.cancel()
        v.rotation = 0f
    }

    fun isPlayLoading(img: View?): Boolean = img != null && playSpinners.containsKey(img)

    /** Ko'tarilib kirish (pastki pler bari, navbat paneli). */
    fun slideInUp(v: View?, px: Float, durationMs: Long = 280L) {
        val t = v ?: return
        t.animate().cancel()
        t.alpha = 1f
        t.translationY = px
        t.visibility = View.VISIBLE
        t.animate().translationY(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /** Pastga sirg'alib chiqib ketish (GONE). */
    fun slideOutDown(v: View?, px: Float, durationMs: Long = 220L) {
        val t = v ?: return
        if (t.visibility != View.VISIBLE) return
        t.animate().translationY(px).alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction {
                t.visibility = View.GONE
                t.alpha = 1f
                t.translationY = 0f
            }
            .start()
    }

    /** Panelni silliq ochish/yopish (alfavit + kichik masshtab). */
    fun fadePanel(v: View?, show: Boolean, durationMs: Long = 180L) {
        val t = v ?: return
        t.animate().cancel()
        if (show) {
            t.alpha = 0f
            t.scaleY = 0.94f
            t.visibility = View.VISIBLE
            t.animate().alpha(1f).scaleY(1f).setDuration(durationMs)
                .setInterpolator(DecelerateInterpolator()).start()
        } else if (t.visibility == View.VISIBLE) {
            t.animate().alpha(0f).scaleY(0.94f).setDuration(durationMs - 20)
                .withEndAction {
                    t.visibility = View.GONE
                    t.alpha = 1f
                    t.scaleY = 1f
                }.start()
        }
    }
}
