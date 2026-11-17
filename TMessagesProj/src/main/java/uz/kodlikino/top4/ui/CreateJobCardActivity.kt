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
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.JobCardCategories
import uz.kodlikino.top4.models.JobCardListingTypes
import uz.kodlikino.top4.models.JobCardSalaryTypes
import uz.kodlikino.top4.services.ClassifiedAdUploadService
import uz.kodlikino.top4.utils.ClassifiedLocationHelper
import uz.kodlikino.top4.utils.JobCardRepository
import uz.kodlikino.top4.utils.YuzAdModeration
import uz.kodlikino.top4.utils.YuzAdUploads
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPriceInput

/**
 * "Ish kartasi qo'shish" — [BaseFragment] ko'rinishida.
 * KinoTOP4'dagi [androidx.appcompat.app.AppCompatActivity] `CreateJobCardActivity`
 * moslashtirildi: PremiumManager/UserBlockRepository/ClassifiedPriceInputHelper/
 * region spinner (ClassifiedLocationHelper) bu fork'da yo'q — shuning uchun
 * narx raqam filtri bilan, joylashuv faqat viloyat/tuman tanlagichi orqali,
 * mavzu doim qora, premium/blok
 * tekshiruvisiz. Rasm tanlash `onActivityResultFragment`, yozuvdan oldin
 * [YuzGramAdsAuth.ensureSignedIn] chaqiriladi.
 */
