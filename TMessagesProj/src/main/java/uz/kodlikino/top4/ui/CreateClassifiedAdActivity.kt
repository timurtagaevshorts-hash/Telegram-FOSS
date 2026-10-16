package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.ClassifiedAdImageThumbAdapter
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCardThemes
import uz.kodlikino.top4.models.ClassifiedAdCategories
import uz.kodlikino.top4.utils.ClassifiedAdRepository
import uz.kodlikino.top4.utils.ClassifiedAdStorageHelper
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * "Elon joylash" (Faza D) — yangi e'lon yaratish / mavjud e'lonni tahrirlash ekrani.
 *
 * KinoTOP4'dagi [AppCompatActivity] `CreateClassifiedAdActivity` Telegram arxitekturasiga
 * moslab [BaseFragment] ko'rinishida qayta yozildi:
 *  - Elonlar feed'idan `presentFragment(CreateClassifiedAdActivity())` orqali ochiladi;
 *  - Material komponentlari [ContextThemeWrapper] (Theme.YuzGramAds) ostida;
 *  - rasm tanlash `onActivityResultFragment` orqali qabul qilinadi;
 *  - yozuvdan oldin [YuzGramAdsAuth.ensureSignedIn] Telegram identitetini Firebase Auth
 *    `tg_<id>` sessiyasiga almashtiradi (Firestore/Storage qoidalari shuni talab qiladi).
 *
 * Tema tanlagich o'chirilgan (faqat black). Manzil oddiy matn maydoni sifatida ishlaydi.
 */
