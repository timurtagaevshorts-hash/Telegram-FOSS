package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.ClassifiedAdImageThumbAdapter
import uz.kodlikino.top4.models.ClassifiedAdCategories
import uz.kodlikino.top4.services.ClassifiedAdUploadService
import uz.kodlikino.top4.utils.ClassifiedAdRepository
import uz.kodlikino.top4.utils.ClassifiedLocationHelper
import uz.kodlikino.top4.utils.YuzAdModeration
import uz.kodlikino.top4.utils.YuzAdUploads
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPriceInput

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
 * Tema tanlagich o'chirilgan (faqat black). Joylashuv faqat viloyat/tuman
 * tanlagichi orqali kiritiladi — erkin matnli manzil maydoni yo'q.
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
    private var btnPickRegion: MaterialButton? = null
    private var selectedRegion = ""
    private var etPhone: TextInputEditText? = null
    private var etDescription: TextInputEditText? = null
    private var btnPublish: MaterialButton? = null

    private val imageItems = mutableListOf<ClassifiedAdImageThumbAdapter.ImageItem>()
    private var imageThumbAdapter: ClassifiedAdImageThumbAdapter? = null
    private var existingCreatedAt: Long = System.currentTimeMillis()
    private var existingAddress = ""
    private val removedRemoteUrls = mutableListOf<String>()

    private var scope: CoroutineScope? = null
    private var publishing = false

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

    override fun createView(context: Context): View {
        val themed = YuzTheme.wrap(context)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_create_classified_ad, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        toolbar = root.findViewById(R.id.toolbar)
        spinnerCategory = root.findViewById(R.id.spinnerCategory)
        btnPickImages = root.findViewById(R.id.btnPickImages)
        tvPickImagesLabel = root.findViewById(R.id.tvPickImagesLabel)
        rvSelectedImages = root.findViewById(R.id.rvSelectedImages)
        etModel = root.findViewById(R.id.etModel)
        etTitle = root.findViewById(R.id.etTitle)
        etPrice = root.findViewById(R.id.etPrice)
        btnPickRegion = root.findViewById(R.id.btnPickRegion)
        etPhone = root.findViewById(R.id.etPhone)
        etDescription = root.findViewById(R.id.etDescription)
        btnPublish = root.findViewById(R.id.btnPublish)

        toolbar?.apply {
            YuzTopInset.applyPadding(this)
            setNavigationOnClickListener { removeSelfFromStack() }
        }

        // #75: viloyat/tuman tanlagichi — filtr qidiruvi uchun region maydoni to'lsin.
        btnPickRegion?.setOnClickListener {
            val ctx = themedContext ?: return@setOnClickListener
            ClassifiedLocationHelper.showLocationPicker(ctx) { loc ->
                selectedRegion = loc
                // "Tozalash" tanlansa eski manzil ham qolmasin (aks holda u card'da ko'rinadi).
                if (loc.isBlank()) existingAddress = ""
                btnPickRegion?.text = if (loc.isBlank()) {
                    getString(R.string.classified_region_optional)
                } else {
                    loc
                }
            }
        }
        attachErrorReset(etModel, etTitle, etPhone, etDescription)

        // Kategoriya ro'yxati: emoji o'rniga chizilgan ikon (har punktda), shunda
        // tanlangan holat ham belgi bilan o'qiladi.
        val categoryLabels = ClassifiedAdCategories.all.map { it.label }
        spinnerCategory?.adapter = object : ArrayAdapter<String>(
            themed, R.layout.item_classified_spinner, categoryLabels
        ) {
            private fun withIcon(view: View, position: Int): View {
                (view as? android.widget.TextView)?.setIconStart(
                    ClassifiedAdCategories.all[position].icon, sizeDp = 16, gapDp = 8
                )
                return view
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                withIcon(super.getView(position, convertView, parent), position)

            override fun getDropDownView(
                position: Int, convertView: View?, parent: ViewGroup
            ): View = withIcon(super.getDropDownView(position, convertView, parent), position)
        }

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
        etPrice?.let { YuzPriceInput.attach(it) }

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
            etPrice?.setText(if (ad.price > 0L) YuzPriceInput.format(ad.price.toString()) else "")
            existingAddress = ad.address
            selectedRegion = ad.region
            if (ad.region.isNotBlank()) btnPickRegion?.text = ad.region
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

    /**
     * Yuklashni fonda boshlaydi va ekranni darhol yopadi.
     *
     * Nega shunday (2026-10-26 "juda sekin, ilovani ishlataversin" shikoyati):
     * avval yuklash shu fragment scope'ida ketardi — ekrandan chiqilsa uzilardi,
     * shuning uchun orqaga qaytish bloklanib, ekran yoniq ushlab turilardi.
     * Endi ish [ClassifiedAdUploadService]ga o'tadi: foydalanuvchi ilovadan
     * bemalol foydalanadi, holat Elonlar feed'idagi banner + bildirishnomada
     * ko'rinadi, xato bo'lsa o'sha yerdan "Qayta urinish" bor.
     */
    private fun publishAd() {
        if (publishing) return
        if (currentUid().isBlank()) {
            toast(R.string.classified_sign_in_required)
            return
        }
        val title = etTitle?.text?.toString()?.trim().orEmpty()
        val model = etModel?.text?.toString()?.trim().orEmpty()
        val description = etDescription?.text?.toString()?.trim().orEmpty()
        val phone = etPhone?.text?.toString()?.trim().orEmpty()
        val price = YuzPriceInput.parse(etPrice?.text) ?: 0L

        if (model.isBlank()) { showFieldError(etModel, R.string.classified_model_required); return }
        if (title.isBlank()) { showFieldError(etTitle, R.string.classified_title_required); return }
        if (description.isBlank()) {
            showFieldError(etDescription, R.string.classified_description_required); return
        }
        if (phone.isBlank()) { showFieldError(etPhone, R.string.classified_phone_required); return }
        if (phone.count { it.isDigit() } < 7) {
            showFieldError(etPhone, R.string.classified_phone_invalid); return
        }
        if (imageItems.isEmpty()) { toast(R.string.classified_images_required); return }

        val category = ClassifiedAdCategories.all
            .getOrNull(spinnerCategory?.selectedItemPosition ?: -1)?.id
            ?: ClassifiedAdCategories.OTHER

        val localUris = imageItems.filterIsInstance<ClassifiedAdImageThumbAdapter.ImageItem.Local>()
            .map { it.uri.toString() }
        val existingUrls = imageItems.filterIsInstance<ClassifiedAdImageThumbAdapter.ImageItem.Remote>()
            .map { it.url }
        val adId = editingAdId?.takeIf { it.isNotBlank() } ?: System.currentTimeMillis().toString()
        // Erkin matnli manzil maydoni yo'q — joylashuv viloyat/tuman
        // tanlagichidan olinadi; eski e'lonning manzili saqlanadi.
        val region = selectedRegion
        val now = System.currentTimeMillis()

        val upload = YuzAdUploads.Upload(
            id = adId,
            adId = adId,
            account = currentAccount,
            title = title,
            model = model,
            description = description,
            price = price,
            category = category,
            phone = phone,
            region = region,
            address = region.ifBlank { existingAddress },
            createdAt = existingCreatedAt,
            localUris = localUris,
            existingUrls = existingUrls,
            removedUrls = removedRemoteUrls.toList(),
            startedAt = now
        )

        val ctx = parentActivity ?: context ?: org.telegram.messenger.ApplicationLoader.applicationContext
            ?: return
        // Ikki marta bosish bitta e'lonni ikki marta yuklashga urinmasin.
        publishing = true
        try {
            YuzAdUploads.begin(upload)
            ClassifiedAdUploadService.startUpload(ctx, adId)
        } catch (e: Exception) {
            publishing = false
            YuzAdUploads.complete(adId)
            toast(e.message ?: getString(R.string.classified_publish_failed))
            return
        }
        toast(
            if (YuzAdModeration.cachedEnabled()) {
                R.string.classified_submitted_for_review
            } else {
                R.string.classified_upload_started
            }
        )
        removeSelfFromStack()
    }

    /** Xatoni maydon ichida ko'rsatadi va fokus bilan ekranni o'sha yerga suradi. */
    private fun showFieldError(field: TextInputEditText?, resId: Int) {
        val target = field ?: return
        target.error = getString(resId)
        target.requestFocus()
    }

    /** Foydalanuvchi yozishni boshlaganda eski xato yozuvi yo'qolsin. */
    private fun attachErrorReset(vararg fields: TextInputEditText?) {
        fields.filterNotNull().forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (field.error != null) field.error = null
                }
            })
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
