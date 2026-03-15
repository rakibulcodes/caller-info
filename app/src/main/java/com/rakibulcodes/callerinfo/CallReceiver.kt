package com.rakibulcodes.callerinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // EXTRA_INCOMING_NUMBER is deprecated in API 29.
                // For Android 10+, we use CallerScreeningService which is the recommended alternative.
                // However, we keep this for older versions or as a fallback if permissions allow.
                @Suppress("DEPRECATION")
                val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                
                if (phoneNumber != null) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            handleIncomingCall(context, phoneNumber)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                // Dismiss overlay when call is picked up or ended
                val stopIntent = Intent(context, CallerOverlayService::class.java)
                context.stopService(stopIntent)
            }
        }
    }

    private suspend fun handleIncomingCall(context: Context, phoneNumber: String) {
        val prefs = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        val lookupKnown = prefs.getBoolean("lookup_known", false)
        if (!isEnabled) return

        // On Android 10+, if CallerScreeningService is active, it might have already handled this.
        // But CallScreeningService only works for non-contacts by default.
        // We use a simple check to avoid double-showing if possible, 
        // though CallerOverlayService handles multiple starts by just updating the same overlay.

        if (lookupKnown || !isNumberInContacts(context, phoneNumber)) {
            val repository = CallerInfoRepository.getInstance(context)
            val result = repository.getCallerInfo(phoneNumber)
            
            // For incoming calls, show the overlay service
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
        }
    }

    private fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
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
}
