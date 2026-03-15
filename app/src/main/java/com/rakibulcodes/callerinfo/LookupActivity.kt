package com.rakibulcodes.callerinfo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import kotlinx.coroutines.launch

class LookupActivity : AppCompatActivity() {

    private lateinit var repository: CallerInfoRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView to keep it transparent/invisible
        
        repository = CallerInfoRepository.getInstance(applicationContext)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        var sharedText: String? = null
        if (intent.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        } else if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sharedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
        }

        sharedText?.let { text ->
            val sanitizedNumber = text.filter { it.isDigit() || it == '+' }
            if (sanitizedNumber.isNotEmpty()) {
                Toast.makeText(this, "Searching $sanitizedNumber...", Toast.LENGTH_SHORT).show()

                lifecycleScope.launch {
                    try {
                        val result = repository.getCallerInfo(sanitizedNumber)
                        val message = buildNotificationMessage(result)
                        NotificationHelper.showNotification(
                            this@LookupActivity,
                            "${result.number}",
                            message
                        )
                    } catch (e: Exception) {
                        // Silent fail or minimal toast
                    } finally {
                        finish()
                    }
                }
            } else {
                Toast.makeText(this, "No valid number found", Toast.LENGTH_SHORT).show()
                finish()
            }
        } ?: run {
            finish()
        }
    }

    private fun buildNotificationMessage(info: CallerInfoEntity): String {
        val sb = StringBuilder()
        sb.append(info.name ?: "Unknown")

        val carrierInfo = listOfNotNull(info.carrier, info.country).joinToString(", ")
        if (carrierInfo.isNotEmpty()) {
            sb.append("\n").append(carrierInfo)
        }

        if (!info.email.isNullOrEmpty()) {
            sb.append("\nEmail: ").append(info.email)
        }
        if (!info.location.isNullOrEmpty()) {
            sb.append("\nLocation: ").append(info.location)
        }
        val fullAddress = listOfNotNull(info.address1, info.address2).joinToString("\n")
        if (fullAddress.isNotEmpty()) {
            sb.append("\nAddress: ").append(fullAddress)
        }
        
        if (info.error != null) {
            sb.append("\nError: ").append(info.error)
        }
        
        return sb.toString()
    }
}
