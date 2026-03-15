package com.rakibulcodes.callerinfo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    private const val CHANNEL_ID = "webhook_notifications"
    private const val SILENT_CHANNEL_ID = "webhook_notifications_silent"

    fun showNotification(context: Context, title: String, message: String, isSilent: Boolean = false) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (isSilent) SILENT_CHANNEL_ID else CHANNEL_ID

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

        val isDark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
            }
        } else {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
}
