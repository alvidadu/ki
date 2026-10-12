package com.sms.stshoppay.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.sms.stshoppay.R
import com.sms.stshoppay.data.remote.ServerTransaction

class TransactionAdapter(
    private val onEditClick: (ServerTransaction) -> Unit,
    private val onDeleteClick: (ServerTransaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

    private val items = mutableListOf<ServerTransaction>()

    fun submitList(newList: List<ServerTransaction>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvProvider: TextView = itemView.findViewById(R.id.tvProvider)
        private val tvTrxId: TextView = itemView.findViewById(R.id.tvTrxId)
        private val btnCopyTrx: ImageView = itemView.findViewById(R.id.btnCopyTrx)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val btnEdit: MaterialButton = itemView.findViewById(R.id.btnEdit)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDelete)

        fun bind(item: ServerTransaction) {
            val context = itemView.context
            val addressLower = item.address.lowercase()

            // Setup Provider Badge and Brand Colors
            tvProvider.text = item.address.ifEmpty { "MFS" }
            when {
                addressLower.contains("bkash") -> {
                    tvProvider.setTextColor(Color.parseColor("#E2136E"))
                    tvProvider.setBackgroundColor(Color.parseColor("#FFF1F7"))
                }
                addressLower.contains("nagad") || addressLower == "16167" -> {
                    tvProvider.setTextColor(Color.parseColor("#EA580C"))
                    tvProvider.setBackgroundColor(Color.parseColor("#FFF7ED"))
                }
                addressLower.contains("rocket") || addressLower == "16216" -> {
                    tvProvider.setTextColor(Color.parseColor("#7C3AED"))
                    tvProvider.setBackgroundColor(Color.parseColor("#F5F3FF"))
                }
                addressLower.contains("upay") || addressLower == "16268" -> {
                    tvProvider.setTextColor(Color.parseColor("#0284C7"))
                    tvProvider.setBackgroundColor(Color.parseColor("#F0F9FF"))
                }
                item.status == "BLOCKED" -> {
                    tvProvider.setTextColor(Color.parseColor("#DC2626"))
                    tvProvider.setBackgroundColor(Color.parseColor("#FEE2E2"))
                }
                else -> {
                    tvProvider.setTextColor(Color.parseColor("#4F46E5"))
                    tvProvider.setBackgroundColor(Color.parseColor("#EEF2FF"))
                }
            }

            // TrxID
            val trxText = item.trxId.ifEmpty { "No TrxID" }
            tvTrxId.text = trxText
            btnCopyTrx.visibility = if (item.trxId.isNotBlank() && item.trxId != "BLOCKED") View.VISIBLE else View.GONE
            btnCopyTrx.setOnClickListener {
                copyToClipboard(context, "TrxID", item.trxId)
            }

            // Status Badge
            val status = item.status.uppercase()
            tvStatusBadge.text = status
            when {
                status.contains("BLOCKED") -> {
                    tvStatusBadge.text = "BLOCKED / NON-MFS"
                    tvStatusBadge.setTextColor(Color.parseColor("#DC2626"))
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
                }
                status.contains("PENDING") -> {
                    tvStatusBadge.setTextColor(Color.parseColor("#D97706"))
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_pending)
                }
                status.contains("LOCAL") -> {
                    tvStatusBadge.setTextColor(Color.parseColor("#64748B"))
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_network)
                }
                else -> {
                    tvStatusBadge.setTextColor(Color.parseColor("#10B981"))
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_pill_success)
                }
            }

            // Amount
            if (item.status == "BLOCKED") {
                tvAmount.text = "🚫 Blocked"
                tvAmount.setTextColor(Color.parseColor("#EF4444"))
                btnEdit.visibility = View.GONE
            } else {
                val rawAmount = item.amount.replace("৳", "").trim()
                tvAmount.text = "৳ $rawAmount"
                tvAmount.setTextColor(Color.parseColor("#1E293B"))
                btnEdit.visibility = View.VISIBLE
            }

            // Date
            tvDate.text = item.date

            // Message Body
            tvMessage.text = item.message

            // Buttons
            btnEdit.setOnClickListener { onEditClick(item) }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "$label কপি হয়েছে: $text", Toast.LENGTH_SHORT).show()
        }
    }
}