class CreateJobCardActivity(
    private val initialListingType: String = JobCardListingTypes.HIRING,
    private val editCardId: String = ""
) : BaseFragment() {

    private companion object {
        const val REQ_PICK_IMAGE = 4472
    }

    private var themedContext: Context? = null
    private var toolbar: Toolbar? = null
    private var spinnerListingType: Spinner? = null
    private var btnPickImage: View? = null
    private var ivPreview: ImageView? = null
    private var tvPickImageLabel: TextView? = null
    private var tilTitle: TextInputLayout? = null
    private var etTitle: TextInputEditText? = null
    private var etDescription: TextInputEditText? = null
    private var salarySection: View? = null
    private var tilSalary: TextInputLayout? = null
    private var etSalary: TextInputEditText? = null
    private var spinnerSalaryType: Spinner? = null
    private var etPhone: TextInputEditText? = null
    private var btnPickRegion: MaterialButton? = null
    private var selectedRegion = ""
    private var btnPublish: MaterialButton? = null

    private var pickedUri: Uri? = null
    private var existingImageUrl = ""
    private var existingCreatedAt = 0L
    private var publishing = false
    private var scope: CoroutineScope? = null

    private val isEditMode: Boolean get() = editCardId.isNotBlank()

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

    override fun createView(context: Context): View {
        val themed = YuzTheme.wrap(context)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_create_job_card, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        toolbar = root.findViewById(R.id.toolbar)
        spinnerListingType = root.findViewById(R.id.spinnerListingType)
        btnPickImage = root.findViewById(R.id.btnPickImage)
        ivPreview = root.findViewById(R.id.ivPreview)
        tvPickImageLabel = root.findViewById(R.id.tvPickImageLabel)
        tilTitle = root.findViewById(R.id.tilTitle)
        etTitle = root.findViewById(R.id.etTitle)
        etDescription = root.findViewById(R.id.etDescription)
        salarySection = root.findViewById(R.id.salarySection)
        tilSalary = root.findViewById(R.id.tilSalary)
        etSalary = root.findViewById(R.id.etSalary)
        spinnerSalaryType = root.findViewById(R.id.spinnerSalaryType)
        etPhone = root.findViewById(R.id.etPhone)
        btnPickRegion = root.findViewById(R.id.btnJobPickRegion)
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
                btnPickRegion?.text = if (loc.isBlank()) {
                    getString(R.string.classified_region_optional)
                } else {
                    loc
                }
            }
        }
        attachErrorReset(etTitle, etPhone)

        val typeLabels = listOf(
            getString(R.string.job_board_type_hiring),
            getString(R.string.job_board_type_seeking)
        )
        spinnerListingType?.adapter = ArrayAdapter(
            themed, R.layout.item_classified_spinner, typeLabels
        ).apply { setDropDownViewResource(R.layout.item_classified_spinner) }
        spinnerListingType?.setSelection(if (initialListingType == JobCardListingTypes.SEEKING_WORK) 1 else 0)

        spinnerSalaryType?.adapter = ArrayAdapter(
            themed,
            R.layout.item_classified_spinner,
            JobCardSalaryTypes.spinnerOrder.map { JobCardSalaryTypes.label(it) }
        ).apply { setDropDownViewResource(R.layout.item_classified_spinner) }

        spinnerListingType?.onItemSelectedListener = spinnerListener {
            updateFieldsForType(spinnerListingType?.selectedItemPosition ?: 0)
            updateSalaryFieldForType()
        }
        spinnerSalaryType?.onItemSelectedListener = spinnerListener { updateSalaryFieldForType() }

        btnPickImage?.setOnClickListener { pickImage() }
        btnPublish?.setOnClickListener { publish() }

        updateFieldsForType(spinnerListingType?.selectedItemPosition ?: 0)
        updateSalaryFieldForType()
        etSalary?.let { YuzPriceInput.attach(it) }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        if (isEditMode) {
            toolbar?.title = getString(R.string.classified_job_edit_title)
            loadCardForEdit()
        }
        return root
    }

    /** Mavjud ish e'lonini tahrirlash uchun maydonlarni to'ldiradi. */
    private fun loadCardForEdit() {
        val s = scope ?: return
        s.launch {
            val card = JobCardRepository.getCardById(editCardId)
            if (fragmentView == null) return@launch
            if (card == null || card.ownerUid != currentUid()) {
                toast(R.string.classified_ad_not_found)
                removeSelfFromStack()
                return@launch
            }
            existingCreatedAt = card.createdAt
            existingImageUrl = card.imageUrl
            spinnerListingType?.setSelection(
                if (card.listingType == JobCardListingTypes.SEEKING_WORK) 1 else 0
            )
            etTitle?.setText(card.title)
            etDescription?.setText(card.description)
            etPhone?.setText(card.phone)
            // Eski kartalarda joylashuv faqat `location`da bo'lishi mumkin.
            selectedRegion = card.region.ifBlank { card.location }
            if (selectedRegion.isNotBlank()) btnPickRegion?.text = selectedRegion
            val salaryIndex = JobCardSalaryTypes.spinnerOrder.indexOf(card.salaryType)
            if (salaryIndex >= 0) spinnerSalaryType?.setSelection(salaryIndex)
            if (card.salaryAmount > 0L) {
                etSalary?.setText(YuzPriceInput.format(card.salaryAmount.toString()))
            }
            if (card.imageUrl.isNotBlank()) {
                ivPreview?.visibility = View.VISIBLE
                tvPickImageLabel?.text = getString(R.string.job_board_change_image)
                ivPreview?.let {
                    Glide.with(it).load(card.imageUrl).centerCrop().into(it)
                }
            }
            updateFieldsForType(spinnerListingType?.selectedItemPosition ?: 0)
            updateSalaryFieldForType()
        }
    }

    private fun spinnerListener(block: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) =
                block()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    private fun updateFieldsForType(spinnerIndex: Int) {
        val hiring = spinnerIndex == 0
        salarySection?.visibility = if (hiring) View.VISIBLE else View.GONE
        tilTitle?.hint = if (hiring) {
            getString(R.string.job_board_title_hiring_hint)
        } else {
            getString(R.string.job_board_title_seeking_hint)
        }
    }

    private fun selectedSalaryType(): String =
        JobCardSalaryTypes.spinnerOrder.getOrNull(spinnerSalaryType?.selectedItemPosition ?: -1)
            ?: JobCardSalaryTypes.NEGOTIABLE

    private fun updateSalaryFieldForType() {
        if (spinnerListingType?.selectedItemPosition != 0) return
        val negotiable = selectedSalaryType() == JobCardSalaryTypes.NEGOTIABLE
        tilSalary?.visibility = if (negotiable) View.GONE else View.VISIBLE
        if (negotiable) {
            etSalary?.text?.clear()
            etSalary?.error = null
        }
    }

    private fun pickImage() {
        val activity = parentActivity ?: return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(Intent.createChooser(intent, "Rasm tanlang"), REQ_PICK_IMAGE)
        } catch (e: ActivityNotFoundException) {
            toast("Rasm tanlagich topilmadi")
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_IMAGE || resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: data.data ?: return
        pickedUri = uri
        ivPreview?.visibility = View.VISIBLE
        val ctx = themedContext ?: return
        ivPreview?.let { Glide.with(ctx).load(uri).centerCrop().into(it) }
        tvPickImageLabel?.text = getString(R.string.job_board_change_image)
    }

    private fun publish() {
        if (publishing) return
        if (currentUid().isBlank()) {
            toast(R.string.classified_sign_in_required)
            return
        }
        val listingType = if (spinnerListingType?.selectedItemPosition == 1) {
            JobCardListingTypes.SEEKING_WORK
        } else {
            JobCardListingTypes.HIRING
        }
        val title = etTitle?.text?.toString()?.trim().orEmpty()
        val description = etDescription?.text?.toString()?.trim().orEmpty()
        val phone = etPhone?.text?.toString()?.trim().orEmpty()

        if (title.isBlank()) {
            showFieldError(etTitle, R.string.job_board_title_required); return
        }
        if (phone.isBlank()) {
            showFieldError(etPhone, R.string.classified_phone_required); return
        }
        if (phone.count { it.isDigit() } < 7) {
            showFieldError(etPhone, R.string.classified_phone_invalid); return
        }

        val salaryAmount = if (listingType == JobCardListingTypes.HIRING &&
            selectedSalaryType() != JobCardSalaryTypes.NEGOTIABLE
        ) {
            YuzPriceInput.parse(etSalary?.text) ?: 0L
        } else {
            0L
        }
        val salaryType = if (listingType == JobCardListingTypes.HIRING) {
            selectedSalaryType()
        } else {
            JobCardSalaryTypes.NEGOTIABLE
        }

        val cardId = editCardId.ifBlank { System.currentTimeMillis().toString() }
        val now = System.currentTimeMillis()
        // Yuklash ekranni bloklamaydi: yozuv trekerga qo'yiladi va servis
        // rasmni Storage'ga, so'ng hujjatni Firestore'ga yozadi. Ekran yopiladi.
        val upload = YuzAdUploads.Upload(
            id = cardId,
            adId = cardId,
            kind = YuzAdUploads.Kind.JOB,
            account = currentAccount,
            title = title,
            model = "",
            description = description,
            price = 0L,
            category = JobCardCategories.fromListingType(listingType),
            phone = phone,
            region = selectedRegion,
            // Eski o'quvchilar faqat `address`ni ko'rsatadi — viloyat u yerga ham yoziladi.
            address = selectedRegion,
            createdAt = existingCreatedAt.takeIf { it > 0L } ?: now,
            localUris = listOfNotNull(pickedUri?.toString()),
            existingUrls = existingImageUrl.trim().takeIf { it.isNotBlank() }
                ?.let { listOf(it) } ?: emptyList(),
            removedUrls = emptyList(),
            listingType = listingType,
            salaryType = salaryType,
            salaryAmount = salaryAmount,
            startedAt = now
        )
        val ctx = parentActivity ?: context ?: ApplicationLoader.applicationContext ?: return
        publishing = true
        try {
            YuzAdUploads.begin(upload)
            ClassifiedAdUploadService.startUpload(ctx, cardId)
        } catch (e: Exception) {
            YuzAdUploads.complete(cardId)
            publishing = false
            toast(e.message ?: getString(R.string.classified_publish_failed))
            return
        }
        toast(
            if (YuzAdModeration.cachedEnabled()) {
                R.string.classified_submitted_for_review
            } else {
                R.string.job_board_upload_started
            }
        )
        removeSelfFromStack()
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

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }
}
