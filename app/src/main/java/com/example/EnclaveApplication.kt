package com.example

import android.app.Application
import com.example.core.crypto.CryptoEngine
import com.example.core.database.EnclaveDatabase
import com.example.core.media.VoiceMediaManager
import com.example.core.network.BackendClientFactory
import com.example.core.network.RelayKeepAliveService
import com.example.core.network.SignalingSocketClient
import com.example.core.notifications.EnclaveNotificationManager
import com.example.core.prefs.SecurityPreferences
import com.example.core.webrtc.WebRtcCallManager
import com.example.core.workers.EnclaveWorkScheduler
import com.example.data.EnclaveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cryptoEngine = CryptoEngine()
    val database = EnclaveDatabase.getInstance(application)
    val securityPrefs = SecurityPreferences(application)
    val okHttpClient = BackendClientFactory.createOkHttpClient()
    val socketClient = SignalingSocketClient(okHttpClient, applicationScope)
    val callManager = WebRtcCallManager(application, socketClient, applicationScope)
    val mediaManager = VoiceMediaManager(application, cryptoEngine)
    val notificationManager = EnclaveNotificationManager(application)

    val repository = EnclaveRepository(
        context = application,
        db = database,
        crypto = cryptoEngine,
        prefs = securityPrefs,
        okHttpClient = okHttpClient,
        socketClient = socketClient,
        callManager = callManager,
        mediaManager = mediaManager,
        notificationManager = notificationManager,
        scope = applicationScope
    )
}

class EnclaveApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationManager.createChannels()
        runCatching { EnclaveWorkScheduler.scheduleAll(this) }
        container.applicationScope.launch(Dispatchers.IO) {
            val st = container.securityPrefs.stateFlow.first()
            if (st.isOnboarded) {
                container.repository.registerPushTokenIfAvailable()
                if (st.alwaysOnBackgroundRelay) {
                    RelayKeepAliveService.start(this@EnclaveApplication)
                }
            }
        }
    }
}
