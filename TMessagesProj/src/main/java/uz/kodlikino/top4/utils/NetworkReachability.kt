package uz.kodlikino.top4.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

object NetworkReachability {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        )
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            false
        }
    }

    enum class NetKind { WIFI, CELLULAR, OTHER, NONE }

    /** Juriy tarmoq turi — preload/buffer siyosati shunga qarab o'zgaradi. */
    fun kind(context: Context): NetKind {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetKind.NONE
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return NetKind.NONE
                val caps = cm.getNetworkCapabilities(network) ?: return NetKind.NONE
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetKind.NONE
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
                    else -> NetKind.OTHER
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                if (info?.isConnected != true) NetKind.NONE
                else if (info.type == ConnectivityManager.TYPE_WIFI) NetKind.WIFI
                else NetKind.CELLULAR
            }
        } catch (_: Exception) {
            NetKind.NONE
        }
    }
}
