package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rakibulcodes.callerinfo.data.CallerInfoRepository

object IncomingCallProcessor {

    private var lastProcessedNumber: String? = null
    private var lastProcessedTime: Long = 0

    suspend fun processCall(context: Context, phoneNumber: String) {
        // Simple debounce based on time and number to prevent dual-trigger 
        // from CallScreeningService and CallReceiver
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (phoneNumber == lastProcessedNumber && (now - lastProcessedTime) < 5000) {
                return
            }
            lastProcessedNumber = phoneNumber
            lastProcessedTime = now
        }

        val prefs = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        val lookupKnown = prefs.getBoolean("lookup_known", false)
        
        if (!isEnabled) return

        if (lookupKnown || !isNumberInContacts(context, phoneNumber)) {
            val repository = CallerInfoRepository.getInstance(context)
            val result = repository.getCallerInfo(phoneNumber)
            
            if (result.error == "No internet connection") {
                // Do not show overlay, schedule offline lookup
                scheduleOfflineLookup(context, phoneNumber)
            } else if (isCallStillActive(context)) {
                // Only show overlay while a call is still active.
                val overlayIntent = Intent(context, CallerOverlayService::class.java).apply {
                    putExtra("number", result.number)
                    putExtra("name", result.name)
                    putExtra("carrier", result.carrier)
                    putExtra("country", result.country)
                    putExtra("location", result.location)
                    putExtra("email", result.email)
                    putExtra("error", result.error)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startService(overlayIntent)
            } else {
                val message = NotificationHelper.buildNotificationMessage(result)
                NotificationHelper.showNotification(context, result.number, message, result = result)
            }
        }
    }

    private fun isCallStillActive(context: Context): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false

        return try {
            telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            phoneNumber
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleOfflineLookup(context: Context, number: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val data = workDataOf("number" to number)

        val workRequest = OneTimeWorkRequestBuilder<OfflineLookupWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline_lookup_$number",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
