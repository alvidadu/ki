package com.sms.stshoppay.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sms: SmsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<SmsEntity>)

    @Update
    suspend fun update(sms: SmsEntity)

    @Query("SELECT * FROM sms_records ORDER BY timestamp DESC")
    fun getAllSmsFlow(): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms_records WHERE isLocalOnly = 0 ORDER BY timestamp DESC")
    fun getMfsSmsFlow(): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms_records WHERE isLocalOnly = 1 ORDER BY timestamp DESC")
    fun getLocalOnlySmsFlow(): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms_records WHERE status = :status ORDER BY timestamp DESC")
    fun getSmsByStatusFlow(status: String): Flow<List<SmsEntity>>

    @Query("SELECT COUNT(*) FROM sms_records WHERE trxId = :trxId AND trxId != ''")
    suspend fun countByTrxId(trxId: String): Int

    @Query("DELETE FROM sms_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sms_records WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: String)

    @Query("DELETE FROM sms_records")
    suspend fun clearAll()
}