class CreateClassifiedAdActivity(
    private val editingAdId: String? = null
) : BaseFragment() {

    companion object {
        private const val MAX_IMAGES = 5
        private const val REQ_PICK_IMAGES = 4471
    }

    private var themedContext: Context? = null
    private var toolbar: Toolbar? = null
    private var spinnerCategory: Spinner? = null
    private var btnPickImages: View? = null
    private var tvPickImagesLabel: android.widget.TextView? = null
    private var rvSelectedImages: RecyclerView? = null
    private var etModel: TextInputEditText? = null
    private var etTitle: TextInputEditText? = null
    private var etPrice: TextInputEditText? = null
    private var etAddress: TextInputEditText? = null
    private var etPhone: TextInputEditText? = null
    private var etDescription: TextInputEditText? = null
    private var uploadProgress: ProgressBar? = null
    private var btnPublish: MaterialButton? = null

    private val imageItems = mutableListOf<ClassifiedAdImageThumbAdapter.ImageItem>()
    private var imageThumbAdapter: ClassifiedAdImageThumbAdapter? = null
    private var existingCreatedAt: Long = System.currentTimeMillis()
    private var removedRemoteUrls = mutableListOf<String>()

    private var scope: CoroutineScope? = null
    private var publishing = false

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_create_classified_ad, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        toolbar = root.findViewById(R.id.toolbar)
        spinnerCategory = root.findViewById(R.id.spinnerCategory)
        btnPickImages = root.findViewById(R.id.btnPickImages)
        tvPickImagesLabel = root.findViewById(R.id.tvPickImagesLabel)
        rvSelectedImages = root.findViewById(R.id.rvSelectedImages)
        etModel = root.findViewById(R.id.etModel)
        etTitle = root.findViewById(R.id.etTitle)
        etPrice = root.findViewById(R.id.etPrice)
        etAddress = root.findViewById(R.id.etAddress)
        etPhone = root.findViewById(R.id.etPhone)
        etDescription = root.findViewById(R.id.etDescription)
        uploadProgress = root.findViewById(R.id.uploadProgress)
        btnPublish = root.findViewById(R.id.btnPublish)

        toolbar?.apply {
            setPadding(paddingLeft, AndroidUtilities.statusBarHeight, paddingRight, paddingBottom)
            setNavigationOnClickListener { removeSelfFromStack() }
        }

        // Manzil: KinoTOP4'da joy tanlagich edi — bu yerda oddiy matn maydoni.
        // TextInputEditText allaqachon tahrirlanuvchan; faqat inputType'ni sozlaymiz.
        etAddress?.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        val categoryLabels = ClassifiedAdCategories.all.map { "${it.emoji} ${it.label}" }
        spinnerCategory?.adapter = ArrayAdapter(
            themed,
            R.layout.item_classified_spinner,
            categoryLabels
        ).apply { setDropDownViewResource(R.layout.item_classified_spinner) }

        imageThumbAdapter = ClassifiedAdImageThumbAdapter { index ->
            val removed = imageItems.removeAt(index)
            if (removed is ClassifiedAdImageThumbAdapter.ImageItem.Remote) {
                removedRemoteUrls.add(removed.url)
            }
            refreshImagesUi()
        }
        rvSelectedImages?.layoutManager =
            LinearLayoutManager(themed, LinearLayoutManager.HORIZONTAL, false)
        rvSelectedImages?.adapter = imageThumbAdapter

        btnPickImages?.setOnClickListener {
            if (imageItems.size >= MAX_IMAGES) {
                toast(R.string.classified_max_images)
                return@setOnClickListener
            }
            pickImages()
        }

        btnPublish?.setOnClickListener { publishAd() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val adId = editingAdId?.takeIf { it.isNotBlank() }
        if (adId != null) {
            toolbar?.title = getString(R.string.classified_edit_ad)
            loadExistingAd(adId)
        } else {
            refreshImagesUi()
        }

        return root
    }

    private fun loadExistingAd(adId: String) {
        val s = scope ?: return
        s.launch {
            val ad = ClassifiedAdRepository.getAdById(adId)
            if (fragmentView == null) return@launch
            val uid = currentUid()
            if (ad == null || ad.ownerUid != uid) {
                toast(R.string.classified_ad_not_found)
                removeSelfFromStack()
                return@launch
            }
            existingCreatedAt = ad.createdAt
            etModel?.setText(ad.model)
            etTitle?.setText(ad.title)
            etPrice?.setText(if (ad.price > 0L) ad.price.toString() else "")
            etAddress?.setText(ad.address)
            etPhone?.setText(ad.phone)
            etDescription?.setText(ad.description)

            val categoryIndex = ClassifiedAdCategories.all.indexOfFirst { it.id == ad.category }
            if (categoryIndex >= 0) spinnerCategory?.setSelection(categoryIndex)

            imageItems.clear()
            ad.imageUrls.forEach { url ->
                if (url.isNotBlank()) {
                    imageItems.add(ClassifiedAdImageThumbAdapter.ImageItem.Remote(url))
                }
            }
            refreshImagesUi()
        }
    }

    private fun pickImages() {
        val activity = parentActivity ?: return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(Intent.createChooser(intent, "Rasm tanlang"), REQ_PICK_IMAGES)
        } catch (e: ActivityNotFoundException) {
            toast("Rasm tanlagich topilmadi")
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_IMAGES || resultCode != Activity.RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        val slotsLeft = MAX_IMAGES - imageItems.size
        if (slotsLeft <= 0) {
            toast(R.string.classified_max_images)
            return
        }
        uris.take(slotsLeft).forEach { uri ->
            imageItems.add(ClassifiedAdImageThumbAdapter.ImageItem.Local(uri))
        }
        refreshImagesUi()
    }

    private fun refreshImagesUi() {
        imageThumbAdapter?.submit(imageItems.toList())
        rvSelectedImages?.visibility = if (imageItems.isEmpty()) View.GONE else View.VISIBLE
        tvPickImagesLabel?.text = if (imageItems.isEmpty()) {
            getString(R.string.classified_add_photos)
        } else {
            getString(R.string.classified_pick_images_count, imageItems.size, MAX_IMAGES)
        }
    }

    private fun publishAd() {
        if (publishing) return
        if (currentUid().isBlank()) {
            toast(R.string.classified_sign_in_required)
            return
        }
        val title = etTitle?.text?.toString()?.trim().orEmpty()
        val model = etModel?.text?.toString()?.trim().orEmpty()
        val description = etDescription?.text?.toString()?.trim().orEmpty()
        val address = etAddress?.text?.toString()?.trim().orEmpty()
        val phone = etPhone?.text?.toString()?.trim().orEmpty()
        val price = (etPrice?.text?.toString()?.filter { it.isDigit() } ?: "").toLongOrNull() ?: 0L

        if (model.isBlank()) { etModel?.error = getString(R.string.classified_model_required); return }
        if (title.isBlank()) { etTitle?.error = getString(R.string.classified_title_required); return }
        if (description.isBlank()) { etDescription?.error = getString(R.string.classified_description_required); return }
        if (address.isBlank()) { etAddress?.error = getString(R.string.classified_address_required); return }
        if (phone.isBlank()) { etPhone?.error = getString(R.string.classified_phone_required); return }
        if (imageItems.isEmpty()) { toast(R.string.classified_images_required); return }

        val category = ClassifiedAdCategories.all
            .getOrNull(spinnerCategory?.selectedItemPosition ?: -1)?.id
            ?: ClassifiedAdCategories.OTHER

        val localUris = imageItems.filterIsInstance<ClassifiedAdImageThumbAdapter.ImageItem.Local>()
            .map { it.uri }
        val existingUrls = imageItems.filterIsInstance<ClassifiedAdImageThumbAdapter.ImageItem.Remote>()
            .map { it.url }
        val adId = editingAdId?.takeIf { it.isNotBlank() } ?: System.currentTimeMillis().toString()

        val user = UserConfig.getInstance(currentAccount).currentUser
        val ownerName = listOfNotNull(
            user?.first_name?.takeIf { it.isNotBlank() },
            user?.last_name?.takeIf { it.isNotBlank() }
        ).joinToString(" ").orEmpty()

        val account = currentAccount
        publishing = true
        btnPublish?.isEnabled = false
        uploadProgress?.visibility = View.VISIBLE
        uploadProgress?.progress = 0

        val s = scope ?: return
        s.launch {
            try {
                val uid = YuzGramAdsAuth.ensureSignedIn(account)
                val uploaded = ClassifiedAdStorageHelper.uploadImages(uid, adId, localUris) { pct ->
                    uploadProgress?.progress = pct
                }
                val allUrls = (existingUrls + uploaded).filter { it.isNotBlank() }.take(5)
                val ad = ClassifiedAd(
                    id = adId,
                    title = title,
                    model = model,
                    description = description,
                    price = price,
                    category = category,
                    imageUrls = allUrls,
                    address = address,
                    phone = phone,
                    ownerUid = uid,
                    ownerName = ownerName,
                    ownerPhotoUrl = "",
                    createdAt = existingCreatedAt,
                    isActive = true,
                    cardTheme = ClassifiedAdCardThemes.BLACK
                )
                ClassifiedAdRepository.saveAd(ad)
                if (removedRemoteUrls.isNotEmpty()) {
                    ClassifiedAdStorageHelper.deleteImages(removedRemoteUrls.toList())
                }
                toast(R.string.classified_published)
                removeSelfFromStack()
            } catch (e: Exception) {
                publishing = false
                btnPublish?.isEnabled = true
                uploadProgress?.visibility = View.GONE
                toast(e.message ?: getString(R.string.classified_publish_failed))
            }
        }
    }

    private fun toast(text: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    // View'da getString() yo'q (faqat Context'da bor) — shuning uchun o'z helperimiz.
    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }
}
