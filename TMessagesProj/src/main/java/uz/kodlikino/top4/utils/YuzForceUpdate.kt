package uz.kodlikino.top4.utils

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import uz.kodlikino.top4.ui.YuzTheme
import org.telegram.messenger.R

/**
 * Majburiy yangilash darvozasi.
 *
 * Admin panel `settings/app` hujjatida belgilaydi:
 * `forceUpdateEnabled` (standart `true`), `minSupportedVersionCode` (0 —
 * o‘chirilgan) va ixtiyoriy `updateNote`. Ilova ochilishida o‘rnatilgan
 * `versionCode` shu qiymatdan kichik bo‘lsa, yopib bo‘lmaydigan yangilash
 * oynasi chiqadi va foydalanuvchi Play Market’ga yo‘naltiriladi.
 *
 * Tarmoq xatosida darvoza ochiq qoladi (foydalanuvchini bloklab qo‘ymaydi):
 * so‘nggi ma’lum talab keshdan olinadi, kesh bo‘sh bo‘lsa hech narsa
 * ko‘rsatilmaydi. Kesh 10 daqiqada yangilanadi.
 */
object YuzForceUpdate {

    private const val SETTINGS_COLLECTION = "settings"
    private const val SETTINGS_DOC = "app"
    private const val PREFS = "yuz_update_gate"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MIN_CODE = "min_code"
    private const val KEY_NOTE = "note"
    private const val KEY_PLAY_CODE = "play_code"
    private const val KEY_LOADED_AT = "loaded_at"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    const val PLAY_PACKAGE = "uz.kodlikino.top4"

    data class Requirement(
        val enabled: Boolean = true,
        val minVersionCode: Int = 0,
        val note: String = ""
    ) {
        fun requiresUpdate(installedCode: Long): Boolean =
            enabled && minVersionCode > 0 && installedCode < minVersionCode
    }

    private val db = FirebaseFirestore.getInstance()

    @Volatile
    private var memory: Requirement? = null

    @Volatile
    private var lastFetchAt = 0L

    @Volatile
    private var refreshing = false

    private var dialog: Dialog? = null
    private var dialogHost: Activity? = null

    private fun prefs(): SharedPreferences? =
        ApplicationLoader.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sanitize(enabled: Boolean, minCode: Int, note: String): Requirement =
        Requirement(
            enabled = enabled,
            minVersionCode = minCode.coerceAtLeast(0),
            note = note.trim()
        )

    private fun parse(data: Map<String, Any>?): Requirement = sanitize(
        enabled = (data?.get("forceUpdateEnabled") as? Boolean) ?: true,
        minCode = (data?.get("minSupportedVersionCode") as? Number)?.toInt() ?: 0,
        note = (data?.get("updateNote") as? String).orEmpty()
    )

    private fun persist(req: Requirement) {
        try {
            prefs()?.edit()
                ?.putBoolean(KEY_ENABLED, req.enabled)
                ?.putInt(KEY_MIN_CODE, req.minVersionCode)
                ?.putString(KEY_NOTE, req.note)
                ?.putLong(KEY_LOADED_AT, System.currentTimeMillis())
                ?.apply()
        } catch (_: Exception) {
        }
    }

    /** Oxirgi ma’lum talab — tarmoqqa chiqmaydi. */
    fun cached(): Requirement {
        memory?.let { return it }
        return try {
            val sp = prefs() ?: return Requirement()
            sanitize(
                enabled = sp.getBoolean(KEY_ENABLED, true),
                minCode = sp.getInt(KEY_MIN_CODE, 0),
                note = sp.getString(KEY_NOTE, "").orEmpty()
            )
        } catch (_: Exception) {
            Requirement()
        }
    }

    /** Play Market aniqlagan eng yangi versionCode (noma’lum bo‘lsa 0). */
    fun playCode(): Int = try {
        prefs()?.getInt(KEY_PLAY_CODE, 0) ?: 0
    } catch (_: Exception) {
        0
    }

