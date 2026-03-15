package com.rakibulcodes.callerinfo.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "caller_info")
data class CallerInfoEntity(
    @PrimaryKey val number: String,
    val country: String?,
    val name: String?,
    val carrier: String?,
    val email: String?,
    val location: String?,
    val address1: String?,
    val address2: String?,
    val error: String?,
    val timestamp: Long = System.currentTimeMillis()
)