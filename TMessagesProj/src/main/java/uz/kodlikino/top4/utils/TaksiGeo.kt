package uz.kodlikino.top4.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Taksi xaritasi uchun GPS + manzil nomi.
 * YuzGram GMS-siz — shuning uchun FusedLocation o'rniga LocationManager,
 * manzil nomi esa OSM Nominatim (HTTP) + Android Geocoder (zaxira).
 */
object TaksiGeo {

    /** Donor KinoTOP4 bilan bir xil boshlang'ich nuqta. */
    const val DEFAULT_LAT = 40.0500
    const val DEFAULT_LNG = 65.6333

    private const val NOMINATIM_UA = "YuzGram/1.0 (uz.kodlikino.top4)"

    private val pinCache = ConcurrentHashMap<String, String>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun formatCoords(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lng)

    fun coordsKey(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f,%.5f", lat, lng)

    fun nearlyEqual(
        latA: Double, lngA: Double,
        latB: Double, lngB: Double
    ): Boolean = coordsKey(latA, lngA) == coordsKey(latB, lngB)

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            try {
                if (!lm.allProviders.contains(p)) continue
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            } catch (_: SecurityException) {
            }
        }
        val fresh = best?.takeIf { System.currentTimeMillis() - it.time < 90_000L }
        if (fresh != null) return@withContext fresh
        // So'nggi aniqtirishni so'rab ko'ramiz (qisqa kutish).
        val requested = withTimeoutOrNull(9_000L) { requestSingleFix(lm, providers) }
        requested ?: best
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(
        lm: LocationManager,
        providers: List<String>
    ): Location? = suspendCancellableCoroutine { cont ->
        var done = false
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!done) { done = true; cont.resume(location) }
            }
            @Deprecated("deprecated in API")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        var registered = false
        for (p in providers) {
            try {
                if (!lm.allProviders.contains(p)) continue
                lm.requestLocationUpdates(p, 0L, 0f, listener)
                registered = true
                break
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        cont.invokeOnCancellation {
            try { if (registered) lm.removeUpdates(listener) } catch (_: Exception) {}
        }
        if (!registered && !done) {
            done = true
            cont.resume(null)
        }
    }

    /** Xarita pin manzili — Nominatim birinchi, Android Geocoder zaxira. */
    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String =
        withContext(Dispatchers.IO) {
            if (!lat.isFinite() || !lng.isFinite() || (lat == 0.0 && lng == 0.0)) return@withContext ""
            val key = coordsKey(lat, lng)
            pinCache[key]?.let { return@withContext it }

            var label = nominatim(lat, lng)
            if (label.isBlank()) {
                label = androidGeocode(context, lat, lng)
            }
            if (label.isBlank()) {
                label = formatCoords(lat, lng)
            }
            if (label.isNotBlank() && !label.matches(COORDS)) {
                pinCache[key] = label
            }
            label
        }

    private val COORDS = Regex("""^-?\d+(\.\d+)?,\s*-?\d+(\.\d+)?$""")

    private fun nominatim(lat: Double, lng: Double): String {
        return try {
            val la = String.format(Locale.US, "%.7f", lat)
            val lo = String.format(Locale.US, "%.7f", lng)
            val url = "https://nominatim.openstreetmap.org/reverse" +
                "?lat=$la&lon=$lo&format=jsonv2&addressdetails=1&zoom=18&accept-language=uz,ru,en"
            val req = Request.Builder().url(url)
                .header("User-Agent", NOMINATIM_UA)
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return ""
                formatNominatim(JSONObject(body))
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatNominatim(json: JSONObject): String {
        val address = json.optJSONObject("address") ?: JSONObject()
        val display = json.optString("display_name").trim()
        val house = str(address, "house_number", "housenumber")
        val road = str(address, "road", "pedestrian", "footway", "street", "residential")
        val amenity = str(address, "amenity", "shop", "building", "tourism", "leisure")
        val area = str(address, "neighbourhood", "suburb", "quarter", "village", "city_district")
        val city = str(address, "city", "town", "municipality", "county")

        val streetLine = when {
            road.isNotBlank() && house.isNotBlank() -> "$road, $house"
            road.isNotBlank() -> road
            else -> ""
        }
        val parts = linkedSetOf<String>()
        val primary = when {
            amenity.isNotBlank() && amenity != road -> amenity
            streetLine.isNotBlank() -> streetLine
            area.isNotBlank() -> area
            else -> ""
        }
        if (primary.isNotBlank()) parts += primary
        if (streetLine.isNotBlank() && streetLine != primary) parts += streetLine
        if (area.isNotBlank() && !parts.any { it.equals(area, true) }) parts += area
        if (city.isNotBlank() && !parts.any { it.equals(city, true) }) parts += city

        val structured = parts.joinToString(", ")
        if (structured.isNotBlank() && !structured.matches(COORDS)) return structured

        val fromDisplay = display.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(3)
            .joinToString(", ")
        return fromDisplay
    }

    private fun str(json: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = json.optString(k).trim()
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun androidGeocode(context: Context, lat: Double, lng: Double): String {
        if (!Geocoder.isPresent()) return ""
        return try {
            val geocoder = Geocoder(context, Locale("uz", "UZ"))
            @Suppress("DEPRECATION")
            val list: List<Address>? = geocoder.getFromLocation(lat, lng, 3)
            val a = list?.firstOrNull() ?: return ""
            val street = a.thoroughfare?.trim().orEmpty()
            val house = a.subThoroughfare?.trim().orEmpty()
            val area = a.subLocality?.trim().orEmpty().ifBlank { a.locality?.trim().orEmpty() }
            val line = when {
                street.isNotBlank() && house.isNotBlank() -> "$street, $house"
                street.isNotBlank() -> street
                else -> a.getAddressLine(0)?.trim().orEmpty()
            }
            val parts = listOf(line, area).map { it.trim() }.filter { it.isNotBlank() }
            parts.joinToString(", ")
        } catch (_: Exception) {
            ""
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