    /**
     * Play In-App Update yangiroq versiya topganda chaqiriladi — talabni
     * kuchaytiradi (o‘rnatilgan koddan katta bo‘lsagina) va bloklovchi
     * oynani darhol ko‘rsatadi.
     */
    @JvmStatic
    fun applyPlayUpdate(activity: Activity, availableCode: Int) {
        if (availableCode <= 0) return
        try {
            val installed = installedVersionCode(activity)
            if (availableCode <= installed) return
            val sp = prefs() ?: return
            if (sp.getInt(KEY_PLAY_CODE, 0) < availableCode) {
                sp.edit().putInt(KEY_PLAY_CODE, availableCode).apply()
            }
            showBlocking(activity)
        } catch (_: Exception) {
        }
    }

    /** Amaldagi minimal kod: admin talabi va Play aniqlagan versiyaning kattasi. */
    fun effectiveMinCode(): Int {
        val req = cached()
        if (!req.enabled) return 0
        return maxOf(req.minVersionCode, playCode())
    }

    private fun needsBlock(installed: Long): Boolean {
        val min = effectiveMinCode()
        return min > 0 && installed < min
    }

    suspend fun load(force: Boolean = false): Requirement {
        val now = System.currentTimeMillis()
        val known = memory ?: cached()
        if (!force && now - lastFetchAt < CACHE_TTL_MS) return known
        return try {
            val snapshot = YuzTaskTimeouts.awaitBound(
                "Yangilash sozlamasi",
                YuzTaskTimeouts.SETTINGS_MS
            ) { db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).get().await() }
            val req = parse(snapshot.data)
            memory = req
            lastFetchAt = now
            persist(req)
            req
        } catch (_: Exception) {
            known
        }
    }

    /** Admin panel uchun — xatoni yashirmaydi. */
    suspend fun adminLoad(): Result<Requirement> = try {
        val snapshot = db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).get().await()
        val req = parse(snapshot.data)
        memory = req
        lastFetchAt = System.currentTimeMillis()
        persist(req)
        Result.success(req)
    } catch (_: Exception) {
        Result.failure(IllegalStateException("Yangilash sozlamasi yuklanmadi"))
    }

    suspend fun save(req: Requirement): Result<Requirement> {
        val clean = sanitize(req.enabled, req.minVersionCode, req.note)
        return try {
            db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).set(
                mapOf(
                    "forceUpdateEnabled" to clean.enabled,
                    "minSupportedVersionCode" to clean.minVersionCode,
                    "updateNote" to clean.note,
                    "forceUpdateUpdatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            memory = clean
            lastFetchAt = System.currentTimeMillis()
            persist(clean)
            Result.success(clean)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Yangilash sozlamasi saqlanmadi"))
        }
    }

    // ------------------------------------------------------------- version

    private fun packageInfo(context: Context): android.content.pm.PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (_: Exception) {
        null
    }

    fun installedVersionCode(context: Context): Long = try {
        val info = packageInfo(context)
        when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }
    } catch (_: Exception) {
        0L
    }

    fun installedVersionName(context: Context): String = try {
        packageInfo(context)?.versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    // ---------------------------------------------------------------- gate

    /**
     * Ilova ochilganda chaqiriladi (LaunchActivity.onResume). Keshdagi talab
     * bo‘yicha darhol tekshiradi va fonda yangilab turadi.
     */
    @JvmStatic
    fun enforce(activity: Activity) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            val installed = installedVersionCode(activity)
            if (installed <= 0L) return
            if (needsBlock(installed)) {
                showBlocking(activity)
            }
            refresh(activity, installed)
        } catch (_: Exception) {
        }
    }

    private fun refresh(activity: Activity, installed: Long) {
        if (refreshing) return
        refreshing = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                val req = load()
                if (req.requiresUpdate(installed) || needsBlock(installed)) {
                    if (!activity.isFinishing && !activity.isDestroyed) showBlocking(activity)
                }
            } catch (_: Exception) {
            } finally {
                refreshing = false
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }

    @JvmStatic
    fun showBlocking(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog != null && dialogHost === activity) return
        dismissDialog()
        val req = cached()
        val installedCode = installedVersionCode(activity)
        val requiredCode = effectiveMinCode()
        if (requiredCode <= 0 || installedCode >= requiredCode) return

        val themed = YuzTheme.wrap(activity)
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(themed, R.color.yuz_surface_raised, 22f)
            setPadding(dp(22f), dp(22f), dp(22f), dp(16f))
        }

        val badge = TextView(themed).apply {
            text = "🔄"
            textSize = 30f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(64f), dp(64f))
            background = rounded(themed, R.color.yuz_field, 32f).apply {
                setStroke(dp(1.2f), ContextCompat.getColor(themed, R.color.yuz_stroke))
            }
        }
        root.addView(badge)

        root.addView(
            TextView(themed).apply {
                text = themed.getString(R.string.yuz_update_title)
                setTextColor(ContextCompat.getColor(themed, R.color.yuz_text_bright))
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(12f), 0, 0)
            }
        )

        root.addView(
            TextView(themed).apply {
                text = themed.getString(R.string.yuz_update_body)
                setTextColor(ContextCompat.getColor(themed, R.color.yuz_text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, 0)
            }
        )

        if (req.note.isNotBlank()) {
            root.addView(
                TextView(themed).apply {
                    text = req.note
                    setTextColor(ContextCompat.getColor(themed, R.color.yuz_text))
                    textSize = 13.5f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8f), 0, 0)
                }
            )
        }

        val name = installedVersionName(activity)
        root.addView(
            TextView(themed).apply {
                text = themed.getString(
                    R.string.yuz_update_versions,
                    if (name.isBlank()) installedCode.toString() else name,
                    requiredCode.toString()
                )
                setTextColor(ContextCompat.getColor(themed, R.color.yuz_text_muted))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, 0)
            }
        )

        val update = MaterialButton(themed).apply {
            text = themed.getString(R.string.yuz_update_open_store)
            setTextColor(ContextCompat.getColor(themed, R.color.yuz_bg))
            backgroundTintList = ContextCompat.getColorStateList(themed, R.color.yuz_action)
            cornerRadius = dp(14f)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48f)
            ).apply { topMargin = dp(16f) }
            setOnClickListener { openStore(themed) }
        }
        root.addView(update)

        val exit = MaterialButton(
            themed,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = themed.getString(R.string.yuz_update_exit)
            setTextColor(ContextCompat.getColor(themed, R.color.yuz_text))
            strokeColor = ContextCompat.getColorStateList(themed, R.color.yuz_stroke)
            strokeWidth = dp(1.2f)
            cornerRadius = dp(14f)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46f)
            ).apply { topMargin = dp(8f) }
            setOnClickListener {
                dismissDialog()
                activity.finishAffinity()
            }
        }
        root.addView(exit)

        val frame = FrameLayout(themed).apply {
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }

        val d = Dialog(themed, R.style.TransparentDialog)
        d.setContentView(frame)
        d.setCanceledOnTouchOutside(false)
        d.setCancelable(false)
        d.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        d.show()
        dialog = d
        dialogHost = activity
    }

    @JvmStatic
    fun dismissDialog() {
        val d = dialog
        dialog = null
        dialogHost = null
        try {
            d?.dismiss()
        } catch (_: Exception) {
        }
    }

    /** Dialog egasi activity yopilganda chaqiriladi. */
    @JvmStatic
    fun onActivityDestroyed(activity: Activity?) {
        if (activity == null || dialogHost === activity) {
            dismissDialog()
        }
    }

    fun openStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLAY_PACKAGE"))
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$PLAY_PACKAGE")
        )
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        market.addFlags(flags)
        web.addFlags(flags)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(web)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- styling

    private fun rounded(context: Context, colorRes: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, colorRes))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Float): Int = AndroidUtilities.dp(value)
}
