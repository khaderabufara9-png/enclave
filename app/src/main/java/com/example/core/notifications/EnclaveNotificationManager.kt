package com.example.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class EnclaveNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_MESSAGES = "enclave_messages"
        const val CHANNEL_CALLS = "enclave_calls"
        const val CHANNEL_SERVICE = "enclave_call_service"
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Encrypted Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Zero-knowledge message alerts"
                setShowBadge(true)
            }
            val callChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Incoming Voice & Video Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time incoming call ring notifications"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Ongoing Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active encrypted voice/video call status"
            }
            nm.createNotificationChannels(listOf(msgChannel, callChannel, serviceChannel))
        }
    }

    fun showMessageNotification(
        notificationId: Int,
        senderName: String,
        messagePreview: String,
        hidePreviews: Boolean,
        spaceName: String,
    ) {
        if (!hasNotificationPermission()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (hidePreviews) "Enclave" else "$senderName ($spaceName)"
        val body = if (hidePreviews) "New encrypted message received" else messagePreview

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVisibility(
                if (hidePreviews) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PRIVATE
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    fun showIncomingCallNotification(
        callId: String,
        callerName: String,
        isVideo: Boolean,
        hidePreviews: Boolean,
    ) {
        if (!hasNotificationPermission()) return
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call_id", callId)
        }
        val pi = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayCaller = if (hidePreviews) "Private Contact" else callerName
        val callLabel = if (isVideo) "Incoming Encrypted Video Call" else "Incoming Encrypted Voice Call"

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayCaller)
            .setContentText(callLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(callId.hashCode(), notification)
        }
    }

    fun cancelCallNotification(callId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(callId.hashCode())
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
