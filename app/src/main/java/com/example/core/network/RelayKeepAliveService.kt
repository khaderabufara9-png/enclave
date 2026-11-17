package com.example.core.network

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.EnclaveApplication
import com.example.R
import com.example.core.notifications.EnclaveNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional Play-Policy-compliant dataSync foreground service that maintains an
 * authenticated WebSocket connection + heartbeat so incoming calls & encrypted
 * messages arrive reliably across countries even when FCM is not configured.
 */
class RelayKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, EnclaveNotificationManager.CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Enclave Private Relay Active")
            .setContentText("Ready for end-to-end encrypted calls & messages")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(9002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(9002, notification)
        }

        val app = applicationContext as? EnclaveApplication
        if (app != null) {
            app.container.repository.connectRealtimeSocket()
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(25_000L)
                    val repo = app.container.repository
                    if (repo.socketStateFlow.value == SocketConnectionState.CONNECTED) {
                        repo.socketClient.sendPing()
                    } else {
                        repo.connectRealtimeSocket()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartbeatJob?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, RelayKeepAliveService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RelayKeepAliveService::class.java)) }
        }
    }
}
