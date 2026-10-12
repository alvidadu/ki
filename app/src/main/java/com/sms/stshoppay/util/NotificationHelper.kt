package com.sms.stshoppay.util

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sms.stshoppay.MainActivity
import com.sms.stshoppay.PaymentKoroApp
import com.sms.stshoppay.R

object NotificationHelper {

    private const val TAG = "NotificationHelper"

    fun showOfficialSmsNotification(
        context: Context,
        sender: String,
        amount: String,
        trxId: String
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "POST_NOTIFICATIONS permission not granted")
                    return
                }
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() % 10000).toInt(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "✓ SMS সফলভাবে Read করা হয়েছে ও Added হয়েছে"
            val body = "প্রেরক: $sender | পরিমাণ: ৳$amount | TrxID: $trxId"

            val smsNotif = NotificationCompat.Builder(context, PaymentKoroApp.SMS_NOTIF_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_check_circle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify((System.currentTimeMillis() % 100000).toInt() + 2000, smsNotif)
            Log.d(TAG, "Official SMS notification posted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display official SMS alert", e)
        }
    }

    fun showFailureNotification(
        context: Context,
        sender: String,
        errorMessage: String
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() % 10000).toInt() + 200,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "⚠️ SMS সিঙ্ক ব্যর্থ হয়েছে ($sender)"
            val body = "সার্ভারে পাঠাতে ব্যর্থ: $errorMessage"

            val notif = NotificationCompat.Builder(context, PaymentKoroApp.SMS_NOTIF_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_delete)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify((System.currentTimeMillis() % 100000).toInt() + 3000, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display failure notification", e)
        }
    }

    fun showBlockedSmsNotification(
        context: Context,
        sender: String,
        message: String
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() % 10000).toInt() + 500,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "🚫 এসএমএস ব্লক করা হয়েছে ($sender)"
            val preview = if (message.length > 55) message.take(52) + "..." else message
            val body = "অফিশিয়াল MFS না হওয়ায় ব্লক করা হলো: $preview"

            val blockedNotif = NotificationCompat.Builder(context, PaymentKoroApp.SMS_NOTIF_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify((System.currentTimeMillis() % 100000).toInt() + 5000, blockedNotif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display blocked SMS alert", e)
        }
    }
}
