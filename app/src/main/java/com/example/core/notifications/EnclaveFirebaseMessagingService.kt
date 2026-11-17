package com.example.core.notifications

import android.content.Context
import com.example.EnclaveApplication
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PushRegistrationHelper {
    fun isFirebaseConfigured(context: Context): Boolean {
        return runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }

    fun fetchTokenIfConfigured(context: Context, onToken: (String) -> Unit) {
        if (!isFirebaseConfigured(context)) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    onToken(token)
                }
            }
        }
    }
}

class EnclaveFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = applicationContext as? EnclaveApplication ?: return
        serviceScope.launch {
            app.container.repository.onFcmTokenRefreshed(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val app = applicationContext as? EnclaveApplication ?: return
        val eventType = message.data["eventType"] ?: "MESSAGE_PENDING"
        serviceScope.launch {
            app.container.repository.handleWakeupPush(eventType)
        }
    }
}
