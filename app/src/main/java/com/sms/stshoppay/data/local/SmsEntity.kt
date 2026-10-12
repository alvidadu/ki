package com.sms.stshoppay.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_records")
data class SmsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serverId: String? = null,
    val address: String,          // e.g. bKash, NAGAD, 16167, Rocket, +88017...
    val message: String,
    val amount: String = "0",      // parsed amount or "0"
    val trxId: String = "",       // parsed transaction ID
    val type: String = "MFS",      // "MFS_BKASH", "MFS_NAGAD", "MFS_ROCKET", "LOCAL_PERSONAL", "OTHER"
    val status: String = "COMPLETE", // "COMPLETE", "PENDING", "LOCAL_SAVED", "FAILED"
    val timestamp: Long = System.currentTimeMillis(),
    val isSentToServer: Boolean = false,
    val isLocalOnly: Boolean = false // true if non-approved number saved only on device
)
