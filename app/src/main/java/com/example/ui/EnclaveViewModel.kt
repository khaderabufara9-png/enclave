package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EnclaveApplication
import com.example.core.database.CallSessionEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.GroupCallEntity
import com.example.core.database.InvitationEntity
import com.example.core.database.MessageEntity
import com.example.core.database.ProfileEntity
import com.example.core.database.RelationshipEntity
import com.example.core.database.SecurityAuditEntity
import com.example.core.database.SpaceEntity
import com.example.core.network.SocketConnectionState
import com.example.core.prefs.SecuritySettingsState
import com.example.core.webrtc.ActiveCallState
import com.example.data.DiagnosticItem
import com.example.data.ParsedInvitation
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class EnclaveViewModel(application: Application) : AndroidViewModel(application) {

    val repo = (application as EnclaveApplication).container.repository

    val settings: StateFlow<SecuritySettingsState> = repo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SecuritySettingsState()
    )

    val visibleSpaces: StateFlow<List<SpaceEntity>> = combine(
        repo.visibleSpacesFlow,
        repo.activeSecretSpaces
    ) { visible, unlockedSecret ->
        visible + unlockedSecret
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeSpace: StateFlow<SpaceEntity?> = settings.flatMapLatest { st ->
        repo.observeSpace(st.activeSpaceId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeProfile: StateFlow<ProfileEntity?> = settings.flatMapLatest { st ->
        repo.observeProfileForSpace(st.activeSpaceId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val relationships: StateFlow<List<RelationshipEntity>> = settings.flatMapLatest { st ->
        repo.observeRelationships(st.activeSpaceId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val invitations: StateFlow<List<InvitationEntity>> = settings.flatMapLatest { st ->
        repo.observeInvitations(st.activeSpaceId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationEntity>> = settings.flatMapLatest { st ->
        repo.observeConversations(st.activeSpaceId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val calls: StateFlow<List<CallSessionEntity>> = settings.flatMapLatest { st ->
        repo.observeCalls(st.activeSpaceId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupCalls: StateFlow<List<GroupCallEntity>> = settings.flatMapLatest { st ->
        repo.observeGroupCalls(st.activeSpaceId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingOutboxCount: StateFlow<Int> = repo.pendingOutboxCountFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val socketState: StateFlow<SocketConnectionState> = repo.socketStateFlow
    val activeCallState: StateFlow<ActiveCallState> = repo.callManager.callState
    val typingStates: StateFlow<Map<String, Boolean>> = repo.typingStates
    val securityAudits: StateFlow<List<SecurityAuditEntity>> = repo.securityAuditFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<DiagnosticItem>>(emptyList())
    val diagnostics: StateFlow<List<DiagnosticItem>> = _diagnostics.asStateFlow()

    private val _isRunningDiagnostics = MutableStateFlow(false)
    val isRunningDiagnostics: StateFlow<Boolean> = _isRunningDiagnostics.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun showBanner(msg: String) {
        _statusBanner.value = msg
    }

    fun evaluateLockOnResume(nowMs: Long = System.currentTimeMillis()) {
        val st = settings.value
        if (!st.isOnboarded) return
        if (!st.pinEnabled && !st.biometricEnabled) return
        if (st.lastBackgroundTimestamp == 0L) {
            _isLocked.value = true
            return
        }
        val elapsedSec = (nowMs - st.lastBackgroundTimestamp) / 1000L
        if (elapsedSec >= st.autoLockSeconds) {
            _isLocked.value = true
        }
    }

    fun recordAppBackgrounded() {
        viewModelScope.launch {
            repo.prefs.recordBackgroundTimestamp(System.currentTimeMillis())
        }
    }

    fun lockNow() {
        val st = settings.value
        if (st.pinEnabled || st.biometricEnabled) {
            _isLocked.value = true
        }
    }

    fun unlockWithPin(pin: String): Boolean {
        val st = settings.value
        val crypto = getApplication<EnclaveApplication>().container.cryptoEngine
        if (st.duressPinEnabled && st.duressPinSaltB64.isNotBlank() &&
            crypto.verifySecretHash(pin, st.duressPinSaltB64, st.duressPinHashB64)
        ) {
            viewModelScope.launch { repo.triggerDuressWipe() }
            _isLocked.value = false
            return true
        }
        if (!st.pinEnabled) {
            _isLocked.value = false
            return true
        }
        val ok = crypto.verifySecretHash(
            pin,
            st.pinSaltB64,
            st.pinHashB64
        )
        if (ok) _isLocked.value = false
        return ok
    }

    fun unlockWithBiometricSuccess() {
        _isLocked.value = false
    }

    fun completeOnboarding(
        displayName: String,
        bio: String,
        avatarUri: Uri?,
        pin: String?,
        enableBiometric: Boolean,
        notificationsEnabled: Boolean,
        callNotificationsEnabled: Boolean,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            repo.initializeAnonymousIdentityAndOnboarding(
                displayName = displayName,
                bio = bio,
                avatarUri = avatarUri,
                pin = pin,
                enableBiometric = enableBiometric,
                notificationsEnabled = notificationsEnabled,
                callNotificationsEnabled = callNotificationsEnabled
            )
            onDone()
        }
    }

    fun createInvite(spaceId: String, onReady: (InvitationEntity, String) -> Unit) {
        viewModelScope.launch {
            val res = repo.createInvitation(spaceId)
            onReady(res.first, res.second)
        }
    }

    fun verifyInviteInput(raw: String, onResult: (ParsedInvitation?) -> Unit) {
        viewModelScope.launch {
            val parsed = repo.parseAndVerifyInvitationInput(raw)
            onResult(parsed)
        }
    }

    fun acceptInvitation(parsed: ParsedInvitation, spaceId: String, customMyName: String?, onDone: (RelationshipEntity) -> Unit) {
        viewModelScope.launch {
            val rel = repo.acceptParsedInvitation(parsed, spaceId, customMyName)
            _statusBanner.value = "Encrypted relationship established with ${rel.peerDisplayName}"
            onDone(rel)
        }
    }

    fun rejectInvitation(invitationId: String) {
        viewModelScope.launch { repo.rejectIncomingInvitation(invitationId) }
    }

    fun observeMessages(conversationId: String) = repo.observeMessages(conversationId)
    fun observeRelationship(relationshipId: String) = repo.observeRelationship(relationshipId)
    fun observeConversation(conversationId: String) = repo.observeConversation(conversationId)

    fun sendMessage(
        relationshipId: String,
        type: String,
        bodyText: String,
        mediaLocalPath: String? = null,
        mediaMimeType: String? = null,
        mediaFileName: String? = null,
        mediaSizeBytes: Long = 0L,
        mediaDurationMs: Long = 0L,
        latitude: Double? = null,
        longitude: Double? = null,
        replyToMessageId: String? = null,
        replyPreviewText: String? = null,
    ) {
        viewModelScope.launch {
            repo.sendEncryptedMessage(
                relationshipId, type, bodyText, mediaLocalPath, mediaMimeType,
                mediaFileName, mediaSizeBytes, mediaDurationMs, latitude, longitude,
                replyToMessageId, replyPreviewText
            )
        }
    }

    fun forwardMessage(targetRelationshipId: String, msg: MessageEntity) {
        viewModelScope.launch {
            repo.sendEncryptedMessage(
                relationshipId = targetRelationshipId,
                type = msg.type,
                bodyText = msg.bodyText,
                mediaLocalPath = msg.mediaLocalPath,
                mediaMimeType = msg.mediaMimeType,
                mediaFileName = msg.mediaFileName,
                mediaSizeBytes = msg.mediaSizeBytes,
                mediaDurationMs = msg.mediaDurationMs,
                latitude = msg.latitude,
                longitude = msg.longitude
            )
            _statusBanner.value = "Encrypted message forwarded"
        }
    }

    fun editMessage(messageId: String, newBody: String) {
        viewModelScope.launch { repo.editLocalMessage(messageId, newBody) }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        viewModelScope.launch { repo.toggleReaction(messageId, emoji) }
    }

    fun deleteMessageLocally(messageId: String) {
        viewModelScope.launch { repo.deleteMessageLocally(messageId) }
    }

    fun deleteMessageForBoth(messageId: String) {
        viewModelScope.launch { repo.deleteMessageForBoth(messageId) }
    }

    fun clearConversationForBoth(conversationId: String) {
        viewModelScope.launch { repo.clearConversationForBoth(conversationId) }
    }

    fun markConversationRead(conversationId: String) {
        viewModelScope.launch { repo.markConversationRead(conversationId) }
    }

    fun updateRelationshipPersona(relationshipId: String, name: String, bio: String, avatarUri: Uri?) {
        viewModelScope.launch {
            repo.updateRelationshipPersona(relationshipId, name, bio, avatarUri)
            _statusBanner.value = "Contact-specific identity updated"
        }
    }

    fun updateRelationshipControls(
        relationshipId: String,
        allowMessaging: Boolean? = null,
        allowCalls: Boolean? = null,
        notificationsEnabled: Boolean? = null,
        disappearingTtlSeconds: Long? = null,
        status: String? = null,
    ) {
        viewModelScope.launch {
            repo.updateRelationshipControls(
                relationshipId, allowMessaging, allowCalls, notificationsEnabled, disappearingTtlSeconds, status
            )
        }
    }

    fun revokeRelationship(relationshipId: String, wipeHistory: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.revokeRelationship(relationshipId, wipeHistory)
            _statusBanner.value = "Relationship revoked"
            onDone()
        }
    }

    fun initiateCall(relationshipId: String, isVideo: Boolean, onStarted: (String) -> Unit) {
        viewModelScope.launch {
            val id = repo.initiateCall(relationshipId, isVideo)
            if (id.isNotBlank()) onStarted(id)
        }
    }

    fun answerIncomingCall() {
        viewModelScope.launch { repo.answerActiveIncomingCall() }
    }

    fun hangupCall(rejected: Boolean = false) {
        viewModelScope.launch { repo.hangupActiveCall(rejected) }
    }

    fun startGroupCall(spaceId: String, title: String, relIds: List<String>, isVideo: Boolean, onStarted: (String) -> Unit) {
        viewModelScope.launch {
            val id = repo.startGroupCall(spaceId, title, relIds, isVideo)
            onStarted(id)
        }
    }

    fun shareCallAttachment(uri: Uri) {
        viewModelScope.launch {
            val ok = repo.shareFileOrVideoDuringCall(uri)
            _statusBanner.value = if (ok) "Encrypted file shared in call" else "Could not share attachment"
        }
    }

    fun switchSpace(spaceId: String) {
        viewModelScope.launch { repo.switchActiveSpace(spaceId) }
    }

    fun createSpace(name: String, accentHex: String, isHidden: Boolean, secretPass: String?, displayName: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val created = repo.createSpace(name, accentHex, isHidden, secretPass, displayName)
            repo.switchActiveSpace(created.spaceId)
            _statusBanner.value = if (isHidden) "Secret Space '${created.name}' unlocked" else "Space '${created.name}' created"
            onDone()
        }
    }

    fun unlockSecretSpace(secretName: String, secretPass: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val unlocked = repo.unlockSecretSpace(secretName, secretPass)
            onResult(unlocked != null)
        }
    }

    fun lockSecretSpaces() {
        viewModelScope.launch {
            repo.lockAllSecretSpaces()
            _statusBanner.value = "Secret Spaces locked and hidden"
        }
    }

    fun updateProfile(spaceId: String, displayName: String, bio: String, avatarUri: Uri?) {
        viewModelScope.launch {
            repo.updateSpaceProfile(spaceId, displayName, bio, avatarUri)
            _statusBanner.value = "Space profile saved"
        }
    }

    fun savePin(pin: String) {
        viewModelScope.launch {
            val (salt, hash) = getApplication<EnclaveApplication>().container.cryptoEngine.deriveSecretHash(pin)
            repo.prefs.savePin(salt, hash, true)
            _statusBanner.value = "App PIN lock updated"
        }
    }

    fun disablePin() {
        viewModelScope.launch { repo.prefs.disablePin() }
    }

    fun saveDuressPin(pin: String) {
        viewModelScope.launch {
            val (salt, hash) = getApplication<EnclaveApplication>().container.cryptoEngine.deriveSecretHash(pin)
            repo.prefs.saveDuressPin(salt, hash, true)
            _statusBanner.value = "Duress Wipe PIN configured"
        }
    }

    fun disableDuressPin() {
        viewModelScope.launch {
            repo.prefs.disableDuressPin()
            _statusBanner.value = "Duress PIN removed"
        }
    }

    fun setBiometric(enabled: Boolean) {
        viewModelScope.launch { repo.prefs.setBiometricEnabled(enabled) }
    }

    fun setAutoLockSeconds(sec: Int) {
        viewModelScope.launch { repo.prefs.setAutoLockSeconds(sec) }
    }

    fun setPrivacyPrefs(hidePreviews: Boolean? = null, screenshotProt: Boolean? = null, readReceipts: Boolean? = null, typing: Boolean? = null) {
        viewModelScope.launch {
            hidePreviews?.let { repo.prefs.setHidePreviews(it) }
            screenshotProt?.let { repo.prefs.setScreenshotProtection(it) }
            readReceipts?.let { repo.prefs.setReadReceipts(it) }
            typing?.let { repo.prefs.setTypingIndicators(it) }
        }
    }

    fun setNotificationPrefs(enabled: Boolean, callsEnabled: Boolean) {
        viewModelScope.launch { repo.prefs.setNotifications(enabled, callsEnabled) }
    }

    fun setThemePreference(pref: ThemePreference) {
        viewModelScope.launch { repo.prefs.setThemePreference(pref) }
    }

    fun setLanguageCode(lang: String) {
        viewModelScope.launch { repo.prefs.setLanguageCode(lang) }
    }

    fun saveNetworkEndpoints(backendUrl: String, stunUrl: String, turnUrl: String) {
        viewModelScope.launch {
            repo.prefs.setNetworkEndpoints(backendUrl, stunUrl, turnUrl)
            repo.registerDeviceWithBackend()
            repo.connectRealtimeSocket()
            _statusBanner.value = "Network endpoints updated & reconnecting"
        }
    }

    fun createTemporaryToken(purpose: String, callId: String? = null, onReady: (String, String) -> Unit) {
        viewModelScope.launch {
            val (token, url) = repo.generateTemporaryToken(purpose, callId)
            onReady(token, url)
        }
    }

    fun exportBackup(passphrase: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val bundle = repo.exportEncryptedBackup(passphrase)
            onReady(bundle)
        }
    }

    fun restoreBackup(bundle: String, passphrase: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repo.restoreEncryptedBackup(bundle, passphrase)
            onResult(ok)
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _isRunningDiagnostics.value = true
            _diagnostics.value = repo.runDiagnostics()
            _isRunningDiagnostics.value = false
        }
    }

    fun onAppForegrounded() {
        val st = settings.value
        if (!st.isOnboarded || st.installationId.isBlank()) return
        if (socketState.value != SocketConnectionState.CONNECTED) {
            repo.connectRealtimeSocket()
        } else {
            viewModelScope.launch {
                repo.syncPendingInboxFromBackend()
                repo.syncPendingCallsFromBackend()
            }
        }
        repo.registerPushTokenIfAvailable()
    }

    fun setAlwaysOnBackgroundRelay(enabled: Boolean) {
        viewModelScope.launch {
            repo.setAlwaysOnBackgroundRelay(enabled)
            _statusBanner.value = if (enabled) {
                "Background relay active for incoming calls"
            } else {
                "Background relay stopped"
            }
        }
    }

    fun applyServerConfigLink(rawInput: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repo.applyServerConfigLink(rawInput)
            _statusBanner.value = if (ok) {
                "Server endpoints updated & reconnecting"
            } else {
                "Invalid server URL or enclave://server link"
            }
            onResult(ok)
        }
    }

    fun getServerShareLink(onReady: (String) -> Unit) {
        viewModelScope.launch {
            onReady(repo.buildServerShareLink())
        }
    }
}
