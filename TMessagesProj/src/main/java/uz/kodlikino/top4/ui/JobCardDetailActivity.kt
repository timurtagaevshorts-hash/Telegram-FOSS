package uz.kodlikino.top4.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCardTheme
import uz.kodlikino.top4.models.JobCard
import uz.kodlikino.top4.models.JobCardListingTypes
import uz.kodlikino.top4.utils.JobCardRepository
import uz.kodlikino.top4.utils.JobCardStorageHelper
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * Ish kartasi bayoni — [BaseFragment]. Eski `JobCardDetailActivity`dan
 * do'stlarga ulashish (JobCardShareHelper, CommentAuthManager profiling) olib
 * tashlandi — fork'da u infra yo'q. Qolgan: qo'ng'iroq, egasi uchun o'chirish.
 */
class JobCardDetailActivity(
    private val cardId: String
) : BaseFragment() {

    private var themedContext: Context? = null
    private var toolbar: Toolbar? = null
    private var ivHero: ImageView? = null
    private var detailCard: MaterialCardView? = null
    private var tvType: TextView? = null
    private var tvTitle: TextView? = null
    private var tvSalary: TextView? = null
    private var tvLocation: TextView? = null
    private var tvDescription: TextView? = null
    private var btnCall: MaterialButton? = null
    private var btnDelete: MaterialButton? = null

    private var phoneNumber = ""
    private var loadedCard: JobCard? = null
    private var myUid: String = ""
    private var scope: CoroutineScope? = null

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_job_card_detail, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        toolbar = root.findViewById(R.id.toolbar)
        ivHero = root.findViewById(R.id.ivHero)
        detailCard = root.findViewById(R.id.detailCard)
        tvType = root.findViewById(R.id.tvType)
        tvTitle = root.findViewById(R.id.tvTitle)
        tvSalary = root.findViewById(R.id.tvSalary)
        tvLocation = root.findViewById(R.id.tvLocation)
        tvDescription = root.findViewById(R.id.tvDescription)
        btnCall = root.findViewById(R.id.btnCall)
        btnDelete = root.findViewById(R.id.btnDelete)

        toolbar?.apply {
            YuzTopInset.applyPadding(this)
            setNavigationOnClickListener { removeSelfFromStack() }
        }

        btnDelete?.visibility = View.GONE
        btnCall?.setOnClickListener {
            val phone = phoneNumber.trim()
            if (phone.isBlank()) {
                toast(R.string.classified_phone_missing)
                return@setOnClickListener
            }
            try {
                context?.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            } catch (e: ActivityNotFoundException) {
                toast("Telefon ilovasi topilmadi")
            }
        }

        btnDelete?.setOnClickListener { confirmDelete() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val s = scope ?: return root
        s.launch {
            val card = JobCardRepository.getCardById(cardId)
            myUid = YuzGramAdsAuth.expectedUid(currentAccount)
            if (fragmentView == null) return@launch
            if (card == null || !card.isActive ||
                (!card.isPubliclyVisible() && card.ownerUid != myUid)
            ) {
                toast(R.string.job_board_not_found)
                removeSelfFromStack()
                return@launch
            }
            bind(card)
        }
        return root
    }

    private fun bind(card: JobCard) {
        loadedCard = card
        phoneNumber = card.phone
        val themed = themedContext ?: return
        tvType?.text = "${JobCardListingTypes.emoji(card.listingType)} ${JobCardListingTypes.label(card.listingType)}"
        tvTitle?.text = card.title
        tvLocation?.text = card.location.ifBlank { getString(R.string.classified_address_missing) }
        tvDescription?.text = card.description.ifBlank { getString(R.string.classified_description_missing) }

        if (card.listingType == JobCardListingTypes.HIRING) {
            tvSalary?.visibility = View.VISIBLE
            tvSalary?.text = card.formattedSalary()
        } else {
            tvSalary?.visibility = View.GONE
        }

        val themeAd = ClassifiedAd(
            id = card.id,
            title = card.title,
            description = card.description,
            address = card.location,
            price = card.salaryAmount,
            cardTheme = card.cardTheme
        )
        val root = detailCard ?: return
        ClassifiedAdCardTheme.applyToCard(
            ad = themeAd,
            card = root,
            tvTitle = tvTitle ?: return,
            tvPrice = tvSalary ?: return,
            tvAddress = tvLocation,
            tvCategory = tvType ?: return,
            addressRow = tvLocation,
            tvModel = null
        )

        val url = card.displayImageUrl()
        ivHero?.visibility = View.VISIBLE
        if (url.isNotBlank()) {
            Glide.with(themed)
                .load(url)
                .centerCrop()
                .placeholder(R.drawable.bg_classified_image_placeholder)
                .error(R.drawable.bg_classified_image_placeholder)
                .into(ivHero!!)
        } else {
            ivHero?.setImageResource(R.drawable.bg_classified_image_placeholder)
        }

        val isOwner = myUid.isNotBlank() && card.ownerUid == myUid
        btnDelete?.visibility = if (isOwner) View.VISIBLE else View.GONE
    }

    private fun confirmDelete() {
        val card = loadedCard ?: return
        if (myUid.isBlank() || card.ownerUid != myUid) return
        val themed = themedContext ?: return
        AlertDialog.Builder(themed)
            .setTitle(R.string.classified_delete_confirm_title)
            .setMessage(R.string.classified_delete_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.classified_delete) { _, _ -> deleteCard(card) }
            .show()
    }

    private fun deleteCard(card: JobCard) {
        btnDelete?.isEnabled = false
        val s = scope ?: return
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(currentAccount)
                JobCardRepository.deleteCard(card.id)
                if (card.imageUrl.isNotBlank()) {
                    JobCardStorageHelper.deleteImage(card.imageUrl)
                }
                toast(R.string.classified_deleted)
                removeSelfFromStack()
            } catch (e: Exception) {
                btnDelete?.isEnabled = true
                toast(e.message ?: getString(R.string.classified_delete_failed))
            }
        }
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
