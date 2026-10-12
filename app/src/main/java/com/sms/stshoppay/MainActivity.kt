package com.sms.stshoppay

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.sms.stshoppay.data.local.AppPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences

    private lateinit var topBar: LinearLayout
    private lateinit var tvNetworkType: TextView
    private lateinit var ivNetworkIcon: ImageView
    private lateinit var tvBatteryPercent: TextView
    private lateinit var ivBatteryIcon: ImageView
    private lateinit var tvGatewayStatus: TextView
    private lateinit var bottomNav: BottomNavigationView

    // Permission Banner Views
    private lateinit var bannerPermission: View
    private lateinit var btnGrantSmsPermission: MaterialButton
    private lateinit var btnOpenAppSettings: MaterialButton
    private lateinit var btnHelpRestricted: TextView

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Battery Broadcast Receiver (Point 10)
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updateBatteryStatus(it) }
        }
    }

    // Permission launcher for SMS and Notifications
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.RECEIVE_SMS] == true
        if (smsGranted) {
            Toast.makeText(this, "SMS পারমিশন মঞ্জুর হয়েছে", Toast.LENGTH_SHORT).show()
            bannerPermission.visibility = View.GONE
            requestBatteryOptimization()
            if (prefs.isServiceEnabled) {
                MyBackgroundService.startService(this)
            }
        } else {
            bannerPermission.visibility = View.VISIBLE
            showRestrictedSettingsDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPreferences(this)
        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        initViews()
        setupWindowInsets()
        setupBottomNav()
        setupNetworkMonitoring()
        checkPermissions()

        if (savedInstanceState == null) {
            loadFragment(DashboardFragment())
        }

        // Start 24/7 background gateway service
        if (prefs.isServiceEnabled) {
            MyBackgroundService.startService(this)
        }
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            val density = resources.displayMetrics.density
            val padSide = (16 * density).toInt()
            val padTop = statusBarInsets.top + (8 * density).toInt()
            val padBottom = (8 * density).toInt()

            topBar.setPadding(padSide, padTop, padSide, padBottom)

            val bottomNavPadBottom = navBarInsets.bottom + (4 * density).toInt()
            bottomNav.setPadding(0, 0, 0, bottomNavPadBottom)

            insets
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateGatewayStatusText()
        checkPermissions()
        if (prefs.isServiceEnabled) {
            MyBackgroundService.startService(this)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
    }

    private fun initViews() {
        topBar = findViewById(R.id.topBar)
        tvNetworkType = findViewById(R.id.tvNetworkType)
        ivNetworkIcon = findViewById(R.id.ivNetworkIcon)
        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        ivBatteryIcon = findViewById(R.id.ivBatteryIcon)
        tvGatewayStatus = findViewById(R.id.tvGatewayStatus)
        bottomNav = findViewById(R.id.bottomNav)

        bannerPermission = findViewById(R.id.bannerPermission)
        btnGrantSmsPermission = findViewById(R.id.btnGrantSmsPermission)
        btnOpenAppSettings = findViewById(R.id.btnOpenAppSettings)
        btnHelpRestricted = findViewById(R.id.btnHelpRestricted)

        btnGrantSmsPermission.setOnClickListener {
            requestPermissions()
        }

        btnOpenAppSettings.setOnClickListener {
            openAppSettings()
        }

        btnHelpRestricted.setOnClickListener {
            showRestrictedSettingsDialog()
        }

        updateGatewayStatusText()
    }

    private fun updateGatewayStatusText() {
        if (MyBackgroundService.isRunning || prefs.isServiceEnabled) {
            tvGatewayStatus.text = "Gateway Active (24/7)"
            tvGatewayStatus.setTextColor(ContextCompat.getColor(this, R.color.success_emerald))
        } else {
            tvGatewayStatus.text = "Gateway Paused"
            tvGatewayStatus.setTextColor(ContextCompat.getColor(this, R.color.pending_amber))
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    loadFragment(DashboardFragment())
                    true
                }
                R.id.nav_add_sms -> {
                    loadFragment(AddFragment())
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragment())
                    true
                }
                R.id.nav_developer -> {
                    loadFragment(DeveloperFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * Point 9: Real-time network detection [ WiFi or Mobile data ]
     */
    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        updateNetworkUI(prefs.getNetworkType())

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    updateNetworkUI(prefs.getNetworkType())
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    updateNetworkUI("No Internet")
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                runOnUiThread {
                    val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    val type = when {
                        isWifi -> "WiFi"
                        isCellular -> "Mobile Data"
                        else -> "Connected"
                    }
                    updateNetworkUI(type)
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            updateNetworkUI(prefs.getNetworkType())
        }
    }

    private fun updateNetworkUI(type: String) {
        tvNetworkType.text = type
        when (type) {
            "WiFi" -> {
                ivNetworkIcon.setImageResource(R.drawable.ic_wifi)
                ivNetworkIcon.setColorFilter(ContextCompat.getColor(this, R.color.wifi_blue))
            }
            "Mobile Data" -> {
                ivNetworkIcon.setImageResource(R.drawable.ic_cellular)
                ivNetworkIcon.setColorFilter(ContextCompat.getColor(this, R.color.cellular_purple))
            }
            else -> {
                ivNetworkIcon.setImageResource(R.drawable.ic_wifi)
                ivNetworkIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_tertiary))
            }
        }
    }

    /**
     * Point 10: Dynamic Battery Percentage monitoring
     */
    private fun updateBatteryStatus(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        if (level >= 0 && scale > 0) {
            val batteryPct = (level * 100) / scale
            val text = if (isCharging) "$batteryPct% ⚡" else "$batteryPct%"
            tvBatteryPercent.text = text

            val color = when {
                batteryPct > 40 -> ContextCompat.getColor(this, R.color.battery_green)
                batteryPct > 15 -> ContextCompat.getColor(this, R.color.pending_amber)
                else -> ContextCompat.getColor(this, R.color.danger_red)
            }
            ivBatteryIcon.setColorFilter(color)
        }
    }

    private fun checkPermissions() {
        val hasSmsReceive = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val hasSmsRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsReceive || !hasSmsRead) {
            bannerPermission.visibility = View.VISIBLE
        } else {
            bannerPermission.visibility = View.GONE
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            bannerPermission.visibility = View.GONE
            Toast.makeText(this, "সব পারমিশন সক্রিয় আছে", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "সেটিংস ওপেন করা যায়নি", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isIgnoringBatteryOptimizations(packageName) == false) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun showRestrictedSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS পারমিশন চালুর নিয়মাবলি")
            .setMessage("Android 13/14 এ ম্যানুয়ালি ইনস্টল করা অ্যাপে পারমিশন ব্লক থাকতে পারে ('Restricted settings')।\n\nএটি চালু করার ধাপসমূহ:\n১. নিচের 'সেটিংসে যান' বাটনে চাপ দিন।\n২. অ্যাপ ইনফো পেজের উপরে ডানপাশে ৩-ডট (⋮) মেন্যুতে চাপ দিন।\n৩. 'Allow restricted settings' এ চাপ দিয়ে আপনার ফোনের পিন/ফিঙ্গারপ্রিন্ট দিন।\n৪. এরপর Permissions অপশনে গিয়ে 'SMS' এবং 'Notifications' Allow (অনুমতি) করে দিন।\n\nএর ফলে বিকাশ, নগদ, রকেট ও উপায় এসএমএস অটো রিড শুরু হবে।")
            .setPositiveButton("সেটিংসে যান") { _, _ ->
                openAppSettings()
            }
            .setNeutralButton("নোটিফিকেশন পারমিশন") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    // ignore
                }
            }
            .setNegativeButton("বুঝেছি", null)
            .show()
    }
}
