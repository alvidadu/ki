package com.sms.stshoppay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sms.stshoppay.data.local.AppPreferences
import com.sms.stshoppay.data.remote.ApiClient

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: ApiClient

    private lateinit var etEmail: EditText
    private lateinit var etDeviceKey: EditText
    private lateinit var btnConnect: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvAndroidId: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        prefs = AppPreferences(this)
        apiClient = ApiClient.getInstance(this)

        initViews()
        populateDeviceInfo()
    }

    private fun initViews() {
        etEmail = findViewById(R.id.etEmail)
        etDeviceKey = findViewById(R.id.etDeviceKey)
        btnConnect = findViewById(R.id.btnConnect)
        progressBar = findViewById(R.id.progressBar)
        tvDeviceIp = findViewById(R.id.tvDeviceIp)
        tvAndroidId = findViewById(R.id.tvAndroidId)

        if (prefs.email.isNotBlank()) {
            etEmail.setText(prefs.email)
        }
        if (prefs.deviceKey.isNotBlank()) {
            etDeviceKey.setText(prefs.deviceKey)
        }

        btnConnect.setOnClickListener {
            performLogin()
        }

        findViewById<View>(R.id.btnTelegramDev).setOnClickListener {
            openUrl("https://t.me/Shawon_Bhai_121")
        }

        findViewById<View>(R.id.btnTelegramChannel).setOnClickListener {
            openUrl("https://t.me/ST_Technical_Creator")
        }
    }

    private fun populateDeviceInfo() {
        tvDeviceIp.text = prefs.getDeviceIp()
        tvAndroidId.text = prefs.getAndroidId()
    }

    private fun performLogin() {
        val email = etEmail.text.toString().trim()
        val deviceKey = etDeviceKey.text.toString().trim()

        if (email.isEmpty()) {
            etEmail.error = "অনুগ্রহ করে ইমেইল লিখুন"
            etEmail.requestFocus()
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "সঠিক ইমেইল ফরম্যাট দিন"
            etEmail.requestFocus()
            return
        }
        if (deviceKey.isEmpty()) {
            etDeviceKey.error = "অনুগ্রহ করে ডিভাইস কি দিন"
            etDeviceKey.requestFocus()
            return
        }

        setLoading(true)

        // POST: https://payment.st-shop.xyz/api/device-connect
        apiClient.connectDevice(
            email = email,
            deviceKey = deviceKey,
            onSuccess = { response, isSuccess, message ->
                setLoading(false)
                if (isSuccess) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    prefs.saveLoginSession(emailVal = email, keyVal = deviceKey)

                    // Fetch user info to populate first_name
                    apiClient.fetchDashboardData(
                        email = email,
                        onSuccess = { data ->
                            if (data.firstName.isNotBlank()) {
                                prefs.firstName = data.firstName
                            }
                        },
                        onError = {}
                    )

                    // Start background SMS Gateway foreground service
                    MyBackgroundService.startService(this)

                    // Navigate to Dashboard
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            },
            onError = { error ->
                setLoading(false)
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            btnConnect.text = ""
            btnConnect.isEnabled = false
            progressBar.visibility = View.VISIBLE
        } else {
            btnConnect.text = "ডিভাইস কানেক্ট করুন"
            btnConnect.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "ব্রাউজার বা টেলিগ্রাম ওপেন করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
        }
    }
}
