package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.Call
import android.telecom.CallScreeningService
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallerScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val handle = callDetails.handle
        if (handle == null) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val phoneNumber = handle.schemeSpecificPart
        if (phoneNumber.isNullOrEmpty()) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            handleIncomingCall(phoneNumber)
        }

        // We always allow the call to proceed, we just want to show the overlay
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }

    private suspend fun handleIncomingCall(phoneNumber: String) {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        if (!isEnabled) return

        // Note: CallScreeningService is typically only called for numbers not in contacts
        // unless the app is the default dialer. 
        // We perform the lookup and show the overlay.
        
        val repository = CallerInfoRepository.getInstance(applicationContext)
        val result = repository.getCallerInfo(phoneNumber)
        
        val overlayIntent = Intent(applicationContext, CallerOverlayService::class.java).apply {
            putExtra("number", result.number)
            putExtra("name", result.name)
            putExtra("carrier", result.carrier)
            putExtra("country", result.country)
            putExtra("location", result.location)
            putExtra("email", result.email)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startService(overlayIntent)
    }
}
