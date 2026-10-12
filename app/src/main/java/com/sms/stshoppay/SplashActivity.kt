package com.sms.stshoppay

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sms.stshoppay.data.local.AppPreferences
import com.sms.stshoppay.data.remote.ApiClient

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: ApiClient
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        prefs = AppPreferences(this)
        apiClient = ApiClient.getInstance(this)
        tvStatus = findViewById(R.id.tvStatus)

        checkSessionAndNavigate()
    }

    private fun checkSessionAndNavigate() {
        if (prefs.isLoggedIn && prefs.email.isNotBlank()) {
            tvStatus.text = "সার্ভার থেকে তথ্য যাচাই করা হচ্ছে..."

            // GET: https://st-shop.xyz/sms/data.php?gmail={email}
            apiClient.fetchDashboardData(
                email = prefs.email,
                onSuccess = { data ->
                    if (data.firstName.isNotBlank()) {
                        prefs.firstName = data.firstName
                    }
                    prefs.totalTxn = data.totalTxn
                    prefs.todayTxn = data.todayTxn
                    prefs.monthTxn = data.thisMonthTxn
                    prefs.completeTxn = data.totalComplete
                    prefs.pendingTxn = data.totalPending
                    prefs.successRate = data.successRate

                    // Start background gateway service if enabled
                    if (prefs.isServiceEnabled) {
                        MyBackgroundService.startService(this)
                    }

                    goToMainActivity()
                },
                onError = { _ ->
                    // Even if network has temporary glitch, if user was already logged in,
                    // proceed to MainActivity where user can refresh or view cached data
                    if (prefs.isServiceEnabled) {
                        MyBackgroundService.startService(this)
                    }
                    goToMainActivity()
                }
            )
        } else {
            // Not logged in -> Go to LoginActivity after short splash animation
            Handler(Looper.getMainLooper()).postDelayed({
                goToLoginActivity()
            }, 1200)
        }
    }

    private fun goToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun goToLoginActivity() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
