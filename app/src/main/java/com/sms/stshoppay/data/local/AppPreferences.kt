package com.sms.stshoppay.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

class AppPreferences(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("payment_koro_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_EMAIL = "email"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_FIRST_NAME = "first_name"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_BALANCE = "balance"
        private const val KEY_TOTAL_TXN = "total_txn"
        private const val KEY_TODAY_TXN = "today_txn"
        private const val KEY_MONTH_TXN = "month_txn"
        private const val KEY_COMPLETE_TXN = "complete_txn"
        private const val KEY_PENDING_TXN = "pending_txn"
        private const val KEY_SUCCESS_RATE = "success_rate"
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var email: String
        get() = prefs.getString(KEY_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var deviceKey: String
        get() = prefs.getString(KEY_DEVICE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_KEY, value).apply()

    var firstName: String
        get() = prefs.getString(KEY_FIRST_NAME, "User") ?: "User"
        set(value) = prefs.edit().putString(KEY_FIRST_NAME, value).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var lastSync: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var totalTxn: Int
        get() = prefs.getInt(KEY_TOTAL_TXN, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_TXN, value).apply()

    var todayTxn: Int
        get() = prefs.getInt(KEY_TODAY_TXN, 0)
        set(value) = prefs.edit().putInt(KEY_TODAY_TXN, value).apply()

    var monthTxn: Int
        get() = prefs.getInt(KEY_MONTH_TXN, 0)
        set(value) = prefs.edit().putInt(KEY_MONTH_TXN, value).apply()

    var completeTxn: Int
        get() = prefs.getInt(KEY_COMPLETE_TXN, 0)
        set(value) = prefs.edit().putInt(KEY_COMPLETE_TXN, value).apply()

    var pendingTxn: Int
        get() = prefs.getInt(KEY_PENDING_TXN, 0)
        set(value) = prefs.edit().putInt(KEY_PENDING_TXN, value).apply()

    var successRate: String
        get() = prefs.getString(KEY_SUCCESS_RATE, "0%") ?: "0%"
        set(value) = prefs.edit().putString(KEY_SUCCESS_RATE, value).apply()

    fun saveLoginSession(emailVal: String, keyVal: String, name: String = "User") {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_EMAIL, emailVal.trim())
            .putString(KEY_DEVICE_KEY, keyVal.trim())
            .putString(KEY_FIRST_NAME, name)
            .apply()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    @SuppressLint("HardwareIds")
    fun getAndroidId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_ANDROID_ID"
        } catch (e: Exception) {
            "UNKNOWN_ANDROID_ID"
        }
    }

    fun getDeviceId(): String {
        val model = Build.MODEL ?: "Device"
        val manufacturer = Build.MANUFACTURER ?: "Android"
        return "${manufacturer}_${model}_${getAndroidId().takeLast(6)}"
    }

    fun getDeviceIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "127.0.0.1"
    }

    fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "No Internet"
        val network = cm.activeNetwork ?: return "No Internet"
        val capabilities = cm.getNetworkCapabilities(network) ?: return "No Internet"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }
}
