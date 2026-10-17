package uz.kodlikino.top4.ui

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.FollowManager
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzGramProfileStore
import java.util.Locale

/**
 * Instagram uslubidagi YuzGram profili — [BaseFragment].
 *
 * [uid] Firebase/YuzGram identiteti (`tg_<telegramId>`). Ekran shu profil uchun:
 *  - avatar + ism + bio;
 *  - jonli statistika: Reels / Obunalar / Obunachilar (ro'yxatlari bosiladi);
 *  - [isSelf]=false → "Obuna bo'lish / bekor qilish" tugmasi:
 *      maqsad YuzGram foydalanuvchisi bo'lsa → [FollowManager] orqali ishlaydi,
 *      bo'lmasa (faqat oddiy Telegram) → bosganda "YuzGram o'rnatmagan" xabari;
 *  - [isSelf]=true → tepada sozlamalar (gear), pastda "Kontentni boshqarish"
 *      (reel'lar grid'ida ✕ o'chirish rejimi);
 *  - 3 ustunli reel grid — katak bosilsa o'sha foydalanuvchining reel'ari
 *      [ReelsActivity] scoped feed'da ochiladi.
 *
 * `comment_profiles` o'qish/write Firebase sessiyasi talab qiladi — shuning uchun
 * ko'rsatishdan oldin [YuzGramAdsAuth.ensureSignedIn]. O'z profilimiz uchun
 * [YuzGramProfileStore.ensureSelfProfile] hujjatni yaratadi (boshqalar bizga
 * obuna bola olishi uchun shart).
 *
 * @param knownYuzGram Kirish nuqtasi (reel muallifi / izohchi) maqsad aniq
 *        YuzGram foydalanuvchisi ekanini biladi — hujjat hali yo'lmasa ham
 *        obuna tugmasini faol holatda ko'rsatamiz.
 */
