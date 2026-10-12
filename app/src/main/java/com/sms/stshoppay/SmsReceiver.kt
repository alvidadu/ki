package com.sms.stshoppay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
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

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val DEBUG_TAG = "SMS_DEBUG"
        const val ACTION_NEW_SMS_PROCESSED = "com.sms.stshoppay.NEW_SMS_PROCESSED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val isForeground = PaymentKoroApp.isAppInForeground
        Log.d(DEBUG_TAG, "[SMS_DEBUG] SMS_RECEIVED broadcast received")
        Log.d(DEBUG_TAG, "[SMS_DEBUG] Receiver process started (App In Foreground: $isForeground, Screen/Background state active)")

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(DEBUG_TAG, "[SMS_DEBUG] Empty messages list in intent")
            return
        }

        // 1. Acquire WakeLock immediately so CPU doesn't sleep while screen is locked
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PaymentKoro:SmsReceiverWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(30000) // 30 seconds
        }

        val sender = messages[0].originatingAddress ?: "Unknown"
        val fullBodyBuilder = StringBuilder()
        for (sms in messages) {
            fullBodyBuilder.append(sms.messageBody)
        }
        val fullMessage = fullBodyBuilder.toString()

        Log.d(DEBUG_TAG, "[SMS_DEBUG] Sender = $sender")
        Log.d(DEBUG_TAG, "[SMS_DEBUG] Message received: $fullMessage")

        val prefs = AppPreferences(context)
        val database = AppDatabase.getInstance(context)
        val apiClient = ApiClient.getInstance(context)

        // Parse using official MFS rules (bKash, Nagad, Rocket, Upay)
        val parsed = MfsParser.parse(sender, fullMessage)

        Log.d(DEBUG_TAG, "[SMS_DEBUG] SMS validation = ${parsed.isValidTransaction} (Provider: ${parsed.mfsProvider}, TrxID: ${parsed.trxId}, Amount: ${parsed.amount})")
        Log.d(DEBUG_TAG, "[SMS_DEBUG] Email available = ${prefs.email.isNotBlank()}")
        Log.d(DEBUG_TAG, "[SMS_DEBUG] Device key available = ${prefs.deviceKey.isNotBlank()}")

        // Use goAsync to allow full asynchronous/synchronous network operation in background
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (parsed.isValidTransaction) {
                    // Check duplicate TrxID
                    val isDuplicate = parsed.trxId.isNotBlank() && database.smsDao().countByTrxId(parsed.trxId) > 0
                    if (isDuplicate) {
                        Log.w(DEBUG_TAG, "[SMS_DEBUG] Duplicate TrxID detected (${parsed.trxId}), skipping API request to prevent duplicate entry.")
                        return@launch
                    }

                    // Save to local database
                    val mfsEntity = SmsEntity(
                        address = sender,
                        message = fullMessage,
                        amount = parsed.amount,
                        trxId = parsed.trxId,
                        type = "MFS_${parsed.mfsProvider.uppercase()}",
                        status = "COMPLETE",
                        isSentToServer = true,
                        isLocalOnly = false
                    )
                    val insertedId = database.smsDao().insert(mfsEntity)

                    Log.d(DEBUG_TAG, "[SMS_DEBUG] API request starting (tmp_id=$insertedId, address=$sender)")

                    if (prefs.isLoggedIn && prefs.email.isNotBlank() && prefs.deviceKey.isNotBlank()) {
                        // Reliable background execution: Wait synchronously for network POST to finish
                        val (isSuccess, response) = apiClient.addSmsDataSync(
                            address = sender,
                            message = fullMessage,
                            tmpId = insertedId.toString()
                        )

                        Log.d(DEBUG_TAG, "[SMS_DEBUG] API response received: $response")
                        Log.d(DEBUG_TAG, "[SMS_DEBUG] API success/failure: isSuccess=$isSuccess")

                        if (isSuccess) {
                            NotificationHelper.showOfficialSmsNotification(
                                context = context,
                                sender = parsed.mfsProvider,
                                amount = parsed.amount,
                                trxId = parsed.trxId
                            )
                        } else {
                            NotificationHelper.showFailureNotification(
                                context = context,
                                sender = parsed.mfsProvider,
                                errorMessage = response
                            )
                        }
                    } else {
                        Log.w(DEBUG_TAG, "[SMS_DEBUG] User not logged in or missing device_key, saved locally only.")
                        NotificationHelper.showOfficialSmsNotification(
                            context = context,
                            sender = parsed.mfsProvider,
                            amount = parsed.amount,
                            trxId = parsed.trxId
                        )
                    }

                } else {
                    // Non-official message (telecom ads, personal, other company OTPs) -> BLOCK IT!
                    Log.d(DEBUG_TAG, "[SMS_DEBUG] Non-official / unauthorized message blocked from: $sender")

                    val blockedEntity = SmsEntity(
                        address = sender,
                        message = fullMessage,
                        amount = parsed.amount,
                        trxId = parsed.trxId,
                        type = "BLOCKED",
                        status = "BLOCKED",
                        isSentToServer = false,
                        isLocalOnly = true
                    )
                    database.smsDao().insert(blockedEntity)

                    NotificationHelper.showBlockedSmsNotification(
                        context = context,
                        sender = sender,
                        message = fullMessage
                    )
                }

                // Notify UI to update lists in real-time if app is in foreground
                try {
                    val broadcastIntent = Intent(ACTION_NEW_SMS_PROCESSED)
                    context.sendBroadcast(broadcastIntent)
                } catch (e: Exception) {
                    // ignore
                }

            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "[SMS_DEBUG] Error in background SMS processing", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    // ignore
                }
                pendingResult.finish()
            }
        }
    }
}
