package com.sms.stshoppay

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import com.sms.stshoppay.data.local.AppDatabase
import com.sms.stshoppay.data.local.AppPreferences
import com.sms.stshoppay.data.remote.ApiClient
import com.sms.stshoppay.data.remote.DashboardData
import com.sms.stshoppay.data.remote.ServerTransaction
import com.sms.stshoppay.ui.adapter.TransactionAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardFragment : Fragment() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: ApiClient
    private lateinit var database: AppDatabase

    // Header & Balance
    private lateinit var tvUserName: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvServiceStatusBadge: TextView
    private lateinit var btnRefreshDashboard: ImageView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // 6 Stats Cards
    private lateinit var tvTotalTxn: TextView
    private lateinit var tvTodayTxn: TextView
    private lateinit var tvThisMonthTxn: TextView
    private lateinit var tvTotalComplete: TextView
    private lateinit var tvTotalPending: TextView
    private lateinit var tvSuccessRate: TextView

    // Tabs & List
    private lateinit var tabLayout: TabLayout
    private lateinit var rvTransactions: RecyclerView
    private lateinit var listProgressBar: ProgressBar
    private lateinit var emptyLayoutContainer: View
    private lateinit var tvEmptyTitle: TextView

    private lateinit var adapter: TransactionAdapter
    private var currentDashboardData: DashboardData? = null
    private val blockedList = mutableListOf<ServerTransaction>()
    private var selectedTab = 0 // 0: Complete, 1: Pending, 2: Blocked

    // Broadcast receiver for incoming SMS
    private val smsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadDashboardData(showLoading = false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())
        apiClient = ApiClient.getInstance(requireContext())
        database = AppDatabase.getInstance(requireContext())

        initViews(view)
        setupRecyclerView()
        setupTabs()
        loadCachedData()
        loadDashboardData(showLoading = true)
        observeBlockedMessages()
    }

    override fun onResume() {
        super.onResume()
        updateServiceBadge()
        try {
            val filter = IntentFilter(SmsReceiver.ACTION_NEW_SMS_PROCESSED)
            requireContext().registerReceiver(smsUpdateReceiver, filter)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(smsUpdateReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun initViews(view: View) {
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        tvUserName = view.findViewById(R.id.tvUserName)
        tvBalance = view.findViewById(R.id.tvBalance)
        tvServiceStatusBadge = view.findViewById(R.id.tvServiceStatusBadge)
        btnRefreshDashboard = view.findViewById(R.id.btnRefreshDashboard)

        tvTotalTxn = view.findViewById(R.id.tvTotalTxn)
        tvTodayTxn = view.findViewById(R.id.tvTodayTxn)
        tvThisMonthTxn = view.findViewById(R.id.tvThisMonthTxn)
        tvTotalComplete = view.findViewById(R.id.tvTotalComplete)
        tvTotalPending = view.findViewById(R.id.tvTotalPending)
        tvSuccessRate = view.findViewById(R.id.tvSuccessRate)

        tabLayout = view.findViewById(R.id.tabLayout)
        rvTransactions = view.findViewById(R.id.rvTransactions)
        listProgressBar = view.findViewById(R.id.listProgressBar)
        emptyLayoutContainer = view.findViewById(R.id.layoutEmpty)
        tvEmptyTitle = view.findViewById(R.id.tvEmptyTitle)

        swipeRefresh.setColorSchemeResources(R.color.primary_indigo, R.color.mfs_bkash, R.color.mfs_nagad)
        swipeRefresh.setOnRefreshListener {
            loadDashboardData(showLoading = false)
        }

        btnRefreshDashboard.setOnClickListener {
            loadDashboardData(showLoading = true)
        }

        updateServiceBadge()
    }

    private fun updateServiceBadge() {
        if (MyBackgroundService.isRunning || prefs.isServiceEnabled) {
            tvServiceStatusBadge.text = "SERVICE ACTIVE (24/7)"
            tvServiceStatusBadge.setTextColor(requireContext().getColor(R.color.success_emerald))
            tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_pill_success)
        } else {
            tvServiceStatusBadge.text = "SERVICE PAUSED"
            tvServiceStatusBadge.setTextColor(requireContext().getColor(R.color.pending_amber))
            tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_pill_pending)
        }
    }

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(
            onEditClick = { txn -> showEditDialog(txn) },
            onDeleteClick = { txn -> confirmDelete(txn) }
        )
        rvTransactions.layoutManager = LinearLayoutManager(requireContext())
        rvTransactions.adapter = adapter
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTab = tab?.position ?: 0
                renderCurrentTabList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadCachedData() {
        tvUserName.text = prefs.firstName.ifEmpty { "User" }
        tvTotalTxn.text = prefs.totalTxn.toString()
        tvTodayTxn.text = prefs.todayTxn.toString()
        tvThisMonthTxn.text = prefs.monthTxn.toString()
        tvTotalComplete.text = prefs.completeTxn.toString()
        tvTotalPending.text = prefs.pendingTxn.toString()
        tvSuccessRate.text = prefs.successRate
    }

    private fun observeBlockedMessages() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.smsDao().getSmsByStatusFlow("BLOCKED").collect { records ->
                    withContext(Dispatchers.Main) {
                        blockedList.clear()
                        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                        records.forEach { r ->
                            blockedList.add(
                                ServerTransaction(
                                    id = r.id.toString(),
                                    address = r.address,
                                    message = r.message,
                                    amount = "0",
                                    trxId = "BLOCKED",
                                    date = sdf.format(Date(r.timestamp)),
                                    status = "BLOCKED"
                                )
                            )
                        }
                        tabLayout.getTabAt(2)?.text = "Blocked (${blockedList.size})"
                        if (selectedTab == 2) {
                            renderCurrentTabList()
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun loadDashboardData(showLoading: Boolean) {
        if (showLoading) {
            listProgressBar.visibility = View.VISIBLE
        }

        // GET: https://st-shop.xyz/sms/data.php?gmail={email}
        apiClient.fetchDashboardData(
            email = prefs.email,
            onSuccess = { data ->
                swipeRefresh.isRefreshing = false
                listProgressBar.visibility = View.GONE
                currentDashboardData = data

                // Save stats to SharedPreferences
                if (data.firstName.isNotBlank()) {
                    prefs.firstName = data.firstName
                }
                prefs.totalTxn = data.totalTxn
                prefs.todayTxn = data.todayTxn
                prefs.monthTxn = data.thisMonthTxn
                prefs.completeTxn = data.totalComplete
                prefs.pendingTxn = data.totalPending
                prefs.successRate = data.successRate

                // Update UI Header & Stats
                tvUserName.text = data.firstName
                tvBalance.text = "৳ ${data.balance}"
                tvTotalTxn.text = data.totalTxn.toString()
                tvTodayTxn.text = data.todayTxn.toString()
                tvThisMonthTxn.text = data.thisMonthTxn.toString()
                tvTotalComplete.text = data.totalComplete.toString()
                tvTotalPending.text = data.totalPending.toString()
                tvSuccessRate.text = data.successRate

                // Update Tab Titles with counts
                tabLayout.getTabAt(0)?.text = "Complete Data (${data.completeList.size})"
                tabLayout.getTabAt(1)?.text = "Pending Data (${data.pendingList.size})"
                tabLayout.getTabAt(2)?.text = "Blocked (${blockedList.size})"

                renderCurrentTabList()
            },
            onError = { _ ->
                swipeRefresh.isRefreshing = false
                listProgressBar.visibility = View.GONE
                renderCurrentTabList()
            }
        )
    }

    private fun renderCurrentTabList() {
        val listToShow = when (selectedTab) {
            0 -> currentDashboardData?.completeList ?: emptyList()
            1 -> currentDashboardData?.pendingList ?: emptyList()
            else -> blockedList
        }

        if (listToShow.isEmpty()) {
            rvTransactions.visibility = View.GONE
            emptyLayoutContainer.visibility = View.VISIBLE
            when (selectedTab) {
                0 -> tvEmptyTitle.text = "কোনো সফল লেনদেন নেই\nনতুন এসএমএস আসলে স্বয়ংক্রিয়ভাবে এখানে যুক্ত হবে"
                1 -> tvEmptyTitle.text = "কোনো পেন্ডিং লেনদেন নেই"
                else -> tvEmptyTitle.text = "কোনো ব্লকড মেসেজ নেই\nঅননুমোদিত মেসেজ আসলে এখানে দেখা যাবে"
            }
        } else {
            rvTransactions.visibility = View.VISIBLE
            emptyLayoutContainer.visibility = View.GONE
            adapter.submitList(listToShow)
        }
    }

    private fun showEditDialog(txn: ServerTransaction) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_sms, null)
        val etAddress = dialogView.findViewById<EditText>(R.id.etEditAddress)
        val etMessage = dialogView.findViewById<EditText>(R.id.etEditMessage)

        etAddress.setText(txn.address)
        etMessage.setText(txn.message)

        AlertDialog.Builder(requireContext())
            .setTitle("এসএমএস তথ্য এডিট করুন")
            .setView(dialogView)
            .setPositiveButton("আপডেট") { _, _ ->
                val newAddress = etAddress.text.toString().trim()
                val newMessage = etMessage.text.toString().trim()
                if (newAddress.isNotBlank() && newMessage.isNotBlank()) {
                    performEditApi(txn.id, newAddress, newMessage)
                } else {
                    Toast.makeText(requireContext(), "সব ঘর পূরণ করুন", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun performEditApi(id: String, address: String, message: String) {
        listProgressBar.visibility = View.VISIBLE
        apiClient.editSmsData(
            id = id,
            address = address,
            message = message,
            onSuccess = { _ ->
                listProgressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "সফলভাবে আপডেট হয়েছে", Toast.LENGTH_SHORT).show()
                loadDashboardData(showLoading = false)
            },
            onError = { err ->
                listProgressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "আপডেট ব্যর্থ: $err", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun confirmDelete(txn: ServerTransaction) {
        AlertDialog.Builder(requireContext())
            .setTitle("মুছে ফেলার নিশ্চিতকরণ")
            .setMessage("আপনি কি নিশ্চিতভাবে এই এসএমএস রেকর্ডটি মুছে ফেলতে চান?")
            .setPositiveButton("মুছুন") { _, _ ->
                performDeleteApi(txn.id)
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun performDeleteApi(id: String) {
        listProgressBar.visibility = View.VISIBLE
        apiClient.deleteSmsData(
            id = id,
            onSuccess = { _ ->
                listProgressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                loadDashboardData(showLoading = false)
            },
            onError = { err ->
                listProgressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "ডিলিট ব্যর্থ: $err", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
