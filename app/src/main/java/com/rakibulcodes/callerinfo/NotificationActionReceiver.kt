package com.rakibulcodes.callerinfo

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val shareText = intent.getStringExtra(NotificationHelper.EXTRA_SHARE_TEXT).orEmpty()

        when (intent.action) {
            NotificationHelper.ACTION_COPY -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("lookup_result", shareText))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
