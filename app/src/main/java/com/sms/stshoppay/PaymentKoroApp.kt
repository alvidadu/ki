package com.sms.stshoppay

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.sms.stshoppay.data.local.AppDatabase
import com.sms.stshoppay.data.local.AppPreferences

class PaymentKoroApp : Application() {

    companion object {
        const val CHANNEL_ID = "payment_koro_gateway_channel"
        const val SMS_NOTIF_CHANNEL_ID = "payment_koro_sms_channel"
        lateinit var instance: PaymentKoroApp
            private set
        var isAppInForeground: Boolean = false
            private set
    }

    lateinit var database: AppDatabase
        private set
    lateinit var preferences: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = AppPreferences(this)
        database = AppDatabase.getInstance(this)
        createNotificationChannels()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var resumedCount = 0

            override fun onActivityResumed(activity: Activity) {
                resumedCount++
                isAppInForeground = resumedCount > 0
            }

            override fun onActivityPaused(activity: Activity) {
                resumedCount = maxOf(0, resumedCount - 1)
                isAppInForeground = resumedCount > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        if (preferences.isLoggedIn && preferences.isServiceEnabled) {
            MyBackgroundService.startService(this)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Foreground Service Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }

            // SMS Alert Notification Channel (high importance for immediate sound and popup)
            val smsChannel = NotificationChannel(
                SMS_NOTIF_CHANNEL_ID,
                "SMS Alerts & Sync Status",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "নোটিফিকেশন যখন কোনো নতুন SMS রিসিভ ও সিঙ্ক করা হয়"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager?.createNotificationChannel(serviceChannel)
            notificationManager?.createNotificationChannel(smsChannel)
        }
    }
}