class YuzGramProfileActivity(
    private val uid: String,
    private val seedName: String = "",
    private val seedPhotoUrl: String = "",
    private val isSelf: Boolean = false,
    private val knownYuzGram: Boolean = false
) : BaseFragment() {

    private var themedContext: Context? = null
    private var toolbarName: TextView? = null
    private var nameView: TextView? = null
    private var bioView: TextView? = null
    private var avatarImage: ImageView? = null
    private var avatarInitial: TextView? = null
    private var tvReelsCount: TextView? = null
    private var tvFollowingCount: TextView? = null
    private var tvFollowersCount: TextView? = null
    private var btnFollow: MaterialButton? = null
    private var tvNotYuzgram: TextView? = null
    private var rvReels: RecyclerView? = null
    private var emptyReels: TextView? = null
    private var btnManage: MaterialButton? = null
    private var tvManageHint: TextView? = null
    private var btnSettings: ImageButton? = null

    private var reelsAdapter: ProfileReelsAdapter? = null
    private var scope: CoroutineScope? = null
    private var statsReg: ListenerRegistration? = null

    private var myUid: String = ""
    private var displayName: String = ""
    private var photoUrl: String = ""
    private var reels: List<Reel> = emptyList()
    private var isFollowing = false
    private var followBusy = false
    private var manageMode = false
    private var isYuzGram = knownYuzGram
    private var followStateResolved = false

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

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
        bioView = root.findViewById(R.id.tvProfileBio)
        avatarImage = root.findViewById(R.id.ivProfileAvatar)
        avatarInitial = root.findViewById(R.id.tvAvatarInitial)
        tvReelsCount = root.findViewById(R.id.tvReelsCount)
        tvFollowingCount = root.findViewById(R.id.tvFollowingCount)
        tvFollowersCount = root.findViewById(R.id.tvFollowersCount)
        btnFollow = root.findViewById(R.id.btnFollow)
        tvNotYuzgram = root.findViewById(R.id.tvNotYuzgram)
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
        root.findViewById<View>(R.id.colFollowing)?.setOnClickListener {
            if (!isSelf) return@setOnClickListener
            presentFragment(FollowListActivity(uid, followers = false), false)
        }
        root.findViewById<View>(R.id.colFollowers)?.setOnClickListener {
            if (!isSelf) return@setOnClickListener
            presentFragment(FollowListActivity(uid, followers = true), false)
        }

        reelsAdapter = ProfileReelsAdapter(themed,
            onClick = { index -> presentFragment(ReelsActivity(uid, index), false) },
            onDelete = { reel -> confirmDeleteReel(reel) })
        rvReels?.layoutManager = GridLayoutManager(themed, 3)
        rvReels?.adapter = reelsAdapter

        btnFollow?.setOnClickListener { onFollowClicked() }

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
        if (myUid.isBlank() && !isSelf) {
            // O'z sessiyamiz yo'q — begona profilni ko'rish uchun kira olmaymiz.
            toast(R.string.profile_sign_in_required)
            return
        }
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            // Firebase sessiyasi: comment_profiles o'qish va obuna yozuvlari shuni
            // talab qiladi.
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
                loadProfileAndReels()
            } else if (isSelf) {
                loadProfileAndReels()
            }
        }
        // Jonli sonlar — Firestore snapshot listener (obuna bo'lishda darhol yangilanadi).
        statsReg = FollowManager.listenFollowStats(uid) { counts ->
            if (fragmentView == null) return@listenFollowStats
            tvFollowersCount?.text = counts.followers.toString()
            tvFollowingCount?.text = counts.following.toString()
        }
    }

    /** O'zimiz uchun comment_profiles doc yaratamiz — boshqalar bizga obuna bola olsin. */
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
            val profile = YuzGramProfileStore.load(uid)
            if (fragmentView == null) return@launch
            if (profile.exists) isYuzGram = true
            if (profile.displayName.isNotBlank()) displayName = profile.displayName
            if (profile.photoUrl.isNotBlank()) photoUrl = profile.photoUrl
            val bio = profile.bio
            renderHeader()
            if (bio.isNotBlank()) {
                bioView?.text = bio
                bioView?.visibility = View.VISIBLE
            }

            reels = ReelsRepository.getReelsByUploader(uid)
            if (fragmentView == null) return@launch
            reelsAdapter?.submit(reels)
            tvReelsCount?.text = reels.size.toString()
            val empty = reels.isEmpty()
            emptyReels?.visibility = if (empty) View.VISIBLE else View.GONE
            rvReels?.visibility = if (empty) View.GONE else View.VISIBLE

            resolveFollowState()
        }
    }

    /** Obuna holatini aniqlaydi va tugma/note ko'rinishini sozlaydi. */
    private suspend fun resolveFollowState() {
        if (isSelf) {
            btnFollow?.visibility = View.GONE
            tvNotYuzgram?.visibility = View.GONE
            return
        }
        if (myUid.isBlank()) {
            btnFollow?.visibility = View.GONE
            return
        }
        isFollowing = try {
            FollowManager.isFollowing(myUid, uid)
        } catch (_: Exception) {
            false
        }
        followStateResolved = true
        if (fragmentView == null) return
        if (isYuzGram) {
            btnFollow?.visibility = View.VISIBLE
            tvNotYuzgram?.visibility = View.GONE
            updateFollowButtonLabel()
        } else {
            // Oddiy Telegram foydalanuvchisi: tugma ko'rinadi, bosilganda xabar chiqadi.
            btnFollow?.visibility = View.VISIBLE
            btnFollow?.text = getString(R.string.profile_follow)
            btnFollow?.isEnabled = true
            tvNotYuzgram?.visibility = View.VISIBLE
        }
    }

    private fun onFollowClicked() {
        if (followBusy) return
        if (isSelf) return
        if (!isYuzGram) {
            // YuzGram o'rnatmagan — obuna bo'lib bo'lmaydi (so'rov talab qilingan xatti-harakat).
            toast(R.string.profile_follow_need_yuzgram)
            return
        }
        if (myUid.isBlank()) {
            toast(R.string.profile_sign_in_required)
            return
        }
        val s = scope ?: return
        val account = currentAccount
        val target = uid
        val wasFollowing = isFollowing
        followBusy = true
        btnFollow?.isEnabled = false
        s.launch {
            val result = try {
                YuzGramAdsAuth.ensureSignedIn(account)
                val myProfile = currentSelfProfile()
                val theirProfile = YuzGramProfileStore.load(target)
                    .let { p ->
                        uz.kodlikino.top4.models.CommentUserProfile(
                            uid = target,
                            firstName = p.firstName.ifBlank { seedName },
                            photoUrl = p.photoUrl.ifBlank { seedPhotoUrl }
                        )
                    }
                if (wasFollowing) {
                    FollowManager.unfollow(myUid, target)
                } else {
                    FollowManager.follow(myUid, target, myProfile, theirProfile)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            followBusy = false
            if (fragmentView == null) return@launch
            btnFollow?.isEnabled = true
            if (result.isSuccess) {
                isFollowing = !wasFollowing
                updateFollowButtonLabel()
                toast(if (isFollowing) R.string.profile_followed else R.string.profile_unfollowed)
            } else {
                toast(R.string.profile_follow_failed)
            }
        }
    }

    private fun updateFollowButtonLabel() {
        btnFollow?.text = getString(
            if (isFollowing) R.string.profile_following_active else R.string.profile_follow
        )
    }

    private fun currentSelfProfile(): uz.kodlikino.top4.models.CommentUserProfile {
        val user = UserConfig.getInstance(currentAccount).currentUser
        return uz.kodlikino.top4.models.CommentUserProfile(
            uid = myUid,
            firstName = user?.first_name.orEmpty().ifBlank { displayName },
            lastName = user?.last_name.orEmpty(),
            photoUrl = photoUrl
        )
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
                toast(R.string.monet_reel_deleted)
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
        val shown = displayName.ifBlank { "YuzGram" }
        nameView?.text = shown
        avatarInitial?.text = shown.take(1).uppercase(Locale.getDefault())
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
    }

    override fun onFragmentDestroy() {
        statsReg?.remove()
        statsReg = null
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
