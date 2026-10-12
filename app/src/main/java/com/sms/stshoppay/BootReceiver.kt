package com.sms.stshoppay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sms.stshoppay.data.local.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = AppPreferences(context)
            if (prefs.isLoggedIn && prefs.isServiceEnabled) {
                MyBackgroundService.startService(context)
            }
        }
    }
}
