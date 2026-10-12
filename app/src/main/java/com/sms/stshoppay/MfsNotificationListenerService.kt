package com.sms.stshoppay

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sms.stshoppay.data.local.AppDatabase
import com.sms.stshoppay.data.local.AppPreferences
import com.sms.stshoppay.data.local.SmsEntity
import com.sms.stshoppay.data.remote.ApiClient
import com.sms.stshoppay.util.MfsParser
import com.sms.stshoppay.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MfsNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MfsNotifListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Skip our own app's notifications to prevent infinite loops
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getCharSequence(Notification.EXTRA_TEXT, "")?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "")?.toString() ?: ""

        val combinedMessage = if (bigText.isNotBlank()) "$title $bigText" else "$title $text"
        if (combinedMessage.isBlank()) return

        // Ignore third-party summary or sync notifications (e.g., "Synced to Firebase")
        // User strictly requires FULL official message
        if (combinedMessage.contains("Synced to Firebase", ignoreCase = true)) {
            Log.d(TAG, "Skipping third-party app notification: $combinedMessage")
            return
        }

        // Check if message belongs to official 4 MFS: bKash, Nagad, Rocket, Upay
        val parsed = MfsParser.parse(title, combinedMessage)
        if (!parsed.isValidTransaction) {
            return
        }

        Log.d(TAG, "Official MFS notification captured from ${sbn.packageName}: ${parsed.mfsProvider}, TrxID: ${parsed.trxId}, Amount: ${parsed.amount}")

        val prefs = AppPreferences(applicationContext)
        val database = AppDatabase.getInstance(applicationContext)
        val apiClient = ApiClient.getInstance(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check duplicate - if already processed by SmsReceiver, skip
                if (parsed.trxId.isNotBlank() && database.smsDao().countByTrxId(parsed.trxId) > 0) {
                    Log.w(TAG, "Duplicate TrxID from notification, already processed.")
                    return@launch
                }

                val entity = SmsEntity(
                    address = parsed.mfsProvider,
                    message = combinedMessage,
                    amount = parsed.amount,
                    trxId = parsed.trxId,
                    type = "MFS_${parsed.mfsProvider.uppercase()}",
                    status = "COMPLETE",
                    isSentToServer = true,
                    isLocalOnly = false
                )
                val insertedId = database.smsDao().insert(entity)

                if (prefs.isLoggedIn && prefs.email.isNotBlank() && prefs.deviceKey.isNotBlank()) {
                    val (isSuccess, res) = apiClient.addSmsDataSync(
                        address = parsed.mfsProvider,
                        message = combinedMessage,
                        tmpId = insertedId.toString()
                    )
                    if (isSuccess) {
                        NotificationHelper.showOfficialSmsNotification(
                            context = applicationContext,
                            sender = parsed.mfsProvider,
                            amount = parsed.amount,
                            trxId = parsed.trxId
                        )
                    } else {
                        NotificationHelper.showFailureNotification(
                            context = applicationContext,
                            sender = parsed.mfsProvider,
                            errorMessage = res
                        )
                    }
                }

                val broadcastIntent = Intent(SmsReceiver.ACTION_NEW_SMS_PROCESSED)
                sendBroadcast(broadcastIntent)

            } catch (e: Exception) {
                Log.e(TAG, "Error handling notification", e)
            }
        }
    }
}
