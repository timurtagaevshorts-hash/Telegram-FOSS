package uz.kodlikino.top4.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzGramProfileStore
import java.util.Locale

/**
 * YuzGram profili — [BaseFragment]. Faqat Telegram ma'lumotlarini ko'rsatadi:
 *  - avatar + ism + @username + bio (TDLib'dan; telefon raqami ko'rsatilmaydi);
 *  - Reels soni va 3 ustunli reel grid — katak bosilsa [ReelsActivity] scoped
 *      feed'da ochiladi;
 *  - [isSelf]=true → tepada sozlamalar (gear, native profil), pastda "Kontentni
 *      boshqarish" (reel'lar grid'ida ✕ o'chirish rejimi).
 *
 * Obuna (subscribe) tizimi loyihadan butunlay olib tashlangan — bu ekran faqat
 * profilni ko'rish va o'z reel'larini boshqarish uchun.
 *
 * @param knownYuzGram eski kirish nuqtalari (reel muallifi / izohchi) ushbu
 *        parametrni yuboradi; obuna tizimi olib tashlangani uchun endi ishlatilmaydi.
 */
class YuzGramProfileActivity(
    private val uid: String,
    private val seedName: String = "",
    private val seedPhotoUrl: String = "",
    private val isSelf: Boolean = false,
    @Suppress("UNUSED_PARAMETER") private val knownYuzGram: Boolean = false
) : BaseFragment() {

    private var themedContext: Context? = null
    private var toolbarName: TextView? = null
    private var nameView: TextView? = null
    private var usernameView: TextView? = null
    private var bioView: TextView? = null
    private var avatarImage: ImageView? = null
    private var avatarInitial: TextView? = null
    private var tvReelsCount: TextView? = null
    private var rvReels: RecyclerView? = null
    private var emptyReels: TextView? = null
    private var btnManage: MaterialButton? = null
    private var tvManageHint: TextView? = null
    private var btnSettings: ImageButton? = null

    private var reelsAdapter: ProfileReelsAdapter? = null
    private var scope: CoroutineScope? = null

    private var myUid: String = ""
    private var displayName: String = ""
    private var username: String = ""
    private var bio: String = ""
    private var photoUrl: String = ""
    private var reels: List<Reel> = emptyList()
    private var manageMode = false

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

    /**
     * LaunchActivity'ning pastki bar'i faqat root ekranda ko'rinadi. Bu YuzGram
     * profili o'zimizniki bo'lsa ([isSelf]=true) — bottom bar'ning "Profil" tab'i
     * uchun root hisoblanadi; begona profil ochilsa bar yashirilsin.
     * Kotlin'da private ctor `val` Java'dan ko'rinmaydi, shuning uchun ochiq accessor.
     */
    fun isRootProfile(): Boolean = isSelf

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_yuzgram_profile, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE

        val topBar = root.findViewById<View>(R.id.profileTopBar)
        topBar?.setPadding(
            topBar.paddingLeft, AndroidUtilities.statusBarHeight,
            topBar.paddingRight, topBar.paddingBottom
        )

        toolbarName = root.findViewById(R.id.tvProfileToolbarName)
        nameView = root.findViewById(R.id.tvProfileName)
        usernameView = root.findViewById(R.id.tvProfileUsername)
        bioView = root.findViewById(R.id.tvProfileBio)
        avatarImage = root.findViewById(R.id.ivProfileAvatar)
        avatarInitial = root.findViewById(R.id.tvAvatarInitial)
        tvReelsCount = root.findViewById(R.id.tvReelsCount)
        rvReels = root.findViewById(R.id.rvProfileReels)
        emptyReels = root.findViewById(R.id.emptyProfileReels)
        btnManage = root.findViewById(R.id.btnManageContent)
        tvManageHint = root.findViewById(R.id.tvManageHint)
        btnSettings = root.findViewById(R.id.btnProfileSettings)

        root.findViewById<View>(R.id.btnProfileBack)?.setOnClickListener { removeSelfFromStack() }

        // Dastlabki (seed) nom/rasm — Firestore javobigacha ham to'liq ko'rinish.
        displayName = seedName
        photoUrl = seedPhotoUrl
        renderHeader()
        toolbarName?.text =
            if (isSelf) getString(R.string.profile_app_name) else displayName

        root.findViewById<View>(R.id.colReels)?.setOnClickListener {
            if (reels.isNotEmpty()) presentFragment(ReelsActivity(uid, 0), false)
        }

        reelsAdapter = ProfileReelsAdapter(themed,
            onClick = { index -> presentFragment(ReelsActivity(uid, index), false) },
            onDelete = { reel -> confirmDeleteReel(reel) })
        rvReels?.layoutManager = GridLayoutManager(themed, 3)
        rvReels?.adapter = reelsAdapter

        // Pastki bar: profil sahifasi pastga surganda g'oyib, tepaga surganda
        // darhol qaytadi (tepagacha chiqish shart emas).
        (root.findViewById<View>(R.id.profileScroll) as? NestedScrollView)?.let {
            YuzGramBarAutoHide.attachTo(it, this)
        }

        if (isSelf) {
            btnSettings?.visibility = View.VISIBLE
            btnSettings?.setOnClickListener { openNativeProfile() }
            btnManage?.visibility = View.VISIBLE
            btnManage?.setOnClickListener { toggleManageMode() }
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        load()
        return root
    }

    private fun load() {
        myUid = currentUid()
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            // Firebase sessiyasi: comment_profiles o'qish (ism/rasm/bio fallback)
            // va reel'lar ro'yxati shuni talab qiladi.
            val signed = try {
                YuzGramAdsAuth.ensureSignedIn(account)
            } catch (e: Exception) {
                if (fragmentView == null) return@launch
                toast(e.message ?: getString(R.string.profile_sign_in_required))
                ""
            }
            if (fragmentView == null) return@launch
            if (signed.isNotBlank()) {
                myUid = signed
                if (isSelf) seedSelfProfile(account)
            }
            loadProfileAndReels()
        }
    }

    /** O'zimiz uchun comment_profiles doc yaratamiz — profil ko'rish uchun. */
    private suspend fun seedSelfProfile(account: Int) {
        val user = UserConfig.getInstance(account).currentUser
        YuzGramProfileStore.ensureSelfProfile(
            uid = myUid,
            firstName = user?.first_name.orEmpty(),
            lastName = user?.last_name.orEmpty(),
            photoUrl = ""
        )
    }

    private fun loadProfileAndReels() {
        val s = scope ?: return
        s.launch {
            // 1) Telegram (TDLib) ma'lumotlari — ism + @username + bio. Telefon
            //    raqami MAXSADAN olinmaydi (xavfsizlik + begona uchun mavjud emas).
            applyTelegramInfo()

            // 2) Firestore profili — Telegram'da yo'q maydonlar uchun fallback (rasm, bio).
            val profile = YuzGramProfileStore.load(uid)
            if (fragmentView == null) return@launch
            if (displayName.isBlank() && profile.displayName.isNotBlank()) displayName = profile.displayName
            if (photoUrl.isBlank() && profile.photoUrl.isNotBlank()) photoUrl = profile.photoUrl
            if (bio.isBlank()) bio = profile.bio

            renderHeader()

            reels = ReelsRepository.getReelsByUploader(uid)
            if (fragmentView == null) return@launch
            reelsAdapter?.submit(reels)
            tvReelsCount?.text = reels.size.toString()
            val empty = reels.isEmpty()
            emptyReels?.visibility = if (empty) View.VISIBLE else View.GONE
            rvReels?.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /**
     * TDLib'dan maqsad foydalanuvchining Telegram ismini, @username'ini va
     * bio'sini (`about`) oladi. O'z profilimizda [UserConfig.currentUser]
     * ishonchli; begona profilda foydalanuvchi lokal katalogda bo'lmasa null
     * qaytadi — unda Firestore/seed ma'lumot qoladi.
     */
    private fun applyTelegramInfo() {
        val account = currentAccount
        val controller = MessagesController.getInstance(account) ?: return
        val userId: Long = if (isSelf) {
            UserConfig.getInstance(account).clientUserId
        } else {
            uid.removePrefix("tg_").toLongOrNull() ?: return
        }
        if (userId == 0L) return
        val user: TLRPC.User? = controller.getUser(userId)
        if (user != null) {
            val tgName = listOf(user.first_name.orEmpty(), user.last_name.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" ")
            if (tgName.isNotBlank()) displayName = tgName
            if (!user.username.isNullOrBlank()) username = user.username
        }
        val about = try {
            controller.getUserFull(userId)?.about
        } catch (_: Exception) {
            null
        }
        if (!about.isNullOrBlank()) bio = about
    }

    private fun toggleManageMode() {
        manageMode = !manageMode
        reelsAdapter?.manageMode = manageMode
        btnManage?.text = getString(
            if (manageMode) R.string.profile_manage_done else R.string.profile_manage_content
        )
        tvManageHint?.visibility = if (manageMode && reels.isNotEmpty()) View.VISIBLE else View.GONE
        if (manageMode && reels.isNotEmpty()) rvReels?.requestFocus()
    }

    private fun confirmDeleteReel(reel: Reel) {
        val activity = parentActivity ?: return
        val title = reel.title.ifBlank { reel.caption() }.ifBlank { getString(R.string.reels_title) }
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.reels_delete)
                .setMessage(getString(R.string.reels_delete_confirm, title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reels_delete) { _, _ -> deleteReel(reel) }
                .show()
        } catch (_: Exception) {
            toast(R.string.reels_delete_failed)
        }
    }

    private fun deleteReel(reel: Reel) {
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(account)
                ReelsRepository.deleteReel(reel.id)
                if (myUid.isNotBlank()) ReelStorageHelper.deleteVideo(myUid, reel.videoUrl)
                if (fragmentView == null) return@launch
                val idx = reels.indexOfFirst { it.id == reel.id }
                if (idx >= 0) reelsAdapter?.removeAt(idx)
                reels = reels.filter { it.id != reel.id }
                tvReelsCount?.text = reels.size.toString()
                toast(R.string.reels_deleted)
                if (reels.isEmpty()) {
                    emptyReels?.visibility = View.VISIBLE
                    rvReels?.visibility = View.GONE
                    tvManageHint?.visibility = View.GONE
                }
            } catch (e: Exception) {
                if (fragmentView == null) return@launch
                toast(R.string.reels_delete_failed)
            }
        }
    }

    private fun openNativeProfile() {
        val account = currentAccount
        val args = Bundle()
        args.putLong("user_id", UserConfig.getInstance(account).clientUserId)
        args.putBoolean("my_profile", true)
        presentFragment(org.telegram.ui.ProfileActivity(args, null), false)
    }

    private fun renderHeader() {
        val shown = displayName.ifBlank { seedName }.ifBlank { "YuzGram" }
        nameView?.text = shown
        toolbarName?.text = if (isSelf) getString(R.string.profile_app_name) else shown
        avatarInitial?.text = shown.take(1).uppercase(Locale.getDefault())

        // @username — Telegram'dan; bo'lmasa yashirin.
        if (username.isNotBlank()) {
            usernameView?.text = "@$username"
            usernameView?.visibility = View.VISIBLE
        } else {
            usernameView?.visibility = View.GONE
        }

        // bio — Telegram `about` yoki Firestore; bo'lmasa yashirin.
        if (bio.isNotBlank()) {
            bioView?.text = bio
            bioView?.visibility = View.VISIBLE
        } else {
            bioView?.text = ""
            bioView?.visibility = View.GONE
        }

        if (photoUrl.isNotBlank()) {
            avatarImage?.visibility = View.VISIBLE
            Glide.with(avatarImage!!)
                .load(photoUrl)
                .apply(RequestOptions.circleCropTransform())
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .error(R.drawable.bg_classified_image_placeholder)
                .into(avatarImage!!)
        } else {
            avatarImage?.visibility = View.GONE
            avatarImage?.setImageDrawable(null)
        }
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        // Telegram katalogi kechikib to'lishi mumkin — qayta urinamiz.
        applyTelegramInfo()
        renderHeader()
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        reelsAdapter = null
        rvReels?.adapter = null
        super.onFragmentDestroy()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        val ctx = parentActivity ?: context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }
}
