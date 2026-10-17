package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
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
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.ClassifiedAdCardThemes
import uz.kodlikino.top4.models.JobCard
import uz.kodlikino.top4.models.JobCardListingTypes
import uz.kodlikino.top4.models.JobCardSalaryTypes
import uz.kodlikino.top4.utils.JobCardRepository
import uz.kodlikino.top4.utils.JobCardStorageHelper
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzUploadUi

/**
 * "Ish kartasi qo'shish" — [BaseFragment] ko'rinishida.
 * KinoTOP4'dagi [androidx.appcompat.app.AppCompatActivity] `CreateJobCardActivity`
 * moslashtirildi: PremiumManager/UserBlockRepository/ClassifiedPriceInputHelper/
 * region spinner (ClassifiedLocationHelper) bu fork'da yo'q — shuning uchun
 * narx raqam filtri bilan, manzil oddiy matn bilan, mavzu doim qora, premium/blok
 * tekshiruvisiz. Rasm tanlash `onActivityResultFragment`, yozuvdan oldin
 * [YuzGramAdsAuth.ensureSignedIn] chaqiriladi.
 */
class CreateJobCardActivity(
    private val initialListingType: String = JobCardListingTypes.HIRING
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
    private var etLocation: TextInputEditText? = null
    private var uploadProgress: ProgressBar? = null
    private var btnPublish: MaterialButton? = null

    private var pickedUri: Uri? = null
    private var publishing = false
    private var scope: CoroutineScope? = null

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
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
        etLocation = root.findViewById(R.id.etLocation)
        uploadProgress = root.findViewById(R.id.uploadProgress)
        btnPublish = root.findViewById(R.id.btnPublish)

        toolbar?.apply {
            YuzTopInset.applyPadding(this)
            setNavigationOnClickListener { removeSelfFromStack() }
        }

        etLocation?.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
        }

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

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return root
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
        val location = etLocation?.text?.toString()?.trim().orEmpty()

        if (title.isBlank()) {
            etTitle?.error = getString(R.string.job_board_title_required); return
        }
        if (phone.isBlank()) {
            etPhone?.error = getString(R.string.classified_phone_required); return
        }

        val salaryAmount = if (listingType == JobCardListingTypes.HIRING &&
            selectedSalaryType() != JobCardSalaryTypes.NEGOTIABLE
        ) {
            (etSalary?.text?.toString()?.filter { it.isDigit() } ?: "").toLongOrNull() ?: 0L
        } else {
            0L
        }
        val salaryType = if (listingType == JobCardListingTypes.HIRING) {
            selectedSalaryType()
        } else {
            JobCardSalaryTypes.NEGOTIABLE
        }

        val user = UserConfig.getInstance(currentAccount).currentUser
        val ownerName = listOfNotNull(
            user?.first_name?.takeIf { it.isNotBlank() },
            user?.last_name?.takeIf { it.isNotBlank() }
        ).joinToString(" ").orEmpty()

        val cardId = System.currentTimeMillis().toString()
        val account = currentAccount
        publishing = true
        btnPublish?.isEnabled = false
        uploadProgress?.visibility = View.VISIBLE
        keepScreenOn(true)

        val s = scope ?: return
        s.launch {
            try {
                val uid = YuzGramAdsAuth.ensureSignedIn(account)
                var imageUrl = ""
                pickedUri?.let { uri ->
                    val ctx = themedContext
                        ?: org.telegram.messenger.ApplicationLoader.applicationContext
                    imageUrl = JobCardStorageHelper.uploadImage(uid, cardId, uri)
                    if (imageUrl.isBlank()) {
                        imageUrl = JobCardRepository.findLatestImageUrlForOwner(uid, excludeId = cardId)
                    }
                }
                if (imageUrl.isBlank()) {
                    imageUrl = JobCardRepository.findLatestImageUrlForOwner(uid, excludeId = cardId)
                }
                val card = JobCard(
                    id = cardId,
                    listingType = listingType,
                    title = title,
                    description = description,
                    location = location,
                    phone = phone,
                    salaryAmount = salaryAmount,
                    salaryType = salaryType,
                    imageUrl = imageUrl,
                    cardTheme = ClassifiedAdCardThemes.BLACK,
                    ownerUid = uid,
                    ownerName = ownerName,
                    ownerPhotoUrl = "",
                    region = ""
                )
                JobCardRepository.saveCard(card)
                keepScreenOn(false)
                toast(R.string.job_board_published)
                removeSelfFromStack()
            } catch (e: Exception) {
                publishing = false
                btnPublish?.isEnabled = true
                uploadProgress?.visibility = View.GONE
                keepScreenOn(false)
                val reason = e.message ?: getString(R.string.classified_publish_failed)
                YuzUploadUi.showError(themedContext ?: context, reason) { publish() }
            }
        }
    }

    private fun keepScreenOn(on: Boolean) {
        YuzUploadUi.keepScreenOn(parentActivity, on)
    }

    override fun onBackPressed(): Boolean {
        if (publishing) {
            toast(R.string.classified_saving)
            return false
        }
        return super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        keepScreenOn(false)
        super.onFragmentDestroy()
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
