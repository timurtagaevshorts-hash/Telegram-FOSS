package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
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
import uz.kodlikino.top4.models.TaxiOrder
import uz.kodlikino.top4.utils.TaxiOrderRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * Taksi chaqirish — buyurtmani `taxi_orders` kolleksiyasiga yozadi.
 * Deploy qilingan `onTaxiOrderCreated` Cloud Function buyurtmani administrator
 * Telegram botiga yuboradi (token server-side, APK'da yo'q).
 */
class TaxiCallActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var etPhone: TextInputEditText? = null
    private var etPickup: TextInputEditText? = null
    private var etDestination: TextInputEditText? = null
    private var progress: ProgressBar? = null
    private var btnSubmit: MaterialButton? = null
    private var activeCard: LinearLayout? = null
    private var tvActiveStatus: TextView? = null
    private var btnCancel: MaterialButton? = null

    private var scope: CoroutineScope? = null
    private val repo = TaxiOrderRepository()
    private var ordersListener: ListenerRegistration? = null
    private var uid: String = ""
    private var busy = false
    private var activeOrderId: String = ""

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_taksi_call, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE

        val toolbar = root.findViewById<Toolbar>(R.id.taksiToolbar)
        toolbar.setPadding(
            toolbar.paddingLeft, AndroidUtilities.statusBarHeight,
            toolbar.paddingRight, toolbar.paddingBottom
        )
        toolbar.setNavigationOnClickListener { removeSelfFromStack() }

        etPhone = root.findViewById(R.id.etTaxiPhone)
        etPickup = root.findViewById(R.id.etTaxiPickup)
        etDestination = root.findViewById(R.id.etTaxiDestination)
        progress = root.findViewById(R.id.taksiProgress)
        btnSubmit = root.findViewById(R.id.btnTaxiSubmit)
        activeCard = root.findViewById(R.id.taksiActiveCard)
        tvActiveStatus = root.findViewById(R.id.tvTaxiActiveStatus)
        btnCancel = root.findViewById(R.id.btnTaxiCancel)

        btnSubmit?.setOnClickListener { submitOrder() }
        btnCancel?.setOnClickListener { cancelActiveOrder() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        uid = YuzGramAdsAuth.expectedUid(currentAccount)
        startListening()
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        ordersListener?.remove()
        ordersListener = null
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    private fun startListening() {
        if (uid.isBlank()) return
        ordersListener = repo.listenUserOrders(uid, onChange = { orders ->
            val active = orders.firstOrNull { repo.isActiveTrackingStatus(it.status) }
            if (active != null) {
                activeOrderId = active.id
                activeCard?.visibility = View.VISIBLE
                tvActiveStatus?.text = statusText(active)
            } else {
                activeOrderId = ""
                activeCard?.visibility = View.GONE
            }
        })
    }

    private fun statusText(order: TaxiOrder): String {
        val base = when (order.status) {
            TaxiOrder.STATUS_PENDING -> getString(R.string.taksi_status_pending)
            TaxiOrder.STATUS_APPROVED -> getString(R.string.taksi_status_approved)
            TaxiOrder.STATUS_REJECTED -> getString(R.string.taksi_status_rejected)
            TaxiOrder.STATUS_CANCELLED -> getString(R.string.taksi_status_cancelled)
            TaxiOrder.STATUS_ARRIVED -> getString(R.string.taksi_status_arrived)
            else -> order.status
        }
        return if (order.statusMessage.isNotBlank()) "$base — ${order.statusMessage}" else base
    }

    private fun submitOrder() {
        if (busy) return
        val phone = etPhone?.text?.toString()?.filter { it.isDigit() || it == '+' }.orEmpty().trim()
        val pickup = etPickup?.text?.toString()?.trim().orEmpty()
        val destination = etDestination?.text?.toString()?.trim().orEmpty()
        if (phone.isBlank() || pickup.isBlank()) {
            toast(R.string.taksi_fill_fields)
            return
        }
        val s = scope ?: return
        busy = true
        setBusyUi(true)
        s.launch {
            try {
                uid = YuzGramAdsAuth.ensureSignedIn(currentAccount)
                val user = UserConfig.getInstance(currentAccount).currentUser
                val name = listOfNotNull(
                    user?.first_name?.takeIf { it.isNotBlank() },
                    user?.last_name?.takeIf { it.isNotBlank() }
                ).joinToString(" ").orEmpty()
                val order = TaxiOrder(
                    id = System.currentTimeMillis().toString(),
                    userId = uid,
                    userName = name,
                    phone = phone,
                    addressLabel = pickup,
                    destinationLabel = destination
                )
                val result = repo.createOrder(order)
                setBusyUi(false)
                if (result.isSuccess) {
                    toast(R.string.taksi_created)
                    startListening()
                } else {
                    toast(getString(R.string.taksi_failed))
                }
            } catch (e: Exception) {
                setBusyUi(false)
                toast(getString(R.string.taksi_failed))
            }
        }
    }

    private fun cancelActiveOrder() {
        if (busy) return
        val orderId = activeOrderId
        if (orderId.isBlank()) return
        val s = scope ?: return
        busy = true
        btnCancel?.isEnabled = false
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(currentAccount)
                repo.cancelOrder(orderId)
                toast(R.string.taksi_cancelled_ok)
            } catch (e: Exception) {
                toast(getString(R.string.taksi_failed))
            } finally {
                busy = false
                btnCancel?.isEnabled = true
            }
        }
    }

    private fun setBusyUi(value: Boolean) {
        busy = value
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        btnSubmit?.isEnabled = !value
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
