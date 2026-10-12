package com.sms.stshoppay.util

import java.util.regex.Pattern

data class ParsedSms(
    val isApprovedMfs: Boolean,
    val mfsProvider: String,   // "bKash", "Nagad", "Rocket", "Upay"
    val trxId: String,
    val amount: String,
    val isValidTransaction: Boolean
)

object MfsParser {

    // Regex for TrxID extraction across all 4 MFS
    private val TRX_ID_REGEX = Pattern.compile(
        "(?:TrxID|TxnID|TxnId|Transaction ID|Trx ID|Ref)[:\\s]+([A-Za-z0-9]{6,18})",
        Pattern.CASE_INSENSITIVE
    )

    // Regex for Amount extraction (Tk 500 / BDT 500 / Amount: Tk 500)
    private val AMOUNT_REGEX = Pattern.compile(
        "(?:Tk|BDT|Tk\\.|amount of Tk|Amount: Tk|Amount:)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Evaluates incoming message against the 4 official Bangladesh MFS providers:
     * 1. বিকাশ (bKash)
     * 2. নগদ (Nagad / 16167)
     * 3. রকেট (Rocket / 16216 / DBBL)
     * 4. উপায় (Upay / 16268)
     */
    fun parse(sender: String, message: String): ParsedSms {
        val cleanSender = sender.trim().lowercase()
        val cleanMessage = message.lowercase()

        // 1. Identify official provider
        val provider = when {
            cleanSender.contains("bkash") || (cleanSender.length < 9 && cleanMessage.contains("bkash")) -> "bKash"
            cleanSender.contains("nagad") || cleanSender == "16167" || (cleanSender.length < 9 && cleanMessage.contains("nagad")) -> "Nagad"
            cleanSender.contains("rocket") || cleanSender.contains("dbbl") || cleanSender == "16216" || (cleanSender.length < 9 && cleanMessage.contains("rocket")) -> "Rocket"
            cleanSender.contains("upay") || cleanSender == "16268" || (cleanSender.length < 9 && cleanMessage.contains("upay")) -> "Upay"
            else -> ""
        }

        val isApproved = provider.isNotBlank()

        // 2. Extract TrxID
        var trxId = ""
        val trxMatcher = TRX_ID_REGEX.matcher(message)
        if (trxMatcher.find()) {
            trxId = trxMatcher.group(1) ?: ""
        }

        // 3. Extract Amount
        var amount = "0"
        val amountMatcher = AMOUNT_REGEX.matcher(message)
        if (amountMatcher.find()) {
            amount = amountMatcher.group(1)?.replace(",", "") ?: "0"
        }

        // Valid Transaction requires approved MFS, non-empty TrxID and valid Amount
        val isValidTxn = isApproved && trxId.isNotBlank() && amount != "0"

        return ParsedSms(
            isApprovedMfs = isApproved,
            mfsProvider = if (isApproved) provider else "Non-MFS",
            trxId = trxId,
            amount = amount,
            isValidTransaction = isValidTxn
        )
    }
}
