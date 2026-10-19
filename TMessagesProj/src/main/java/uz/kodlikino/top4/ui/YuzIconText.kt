package uz.kodlikino.top4.ui

import android.graphics.PorterDuff
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import org.telegram.messenger.R

/**
 * Matn oldiga qo'yiladigan ikon — emoji o'rniga chizilgan vektor. Ikon rangi
 * sukutda matn rangidan olinadi, shunda "tur"/"kategoriya" labeli bilan belgisi
 * bir tilda ko'rinadi.
 */
fun TextView.setIconStart(
    @DrawableRes res: Int,
    sizeDp: Int = 15,
    gapDp: Int = 6,
    tintColor: Int = 0
) {
    setCompoundDrawablesRelativeWithIntrinsicBounds(
        compound(res, sizeDp, gapDp, tintColor), null, null, null
    )
}

/** Matn orqasidagi ikon — masalan bekor qilish belgisi (matnoldi "✕" o'rniga). */
fun TextView.setIconEnd(
    @DrawableRes res: Int,
    sizeDp: Int = 15,
    gapDp: Int = 6,
    tintColor: Int = 0
) {
    setCompoundDrawablesRelativeWithIntrinsicBounds(
        null, null, compound(res, sizeDp, gapDp, tintColor), null
    )
}

private fun TextView.compound(
    @DrawableRes res: Int,
    sizeDp: Int,
    gapDp: Int,
    tintColor: Int
): android.graphics.drawable.Drawable? {
    val d = context.resources.displayMetrics.density
    val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
    val px = (sizeDp * d).toInt()
    drawable.setBounds(0, 0, px, px)
    drawable.setColorFilter(
        if (tintColor != 0) tintColor else currentTextColor,
        PorterDuff.Mode.SRC_IN
    )
    compoundDrawablePadding = (gapDp * d).toInt()
    return drawable
}
