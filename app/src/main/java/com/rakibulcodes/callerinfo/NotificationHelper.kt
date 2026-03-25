package com.rakibulcodes.callerinfo

import android.Manifest
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity

object NotificationHelper {
    private const val CHANNEL_ID = "webhook_notifications"
    private const val SILENT_CHANNEL_ID = "webhook_notifications_silent"
    const val ACTION_SAVE = "com.rakibulcodes.callerinfo.action.SAVE"
    const val ACTION_COPY = "com.rakibulcodes.callerinfo.action.COPY"
    const val ACTION_SHARE = "com.rakibulcodes.callerinfo.action.SHARE"
    const val EXTRA_NUMBER = "extra_number"
    const val EXTRA_NAME = "extra_name"
    const val EXTRA_SHARE_TEXT = "extra_share_text"

    fun showNotification(
        context: Context,
        title: String,
        message: String,
        isSilent: Boolean = false,
        result: CallerInfoEntity? = null
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (isSilent) SILENT_CHANNEL_ID else CHANNEL_ID
        val notificationId = System.currentTimeMillis().toInt()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (isSilent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
            val name = if (isSilent) "Silent Alerts" else "Webhook Alerts"
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = "Notifications for call lookups and webhooks"
                if (isSilent) {
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val accentColor = ContextCompat.getColor(context, R.color.notification_accent)
        val largeIconBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.rakibulcodes.callerinfo.R.drawable.ic_notification_small_icon)
            .setLargeIcon(largeIconBitmap)
            .setContentTitle(title)
            .setContentText(message)
            .setColor(accentColor)
            .setPriority(if (isSilent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))

        if (result != null) {
            val displayName = result.name?.takeIf { it.isNotBlank() } ?: "Unknown"
            val shareText = buildShareText(result, message)

            builder
                .addAction(
                    R.drawable.ic_save_contact,
                    "Save",
                    createSaveContactPendingIntent(
                        context = context,
                        notificationId = notificationId,
                        number = result.number,
                        name = displayName,
                        requestCodeOffset = 1
                    )
                )
                .addAction(
                    R.drawable.ic_copy,
                    "Copy",
                    createActionPendingIntent(
                        context = context,
                        action = ACTION_COPY,
                        notificationId = notificationId,
                        number = result.number,
                        name = displayName,
                        shareText = shareText,
                        requestCodeOffset = 2
                    )
                )
                .addAction(
                    R.drawable.ic_share,
                    "Share",
                    createSharePendingIntent(
                        context = context,
                        notificationId = notificationId,
                        shareText = shareText,
                        requestCodeOffset = 3
                    )
                )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            }
        } else {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    private fun createActionPendingIntent(
        context: Context,
        action: String,
        notificationId: Int,
        number: String,
        name: String,
        shareText: String,
        requestCodeOffset: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_NUMBER, number)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_SHARE_TEXT, shareText)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, notificationId + requestCodeOffset, intent, flags)
    }

    private fun createSaveContactPendingIntent(
        context: Context,
        notificationId: Int,
        number: String,
        name: String,
        requestCodeOffset: Int
    ): PendingIntent {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, notificationId + requestCodeOffset, intent, flags)
    }

    private fun createSharePendingIntent(
        context: Context,
        notificationId: Int,
        shareText: String,
        requestCodeOffset: Int
    ): PendingIntent {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, notificationId + requestCodeOffset, chooserIntent, flags)
    }

    fun buildShareText(info: CallerInfoEntity, message: String = buildNotificationMessage(info)): String {
        return "Name: ${info.name ?: "Unknown"}\nNumber: ${info.number}\n\n$message"
    }

    fun buildNotificationMessage(info: CallerInfoEntity): String {
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
