package com.sms.stshoppay

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.sms.stshoppay.data.local.AppPreferences

class SettingsFragment : Fragment() {

    private lateinit var prefs: AppPreferences

    private lateinit var tvSettingEmail: TextView
    private lateinit var tvSettingDeviceKey: TextView
    private lateinit var btnCopyKey: ImageView
    private lateinit var btnLogout: MaterialButton

    private lateinit var switchService: SwitchCompat
    private lateinit var tvServiceDesc: TextView

    // Diagnostic views
    private lateinit var tvDiagSms: TextView
    private lateinit var tvDiagNotification: TextView
    private lateinit var tvDiagBattery: TextView
    private lateinit var tvDiagAutostart: TextView

    private lateinit var btnAppPermissions: MaterialButton
    private lateinit var btnNotificationAccess: MaterialButton
    private lateinit var btnIgnoreBatteryOpt: MaterialButton
    private lateinit var btnTecnoAutoStart: MaterialButton
    private lateinit var btnSupportDev: MaterialButton
    private lateinit var btnSupportChannel: MaterialButton

    private var isKeyMasked = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        prefs = AppPreferences(requireContext())

        initViews(view)
        loadData()

        return view
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun initViews(view: View) {
        tvSettingEmail = view.findViewById(R.id.tvSettingEmail)
        tvSettingDeviceKey = view.findViewById(R.id.tvSettingDeviceKey)
        btnCopyKey = view.findViewById(R.id.btnCopyKey)
        btnLogout = view.findViewById(R.id.btnLogout)

        switchService = view.findViewById(R.id.switchService)
        tvServiceDesc = view.findViewById(R.id.tvServiceDesc)

        tvDiagSms = view.findViewById(R.id.tvDiagSms)
        tvDiagNotification = view.findViewById(R.id.tvDiagNotification)
        tvDiagBattery = view.findViewById(R.id.tvDiagBattery)
        tvDiagAutostart = view.findViewById(R.id.tvDiagAutostart)

        btnAppPermissions = view.findViewById(R.id.btnAppPermissions)
        btnNotificationAccess = view.findViewById(R.id.btnNotificationAccess)
        btnIgnoreBatteryOpt = view.findViewById(R.id.btnIgnoreBatteryOpt)
        btnTecnoAutoStart = view.findViewById(R.id.btnTecnoAutoStart)
        btnSupportDev = view.findViewById(R.id.btnSupportDev)
        btnSupportChannel = view.findViewById(R.id.btnSupportChannel)

        tvSettingDeviceKey.setOnClickListener {
            isKeyMasked = !isKeyMasked
            updateDeviceKeyDisplay()
        }

        btnCopyKey.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Device Key", prefs.deviceKey)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "ডিভাইস কি কপি হয়েছে", Toast.LENGTH_SHORT).show()
        }

