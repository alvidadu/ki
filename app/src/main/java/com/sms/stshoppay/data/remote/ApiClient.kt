package com.sms.stshoppay.data.remote

import android.content.Context
import android.util.Log
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.sms.stshoppay.data.local.AppPreferences
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class DashboardData(
    val firstName: String = "User",
    val email: String = "",
    val balance: String = "0",
    val totalTxn: Int = 0,
    val todayTxn: Int = 0,
    val thisMonthTxn: Int = 0,
    val totalComplete: Int = 0,
    val totalPending: Int = 0,
    val successRate: String = "100%",
    val completeList: List<ServerTransaction> = emptyList(),
    val pendingList: List<ServerTransaction> = emptyList()
)

data class ServerTransaction(
    val id: String,
    val address: String,
    val message: String,
    val amount: String,
    val trxId: String,
    val date: String,
    val status: String
)

class ApiClient private constructor(context: Context) {

    private val requestQueue: RequestQueue = Volley.newRequestQueue(context.applicationContext)
    private val prefs = AppPreferences(context)

    companion object {
        private const val TAG = "PaymentKoroApi"

        const val URL_DEVICE_CONNECT = "https://payment.st-shop.xyz/api/device-connect"
        const val URL_DATA_PHP = "https://st-shop.xyz/sms/data.php"
        const val URL_ADD_DATA = "https://payment.st-shop.xyz/api/add-data"
        const val URL_ADD_PHP = "https://st-shop.xyz/sms/add"
        const val URL_EDIT_PHP = "https://st-shop.xyz/sms/edit.php"
        const val URL_DELETE_PHP = "https://st-shop.xyz/sms/delete.php"

        @Volatile
        private var INSTANCE: ApiClient? = null

        fun getInstance(context: Context): ApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiClient(context).also { INSTANCE = it }
            }
        }
    }

    /**
     * 1. Device Connect / Login / Heartbeat
     * POST: https://payment.st-shop.xyz/api/device-connect
     * params: email, user_email, device_key, device_ip, android_id, device_id
     */
    fun connectDevice(
        email: String,
        deviceKey: String,
        onSuccess: (response: String, isSuccess: Boolean, message: String) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val stringRequest = object : StringRequest(
            Method.POST,
            URL_DEVICE_CONNECT,
            Response.Listener { res ->
                Log.d(TAG, "DeviceConnect Response: $res")
                try {
                    // Try parsing JSON if available, otherwise check raw response
                    val json = JSONObject(res)
                    val status = json.optBoolean("status", true) ||
                            json.optString("status").equals("success", ignoreCase = true) ||
                            json.optInt("code", 200) == 200
                    val msg = json.optString("message", "কানেকশন সফল হয়েছে")
                    onSuccess(res, status, msg)
                } catch (e: Exception) {
                    val isSuccess = res.contains("success", ignoreCase = true) ||
                            res.contains("connected", ignoreCase = true) ||
                            res.contains("true", ignoreCase = true) ||
                            res.trim().startsWith("{")
                    onSuccess(res, isSuccess, if (isSuccess) "কানেকশন সফল হয়েছে" else res)
                }
            },
            Response.ErrorListener { error ->
                val errorMsg = error.message ?: "কানেকশনে সমস্যা হয়েছে (${error.networkResponse?.statusCode ?: "Network Error"})"
                Log.e(TAG, "DeviceConnect Error: $errorMsg")
                onError(errorMsg)
            }
        ) {
            override fun getParams(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["email"] = email.trim()
                params["user_email"] = email.trim()
                params["device_key"] = deviceKey.trim()
                params["device_ip"] = prefs.getDeviceIp()
                params["android_id"] = prefs.getAndroidId()
                params["device_id"] = prefs.getDeviceId()
                return params
            }

            override fun getHeaders(): MutableMap<String, String> {
                val headers = HashMap<String, String>()
                headers["Accept"] = "application/json"
                return headers
            }
        }

        stringRequest.retryPolicy = DefaultRetryPolicy(
            15000,
            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        requestQueue.add(stringRequest)
    }

    /**
     * 2. Fetch User Dashboard Data
     * GET: https://st-shop.xyz/sms/data.php?gmail={email}
     */
    fun fetchDashboardData(
        email: String,
        onSuccess: (data: DashboardData) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val url = "$URL_DATA_PHP?gmail=${email.trim()}"
        val stringRequest = StringRequest(
            Request.Method.GET,
            url,
            { response ->
                Log.d(TAG, "FetchData Response: $response")
                try {
                    val parsed = parseDashboardResponse(response, email)
                    onSuccess(parsed)
                } catch (e: Exception) {
                    Log.e(TAG, "Parsing dashboard error", e)
                    onError("ডাটা পার্স করতে সমস্যা হয়েছে: ${e.localizedMessage}")
                }
            },
            { error ->
                val msg = error.message ?: "সার্ভার থেকে তথ্য আনতে ব্যর্থ হয়েছে"
                Log.e(TAG, "FetchData Error: $msg")
                onError(msg)
            }
        )

        stringRequest.retryPolicy = DefaultRetryPolicy(15000, 1, 1.0f)
        requestQueue.add(stringRequest)
    }

    /**
     * 3. Add SMS Data (used by Manual Entry and Auto SMS Listener)
     * POST: https://payment.st-shop.xyz/api/add-data and https://st-shop.xyz/sms/add
     */
    fun addSmsData(
        address: String,
        message: String,
        tmpId: String = System.currentTimeMillis().toString(),
        onSuccess: (response: String) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val stringRequest = object : StringRequest(
            Method.POST,
            URL_ADD_DATA,
            Response.Listener { res ->
                Log.d(TAG, "AddSms Primary Response: $res")
                // Trigger secondary add in background for synchronization
                sendToSecondaryAdd(address, message, tmpId)
                onSuccess(res)
            },
            Response.ErrorListener { error ->
                Log.w(TAG, "AddSms Primary Failed, attempting fallback...")
                // If primary fails, try fallback immediately
                sendToSecondaryAdd(address, message, tmpId, onDone = { success, msg ->
                    if (success) {
                        onSuccess(msg)
                    } else {
                        val errMsg = error.message ?: "সার্ভারে SMS পাঠাতে ব্যর্থ হয়েছে"
                        onError(errMsg)
                    }
                })
            }
        ) {
            override fun getParams(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["email"] = prefs.email
                params["user_email"] = prefs.email
                params["device_key"] = prefs.deviceKey
                params["address"] = address.trim()
                params["message"] = message.trim()
                params["tmp_id"] = tmpId
                params["device_ip"] = prefs.getDeviceIp()
                params["android_id"] = prefs.getAndroidId()
                return params
            }
        }

        stringRequest.retryPolicy = DefaultRetryPolicy(15000, 2, 1.0f)
        requestQueue.add(stringRequest)
    }

    private fun sendToSecondaryAdd(
        address: String,
        message: String,
        tmpId: String,
        onDone: ((Boolean, String) -> Unit)? = null
    ) {
        val secRequest = object : StringRequest(
            Method.POST,
            URL_ADD_PHP,
            Response.Listener { res ->
                Log.d(TAG, "Secondary Add Success: $res")
                onDone?.invoke(true, res)
            },
            Response.ErrorListener { err ->
                Log.e(TAG, "Secondary Add Error: ${err.message}")
                onDone?.invoke(false, err.message ?: "Error")
            }
        ) {
            override fun getParams(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["email"] = prefs.email
                params["user_email"] = prefs.email
                params["device_key"] = prefs.deviceKey
                params["address"] = address.trim()
                params["message"] = message.trim()
                params["tmp_id"] = tmpId
                params["device_ip"] = prefs.getDeviceIp()
                params["android_id"] = prefs.getAndroidId()
                return params
            }
        }
        secRequest.retryPolicy = DefaultRetryPolicy(15000, 1, 1.0f)
        requestQueue.add(secRequest)
    }

    /**
     * Synchronous HTTP POST execution for Reliable Background SMS processing.
     * Ensures request is fully sent and response received before BroadcastReceiver completes goAsync().
     */
    suspend fun addSmsDataSync(
        address: String,
        message: String,
        tmpId: String = System.currentTimeMillis().toString()
    ): Pair<Boolean, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val params = mapOf(
            "email" to prefs.email,
            "user_email" to prefs.email,
            "device_key" to prefs.deviceKey,
            "address" to address.trim(),
            "message" to message.trim(),
            "tmp_id" to tmpId,
            "device_ip" to prefs.getDeviceIp(),
            "android_id" to prefs.getAndroidId()
        )

        // 1. Primary POST to URL_ADD_DATA (https://payment.st-shop.xyz/api/add-data)
        val primary = executeHttpUrlPost(URL_ADD_DATA, params)
        if (primary.first) {
            // Also notify secondary endpoint asynchronously
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                executeHttpUrlPost(URL_ADD_PHP, params)
            }
            return@withContext primary
        }

        // 2. Fallback POST to URL_ADD_PHP (https://st-shop.xyz/sms/add)
        val fallback = executeHttpUrlPost(URL_ADD_PHP, params)
        return@withContext fallback
    }

    private fun executeHttpUrlPost(urlString: String, params: Map<String, String>): Pair<Boolean, String> {
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(urlString)
            connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", "PaymentKoro-Android/1.0")
            }

            val postData = StringBuilder()
            for ((key, value) in params) {
                if (postData.isNotEmpty()) postData.append("&")
                postData.append(java.net.URLEncoder.encode(key, "UTF-8"))
                postData.append("=")
                postData.append(java.net.URLEncoder.encode(value, "UTF-8"))
            }

            val outputBytes = postData.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            connection.outputStream.use { os ->
                os.write(outputBytes)
                os.flush()
            }

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

            Log.d(TAG, "executeHttpUrlPost [$urlString] code: $responseCode, resp: $responseText")
            Pair(responseCode in 200..299, responseText)
        } catch (e: Exception) {
            Log.e(TAG, "executeHttpUrlPost [$urlString] error: ${e.message}")
            Pair(false, e.message ?: "Network error")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 4. Edit SMS Data
     * POST/GET: https://st-shop.xyz/sms/edit.php?id={id}&message={msg}&address={addr}
     */
    fun editSmsData(
        id: String,
        address: String,
        message: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val encodedMsg = java.net.URLEncoder.encode(message, "UTF-8")
        val encodedAddr = java.net.URLEncoder.encode(address, "UTF-8")
        val url = "$URL_EDIT_PHP?id=$id&message=$encodedMsg&address=$encodedAddr"

        val request = StringRequest(
            Request.Method.GET,
            url,
            { res -> onSuccess(res) },
            { err -> onError(err.message ?: "এডিট ব্যর্থ হয়েছে") }
        )
        request.retryPolicy = DefaultRetryPolicy(15000, 1, 1.0f)
        requestQueue.add(request)
    }

    /**
     * 5. Delete SMS Data
     * GET: https://st-shop.xyz/sms/delete.php?id={id}
     */
    fun deleteSmsData(
        id: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val url = "$URL_DELETE_PHP?id=$id"
        val request = StringRequest(
            Request.Method.GET,
            url,
            { res -> onSuccess(res) },
            { err -> onError(err.message ?: "ডিলিট ব্যর্থ হয়েছে") }
        )
        request.retryPolicy = DefaultRetryPolicy(15000, 1, 1.0f)
        requestQueue.add(request)
    }

    private fun parseDashboardResponse(raw: String, email: String): DashboardData {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") {
            return DashboardData(email = email)
        }

        // Could be a JSON Object or JSON Array
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)

            val userObj = json.optJSONObject("user") ?: json.optJSONObject("data") ?: json
            val firstName = userObj.optString("first_name", userObj.optString("name", "User"))
            val balance = userObj.optString("balance", "0")

            val totalTxn = json.optInt("total_txn", json.optInt("total", 0))
            val todayTxn = json.optInt("today_txn", json.optInt("today", 0))
            val monthTxn = json.optInt("this_month", json.optInt("month", 0))
            val completeTxn = json.optInt("total_complete", json.optInt("complete", 0))
            val pendingTxn = json.optInt("total_pending", json.optInt("pending", 0))

            val successRate = when {
                totalTxn > 0 -> "${(completeTxn * 100) / totalTxn}%"
                completeTxn > 0 -> "100%"
                else -> json.optString("success_rate", "100%")
            }

            val completeList = mutableListOf<ServerTransaction>()
            val pendingList = mutableListOf<ServerTransaction>()

            // Parse complete list
            val completeArray = json.optJSONArray("complete_data") ?:
                                json.optJSONArray("success") ?:
                                json.optJSONArray("complete")
            if (completeArray != null) {
                parseTxnArray(completeArray, "COMPLETE", completeList)
            }

            // Parse pending list
            val pendingArray = json.optJSONArray("pending_data") ?:
                               json.optJSONArray("pending")
            if (pendingArray != null) {
                parseTxnArray(pendingArray, "PENDING", pendingList)
            }

            // If a general 'transactions' array was returned
            val generalArray = json.optJSONArray("transactions") ?: json.optJSONArray("data")
            if (generalArray != null && completeList.isEmpty() && pendingList.isEmpty()) {
                for (i in 0 until generalArray.length()) {
                    val item = generalArray.optJSONObject(i) ?: continue
                    val status = item.optString("status", "COMPLETE")
                    val txn = parseTxnObject(item, status)
                    if (status.contains("pending", ignoreCase = true)) {
                        pendingList.add(txn)
                    } else {
                        completeList.add(txn)
                    }
                }
            }

            val finalTotal = if (totalTxn == 0) completeList.size + pendingList.size else totalTxn
            val finalComplete = if (completeTxn == 0) completeList.size else completeTxn
            val finalPending = if (pendingTxn == 0) pendingList.size else pendingTxn

            return DashboardData(
                firstName = firstName,
                email = email,
                balance = balance,
                totalTxn = finalTotal,
                todayTxn = todayTxn,
                thisMonthTxn = monthTxn,
                totalComplete = finalComplete,
                totalPending = finalPending,
                successRate = if (finalTotal > 0) "${(finalComplete * 100) / finalTotal}%" else successRate,
                completeList = completeList,
                pendingList = pendingList
            )
        } else if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val completeList = mutableListOf<ServerTransaction>()
            val pendingList = mutableListOf<ServerTransaction>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val status = item.optString("status", "COMPLETE")
                val txn = parseTxnObject(item, status)
                if (status.contains("pending", ignoreCase = true)) {
                    pendingList.add(txn)
                } else {
                    completeList.add(txn)
                }
            }

            val total = completeList.size + pendingList.size
            return DashboardData(
                firstName = "User",
                email = email,
                totalTxn = total,
                totalComplete = completeList.size,
                totalPending = pendingList.size,
                successRate = if (total > 0) "${(completeList.size * 100) / total}%" else "100%",
                completeList = completeList,
                pendingList = pendingList
            )
        }

        return DashboardData(email = email)
    }

    private fun parseTxnArray(arr: JSONArray, defaultStatus: String, list: MutableList<ServerTransaction>) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val status = obj.optString("status", defaultStatus)
            list.add(parseTxnObject(obj, status))
        }
    }

    private fun parseTxnObject(obj: JSONObject, status: String): ServerTransaction {
        val id = obj.optString("id", System.currentTimeMillis().toString())
        val address = obj.optString("address", obj.optString("sender", "MFS"))
        val message = obj.optString("message", obj.optString("sms", ""))
        val amount = obj.optString("amount", "0")
        val trxId = obj.optString("trx_id", obj.optString("trxId", obj.optString("txnid", "")))
        val date = obj.optString("created_at", obj.optString("date", "Today"))

        return ServerTransaction(
            id = id,
            address = address,
            message = message,
            amount = amount,
            trxId = trxId,
            date = date,
            status = status
        )
    }
}
