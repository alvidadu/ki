package com.sms.stshoppay

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.sms.stshoppay.data.local.AppDatabase
import com.sms.stshoppay.data.local.AppPreferences
import com.sms.stshoppay.data.local.SmsEntity
import com.sms.stshoppay.data.remote.ApiClient
import com.sms.stshoppay.data.remote.ServerTransaction
import com.sms.stshoppay.ui.adapter.TransactionAdapter
import com.sms.stshoppay.util.MfsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddFragment : Fragment() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: ApiClient
    private lateinit var database: AppDatabase

    private lateinit var etAddress: EditText
    private lateinit var etMessage: EditText
    private lateinit var btnAddSmsSubmit: Button
    private lateinit var pbAddSms: ProgressBar
    private lateinit var btnPasteMessage: MaterialButton

    private lateinit var layoutParsedPreview: View
    private lateinit var tvPreviewTrx: TextView
    private lateinit var tvPreviewAmount: TextView

    private lateinit var tvRecordCount: TextView
    private lateinit var rvAddedSms: RecyclerView
    private lateinit var adapter: TransactionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_add, container, false)
        prefs = AppPreferences(requireContext())
        apiClient = ApiClient.getInstance(requireContext())
        database = AppDatabase.getInstance(requireContext())

        initViews(view)
        setupChips(view)
        setupRecyclerView()
        observeLocalRecords()

        return view
    }

    private fun initViews(view: View) {
        etAddress = view.findViewById(R.id.etAddress)
        etMessage = view.findViewById(R.id.etMessage)
        btnAddSmsSubmit = view.findViewById(R.id.btnAddSmsSubmit)
        pbAddSms = view.findViewById(R.id.pbAddSms)
        btnPasteMessage = view.findViewById(R.id.btnPasteMessage)

        layoutParsedPreview = view.findViewById(R.id.layoutParsedPreview)
        tvPreviewTrx = view.findViewById(R.id.tvPreviewTrx)
        tvPreviewAmount = view.findViewById(R.id.tvPreviewAmount)

        tvRecordCount = view.findViewById(R.id.tvRecordCount)
        rvAddedSms = view.findViewById(R.id.rvAddedSms)

        btnPasteMessage.setOnClickListener {
            pasteFromClipboard()
        }

        // Live Regex parsing as user types or pastes
        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                parseMessagePreview(etAddress.text.toString(), s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnAddSmsSubmit.setOnClickListener {
            submitSms()
        }
    }

    private fun setupChips(view: View) {
        view.findViewById<View>(R.id.chipBkash).setOnClickListener {
            etAddress.setText("bKash")
        }
        view.findViewById<View>(R.id.chipNagad).setOnClickListener {
            etAddress.setText("NAGAD")
        }
        view.findViewById<View>(R.id.chip16167).setOnClickListener {
            etAddress.setText("16167")
        }
        view.findViewById<View>(R.id.chipRocket).setOnClickListener {
            etAddress.setText("Rocket")
        }
        view.findViewById<View>(R.id.chip16216).setOnClickListener {
            etAddress.setText("16216")
        }
    }

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(
            onEditClick = { txn -> showEditDialog(txn) },
            onDeleteClick = { txn -> confirmDelete(txn) }
        )
        rvAddedSms.layoutManager = LinearLayoutManager(requireContext())
        rvAddedSms.adapter = adapter
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text.toString()
            etMessage.setText(text)
            Toast.makeText(requireContext(), "ক্লিপবোর্ড থেকে পেস্ট করা হয়েছে", Toast.LENGTH_SHORT).show()

            // If address is empty, try to auto-detect from text
            if (etAddress.text.isNullOrBlank()) {
                val lower = text.lowercase()
                when {
                    lower.contains("bkash") -> etAddress.setText("bKash")
                    lower.contains("nagad") -> etAddress.setText("NAGAD")
                    lower.contains("rocket") || lower.contains("dbbl") -> etAddress.setText("Rocket")
                    lower.contains("upay") -> etAddress.setText("Upay")
                }
            }
        } else {
            Toast.makeText(requireContext(), "ক্লিপবোর্ডে কোনো টেক্সট নেই", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseMessagePreview(sender: String, message: String) {
        if (message.isBlank()) {
            layoutParsedPreview.visibility = View.GONE
            return
        }

        val parsed = MfsParser.parse(sender, message)
        layoutParsedPreview.visibility = View.VISIBLE
        tvPreviewTrx.text = if (parsed.trxId.isNotBlank()) "TrxID: ${parsed.trxId}" else "TrxID: Not detected"
        tvPreviewAmount.text = "Amount: ৳${parsed.amount}"
    }

    private fun submitSms() {
        val address = etAddress.text.toString().trim()
        val message = etMessage.text.toString().trim()

        if (address.isEmpty()) {
            etAddress.error = "অ্যাড্রেস প্রদান করুন"
            etAddress.requestFocus()
            return
        }
        if (message.isEmpty()) {
            etMessage.error = "এসএমএস মেসেজ প্রদান করুন"
            etMessage.requestFocus()
            return
        }

        setLoading(true)

        val parsed = MfsParser.parse(address, message)
        val tmpId = System.currentTimeMillis().toString()

        // 1. Save to Room database first
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val entity = SmsEntity(
                address = address,
                message = message,
                amount = parsed.amount,
                trxId = parsed.trxId,
                type = if (parsed.isApprovedMfs) "MFS_${parsed.mfsProvider.uppercase()}" else "MANUAL",
                status = "PENDING",
                timestamp = System.currentTimeMillis(),
                isSentToServer = false,
                isLocalOnly = !parsed.isApprovedMfs
            )
            val rowId = database.smsDao().insert(entity)

            // 2. Send POST to primary & secondary APIs:
            // POST: https://payment.st-shop.xyz/api/add-data and https://st-shop.xyz/sms/add
            withContext(Dispatchers.Main) {
                apiClient.addSmsData(
                    address = address,
                    message = message,
                    tmpId = rowId.toString(),
                    onSuccess = { res ->
                        setLoading(false)
                        Toast.makeText(requireContext(), "এসএমএস সফলভাবে সার্ভারে যুক্ত হয়েছে", Toast.LENGTH_SHORT).show()
                        etMessage.setText("")
                        layoutParsedPreview.visibility = View.GONE

                        // Update entity status to COMPLETE
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            database.smsDao().update(
                                entity.copy(id = rowId, status = "COMPLETE", isSentToServer = true)
                            )
                        }
                    },
                    onError = { err ->
                        setLoading(false)
                        Toast.makeText(requireContext(), "সার্ভারে যুক্ত করতে সমস্যা: $err (অ্যাপে সংরক্ষিত)", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun observeLocalRecords() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.smsDao().getAllSmsFlow().collect { records ->
                tvRecordCount.text = "${records.size} records"
                val txnList = records.map { r ->
                    ServerTransaction(
                        id = r.id.toString(),
                        address = r.address,
                        message = r.message,
                        amount = r.amount,
                        trxId = r.trxId,
                        date = "Record #${r.id}",
                        status = r.status
                    )
                }
                adapter.submitList(txnList)
            }
        }
    }

    private fun showEditDialog(txn: ServerTransaction) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_sms, null)
        val etEditAddr = dialogView.findViewById<EditText>(R.id.etEditAddress)
        val etEditMsg = dialogView.findViewById<EditText>(R.id.etEditMessage)

        etEditAddr.setText(txn.address)
        etEditMsg.setText(txn.message)

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("আপডেট") { _, _ ->
                val newAddr = etEditAddr.text.toString().trim()
                val newMsg = etEditMsg.text.toString().trim()
                if (newAddr.isNotBlank() && newMsg.isNotBlank()) {
                    // POST/GET: https://st-shop.xyz/sms/edit.php?id={id}&message={msg}&address={addr}
                    apiClient.editSmsData(
                        id = txn.id,
                        address = newAddr,
                        message = newMsg,
                        onSuccess = {
                            Toast.makeText(requireContext(), "সফলভাবে আপডেট হয়েছে", Toast.LENGTH_SHORT).show()
                            // Update local db
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                val parsed = MfsParser.parse(newAddr, newMsg)
                                database.smsDao().update(
                                    SmsEntity(
                                        id = txn.id.toLongOrNull() ?: 0L,
                                        address = newAddr,
                                        message = newMsg,
                                        amount = parsed.amount,
                                        trxId = parsed.trxId,
                                        status = "COMPLETE"
                                    )
                                )
                            }
                        },
                        onError = { err ->
                            Toast.makeText(requireContext(), "আপডেট ব্যর্থ: $err", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun confirmDelete(txn: ServerTransaction) {
        AlertDialog.Builder(requireContext())
            .setTitle("ডিলিট নিশ্চিত করুন")
            .setMessage("আপনি কি এই রেকর্ডটি মুছে ফেলতে চান?")
            .setPositiveButton("ডিলিট") { _, _ ->
                // GET: https://st-shop.xyz/sms/delete.php?id={id}
                apiClient.deleteSmsData(
                    id = txn.id,
                    onSuccess = {
                        Toast.makeText(requireContext(), "মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            txn.id.toLongOrNull()?.let { database.smsDao().deleteById(it) }
                        }
                    },
                    onError = { err ->
                        // Fallback delete local record
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            txn.id.toLongOrNull()?.let { database.smsDao().deleteById(it) }
                        }
                        Toast.makeText(requireContext(), "মুছে ফেলা হয়েছে: $err", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            btnAddSmsSubmit.text = ""
            btnAddSmsSubmit.isEnabled = false
            pbAddSms.visibility = View.VISIBLE
        } else {
            btnAddSmsSubmit.text = "সার্ভারে যুক্ত করুন"
            btnAddSmsSubmit.isEnabled = true
            pbAddSms.visibility = View.GONE
        }
    }
}