        // Service Toggle (ON / OFF)
        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.isServiceEnabled = isChecked
            if (isChecked) {
                MyBackgroundService.startService(requireContext())
                tvServiceDesc.text = "সার্ভিস চালু আছে: সার্বক্ষণিক SMS সিঙ্ক হবে"
                Toast.makeText(requireContext(), "Gateway সার্ভিস চালু করা হয়েছে", Toast.LENGTH_SHORT).show()
            } else {
                MyBackgroundService.stopService(requireContext())
                tvServiceDesc.text = "সার্ভিস বন্ধ আছে: SMS অটো রিড বন্ধ থাকবে"
                Toast.makeText(requireContext(), "Gateway সার্ভিস বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }

        btnAppPermissions.setOnClickListener {
            openAppDetailsSettings()
        }

        btnNotificationAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "নোটিফিকেশন অ্যাক্সেস সেটিংস ওপেন করা যায়নি", Toast.LENGTH_SHORT).show()
            }
        }

        btnIgnoreBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        btnTecnoAutoStart.setOnClickListener {
            openTecnoAutoStart()
        }

        btnSupportDev.setOnClickListener {
            openUrl("https://t.me/Shawon_Bhai_121")
        }

        btnSupportChannel.setOnClickListener {
            openUrl("https://t.me/ST_Technical_Creator")
        }

        btnLogout.setOnClickListener {
            confirmLogout()
        }
    }

    private fun loadData() {
        tvSettingEmail.text = prefs.email.ifEmpty { "N/A" }
        updateDeviceKeyDisplay()

        switchService.isChecked = prefs.isServiceEnabled && MyBackgroundService.isRunning
        tvServiceDesc.text = if (switchService.isChecked)
            "সার্ভিস চালু আছে: সার্বক্ষণিক SMS সিঙ্ক হবে"
        else
            "সার্ভিস বন্ধ আছে: SMS অটো রিড বন্ধ থাকবে"

        // Diagnostics check
        updateDiagnosticsUI()
    }

    private fun updateDiagnosticsUI() {
        val context = requireContext()

        // 1. SMS Permission
        val hasSmsReceive = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val hasSmsRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (hasSmsReceive && hasSmsRead) {
            tvDiagSms.text = "Granted ✓"
            tvDiagSms.setTextColor(ContextCompat.getColor(context, R.color.success_emerald))
        } else {
            tvDiagSms.text = "Denied ✗"
            tvDiagSms.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
        }

        // 2. Notification
        val notifsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (notifsEnabled) {
            tvDiagNotification.text = "Allowed ✓"
            tvDiagNotification.setTextColor(ContextCompat.getColor(context, R.color.success_emerald))
        } else {
            tvDiagNotification.text = "Disabled ✗"
            tvDiagNotification.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
        }

        // 3. Battery Optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            if (isIgnoring) {
                tvDiagBattery.text = "Ignoring (Unrestricted) ✓"
                tvDiagBattery.setTextColor(ContextCompat.getColor(context, R.color.success_emerald))
            } else {
                tvDiagBattery.text = "Restricted (Optimized) ⚠️"
                tvDiagBattery.setTextColor(ContextCompat.getColor(context, R.color.pending_amber))
            }
        } else {
            tvDiagBattery.text = "Ignoring ✓"
            tvDiagBattery.setTextColor(ContextCompat.getColor(context, R.color.success_emerald))
        }

        // 4. Tecno Autostart
        tvDiagAutostart.text = "Phone Master Auto-Start"
    }

    private fun updateDeviceKeyDisplay() {
        if (prefs.deviceKey.isBlank()) {
            tvSettingDeviceKey.text = "None"
            return
        }
        if (isKeyMasked) {
            val visibleChars = prefs.deviceKey.take(4)
            tvSettingDeviceKey.text = "$visibleChars••••••••••••"
        } else {
            tvSettingDeviceKey.text = prefs.deviceKey
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "সেটিংস পেজ ওপেন করা যায়নি", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager
                val packageName = requireContext().packageName

                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(requireContext(), "ব্যাটারি অপটিমাইজেশন ইতিমধ্যে Unrestricted আছে!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "অতিরিক্ত ব্যাটারি পারমিশনের প্রয়োজন নেই", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ex: Exception) {
                Toast.makeText(requireContext(), "সেটিংস ওপেন করা যায়নি", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Attempts to open Tecno / HiOS / Transsion Phone Master Auto-Start manager.
     * Falls back to app info where the user can enable Auto-Start and Background running.
     */
    private fun openTecnoAutoStart() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartManageActivity")),
            Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.view.activity.AutoStartActivity")),
            Intent().setComponent(ComponentName("com.transsion.powerchecker", "com.transsion.powerchecker.view.activity.PowerCheckerActivity")),
            Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.MainActivity")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
        )

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                Toast.makeText(requireContext(), "Phone Master / সেটিংস খোলা হয়েছে: Payment Koro-কে Auto-start চালু করুন", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                // Try next candidate
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setTitle("লগআউট নিশ্চিতকরণ")
            .setMessage("আপনি কি অ্যাকাউন্ট থেকে লগআউট করতে চান? এটি ব্যাকগ্রাউন্ড SMS গেটওয়ে বন্ধ করবে।")
            .setPositiveButton("লগআউট") { _, _ ->
                MyBackgroundService.stopService(requireContext())
                prefs.clearSession()

                val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                requireActivity().finish()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "লিংক ওপেন করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
        }
    }
}
