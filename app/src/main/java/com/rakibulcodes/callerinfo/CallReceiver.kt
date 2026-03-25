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
                            IncomingCallProcessor.processCall(context.applicationContext, phoneNumber)
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
}
