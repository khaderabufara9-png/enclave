package com.example.core.webrtc

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.core.notifications.EnclaveNotificationManager

class CallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra("peer_name") ?: "Encrypted Call"
        val isVideo = intent?.getBooleanExtra("is_video", false) ?: false
        val isScreenSharing = intent?.getBooleanExtra("is_screen_sharing", false) ?: false

        val statusText = when {
            isScreenSharing -> "Screen sharing active (DTLS-SRTP Encrypted)"
            isVideo -> "End-to-end encrypted video call (DTLS-SRTP)"
            else -> "End-to-end encrypted voice call (DTLS-SRTP)"
        }

        val notification = NotificationCompat.Builder(this, EnclaveNotificationManager.CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(peerName)
            .setContentText(statusText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (isVideo) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (isScreenSharing) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(9001, notification, fgsType)
        } else {
            startForeground(9001, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
