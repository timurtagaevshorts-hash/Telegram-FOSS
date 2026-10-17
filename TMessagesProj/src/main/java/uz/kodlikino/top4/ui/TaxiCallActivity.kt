package uz.kodlikino.top4.ui

import android.Manifest
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
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.TaxiOrder
import uz.kodlikino.top4.utils.TaksiGeo
import uz.kodlikino.top4.utils.TaxiOrderRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * Taksi chaqirish — xarita markazidagi pin orqali olish nuqtasi tanlanadi
 * (osmdroid), manzil nomi Nominatim/Geocoder orqali aniqlanadi.
 * Buyurtma `taxi_orders` kolleksiyasiga koordinatalar bilan yoziladi.
 */
class TaxiCallActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var mapView: MapView? = null
    private var tvPickup: TextView? = null
    private var etPhone: TextInputEditText? = null
    private var etDestination: TextInputEditText? = null
    private var progress: ProgressBar? = null
    private var btnSubmit: MaterialButton? = null
    private var btnRecenter: FloatingActionButton? = null
    private var activeCard: LinearLayout? = null
    private var tvActiveStatus: TextView? = null
    private var btnCancel: MaterialButton? = null

    private var scope: CoroutineScope? = null
    private val repo = TaxiOrderRepository()
    private var ordersListener: ListenerRegistration? = null
    private var uid: String = ""
    private var busy = false
    private var activeOrderId: String = ""

    private var latitude = TaksiGeo.DEFAULT_LAT
    private var longitude = TaksiGeo.DEFAULT_LNG
    private var addressLabel = ""
    private var hasCoords = false
    private var geocodeJob: Job? = null
    private var geocodeSeq = 0

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_taksi_call, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        val toolbar = root.findViewById<Toolbar>(R.id.taksiToolbar)
        YuzTopInset.applyPadding(toolbar)
        toolbar.setNavigationOnClickListener { removeSelfFromStack() }

        mapView = root.findViewById(R.id.taksiMap)
        tvPickup = root.findViewById(R.id.tvTaxiPickup)
        etPhone = root.findViewById(R.id.etTaxiPhone)
        etDestination = root.findViewById(R.id.etTaxiDestination)
        progress = root.findViewById(R.id.taksiProgress)
        btnSubmit = root.findViewById(R.id.btnTaxiSubmit)
        btnRecenter = root.findViewById(R.id.btnTaxiRecenter)
        activeCard = root.findViewById(R.id.taksiActiveCard)
        tvActiveStatus = root.findViewById(R.id.tvTaxiActiveStatus)
        btnCancel = root.findViewById(R.id.btnTaxiCancel)

        btnSubmit?.setOnClickListener { submitOrder() }
        btnCancel?.setOnClickListener { cancelActiveOrder() }
        btnRecenter?.setOnClickListener { moveToMyLocation() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        uid = YuzGramAdsAuth.expectedUid(currentAccount)

        setupMap()
        startListening()
        return root
    }

    private fun setupMap() {
        val map = mapView ?: return
        // Osmdroid/xarita katlamany any bir sabab bilan yiqilishi mumkin
        // (kesh, tarmoq, ROM). Bu ekran CRASH qilmasligi kerak: xarita
        // bo'lmasa ham buyurtma standart koordinatalar bilan yuboriladi.
        try {
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setTilesScaledToDpi(true)
            map.setMultiTouchControls(true)
            map.controller.setZoom(15.0)
            map.controller.setCenter(GeoPoint(latitude, longitude))
            tvPickup?.text = getString(R.string.taksi_locating)
            map.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    scheduleGeocode()
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    scheduleGeocode()
                    return false
                }
            })
        } catch (e: Exception) {
            try {
                map.visibility = View.GONE
            } catch (_: Exception) {
            }
            tvPickup?.text = getString(R.string.taksi_map_failed)
            addressLabel = ""
        }
        ensureLocationPermission()
        moveToMyLocation()
    }

    private fun ensureLocationPermission() {
        val ctx = themedContext ?: context ?: return
        if (TaksiGeo.hasLocationPermission(ctx)) return
        val activity = parentActivity ?: return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            0x7102
        )
    }

    private fun moveToMyLocation() {
        val s = scope ?: return
        val ctx = themedContext ?: context ?: return
        s.launch {
            val loc = TaksiGeo.currentLocation(ctx) ?: return@launch
            val map = mapView ?: return@launch
            latitude = loc.latitude
            longitude = loc.longitude
            hasCoords = true
            try {
                map.controller.animateTo(GeoPoint(latitude, longitude))
            } catch (_: Exception) {
            }
            scheduleGeocode(immediate = true)
        }
    }

    private fun scheduleGeocode(immediate: Boolean = false) {
        val map = mapView ?: return
        val point = map.mapCenter
        latitude = point.latitude
        longitude = point.longitude
        hasCoords = true
        val s = scope ?: return
        geocodeJob?.cancel()
        val seq = ++geocodeSeq
        geocodeJob = s.launch {
            if (!immediate) delay(550)
            if (seq != geocodeSeq) return@launch
            tvPickup?.text = TaksiGeo.formatCoords(latitude, longitude)
            val ctx = themedContext ?: return@launch
            val resolved = TaksiGeo.reverseGeocode(ctx, latitude, longitude)
            if (seq != geocodeSeq) return@launch
            addressLabel = resolved
            tvPickup?.text = resolved.ifBlank { TaksiGeo.formatCoords(latitude, longitude) }
        }
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        try {
            mapView?.onResume()
        } catch (_: Exception) {
        }
    }

    override fun onFragmentDestroy() {
        ordersListener?.remove()
        ordersListener = null
        geocodeJob?.cancel()
        try {
            mapView?.onPause()
        } catch (_: Exception) {
        }
        mapView = null
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    private fun startListening() {
        if (uid.isBlank()) return
        // Ikki marta chaqirilsa (submit'dan keyin) eski dinlovchi takrorlanmasin.
        try {
            ordersListener?.remove()
        } catch (_: Exception) {
        }
        ordersListener = try {
            repo.listenUserOrders(uid, onChange = { orders ->
                try {
                    val active = orders.firstOrNull { repo.isActiveTrackingStatus(it.status) }
                    if (active != null) {
                        activeOrderId = active.id
                        activeCard?.visibility = View.VISIBLE
                        tvActiveStatus?.text = statusText(active)
                    } else {
                        activeOrderId = ""
                        activeCard?.visibility = View.GONE
                    }
                } catch (_: Exception) {
                }
            }, onError = {
                // Ruxsat/indeks xatosi foydachini crush qilmaydi — jim o'tamiz.
            })
        } catch (_: Exception) {
            null
        }
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
        val destination = etDestination?.text?.toString()?.trim().orEmpty()
        if (phone.isBlank()) {
            toast(R.string.taksi_fill_phone)
            return
        }
        if (!hasCoords) {
            toast(R.string.taksi_pick_location)
            return
        }
        val pickupLabel = addressLabel.ifBlank { TaksiGeo.formatCoords(latitude, longitude) }
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
                    id = java.util.UUID.randomUUID().toString(),
                    userId = uid,
                    userName = name,
                    phone = phone,
                    latitude = latitude,
                    longitude = longitude,
                    addressLabel = pickupLabel,
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
