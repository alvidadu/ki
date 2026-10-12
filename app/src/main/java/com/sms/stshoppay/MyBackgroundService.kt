package com.sms.stshoppay

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sms.stshoppay.data.local.AppDatabase
import com.sms.stshoppay.data.local.AppPreferences
import com.sms.stshoppay.data.local.SmsEntity
import com.sms.stshoppay.data.remote.ApiClient
import com.sms.stshoppay.util.MfsParser
import com.sms.stshoppay.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MyBackgroundService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: ApiClient
    private lateinit var database: AppDatabase
    private var smsObserver: ContentObserver? = null

    companion object {
        private const val TAG = "MyBackgroundService"
        private const val DEBUG_TAG = "SMS_DEBUG"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_GATEWAY"
        const val ACTION_STOP = "ACTION_STOP_GATEWAY"
        const val ACTION_SHOW_SMS_NOTIF = "ACTION_SHOW_SMS_NOTIF"

        const val EXTRA_IS_MFS = "extra_is_mfs"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_TRX_ID = "extra_trx_id"
        const val EXTRA_MESSAGE = "extra_message"

        var isRunning = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, MyBackgroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MyBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        apiClient = ApiClient.getInstance(this)
        database = AppDatabase.getInstance(this)
        acquireWakeLock()
        registerSmsObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_SHOW_SMS_NOTIF -> {
                val isMfs = intent.getBooleanExtra(EXTRA_IS_MFS, false)
                val sender = intent.getStringExtra(EXTRA_SENDER) ?: ""
                val amount = intent.getStringExtra(EXTRA_AMOUNT) ?: ""
                val trxId = intent.getStringExtra(EXTRA_TRX_ID) ?: ""
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""

                if (isMfs) {
                    NotificationHelper.showOfficialSmsNotification(this, sender, amount, trxId)
                } else {
                    NotificationHelper.showBlockedSmsNotification(this, sender, message)
                }
            }
            else -> {
                startForegroundWithNotification()
                startHeartbeatLoop()
                scanRecentSmsFromInbox()
            }
        }
        return START_STICKY
    }

    private fun registerSmsObserver() {
        try {
            val handler = Handler(Looper.getMainLooper())
            smsObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    Log.d(DEBUG_TAG, "[SMS_DEBUG] SmsObserver triggered on URI: $uri (App in Foreground: ${PaymentKoroApp.isAppInForeground})")
                    scanRecentSmsFromInbox()
                }
            }
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                smsObserver!!
            )
            Log.d(TAG, "SmsObserver registered successfully on Telephony.Sms.CONTENT_URI")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SmsObserver", e)
        }
    }

    /**
     * Fallback and Real-Time Inbox Detection: Scans the latest 10 SMS directly from the device's inbox.
     * Works even if the OEM delayed or swallowed the SMS_RECEIVED broadcast.
     */
    fun scanRecentSmsFromInbox() {
        serviceScope.launch {
            try {
                val hasPermission = ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    Log.w(DEBUG_TAG, "[SMS_DEBUG] Cannot scan inbox: READ_SMS permission denied")
                    return@launch
                }

                // Query the latest 10 messages without restrictive date math to avoid device clock skew
                val cursor = contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE
                    ),
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC LIMIT 10"
                )

                cursor?.use { c ->
                    val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
                    val dateCol = c.getColumnIndex(Telephony.Sms.DATE)

                    while (c.moveToNext()) {
                        val sender = if (addrCol >= 0) c.getString(addrCol) ?: "" else ""
                        val body = if (bodyCol >= 0) c.getString(bodyCol) ?: "" else ""
                        val date = if (dateCol >= 0) c.getLong(dateCol) else System.currentTimeMillis()

                        if (sender.isBlank() || body.isBlank()) continue

                        val parsed = MfsParser.parse(sender, body)
                        if (parsed.isValidTransaction) {
                            // Check if already processed to prevent duplicate API requests
                            val alreadyInDb = database.smsDao().countByTrxId(parsed.trxId) > 0
                            if (!alreadyInDb) {
                                Log.d(DEBUG_TAG, "[SMS_DEBUG] SmsObserver captured NEW unprocessed MFS SMS from: $sender, TrxID: ${parsed.trxId}")

                                val entity = SmsEntity(
                                    address = sender,
                                    message = body,
                                    amount = parsed.amount,
                                    trxId = parsed.trxId,
                                    type = "MFS_${parsed.mfsProvider.uppercase()}",
                                    status = "COMPLETE",
                                    isSentToServer = true,
                                    isLocalOnly = false,
                                    timestamp = date
                                )
                                val insertedId = database.smsDao().insert(entity)

                                Log.d(DEBUG_TAG, "[SMS_DEBUG] API request starting (SmsObserver, tmp_id=$insertedId, address=$sender)")

                                if (prefs.isLoggedIn && prefs.email.isNotBlank() && prefs.deviceKey.isNotBlank()) {
                                    val (isSuccess, res) = apiClient.addSmsDataSync(
                                        address = sender,
                                        message = body,
                                        tmpId = insertedId.toString()
                                    )
                                    Log.d(DEBUG_TAG, "[SMS_DEBUG] API response received: $res")
                                    Log.d(DEBUG_TAG, "[SMS_DEBUG] API success/failure: isSuccess=$isSuccess")

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
                                } else {
                                    NotificationHelper.showOfficialSmsNotification(
                                        context = applicationContext,
                                        sender = parsed.mfsProvider,
                                        amount = parsed.amount,
                                        trxId = parsed.trxId
                                    )
                                }

                                try {
                                    sendBroadcast(Intent(SmsReceiver.ACTION_NEW_SMS_PROCESSED))
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "[SMS_DEBUG] Error in scanRecentSmsFromInbox", e)
            }
        }
    }

    private fun startForegroundWithNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, PaymentKoroApp.CHANNEL_ID)
            .setContentTitle("SMS Reader সার্ভিস চালু আছে")
            .setContentText("Payment Koro ব্যাকগ্রাউন্ডে SMS রিড করছে...")
            .setSubText("Gateway Active (24/7)")
            .setSmallIcon(R.drawable.ic_nav_dashboard)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        prefs.isServiceEnabled = true
    }

    private fun startHeartbeatLoop() {
        serviceScope.launch {
            while (isActive) {
                if (prefs.isLoggedIn && prefs.email.isNotBlank() && prefs.deviceKey.isNotBlank()) {
                    apiClient.connectDevice(
                        email = prefs.email,
                        deviceKey = prefs.deviceKey,
                        onSuccess = { _, _, _ ->
                            prefs.lastSync = System.currentTimeMillis()
                        },
                        onError = { err ->
                            Log.w(TAG, "Heartbeat warning: $err")
                        }
                    )
                }
                delay(15 * 60 * 1000L) // 15 minutes
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PaymentKoro:GatewayWakeLock247"
            )?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.isServiceEnabled && prefs.isLoggedIn) {
            val restartServiceIntent = Intent(applicationContext, MyBackgroundService::class.java).apply {
                action = ACTION_START
            }
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext,
                101,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopForegroundService() {
        isRunning = false
        prefs.isServiceEnabled = false
        smsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        serviceJob.cancel()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        smsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        serviceJob.cancel()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
