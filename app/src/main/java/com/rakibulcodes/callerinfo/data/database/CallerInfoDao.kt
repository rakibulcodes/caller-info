package com.rakibulcodes.callerinfo.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CallerInfoDao {
    @Query("SELECT * FROM caller_info WHERE number = :number")
    suspend fun getCallerInfo(number: String): CallerInfoEntity?

    @Query("SELECT * FROM caller_info ORDER BY timestamp DESC")
    suspend fun getAllCallerInfo(): List<CallerInfoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallerInfo(callerInfo: CallerInfoEntity): Long

    @Query("DELETE FROM caller_info")
    suspend fun clearAll(): Int

    @Query("DELETE FROM caller_info WHERE number NOT IN (SELECT number FROM caller_info ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun deleteOldEntries(limit: Int): Int

    @Query("DELETE FROM caller_info WHERE number = :number")
    suspend fun deleteByNumber(number: String)

    @Transaction
    suspend fun insertAndTrim(callerInfo: CallerInfoEntity, limit: Int): Int {
        insertCallerInfo(callerInfo)
        return deleteOldEntries(limit)
    }
}
