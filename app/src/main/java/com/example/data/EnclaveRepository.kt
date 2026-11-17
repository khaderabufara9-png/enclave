package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.core.crypto.CryptoEngine
import com.example.core.crypto.EncryptedEnvelope
import com.example.core.database.CallSessionEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.DeletionEventEntity
import com.example.core.database.EnclaveDatabase
import com.example.core.database.GroupCallEntity
import com.example.core.database.InvitationEntity
import com.example.core.database.MessageEntity
import com.example.core.database.PendingOutboxEntity
import com.example.core.database.ProfileEntity
import com.example.core.database.RelationshipEntity
import com.example.core.database.SecurityAuditEntity
import com.example.core.database.SpaceEntity
import com.example.core.media.VoiceMediaManager
import com.example.core.network.AcceptInvitationRequest
import com.example.core.network.AckEnvelopeRequest
import com.example.core.network.BackendClientFactory
import com.example.core.network.DeletionEventRequest
import com.example.core.network.EnclaveApiService
import com.example.core.network.IncomingSignalEvent
import com.example.core.network.PendingRelayEnvelope
import com.example.core.network.PublishInvitationRequest
import com.example.core.network.PushTokenRequest
import com.example.core.network.RegisterDeviceRequest
import com.example.core.network.RelationshipActionRequest
import com.example.core.network.RelayKeepAliveService
import com.example.core.network.RelayMessageRequest
import com.example.core.network.SignalingSocketClient
import com.example.core.network.SocketConnectionState
import com.example.core.network.TemporaryTokenRequest
import com.example.core.network.UploadMediaRequest
import com.example.core.notifications.EnclaveNotificationManager
import com.example.core.notifications.PushRegistrationHelper
import com.example.core.prefs.SecurityPreferences
import com.example.core.prefs.SecuritySettingsState
import com.example.core.webrtc.CallSharedItem
import com.example.core.webrtc.GroupParticipantState
import com.example.core.webrtc.WebRtcCallManager
import com.example.ui.theme.ThemePreference
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

data class DiagnosticItem(
    val id: String,
    val title: String,
    val status: String, // PASS, WARN, FAIL
    val detail: String,
    val recommendation: String,
)

data class ParsedInvitation(
    val invitationId: String,
    val code: String,
    val peerDeviceId: String,
    val peerSigningPubB64: String,
    val peerEcdhPubB64: String,
    val peerDisplayName: String,
    val expiresAtMs: Long,
    val signatureB64: String,
    val signatureValid: Boolean,
)

class EnclaveRepository(
    private val context: Context,
    private val db: EnclaveDatabase,
    private val crypto: CryptoEngine,
    val prefs: SecurityPreferences,
    private val okHttpClient: OkHttpClient,
    val socketClient: SignalingSocketClient,
    val callManager: WebRtcCallManager,
    val mediaManager: VoiceMediaManager,
    private val notificationManager: EnclaveNotificationManager,
    private val scope: CoroutineScope,
) {
    private val _typingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typingStates: StateFlow<Map<String, Boolean>> = _typingStates.asStateFlow()

    private val _unlockedSecretSpaceIds = MutableStateFlow<Set<String>>(emptySet())
    val unlockedSecretSpaceIds: StateFlow<Set<String>> = _unlockedSecretSpaceIds.asStateFlow()

    private val _activeSecretSpaces = MutableStateFlow<List<SpaceEntity>>(emptyList())
    val activeSecretSpaces: StateFlow<List<SpaceEntity>> = _activeSecretSpaces.asStateFlow()

    @Volatile
    private var cachedApiUrl: String = ""
    @Volatile
    private var cachedApiService: EnclaveApiService? = null

    init {
        observeSocketEvents()
        startEphemeralPurgeTicker()
        callManager.setDataChannelListener { json ->
            scope.launch(Dispatchers.IO) { handleIncomingCallDataChannelPayload(json) }
        }
    }

    private suspend fun apiService(): EnclaveApiService {
        val url = prefs.stateFlow.first().backendUrl
        val existing = cachedApiService
        if (existing != null && cachedApiUrl == url) return existing
        val created = BackendClientFactory.createApiService(url, okHttpClient)
        cachedApiUrl = url
        cachedApiService = created
        return created
    }

    // --- Flows ---
    val settingsFlow: Flow<SecuritySettingsState> = prefs.stateFlow
    val visibleSpacesFlow: Flow<List<SpaceEntity>> = db.spaceDao().observeVisibleSpaces()
    val pendingOutboxCountFlow: Flow<Int> = db.outboxDao().observePendingCount()
    val securityAuditFlow: Flow<List<SecurityAuditEntity>> = db.securityAuditDao().observeRecentEvents()
    val socketStateFlow: StateFlow<SocketConnectionState> = socketClient.connectionState

    fun observeSpace(spaceId: String): Flow<SpaceEntity?> = db.spaceDao().observeSpace(spaceId)
    fun observeProfileForSpace(spaceId: String): Flow<ProfileEntity?> = db.profileDao().observeProfileForSpace(spaceId)
    fun observeRelationships(spaceId: String): Flow<List<RelationshipEntity>> =
        db.relationshipDao().observeRelationshipsInSpace(spaceId)
    fun observeRelationship(relationshipId: String): Flow<RelationshipEntity?> =
        db.relationshipDao().observeRelationship(relationshipId)
    fun observeInvitations(spaceId: String): Flow<List<InvitationEntity>> =
        db.invitationDao().observeInvitationsForSpace(spaceId)
    fun observeConversations(spaceId: String): Flow<List<ConversationEntity>> =
        db.conversationDao().observeConversationsInSpace(spaceId)
    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        db.conversationDao().observeConversation(conversationId)
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeMessages(conversationId)
    fun observeDeletionEvents(relationshipId: String): Flow<List<DeletionEventEntity>> =
        db.deletionEventDao().observeForRelationship(relationshipId)
    fun observeCalls(spaceId: String): Flow<List<CallSessionEntity>> =
        db.callDao().observeCallsInSpace(spaceId)
    fun observeGroupCalls(spaceId: String): Flow<List<GroupCallEntity>> =
        db.callDao().observeGroupCalls(spaceId)

    // --- Phase 2: Anonymous Identity & Onboarding ---
    suspend fun initializeAnonymousIdentityAndOnboarding(
        displayName: String,
        bio: String,
        avatarUri: Uri?,
        pin: String?,
        enableBiometric: Boolean,
        notificationsEnabled: Boolean,
        callNotificationsEnabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        val currentState = prefs.stateFlow.first()
        val keyMaterial = if (currentState.installationId.isBlank()) {
            crypto.generateDeviceKeyMaterial().also { km ->
                prefs.saveDeviceKeys(
                    installationId = km.installationId,
                    signingPublicKeyB64 = km.signingPublicKeyB64,
                    signingPrivateKeyWrappedB64 = km.signingPrivateKeyWrappedB64,
                    ecdhPublicKeyB64 = km.ecdhPublicKeyB64,
                    ecdhPrivateKeyWrappedB64 = km.ecdhPrivateKeyWrappedB64
                )
            }
        } else {
            null
        }

        val installId = keyMaterial?.installationId ?: currentState.installationId
        val signingPub = keyMaterial?.signingPublicKeyB64 ?: currentState.signingPublicKeyB64
        val signingPrivWrapped = keyMaterial?.signingPrivateKeyWrappedB64 ?: currentState.signingPrivateKeyWrappedB64
        val ecdhPub = keyMaterial?.ecdhPublicKeyB64 ?: currentState.ecdhPublicKeyB64

        val savedAvatarPath = avatarUri?.let { mediaManager.importUriToPrivateStorage(it, "jpg")?.localFilePath }

        // Seed default Spaces (Personal, Work, Family, Friends)
        val defaultSpaces = listOf(
            SpaceEntity("space_personal", "Personal", "shield", "#00D2B8", isDefault = true),
            SpaceEntity("space_work", "Work", "work", "#6366F1"),
            SpaceEntity("space_family", "Family", "home", "#10B981"),
            SpaceEntity("space_friends", "Friends", "group", "#F59E0B"),
        )
        defaultSpaces.forEach { space ->
            if (db.spaceDao().getSpace(space.spaceId) == null) {
                db.spaceDao().upsertSpace(space)
            }
            if (db.profileDao().getProfileForSpace(space.spaceId) == null) {
                val spaceLabel = if (space.spaceId == "space_personal") displayName.ifBlank { "Anonymous" }
                else "${displayName.ifBlank { "Anonymous" }} (${space.name})"
                db.profileDao().upsertProfile(
                    ProfileEntity(
                        profileId = "prof_${space.spaceId}",
                        spaceId = space.spaceId,
                        displayName = spaceLabel,
                        bio = bio,
                        avatarLocalPath = savedAvatarPath,
                        enclaveCode = "ENC-${crypto.generateShortCode(8)}"
                    )
                )
            }
        }

        if (!pin.isNullOrBlank()) {
            val (salt, hash) = crypto.deriveSecretHash(pin)
            prefs.savePin(salt, hash, enabled = true)
        }
        prefs.setBiometricEnabled(enableBiometric)
        prefs.setNotifications(notificationsEnabled, callNotificationsEnabled)
        prefs.setActiveSpaceId("space_personal")
        prefs.setOnboarded(true)

        logAudit("IDENTITY_CREATED", "Generated anonymous installation ID $installId & EC P-256 Keystore keys")

        registerDeviceWithBackend(installId, signingPub, signingPrivWrapped, ecdhPub)
        connectRealtimeSocket()
    }

    suspend fun registerDeviceWithBackend(
        installId: String? = null,
        signingPub: String? = null,
        signingPrivWrapped: String? = null,
        ecdhPub: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        val id = installId ?: state.installationId
        val sPub = signingPub ?: state.signingPublicKeyB64
        val sPriv = signingPrivWrapped ?: state.signingPrivateKeyWrappedB64
        val ePub = ecdhPub ?: state.ecdhPublicKeyB64
        if (id.isBlank() || sPriv.isBlank()) return@withContext false

        val ts = System.currentTimeMillis()
        val canonical = "register|$id|$sPub|$ePub|$ts"
        val sig = crypto.signData(sPriv, canonical.toByteArray())
        return@withContext runCatching {
            val resp = apiService().registerDevice(
                RegisterDeviceRequest(
                    installationId = id,
                    signingPublicKeyB64 = sPub,
                    ecdhPublicKeyB64 = ePub,
                    signatureB64 = sig,
                    timestampMs = ts
                )
            )
            if (resp.isSuccessful) {
                registerPushTokenIfAvailable()
            }
            resp.isSuccessful
        }.getOrDefault(false)
    }

    fun registerPushTokenIfAvailable() {
        PushRegistrationHelper.fetchTokenIfConfigured(context) { token ->
            scope.launch(Dispatchers.IO) {
                onFcmTokenRefreshed(token)
            }
        }
    }

    suspend fun onFcmTokenRefreshed(token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext
        prefs.setFcmToken(token)
        val state = prefs.stateFlow.first()
        if (!state.isOnboarded || state.installationId.isBlank() || state.signingPrivateKeyWrappedB64.isBlank()) {
            return@withContext
        }
        val sig = crypto.signData(
            state.signingPrivateKeyWrappedB64,
            "push-register|${state.installationId}|$token".toByteArray()
        )
        runCatching {
            apiService().registerPushToken(
                PushTokenRequest(
                    deviceId = state.installationId,
                    fcmToken = token,
                    signatureB64 = sig
                )
            )
        }
    }

    suspend fun handleWakeupPush(eventType: String) = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        if (!state.isOnboarded || state.installationId.isBlank()) return@withContext
        connectRealtimeSocket()
        syncPendingInboxFromBackend()
        syncPendingCallsFromBackend()
        if (eventType == "INCOMING_CALL") {
            logAudit("PUSH_WAKEUP", "Zero-knowledge wake-up received for incoming call")
        }
    }

    suspend fun syncPendingCallsFromBackend() = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        if (state.installationId.isBlank() || state.signingPrivateKeyWrappedB64.isBlank()) return@withContext
        val ts = System.currentTimeMillis()
        val sig = crypto.signData(
            state.signingPrivateKeyWrappedB64,
            "pending-calls|${state.installationId}|$ts".toByteArray()
        )
        val signals = runCatching {
            apiService().fetchPendingCallSignals(state.installationId, ts, sig).body()?.signals
        }.getOrNull() ?: return@withContext

        for (rawJson in signals) {
            val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: continue
            val event = socketClient.parseSignalJson(obj) ?: continue
            handleIncomingSignalEvent(event)
        }
    }

    suspend fun setAlwaysOnBackgroundRelay(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.setAlwaysOnBackgroundRelay(enabled)
        if (enabled) {
            RelayKeepAliveService.start(context)
        } else {
            RelayKeepAliveService.stop(context)
        }
    }

    suspend fun buildServerShareLink(): String {
        val st = prefs.stateFlow.first()
        val encBackend = URLEncoder.encode(st.backendUrl, "UTF-8")
        val encStun = URLEncoder.encode(st.stunUrl, "UTF-8")
        val encTurn = URLEncoder.encode(st.turnUrl, "UTF-8")
        return "enclave://server?backend=$encBackend&stun=$encStun&turn=$encTurn"
    }

    suspend fun applyServerConfigLink(rawInput: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return@withContext false
        val st = prefs.stateFlow.first()
        val (newBackend, newStun, newTurn) = if (trimmed.startsWith("enclave://server", ignoreCase = true)) {
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return@withContext false
            val params = parseQueryParams(uri.rawQuery.orEmpty())
            val be = params["backend"]?.trim()?.removeSuffix("/") ?: return@withContext false
            val stun = params["stun"]?.trim().takeIf { !it.isNullOrBlank() } ?: st.stunUrl
            val turn = params["turn"]?.trim() ?: st.turnUrl
            Triple(be, stun, turn)
        } else if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            Triple(trimmed.removeSuffix("/"), st.stunUrl, st.turnUrl)
        } else {
            return@withContext false
        }

        prefs.setNetworkEndpoints(newBackend, newStun, newTurn)
        if (st.isOnboarded && st.installationId.isNotBlank()) {
            registerDeviceWithBackend()
            connectRealtimeSocket()
        }
        true
    }

    fun connectRealtimeSocket() {
        scope.launch(Dispatchers.IO) {
            val st = prefs.stateFlow.first()
            if (!st.isOnboarded || st.installationId.isBlank()) return@launch
            socketClient.connect(st.backendUrl, st.installationId) { ts ->
                crypto.signData(st.signingPrivateKeyWrappedB64, "ws-auth|${st.installationId}|$ts".toByteArray())
            }
            syncPendingInboxFromBackend()
            syncPendingCallsFromBackend()
            flushPendingOutbox()
        }
    }

    // --- Invitations & Per-Relationship Identity ---
    suspend fun createInvitation(spaceId: String, ttlHours: Int = 48): Pair<InvitationEntity, String> =
        withContext(Dispatchers.IO) {
            val state = prefs.stateFlow.first()
            val profile = db.profileDao().getProfileForSpace(spaceId)
                ?: ProfileEntity("prof_$spaceId", spaceId, "Anonymous", "", null, "ENC-${crypto.generateShortCode(8)}")
            val invId = "inv_${crypto.generateRandomId(8)}"
            val code = crypto.generateShortCode(10)
            val expiresAt = System.currentTimeMillis() + ttlHours * 3600_000L

            val canonical = "$invId|$code|${state.installationId}|${state.signingPublicKeyB64}|${state.ecdhPublicKeyB64}|$expiresAt"
            val sig = crypto.signData(state.signingPrivateKeyWrappedB64, canonical.toByteArray())

            val entity = InvitationEntity(
                invitationId = invId,
                code = code,
                spaceId = spaceId,
                direction = "OUTGOING",
                offeredDisplayName = profile.displayName,
                offeredBio = profile.bio,
                signatureB64 = sig,
                status = "PENDING",
                expiresAt = expiresAt
            )
            db.invitationDao().upsertInvitation(entity)

            runCatching {
                apiService().publishInvitation(
                    deviceId = state.installationId,
                    request = PublishInvitationRequest(
                        invitationId = invId,
                        code = code,
                        inviterDeviceId = state.installationId,
                        inviterSigningPublicKeyB64 = state.signingPublicKeyB64,
                        inviterEcdhPublicKeyB64 = state.ecdhPublicKeyB64,
                        offeredDisplayName = profile.displayName,
                        expiresAtMs = expiresAt,
                        signatureB64 = sig
                    )
                )
            }

            val shareUri = buildInviteUri(
                invId = invId,
                code = code,
                deviceId = state.installationId,
                signingPub = state.signingPublicKeyB64,
                ecdhPub = state.ecdhPublicKeyB64,
                displayName = profile.displayName,
                expiresAt = expiresAt,
                sig = sig
            )
            logAudit("INVITE_CREATED", "Generated Enclave invite $code for space $spaceId")
            entity to shareUri
        }

    fun buildInviteUri(
        invId: String,
        code: String,
        deviceId: String,
        signingPub: String,
        ecdhPub: String,
        displayName: String,
        expiresAt: Long,
        sig: String,
    ): String {
        return Uri.Builder()
            .scheme("enclave")
            .authority("invite")
            .appendQueryParameter("id", invId)
            .appendQueryParameter("code", code)
            .appendQueryParameter("did", deviceId)
            .appendQueryParameter("spub", signingPub)
            .appendQueryParameter("epub", ecdhPub)
            .appendQueryParameter("name", displayName)
            .appendQueryParameter("exp", expiresAt.toString())
            .appendQueryParameter("sig", sig)
            .build()
            .toString()
    }

    suspend fun parseAndVerifyInvitationInput(rawInput: String): ParsedInvitation? = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("enclave://invite")) {
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return@withContext null
            val params = parseQueryParams(uri.rawQuery ?: "")
            val invId = params["id"] ?: return@withContext null
            val code = params["code"] ?: return@withContext null
            val did = params["did"] ?: return@withContext null
            val spub = params["spub"] ?: return@withContext null
            val epub = params["epub"] ?: return@withContext null
            val name = params["name"] ?: "Anonymous Peer"
            val exp = params["exp"]?.toLongOrNull() ?: 0L
            val sig = params["sig"] ?: return@withContext null
            val canonical = "$invId|$code|$did|$spub|$epub|$exp"
            val valid = crypto.verifySignature(spub, canonical.toByteArray(), sig)
            return@withContext ParsedInvitation(invId, code, did, spub, epub, name, exp, sig, valid)
        }

        // Otherwise treat as short invite code & look up from backend
        val state = prefs.stateFlow.first()
        val remote = runCatching {
            apiService().lookupInvitation(trimmed.uppercase(), state.installationId).body()
        }.getOrNull() ?: return@withContext null
        val canonical = "${remote.invitationId}|${remote.code}|${remote.inviterDeviceId}|${remote.inviterSigningPublicKeyB64}|${remote.inviterEcdhPublicKeyB64}|${remote.expiresAtMs}"
        val valid = crypto.verifySignature(remote.inviterSigningPublicKeyB64, canonical.toByteArray(), remote.signatureB64)
        ParsedInvitation(
            invitationId = remote.invitationId,
            code = remote.code,
            peerDeviceId = remote.inviterDeviceId,
            peerSigningPubB64 = remote.inviterSigningPublicKeyB64,
            peerEcdhPubB64 = remote.inviterEcdhPublicKeyB64,
            peerDisplayName = remote.offeredDisplayName,
            expiresAtMs = remote.expiresAtMs,
            signatureB64 = remote.signatureB64,
            signatureValid = valid
        )
    }

    suspend fun acceptParsedInvitation(
        parsed: ParsedInvitation,
        spaceId: String,
        myCustomNameForPeer: String? = null,
    ): RelationshipEntity = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        val spaceProfile = db.profileDao().getProfileForSpace(spaceId)
        val myDisplay = myCustomNameForPeer?.takeIf { it.isNotBlank() }
            ?: spaceProfile?.displayName
            ?: "Anonymous"
        val relationshipId = "rel_${crypto.generateRandomId(10)}"

        val rootKey = crypto.deriveRelationshipRootKey(
            myWrappedEcdhPrivateKeyB64 = state.ecdhPrivateKeyWrappedB64,
            peerEcdhPublicKeyB64 = parsed.peerEcdhPubB64,
            relationshipSalt = parsed.code
        )
        val wrappedRootKey = crypto.wrapSecretB64(rootKey)
        val fingerprint = crypto.computeSafetyFingerprint(state.signingPublicKeyB64, parsed.peerSigningPubB64)

        val relationship = RelationshipEntity(
            relationshipId = relationshipId,
            spaceId = spaceId,
            peerDeviceId = parsed.peerDeviceId,
            peerSigningPublicKeyB64 = parsed.peerSigningPubB64,
            peerEcdhPublicKeyB64 = parsed.peerEcdhPubB64,
            peerDisplayName = parsed.peerDisplayName,
            myDisplayName = myDisplay,
            myBio = spaceProfile?.bio ?: "",
            myAvatarPath = spaceProfile?.avatarLocalPath,
            wrappedRootKeyB64 = wrappedRootKey,
            safetyFingerprint = fingerprint,
            status = "ACTIVE"
        )
        db.relationshipDao().upsertRelationship(relationship)

        val conv = ConversationEntity(
            conversationId = "conv_$relationshipId",
            relationshipId = relationshipId,
            spaceId = spaceId,
            lastMessagePreview = "Encrypted channel established",
            lastMessageAt = System.currentTimeMillis()
        )
        db.conversationDao().upsertConversation(conv)

        // Store invitation record
        db.invitationDao().upsertInvitation(
            InvitationEntity(
                invitationId = parsed.invitationId,
                code = parsed.code,
                spaceId = spaceId,
                direction = "INCOMING",
                peerDeviceId = parsed.peerDeviceId,
                peerDisplayName = parsed.peerDisplayName,
                peerSigningPublicKeyB64 = parsed.peerSigningPubB64,
                peerEcdhPublicKeyB64 = parsed.peerEcdhPubB64,
                offeredDisplayName = myDisplay,
                signatureB64 = parsed.signatureB64,
                status = "ACCEPTED",
                expiresAt = parsed.expiresAtMs
            )
        )

        // Notify backend so inviter receives the acceptance & public key
        val acceptSig = crypto.signData(
            state.signingPrivateKeyWrappedB64,
            "accept|${parsed.code}|$relationshipId|${state.installationId}".toByteArray()
        )
        runCatching {
            apiService().acceptInvitation(
                code = parsed.code,
                request = AcceptInvitationRequest(
                    relationshipId = relationshipId,
                    responderDeviceId = state.installationId,
                    responderSigningPublicKeyB64 = state.signingPublicKeyB64,
                    responderEcdhPublicKeyB64 = state.ecdhPublicKeyB64,
                    responderDisplayName = myDisplay,
                    signatureB64 = acceptSig
                )
            )
        }
        logAudit("RELATIONSHIP_ESTABLISHED", "Established E2EE relationship $relationshipId with ${parsed.peerDisplayName}")
        relationship
    }

    suspend fun rejectIncomingInvitation(invitationId: String) = withContext(Dispatchers.IO) {
        db.invitationDao().updateStatus(invitationId, "REJECTED")
    }

    suspend fun updateRelationshipPersona(
        relationshipId: String,
        myDisplayName: String,
        myBio: String,
        myAvatarUri: Uri?,
    ) = withContext(Dispatchers.IO) {
        val rel = db.relationshipDao().getRelationship(relationshipId) ?: return@withContext
        val newAvatar = myAvatarUri?.let { mediaManager.importUriToPrivateStorage(it, "jpg")?.localFilePath } ?: rel.myAvatarPath
        val updated = rel.copy(
            myDisplayName = myDisplayName.ifBlank { rel.myDisplayName },
            myBio = myBio,
            myAvatarPath = newAvatar
        )
        db.relationshipDao().updateRelationship(updated)
        logAudit("PERSONA_UPDATED", "Updated per-contact identity for ${rel.peerDisplayName} to '$myDisplayName'")
    }

    suspend fun updateRelationshipControls(
        relationshipId: String,
        allowMessaging: Boolean? = null,
        allowIncomingCalls: Boolean? = null,
        notificationsEnabled: Boolean? = null,
        disappearingTtlSeconds: Long? = null,
        status: String? = null,
    ) = withContext(Dispatchers.IO) {
        val rel = db.relationshipDao().getRelationship(relationshipId) ?: return@withContext
        val updated = rel.copy(
            allowMessaging = allowMessaging ?: rel.allowMessaging,
            allowIncomingCalls = allowIncomingCalls ?: rel.allowIncomingCalls,
            notificationsEnabled = notificationsEnabled ?: rel.notificationsEnabled,
            disappearingTtlSeconds = disappearingTtlSeconds ?: rel.disappearingTtlSeconds,
            status = status ?: rel.status
        )
        db.relationshipDao().updateRelationship(updated)

        if (status == "BLOCKED" || status == "ACTIVE" || status == "REVOKED") {
            val action = when (status) {
                "BLOCKED" -> "BLOCK"
                "REVOKED" -> "REVOKE"
                else -> "UNBLOCK"
            }
            sendRelationshipActionToBackend(updated, action)
        }
    }

    suspend fun revokeRelationship(relationshipId: String, wipeLocalHistory: Boolean = true) =
        withContext(Dispatchers.IO) {
            val rel = db.relationshipDao().getRelationship(relationshipId) ?: return@withContext
            db.relationshipDao().updateRelationship(rel.copy(status = "REVOKED", allowMessaging = false, allowIncomingCalls = false))
            sendRelationshipActionToBackend(rel, "REVOKE")
            if (wipeLocalHistory) {
                val conv = db.conversationDao().getByRelationship(relationshipId)
                if (conv != null) {
                    db.messageDao().deleteMessagesForConversation(conv.conversationId)
                    db.conversationDao().deleteConversation(conv.conversationId)
                }
            }
            logAudit("RELATIONSHIP_REVOKED", "Revoked relationship $relationshipId (${rel.peerDisplayName})")
        }

    private suspend fun sendRelationshipActionToBackend(rel: RelationshipEntity, action: String) {
        val state = prefs.stateFlow.first()
        val sig = crypto.signData(
            state.signingPrivateKeyWrappedB64,
            "rel-action|${rel.relationshipId}|${state.installationId}|$action".toByteArray()
        )
        runCatching {
            apiService().updateRelationshipState(
                RelationshipActionRequest(
                    relationshipId = rel.relationshipId,
                    actorDeviceId = state.installationId,
                    peerDeviceId = rel.peerDeviceId,
                    action = action,
                    signatureB64 = sig
                )
            )
        }
    }

    // --- Phase 3: Encrypted Messaging, Offline Outbox, Ephemeral & Both-Device Deletion ---
    suspend fun sendEncryptedMessage(
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
    ): MessageEntity? = withContext(Dispatchers.IO) {
        val rel = db.relationshipDao().getRelationship(relationshipId) ?: return@withContext null
        if (rel.status != "ACTIVE" || !rel.allowMessaging) return@withContext null

        val state = prefs.stateFlow.first()
        val conv = db.conversationDao().getByRelationship(relationshipId)
            ?: ConversationEntity("conv_$relationshipId", relationshipId, rel.spaceId).also {
                db.conversationDao().upsertConversation(it)
            }

        val now = System.currentTimeMillis()
        val messageId = "msg_${crypto.generateRandomId(10)}"
        val nextSeq = rel.sendSequence + 1L
        val ttl = rel.disappearingTtlSeconds
        val expiresAt = if (ttl > 0L) now + ttl * 1000L else null

        val localMsg = MessageEntity(
            messageId = messageId,
            conversationId = conv.conversationId,
            relationshipId = relationshipId,
            spaceId = rel.spaceId,
            senderDeviceId = state.installationId,
            isOutgoing = true,
            type = type,
            bodyText = bodyText,
            mediaLocalPath = mediaLocalPath,
            mediaMimeType = mediaMimeType,
            mediaFileName = mediaFileName,
            mediaSizeBytes = mediaSizeBytes,
            mediaDurationMs = mediaDurationMs,
            latitude = latitude,
            longitude = longitude,
            replyToMessageId = replyToMessageId,
            replyPreviewText = replyPreviewText,
            deliveryStatus = "SENDING",
            ttlSeconds = ttl,
            expiresAt = expiresAt,
            sequenceNumber = nextSeq,
            createdAt = now
        )
        db.messageDao().upsertMessage(localMsg)
        db.relationshipDao().updateRelationship(rel.copy(sendSequence = nextSeq))

        val preview = when (type) {
            "IMAGE" -> "Photo"
            "VIDEO" -> "Video"
            "VOICE" -> "Voice message"
            "AUDIO" -> "Audio"
            "DOCUMENT" -> mediaFileName ?: "Document"
            "LOCATION" -> "Shared location"
            else -> bodyText
        }
        db.conversationDao().upsertConversation(
            conv.copy(lastMessagePreview = preview, lastMessageAt = now)
        )

        // If attachment exists, encrypt per-file with AES-256-GCM + SHA-256 and upload ciphertext blob
        var remoteMediaId: String? = null
        var remoteFileKeyB64: String? = null
        var remoteFileIvB64: String? = null
        var remoteFileSha256: String? = null
        if (!mediaLocalPath.isNullOrBlank()) {
            val rawBytes = mediaManager.readMediaBytes(mediaLocalPath)
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val blob = crypto.encryptFileForRelay(rawBytes)
                val mediaId = "med_${crypto.generateRandomId(10)}"
                val upTs = System.currentTimeMillis()
                val upSig = crypto.signData(
                    state.signingPrivateKeyWrappedB64,
                    "media-upload|$mediaId|${state.installationId}|$upTs".toByteArray()
                )
                val uploaded = runCatching {
                    apiService().uploadEncryptedMedia(
                        UploadMediaRequest(
                            mediaId = mediaId,
                            uploaderDeviceId = state.installationId,
                            ciphertextB64 = crypto.encodeB64(blob.ciphertextBytes),
                            sizeBytes = rawBytes.size.toLong(),
                            timestampMs = upTs,
                            signatureB64 = upSig
                        )
                    ).isSuccessful
                }.getOrDefault(false)
                if (uploaded) {
                    remoteMediaId = mediaId
                    remoteFileKeyB64 = blob.fileKeyB64
                    remoteFileIvB64 = blob.ivB64
                    remoteFileSha256 = blob.sha256Hex
                }
            }
        }

        // Build inner plaintext JSON (encrypted before leaving device!)
        val innerJson = JSONObject().apply {
            put("messageId", messageId)
            put("type", type)
            put("bodyText", bodyText)
            put("ttlSeconds", ttl)
            put("replyToMessageId", replyToMessageId ?: "")
            put("replyPreviewText", replyPreviewText ?: "")
            if (latitude != null && longitude != null) {
                put("latitude", latitude)
                put("longitude", longitude)
            }
            if (mediaFileName != null) {
                put("mediaFileName", mediaFileName)
                put("mediaMimeType", mediaMimeType ?: "")
                put("mediaSizeBytes", mediaSizeBytes)
                put("mediaDurationMs", mediaDurationMs)
            }
            if (remoteMediaId != null) {
                put("mediaId", remoteMediaId)
                put("fileKeyB64", remoteFileKeyB64)
                put("fileIvB64", remoteFileIvB64)
                put("fileSha256", remoteFileSha256)
            }
        }

        val rootKey = crypto.unwrapSecretB64(rel.wrappedRootKeyB64)
        val aad = "$relationshipId|${state.installationId}|$messageId|$now"
        val envelope = crypto.encryptMessage(
            plaintext = innerJson.toString().toByteArray(),
            rootKey = rootKey,
            sequence = nextSeq,
            aad = aad
        )

        val relayCanonical = "$messageId|$relationshipId|${state.installationId}|${rel.peerDeviceId}|${envelope.ivB64}|${envelope.ciphertextB64}|$nextSeq|$now"
        val sig = crypto.signData(state.signingPrivateKeyWrappedB64, relayCanonical.toByteArray())

        val relayReq = RelayMessageRequest(
            messageId = messageId,
            relationshipId = relationshipId,
            senderDeviceId = state.installationId,
            recipientDeviceId = rel.peerDeviceId,
            ivB64 = envelope.ivB64,
            ciphertextB64 = envelope.ciphertextB64,
            sequence = nextSeq,
            ttlSeconds = ttl,
            signatureB64 = sig,
            sentAtMs = now
        )

        val deliveredStatus = trySendRelay(relayReq)
        if (deliveredStatus != null) {
            db.messageDao().updateDeliveryStatus(messageId, deliveredStatus)
        } else {
            // Enqueue in encrypted outbox for offline recovery
            val outboxJson = JSONObject().apply {
                put("messageId", messageId)
                put("relationshipId", relationshipId)
                put("senderDeviceId", state.installationId)
                put("recipientDeviceId", rel.peerDeviceId)
                put("ivB64", envelope.ivB64)
                put("ciphertextB64", envelope.ciphertextB64)
                put("sequence", nextSeq)
                put("ttlSeconds", ttl)
                put("signatureB64", sig)
                put("sentAtMs", now)
            }.toString()
            db.outboxDao().enqueue(
                PendingOutboxEntity(
                    outboxId = "out_$messageId",
                    kind = "MESSAGE",
                    relationshipId = relationshipId,
                    targetDeviceId = rel.peerDeviceId,
                    encryptedPayloadJson = outboxJson
                )
            )
            db.messageDao().updateDeliveryStatus(messageId, "QUEUED_OFFLINE")
        }
        db.messageDao().getMessage(messageId)
    }

    private suspend fun trySendRelay(req: RelayMessageRequest): String? {
        return runCatching {
            val resp = apiService().sendEncryptedRelay(req.senderDeviceId, req)
            if (resp.isSuccessful) {
                val bodyStatus = resp.body()?.status
                if (bodyStatus == "DELIVERED_WS") "DELIVERED" else "SENT"
            } else null
        }.getOrNull()
    }

    suspend fun editLocalMessage(messageId: String, newBody: String) = withContext(Dispatchers.IO) {
        val msg = db.messageDao().getMessage(messageId) ?: return@withContext
        if (!msg.isOutgoing) return@withContext
        val now = System.currentTimeMillis()
        db.messageDao().editMessage(messageId, newBody, now)
        val rel = db.relationshipDao().getRelationship(msg.relationshipId) ?: return@withContext
        socketClient.sendJson(JSONObject().apply {
            put("type", "edit_message")
            put("messageId", messageId)
            put("relationshipId", msg.relationshipId)
            put("toDeviceId", rel.peerDeviceId)
            put("newBody", newBody)
            put("editedAtMs", now)
        })
    }

    suspend fun toggleReaction(messageId: String, emoji: String) = withContext(Dispatchers.IO) {
        val msg = db.messageDao().getMessage(messageId) ?: return@withContext
        val obj = runCatching { JSONObject(msg.reactionsJson) }.getOrDefault(JSONObject())
        val currentCount = obj.optInt(emoji, 0)
        val active = currentCount <= 0
        if (active) obj.put(emoji, 1) else obj.remove(emoji)
        db.messageDao().updateReactions(messageId, obj.toString())
        val rel = db.relationshipDao().getRelationship(msg.relationshipId) ?: return@withContext
        socketClient.sendJson(JSONObject().apply {
            put("type", "reaction_update")
            put("messageId", messageId)
            put("relationshipId", msg.relationshipId)
            put("toDeviceId", rel.peerDeviceId)
            put("emoji", emoji)
            put("active", active)
        })
    }

    suspend fun deleteMessageLocally(messageId: String) = withContext(Dispatchers.IO) {
        val msg = db.messageDao().getMessage(messageId)
        mediaManager.deleteMediaFile(msg?.mediaLocalPath)
        db.messageDao().deleteMessage(messageId)
    }

    suspend fun deleteMessageForBoth(messageId: String) = withContext(Dispatchers.IO) {
        val msg = db.messageDao().getMessage(messageId) ?: return@withContext
        val rel = db.relationshipDao().getRelationship(msg.relationshipId) ?: return@withContext
        val state = prefs.stateFlow.first()

        // Mark pending peer acknowledgment locally first (never falsely claim completed!)
        db.messageDao().updateRemoteDeleteStatus(messageId, "PENDING_PEER_ACK")
        val eventId = "del_${crypto.generateRandomId(8)}"
        val now = System.currentTimeMillis()
        val canonical = "$eventId|${rel.relationshipId}|MESSAGE|$messageId|${state.installationId}|$now"
        val sig = crypto.signData(state.signingPrivateKeyWrappedB64, canonical.toByteArray())

        db.deletionEventDao().upsert(
            DeletionEventEntity(
                eventId = eventId,
                relationshipId = rel.relationshipId,
                scope = "MESSAGE",
                targetMessageId = messageId,
                status = "PENDING_ACK",
                signatureB64 = sig,
                createdAt = now
            )
        )

        val sent = runCatching {
            apiService().sendDeletionEvent(
                DeletionEventRequest(
                    eventId = eventId,
                    relationshipId = rel.relationshipId,
                    senderDeviceId = state.installationId,
                    recipientDeviceId = rel.peerDeviceId,
                    scope = "MESSAGE",
                    targetMessageId = messageId,
                    signatureB64 = sig,
                    createdAtMs = now
                )
            ).isSuccessful
        }.getOrDefault(false)

        if (!sent) {
            db.outboxDao().enqueue(
                PendingOutboxEntity(
                    outboxId = "out_$eventId",
                    kind = "DELETION",
                    relationshipId = rel.relationshipId,
                    targetDeviceId = rel.peerDeviceId,
                    encryptedPayloadJson = JSONObject().apply {
                        put("eventId", eventId)
                        put("relationshipId", rel.relationshipId)
                        put("senderDeviceId", state.installationId)
                        put("recipientDeviceId", rel.peerDeviceId)
                        put("scope", "MESSAGE")
                        put("targetMessageId", messageId)
                        put("signatureB64", sig)
                        put("createdAtMs", now)
                    }.toString()
                )
            )
        }
    }

    suspend fun clearConversationForBoth(conversationId: String) = withContext(Dispatchers.IO) {
        val conv = db.conversationDao().getConversation(conversationId) ?: return@withContext
        val rel = db.relationshipDao().getRelationship(conv.relationshipId) ?: return@withContext
        val state = prefs.stateFlow.first()

        db.messageDao().deleteMessagesForConversation(conversationId)
        db.conversationDao().upsertConversation(
            conv.copy(
                lastMessagePreview = "Conversation cleared (awaiting peer ack)",
                unreadCount = 0,
                pendingRemoteClearStatus = "PENDING_PEER_ACK"
            )
        )

        val eventId = "del_${crypto.generateRandomId(8)}"
        val now = System.currentTimeMillis()
        val canonical = "$eventId|${rel.relationshipId}|CONVERSATION||${state.installationId}|$now"
        val sig = crypto.signData(state.signingPrivateKeyWrappedB64, canonical.toByteArray())
        db.deletionEventDao().upsert(
            DeletionEventEntity(
                eventId = eventId,
                relationshipId = rel.relationshipId,
                scope = "CONVERSATION",
                status = "PENDING_ACK",
                signatureB64 = sig,
                createdAt = now
            )
        )

        runCatching {
            apiService().sendDeletionEvent(
                DeletionEventRequest(
                    eventId = eventId,
                    relationshipId = rel.relationshipId,
                    senderDeviceId = state.installationId,
                    recipientDeviceId = rel.peerDeviceId,
                    scope = "CONVERSATION",
                    signatureB64 = sig,
                    createdAtMs = now
                )
            )
        }
    }

    suspend fun searchConversationMessages(conversationId: String, query: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) emptyList()
            else db.messageDao().searchMessages(conversationId, query)
        }

    suspend fun markConversationRead(conversationId: String) = withContext(Dispatchers.IO) {
        db.conversationDao().clearUnread(conversationId)
    }

    suspend fun purgeExpiredMessagesNow(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val expired = db.messageDao().getExpiredMessages(now)
        expired.forEach { mediaManager.deleteMediaFile(it.mediaLocalPath) }
        db.messageDao().purgeExpiredMessages(now)
    }

    suspend fun flushPendingOutbox() = withContext(Dispatchers.IO) {
        val pending = db.outboxDao().getAllPending()
        for (item in pending) {
            val obj = runCatching { JSONObject(item.encryptedPayloadJson) }.getOrNull() ?: continue
            val ok = when (item.kind) {
                "MESSAGE" -> {
                    val req = RelayMessageRequest(
                        messageId = obj.getString("messageId"),
                        relationshipId = obj.getString("relationshipId"),
                        senderDeviceId = obj.getString("senderDeviceId"),
                        recipientDeviceId = obj.getString("recipientDeviceId"),
                        ivB64 = obj.getString("ivB64"),
                        ciphertextB64 = obj.getString("ciphertextB64"),
                        sequence = obj.getLong("sequence"),
                        ttlSeconds = obj.getLong("ttlSeconds"),
                        signatureB64 = obj.getString("signatureB64"),
                        sentAtMs = obj.getLong("sentAtMs")
                    )
                    val st = trySendRelay(req)
                    if (st != null) {
                        db.messageDao().updateDeliveryStatus(req.messageId, st)
                        true
                    } else false
                }
                "DELETION" -> {
                    runCatching {
                        apiService().sendDeletionEvent(
                            DeletionEventRequest(
                                eventId = obj.getString("eventId"),
                                relationshipId = obj.getString("relationshipId"),
                                senderDeviceId = obj.getString("senderDeviceId"),
                                recipientDeviceId = obj.getString("recipientDeviceId"),
                                scope = obj.getString("scope"),
                                targetMessageId = obj.optString("targetMessageId").takeIf { it.isNotBlank() },
                                signatureB64 = obj.getString("signatureB64"),
                                createdAtMs = obj.getLong("createdAtMs")
                            )
                        ).isSuccessful
                    }.getOrDefault(false)
                }
                else -> false
            }

            if (ok) {
                db.outboxDao().remove(item.outboxId)
            } else {
                db.outboxDao().markRetry(item.outboxId, System.currentTimeMillis() + 30_000L)
            }
        }
    }

    suspend fun syncPendingInboxFromBackend() = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        if (state.installationId.isBlank()) return@withContext
        val ts = System.currentTimeMillis()
        val sig = crypto.signData(state.signingPrivateKeyWrappedB64, "pending|${state.installationId}|$ts".toByteArray())
        val envelopes = runCatching {
            apiService().fetchPendingEnvelopes(state.installationId, ts, sig).body()?.envelopes
        }.getOrNull() ?: return@withContext

        for (env in envelopes) {
            processIncomingEnvelope(env)
        }
    }

    private suspend fun processIncomingEnvelope(env: PendingRelayEnvelope) {
        val rel = db.relationshipDao().getRelationship(env.relationshipId) ?: return
        if (rel.status == "REVOKED" || rel.status == "BLOCKED") return

        val canonical = "${env.messageId}|${env.relationshipId}|${env.senderDeviceId}|${prefs.stateFlow.first().installationId}|${env.ivB64}|${env.ciphertextB64}|${env.sequence}|${env.sentAtMs}"
        if (!crypto.verifySignature(rel.peerSigningPublicKeyB64, canonical.toByteArray(), env.signatureB64)) {
            logAudit("SIGNATURE_REJECTED", "Rejected unauthenticated envelope ${env.messageId}")
            return
        }

        val rootKey = crypto.unwrapSecretB64(rel.wrappedRootKeyB64)
        val aad = "${env.relationshipId}|${env.senderDeviceId}|${env.messageId}|${env.sentAtMs}"
        val decryptedBytes = runCatching {
            crypto.decryptMessage(
                EncryptedEnvelope(env.ivB64, env.ciphertextB64, env.sequence),
                rootKey,
                aad
            )
        }.getOrNull() ?: return

        val inner = JSONObject(String(decryptedBytes))
        val ttl = inner.optLong("ttlSeconds", env.ttlSeconds)
        val now = System.currentTimeMillis()
        val convId = "conv_${rel.relationshipId}"
        val state = prefs.stateFlow.first()

        var downloadedPath: String? = null
        val remoteMediaId = inner.optString("mediaId")
        val fileKeyB64 = inner.optString("fileKeyB64")
        val fileIvB64 = inner.optString("fileIvB64")
        val fileSha256 = inner.optString("fileSha256")
        val fileName = inner.optString("mediaFileName").takeIf { it.isNotBlank() }
        val mimeType = inner.optString("mediaMimeType").takeIf { it.isNotBlank() }

        if (remoteMediaId.isNotBlank() && fileKeyB64.isNotBlank() && fileIvB64.isNotBlank()) {
            val dlTs = System.currentTimeMillis()
            val dlSig = crypto.signData(
                state.signingPrivateKeyWrappedB64,
                "media-download|$remoteMediaId|${state.installationId}|$dlTs".toByteArray()
            )
            val resp = runCatching {
                apiService().downloadEncryptedMedia(remoteMediaId, state.installationId, dlTs, dlSig).body()
            }.getOrNull()
            if (resp != null) {
                val plainBytes = runCatching {
                    crypto.decryptFileFromRelay(
                        ciphertextBytes = crypto.decodeB64(resp.ciphertextB64),
                        fileKeyB64 = fileKeyB64,
                        ivB64 = fileIvB64,
                        expectedSha256Hex = fileSha256
                    )
                }.getOrNull()
                if (plainBytes != null) {
                    downloadedPath = mediaManager.saveMediaBytes(
                        bytes = plainBytes,
                        fileNameHint = fileName ?: "media_$remoteMediaId.bin",
                        mimeType = mimeType ?: "application/octet-stream"
                    )?.localFilePath
                }
            }
        }

        val msg = MessageEntity(
            messageId = env.messageId,
            conversationId = convId,
            relationshipId = rel.relationshipId,
            spaceId = rel.spaceId,
            senderDeviceId = env.senderDeviceId,
            isOutgoing = false,
            type = inner.optString("type", "TEXT"),
            bodyText = inner.optString("bodyText", ""),
            mediaLocalPath = downloadedPath,
            mediaMimeType = mimeType,
            mediaFileName = fileName,
            mediaSizeBytes = inner.optLong("mediaSizeBytes", 0L),
            mediaDurationMs = inner.optLong("mediaDurationMs", 0L),
            latitude = if (inner.has("latitude")) inner.optDouble("latitude") else null,
            longitude = if (inner.has("longitude")) inner.optDouble("longitude") else null,
            replyToMessageId = inner.optString("replyToMessageId").takeIf { it.isNotBlank() },
            replyPreviewText = inner.optString("replyPreviewText").takeIf { it.isNotBlank() },
            deliveryStatus = "DELIVERED",
            ttlSeconds = ttl,
            expiresAt = if (ttl > 0L) now + ttl * 1000L else null,
            sequenceNumber = env.sequence,
            createdAt = env.sentAtMs
        )
        db.messageDao().upsertMessage(msg)
        val existingConv = db.conversationDao().getConversation(convId)
        db.conversationDao().upsertConversation(
            (existingConv ?: ConversationEntity(convId, rel.relationshipId, rel.spaceId)).copy(
                lastMessagePreview = msg.bodyText,
                lastMessageAt = now,
                unreadCount = (existingConv?.unreadCount ?: 0) + 1
            )
        )

        if (state.notificationsEnabled && rel.notificationsEnabled) {
            val space = db.spaceDao().getSpace(rel.spaceId)
            notificationManager.showMessageNotification(
                notificationId = env.messageId.hashCode(),
                senderName = rel.peerDisplayName,
                messagePreview = msg.bodyText,
                hidePreviews = state.hideMessagePreviews,
                spaceName = space?.name ?: "Enclave"
            )
        }

        // Ack server so encrypted relay copy is purged immediately
        val ackSig = crypto.signData(state.signingPrivateKeyWrappedB64, "ack|${env.messageId}|${state.installationId}".toByteArray())
        runCatching {
            apiService().acknowledgeEnvelope(AckEnvelopeRequest(env.messageId, state.installationId, ackSig))
        }
    }

    // --- Phase 4: WebRTC Voice, Video & Group Calls ---
    suspend fun initiateCall(relationshipId: String, isVideo: Boolean): String = withContext(Dispatchers.IO) {
        val rel = db.relationshipDao().getRelationship(relationshipId) ?: return@withContext ""
        val state = prefs.stateFlow.first()
        val callId = "call_${crypto.generateRandomId(8)}"
        val iceCfg = runCatching { apiService().getIceConfig(state.installationId).body() }.getOrNull()

        db.callDao().upsertCall(
            CallSessionEntity(
                callId = callId,
                relationshipId = relationshipId,
                spaceId = rel.spaceId,
                peerName = rel.peerDisplayName,
                callType = if (isVideo) "VIDEO" else "AUDIO",
                direction = "OUTGOING",
                status = "RINGING"
            )
        )

        callManager.startOutgoingCall(
            myDeviceId = state.installationId,
            callId = callId,
            relationshipId = relationshipId,
            peerDeviceId = rel.peerDeviceId,
            peerName = rel.peerDisplayName,
            isVideo = isVideo,
            stunUrl = iceCfg?.urls?.firstOrNull() ?: state.stunUrl,
            turnUrl = state.turnUrl.takeIf { it.isNotBlank() } ?: iceCfg?.urls?.getOrNull(1),
            turnUser = iceCfg?.username,
            turnCred = iceCfg?.credential
        )
        callId
    }

    suspend fun answerActiveIncomingCall() = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        val callId = callManager.callState.value.callId
        notificationManager.cancelCallNotification(callId)
        val iceCfg = runCatching { apiService().getIceConfig(state.installationId).body() }.getOrNull()
        callManager.answerIncomingCall(
            stunUrl = iceCfg?.urls?.firstOrNull() ?: state.stunUrl,
            turnUrl = state.turnUrl.takeIf { it.isNotBlank() } ?: iceCfg?.urls?.getOrNull(1),
            turnUser = iceCfg?.username,
            turnCred = iceCfg?.credential
        )
        db.callDao().getCall(callId)?.let {
            db.callDao().upsertCall(it.copy(status = "ACTIVE"))
        }
    }

    suspend fun hangupActiveCall(rejected: Boolean = false) = withContext(Dispatchers.IO) {
        val callId = callManager.callState.value.callId
        notificationManager.cancelCallNotification(callId)
        val duration = callManager.endCall(if (rejected) "rejected" else "hangup")
        db.callDao().getCall(callId)?.let {
            db.callDao().upsertCall(
                it.copy(
                    status = if (rejected) "REJECTED" else "ENDED",
                    endedAt = System.currentTimeMillis(),
                    durationSeconds = duration
                )
            )
        }
    }

    suspend fun startGroupCall(spaceId: String, title: String, relationshipIds: List<String>, isVideo: Boolean): String =
        withContext(Dispatchers.IO) {
            val state = prefs.stateFlow.first()
            val groupCallId = "grp_${crypto.generateRandomId(8)}"
            val participants = relationshipIds.mapNotNull { id ->
                db.relationshipDao().getRelationship(id)?.let { rel ->
                    GroupParticipantState(rel.peerDeviceId, rel.peerDisplayName)
                }
            }
            db.callDao().upsertGroupCall(
                GroupCallEntity(
                    groupCallId = groupCallId,
                    spaceId = spaceId,
                    title = title,
                    hostDeviceId = state.installationId,
                    participantIdsJson = JSONArray(participants.map { it.deviceId }).toString(),
                    isVideo = isVideo
                )
            )
            callManager.startGroupCall(
                myDeviceId = state.installationId,
                groupCallId = groupCallId,
                title = title,
                isVideo = isVideo,
                invitedMembers = participants,
                stunUrl = state.stunUrl
            )
            groupCallId
        }

    suspend fun shareFileOrVideoDuringCall(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val cur = callManager.callState.value
        if (cur.callId.isBlank()) return@withContext false
        val imported = mediaManager.importUriToPrivateStorage(uri, "bin") ?: return@withContext false
        val bytes = mediaManager.readMediaBytes(imported.localFilePath) ?: return@withContext false
        val itemId = "citem_${crypto.generateRandomId(8)}"
        val state = prefs.stateFlow.first()

        callManager.addSharedCallItem(
            CallSharedItem(
                itemId = itemId,
                senderName = "You",
                title = imported.fileName,
                mimeType = imported.mimeType,
                localPath = imported.localFilePath,
                sizeBytes = imported.sizeBytes,
                isOutgoing = true
            )
        )

        // Try direct P2P DataChannel for small files (< 180 KB) first
        if (bytes.size <= 180 * 1024 && !cur.isGroupCall) {
            val dcPayload = JSONObject().apply {
                put("kind", "call_file")
                put("itemId", itemId)
                put("callId", cur.callId)
                put("senderName", cur.peerName)
                put("fileName", imported.fileName)
                put("mimeType", imported.mimeType)
                put("sizeBytes", imported.sizeBytes)
                put("payloadB64", crypto.encodeB64(bytes))
            }
            if (callManager.sendJsonOverDataChannel(dcPayload)) {
                return@withContext true
            }
        }

        // Otherwise encrypt per-file with AES-256-GCM + SHA-256, upload ciphertext to relay, and notify peers over WebSocket
        val blob = crypto.encryptFileForRelay(bytes)
        val mediaId = "cmed_${crypto.generateRandomId(10)}"
        val upTs = System.currentTimeMillis()
        val upSig = crypto.signData(
            state.signingPrivateKeyWrappedB64,
            "media-upload|$mediaId|${state.installationId}|$upTs".toByteArray()
        )
        runCatching {
            apiService().uploadEncryptedMedia(
                UploadMediaRequest(
                    mediaId = mediaId,
                    uploaderDeviceId = state.installationId,
                    ciphertextB64 = crypto.encodeB64(blob.ciphertextBytes),
                    sizeBytes = bytes.size.toLong(),
                    timestampMs = upTs,
                    signatureB64 = upSig
                )
            )
        }

        val notifyJson = JSONObject().apply {
            put("type", "call_shared_item")
            put("callId", cur.callId)
            put("itemId", itemId)
            put("fromDeviceId", state.installationId)
            if (cur.isGroupCall) {
                put("toDeviceIds", JSONArray(cur.participants.map { it.deviceId }.filter { it != state.installationId }))
            } else {
                put("toDeviceId", cur.peerDeviceId)
            }
            put("mediaId", mediaId)
            put("fileName", imported.fileName)
            put("mimeType", imported.mimeType)
            put("sizeBytes", imported.sizeBytes)
            put("fileKeyB64", blob.fileKeyB64)
            put("fileIvB64", blob.ivB64)
            put("fileSha256", blob.sha256Hex)
        }
        socketClient.sendJson(notifyJson)
        true
    }

    private suspend fun handleIncomingCallDataChannelPayload(obj: JSONObject) {
        if (obj.optString("kind") != "call_file") return
        val itemId = obj.optString("itemId").ifBlank { "citem_${crypto.generateRandomId(6)}" }
        val fileName = obj.optString("fileName", "shared_file.bin")
        val mimeType = obj.optString("mimeType", "application/octet-stream")
        val bytes = runCatching { crypto.decodeB64(obj.getString("payloadB64")) }.getOrNull() ?: return
        val stored = mediaManager.saveMediaBytes(bytes, fileName, mimeType) ?: return
        callManager.addSharedCallItem(
            CallSharedItem(
                itemId = itemId,
                senderName = callManager.callState.value.peerName.ifBlank { "Peer" },
                title = fileName,
                mimeType = mimeType,
                localPath = stored.localFilePath,
                sizeBytes = bytes.size.toLong(),
                isOutgoing = false
            )
        )
    }

    // --- Phase 5: Spaces, Secret Spaces & Profiles ---
    suspend fun switchActiveSpace(spaceId: String) {
        prefs.setActiveSpaceId(spaceId)
    }

    suspend fun createSpace(
        name: String,
        accentHex: String,
        isHidden: Boolean,
        secretPassphrase: String?,
        profileDisplayName: String,
    ): SpaceEntity = withContext(Dispatchers.IO) {
        val spaceId = "space_${crypto.generateRandomId(6)}"
        val (salt, hash) = if (isHidden && !secretPassphrase.isNullOrBlank()) {
            crypto.deriveSecretHash("${name.trim().lowercase()}|$secretPassphrase")
        } else "" to ""

        val space = SpaceEntity(
            spaceId = spaceId,
            name = name.trim(),
            iconName = if (isHidden) "lock" else "layers",
            accentHex = accentHex,
            isHidden = isHidden,
            secretSaltB64 = salt,
            secretHashB64 = hash
        )
        db.spaceDao().upsertSpace(space)
        db.profileDao().upsertProfile(
            ProfileEntity(
                profileId = "prof_$spaceId",
                spaceId = spaceId,
                displayName = profileDisplayName.ifBlank { name },
                enclaveCode = "ENC-${crypto.generateShortCode(8)}"
            )
        )
        if (isHidden) {
            _unlockedSecretSpaceIds.value = _unlockedSecretSpaceIds.value + spaceId
            _activeSecretSpaces.value = (_activeSecretSpaces.value + space).distinctBy { it.spaceId }
        }
        logAudit(
            if (isHidden) "SECRET_SPACE_CREATED" else "SPACE_CREATED",
            "Created ${if (isHidden) "hidden Secret " else ""}Space '${space.name}'"
        )
        space
    }

    suspend fun unlockSecretSpace(secretName: String, secretPassphrase: String): SpaceEntity? =
        withContext(Dispatchers.IO) {
            val hidden = db.spaceDao().getHiddenSpaces()
            val candidateKey = "${secretName.trim().lowercase()}|$secretPassphrase"
            val matched = hidden.firstOrNull { space ->
                space.secretSaltB64.isNotBlank() &&
                    crypto.verifySecretHash(candidateKey, space.secretSaltB64, space.secretHashB64)
            } ?: return@withContext null

            _unlockedSecretSpaceIds.value = _unlockedSecretSpaceIds.value + matched.spaceId
            _activeSecretSpaces.value = (_activeSecretSpaces.value + matched).distinctBy { it.spaceId }
            prefs.setActiveSpaceId(matched.spaceId)
            matched
        }

    suspend fun lockAllSecretSpaces() {
        _unlockedSecretSpaceIds.value = emptySet()
        _activeSecretSpaces.value = emptyList()
        prefs.setActiveSpaceId("space_personal")
    }

    suspend fun triggerDuressWipe() = withContext(Dispatchers.IO) {
        db.spaceDao().deleteHiddenSpaces()
        _unlockedSecretSpaceIds.value = emptySet()
        _activeSecretSpaces.value = emptyList()
        prefs.setActiveSpaceId("space_personal")
        logAudit("DURESS_UNLOCK", "Duress PIN verified; hidden spaces wiped and switched to Personal Space")
    }

    suspend fun updateSpaceProfile(spaceId: String, displayName: String, bio: String, avatarUri: Uri?) =
        withContext(Dispatchers.IO) {
            val current = db.profileDao().getProfileForSpace(spaceId)
                ?: ProfileEntity("prof_$spaceId", spaceId, displayName, bio, null, "ENC-${crypto.generateShortCode(8)}")
            val savedPath = avatarUri?.let { mediaManager.importUriToPrivateStorage(it, "jpg")?.localFilePath }
                ?: current.avatarLocalPath
            db.profileDao().upsertProfile(
                current.copy(
                    displayName = displayName.ifBlank { current.displayName },
                    bio = bio,
                    avatarLocalPath = savedPath,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    // --- Phase 6: Call Transfer, Click-to-Call, Encrypted Backup & Diagnostics ---
    suspend fun generateTemporaryToken(purpose: String, callId: String? = null): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val state = prefs.stateFlow.first()
            val sig = crypto.signData(
                state.signingPrivateKeyWrappedB64,
                "token|$purpose|${state.installationId}|${callId ?: ""}".toByteArray()
            )
            val remote = runCatching {
                apiService().createTemporaryToken(
                    TemporaryTokenRequest(
                        purpose = purpose,
                        deviceId = state.installationId,
                        callId = callId,
                        ttlSeconds = 300,
                        signatureB64 = sig
                    )
                ).body()
            }.getOrNull()

            if (remote != null) {
                if (purpose == "CALL_TRANSFER") callManager.setTransferToken(remote.token, remote.url)
                return@withContext remote.token to remote.url
            }

            // Offline fallback token formatted identically to server token
            val fallbackToken = "tok_${crypto.generateShortCode(12)}"
            val url = "${state.backendUrl}/call/$fallbackToken"
            if (purpose == "CALL_TRANSFER") callManager.setTransferToken(fallbackToken, url)
            fallbackToken to url
        }

    suspend fun exportEncryptedBackup(passphrase: String): String = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        val spaces = db.spaceDao().getAllSpaces()
        val profiles = db.profileDao().getAllProfiles()
        val rels = db.relationshipDao().getAllRelationships()

        val payload = JSONObject().apply {
            put("installationId", state.installationId)
            put("signingPublicKeyB64", state.signingPublicKeyB64)
            put("signingPrivateKeyRawB64", crypto.encodeB64(crypto.unwrapSecretB64(state.signingPrivateKeyWrappedB64)))
            put("ecdhPublicKeyB64", state.ecdhPublicKeyB64)
            put("ecdhPrivateKeyRawB64", crypto.encodeB64(crypto.unwrapSecretB64(state.ecdhPrivateKeyWrappedB64)))
            put("spaces", JSONArray().apply {
                spaces.forEach { s ->
                    put(JSONObject().apply {
                        put("spaceId", s.spaceId)
                        put("name", s.name)
                        put("accentHex", s.accentHex)
                        put("isHidden", s.isHidden)
                    })
                }
            })
            put("profiles", JSONArray().apply {
                profiles.forEach { p ->
                    put(JSONObject().apply {
                        put("profileId", p.profileId)
                        put("spaceId", p.spaceId)
                        put("displayName", p.displayName)
                        put("bio", p.bio)
                        put("enclaveCode", p.enclaveCode)
                    })
                }
            })
            put("relationshipsCount", rels.size)
        }.toString()

        logAudit("BACKUP_EXPORTED", "Exported encrypted identity backup bundle")
        crypto.encryptRecoveryBackup(payload, passphrase)
    }

    suspend fun restoreEncryptedBackup(bundleJson: String, passphrase: String): Boolean =
        withContext(Dispatchers.IO) {
            val plainJson = runCatching { crypto.decryptRecoveryBackup(bundleJson, passphrase) }.getOrNull()
                ?: return@withContext false
            val obj = JSONObject(plainJson)
            val installId = obj.getString("installationId")
            val sPub = obj.getString("signingPublicKeyB64")
            val sPrivWrapped = crypto.wrapSecretB64(crypto.decodeB64(obj.getString("signingPrivateKeyRawB64")))
            val ePub = obj.getString("ecdhPublicKeyB64")
            val ePrivWrapped = crypto.wrapSecretB64(crypto.decodeB64(obj.getString("ecdhPrivateKeyRawB64")))

            prefs.saveDeviceKeys(installId, sPub, sPrivWrapped, ePub, ePrivWrapped)

            val spaces = obj.optJSONArray("spaces")
            if (spaces != null) {
                for (i in 0 until spaces.length()) {
                    val s = spaces.getJSONObject(i)
                    db.spaceDao().upsertSpace(
                        SpaceEntity(
                            spaceId = s.getString("spaceId"),
                            name = s.getString("name"),
                            iconName = "shield",
                            accentHex = s.optString("accentHex", "#00D2B8"),
                            isHidden = s.optBoolean("isHidden", false),
                            isDefault = s.getString("spaceId") == "space_personal"
                        )
                    )
                }
            }
            val profiles = obj.optJSONArray("profiles")
            if (profiles != null) {
                for (i in 0 until profiles.length()) {
                    val p = profiles.getJSONObject(i)
                    db.profileDao().upsertProfile(
                        ProfileEntity(
                            profileId = p.getString("profileId"),
                            spaceId = p.getString("spaceId"),
                            displayName = p.getString("displayName"),
                            bio = p.optString("bio", ""),
                            enclaveCode = p.getString("enclaveCode")
                        )
                    )
                }
            }
            prefs.setOnboarded(true)
            registerDeviceWithBackend(installId, sPub, sPrivWrapped, ePub)
            connectRealtimeSocket()
            logAudit("BACKUP_RESTORED", "Restored encrypted device identity $installId")
            true
        }

    suspend fun runDiagnostics(): List<DiagnosticItem> = withContext(Dispatchers.IO) {
        val state = prefs.stateFlow.first()
        val results = mutableListOf<DiagnosticItem>()

        // 1. Internet connectivity
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        results += DiagnosticItem(
            id = "internet",
            title = "Internet Connectivity",
            status = if (hasNet) "PASS" else "FAIL",
            detail = if (hasNet) "Active network transport available" else "No active internet transport detected",
            recommendation = if (hasNet) "Ready for real-time signaling and encrypted relay." else "Check Wi-Fi or mobile data."
        )

        // 2. Backend HTTP health
        val health = runCatching { apiService().healthCheck().body() }.getOrNull()
        results += DiagnosticItem(
            id = "backend",
            title = "Enclave Relay & Signaling API",
            status = if (health != null) "PASS" else "WARN",
            detail = if (health != null) "Connected (${health.postgres} / ${health.redis})" else "Unreachable at ${state.backendUrl}",
            recommendation = if (health != null) "Zero-knowledge relay operational." else "Start the backend server or update Server URL in Settings."
        )

        // 3. WebSocket signaling
        val wsState = socketClient.connectionState.value
        results += DiagnosticItem(
            id = "ws",
            title = "Real-Time WebSocket Signaling",
            status = if (wsState == SocketConnectionState.CONNECTED) "PASS" else "WARN",
            detail = "Socket state: ${wsState.name}",
            recommendation = if (wsState == SocketConnectionState.CONNECTED) "Instant delivery & calls active." else "Messages queue safely in Outbox until reconnected."
        )

        // 4. STUN / TURN reachability (Cross-country NAT traversal)
        val stunHost = state.stunUrl.removePrefix("stun:").substringBefore(":")
        val stunPort = state.stunUrl.substringAfterLast(":", "19302").toIntOrNull() ?: 19302
        val stunReachable = runCatching {
            val addr = InetAddress.getByName(stunHost)
            DatagramSocket().use { sock ->
                sock.soTimeout = 1500
                sock.connect(addr, stunPort)
                true
            }
        }.getOrDefault(false)
        val hasTurn = state.turnUrl.isNotBlank() || (health?.turnConfigured == true)
        val isLocalOnlyBackend = state.backendUrl.contains("10.0.2.2") || state.backendUrl.contains("localhost") || state.backendUrl.contains("127.0.0.1")
        results += DiagnosticItem(
            id = "stun",
            title = "STUN / TURN NAT Traversal",
            status = when {
                stunReachable && hasTurn -> "PASS"
                stunReachable || hasTurn -> "WARN"
                else -> "FAIL"
            },
            detail = "STUN: ${state.stunUrl} • " + when {
                state.turnUrl.isNotBlank() -> "Client TURN: ${state.turnUrl}"
                health?.turnConfigured == true -> "Server TURN: ${health.turnServer}"
                else -> "No TURN relay configured"
            },
            recommendation = when {
                hasTurn && !isLocalOnlyBackend -> "Cross-country & 4G/5G NAT relay ready."
                !hasTurn -> "Configure Coturn TURN in backend/.env so calls work reliably across different countries & mobile networks."
                else -> "Backend URL points to emulator localhost (10.0.2.2); set a public HTTPS URL for friends on other networks."
            }
        )

        // 5. WebRTC stack
        val rtcReady = callManager.probeIceLoopback()
        results += DiagnosticItem(
            id = "webrtc",
            title = "WebRTC Engine (DTLS-SRTP)",
            status = if (rtcReady) "PASS" else "FAIL",
            detail = if (rtcReady) "PeerConnectionFactory initialized cleanly" else "Native WebRTC library unavailable",
            recommendation = if (rtcReady) "Hardware media pipelines ready." else "Verify ABI native libraries."
        )

        // 6. Permissions
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notifGranted = notificationManager.hasNotificationPermission()
        results += DiagnosticItem(
            id = "perms",
            title = "Media & Notification Permissions",
            status = if (micGranted && camGranted && notifGranted) "PASS" else "WARN",
            detail = "Mic: ${if (micGranted) "OK" else "Denied"} • Camera: ${if (camGranted) "OK" else "Denied"} • Alerts: ${if (notifGranted) "OK" else "Denied"}",
            recommendation = "Grant Microphone, Camera, and Notifications for incoming call rings and QR scanning."
        )

        // 7. Background push & wake-up readiness
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringDoze = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
        val fcmReady = PushRegistrationHelper.isFirebaseConfigured(context) && state.fcmToken.isNotBlank() && (health?.fcmConfigured == true)
        val keepAliveOn = state.alwaysOnBackgroundRelay
        results += DiagnosticItem(
            id = "battery",
            title = "Background Call Wake-Up & Push",
            status = if (fcmReady || keepAliveOn) "PASS" else "WARN",
            detail = buildString {
                append(if (fcmReady) "FCM Push: Registered" else "FCM Push: Not configured")
                append(" • Always-On Relay: ${if (keepAliveOn) "ON" else "OFF"}")
                append(" • Doze Exempt: ${if (ignoringDoze) "Yes" else "No"}")
            },
            recommendation = when {
                fcmReady -> "Closed-app call wake-ups active via zero-knowledge FCM."
                keepAliveOn -> "Background relay active for incoming calls while app is minimized."
                else -> "Enable 'Keep Background Relay Active' in Notification Settings (or add google-services.json + FCM) so closed-app calls ring."
            }
        )

        results
    }

    private fun observeSocketEvents() {
        scope.launch(Dispatchers.IO) {
            socketClient.events.collect { event ->
                handleIncomingSignalEvent(event)
            }
        }
    }

    private suspend fun handleIncomingSignalEvent(event: IncomingSignalEvent) {
                when (event) {
                    is IncomingSignalEvent.EncryptedMessage -> {
                        val j = event.json
                        processIncomingEnvelope(
                            PendingRelayEnvelope(
                                messageId = j.getString("messageId"),
                                relationshipId = j.getString("relationshipId"),
                                senderDeviceId = j.getString("senderDeviceId"),
                                ivB64 = j.getString("ivB64"),
                                ciphertextB64 = j.getString("ciphertextB64"),
                                sequence = j.getLong("sequence"),
                                ttlSeconds = j.optLong("ttlSeconds", 0L),
                                signatureB64 = j.getString("signatureB64"),
                                sentAtMs = j.getLong("sentAtMs")
                            )
                        )
                    }
                    is IncomingSignalEvent.DeliveryReceipt -> {
                        db.messageDao().updateDeliveryStatus(event.messageId, event.status)
                    }
                    is IncomingSignalEvent.TypingIndicator -> {
                        _typingStates.value = _typingStates.value + (event.relationshipId to event.isTyping)
                    }
                    is IncomingSignalEvent.DeletionSync -> {
                        val relId = event.json.optString("relationshipId")
                        val scopeType = event.json.optString("scope")
                        val targetMsgId = event.json.optString("targetMessageId")
                        val eventId = event.json.optString("eventId")
                        val rel = db.relationshipDao().getRelationship(relId)
                        if (rel != null) {
                            when (scopeType) {
                                "MESSAGE" -> db.messageDao().deleteMessage(targetMsgId)
                                "CONVERSATION" -> db.messageDao().deleteMessagesForConversation("conv_$relId")
                            }
                            socketClient.sendJson(JSONObject().apply {
                                put("type", "deletion_ack")
                                put("eventId", eventId)
                                put("relationshipId", relId)
                                put("toDeviceId", rel.peerDeviceId)
                            })
                        }
                    }
                    is IncomingSignalEvent.DeletionAck -> {
                        db.deletionEventDao().markAcknowledged(event.eventId, System.currentTimeMillis())
                        db.messageDao().updateRemoteDeleteStatus(event.eventId, "DELETED_BOTH")
                    }
                    is IncomingSignalEvent.RelationshipAccepted -> {
                        val j = event.json
                        val state = prefs.stateFlow.first()
                        val code = j.optString("code")
                        val relId = j.optString("relationshipId")
                        val peerId = j.optString("responderDeviceId")
                        val peerSPub = j.optString("responderSigningPublicKeyB64")
                        val peerEPub = j.optString("responderEcdhPublicKeyB64")
                        val peerName = j.optString("responderDisplayName", "Contact")
                        val inv = db.invitationDao().getByCode(code)
                        val spaceId = inv?.spaceId ?: "space_personal"
                        val rootKey = crypto.deriveRelationshipRootKey(
                            state.ecdhPrivateKeyWrappedB64,
                            peerEPub,
                            code
                        )
                        val rel = RelationshipEntity(
                            relationshipId = relId,
                            spaceId = spaceId,
                            peerDeviceId = peerId,
                            peerSigningPublicKeyB64 = peerSPub,
                            peerEcdhPublicKeyB64 = peerEPub,
                            peerDisplayName = peerName,
                            myDisplayName = inv?.offeredDisplayName ?: "Anonymous",
                            wrappedRootKeyB64 = crypto.wrapSecretB64(rootKey),
                            safetyFingerprint = crypto.computeSafetyFingerprint(state.signingPublicKeyB64, peerSPub),
                            status = "ACTIVE"
                        )
                        db.relationshipDao().upsertRelationship(rel)
                        db.conversationDao().upsertConversation(
                            ConversationEntity("conv_$relId", relId, spaceId, "Encrypted channel established")
                        )
                    }
                    is IncomingSignalEvent.RelationshipRevoked -> {
                        db.relationshipDao().getRelationship(event.relationshipId)?.let {
                            db.relationshipDao().updateRelationship(it.copy(status = "REVOKED"))
                        }
                    }
                    is IncomingSignalEvent.CallOffer -> {
                        val j = event.json
                        val callId = j.getString("callId")
                        if (j.optBoolean("renegotiation", false) && callManager.callState.value.callId == callId) {
                            callManager.handleIncomingRenegotiationOffer(j.optString("sdp"))
                            return
                        }
                        val relId = j.optString("relationshipId")
                        val rel = db.relationshipDao().getRelationship(relId)
                        if (rel == null || !rel.allowIncomingCalls || rel.status != "ACTIVE") return
                        val state = prefs.stateFlow.first()
                        val isVideo = j.optBoolean("isVideo", false)
                        db.callDao().upsertCall(
                            CallSessionEntity(
                                callId = callId,
                                relationshipId = relId,
                                spaceId = rel.spaceId,
                                peerName = rel.peerDisplayName,
                                callType = if (isVideo) "VIDEO" else "AUDIO",
                                direction = "INCOMING",
                                status = "RINGING"
                            )
                        )
                        callManager.handleIncomingOffer(
                            myDeviceId = state.installationId,
                            callId = callId,
                            relationshipId = relId,
                            fromDeviceId = j.getString("fromDeviceId"),
                            peerName = rel.peerDisplayName,
                            isVideo = isVideo,
                            remoteSdp = j.optString("sdp")
                        )
                        if (state.callNotificationsEnabled && rel.notificationsEnabled) {
                            notificationManager.showIncomingCallNotification(callId, rel.peerDisplayName, isVideo, state.hideMessagePreviews)
                        }
                    }
                    is IncomingSignalEvent.CallAnswer -> {
                        callManager.handleRemoteAnswer(event.json.optString("sdp"))
                    }
                    is IncomingSignalEvent.IceCandidate -> {
                        val j = event.json
                        callManager.handleRemoteIceCandidate(
                            j.optString("sdpMid"),
                            j.optInt("sdpMLineIndex"),
                            j.optString("candidate")
                        )
                    }
                    is IncomingSignalEvent.CallHangup -> {
                        notificationManager.cancelCallNotification(event.callId)
                        callManager.endCall(event.reason)
                    }
                    is IncomingSignalEvent.GuestClickToCall -> {
                        callManager.handleIncomingGuestClickToCall(event.callId, event.guestName, event.token)
                    }
                    is IncomingSignalEvent.MediaSync -> {
                        callManager.handleIncomingMediaSync(event.mediaTitle, event.positionMs, event.isPlaying)
                    }
                    is IncomingSignalEvent.EditMessage -> {
                        db.messageDao().editMessage(event.messageId, event.newBody, event.editedAtMs)
                    }
                    is IncomingSignalEvent.ReactionUpdate -> {
                        val msg = db.messageDao().getMessage(event.messageId)
                        if (msg != null) {
                            val obj = runCatching { JSONObject(msg.reactionsJson) }.getOrDefault(JSONObject())
                            if (event.active) obj.put(event.emoji, 1) else obj.remove(event.emoji)
                            db.messageDao().updateReactions(event.messageId, obj.toString())
                        }
                    }
                    is IncomingSignalEvent.ScreenShareState -> {
                        callManager.handleIncomingScreenShareState(event.isSharing)
                    }
                    is IncomingSignalEvent.CallSharedItem -> {
                        val j = event.json
                        val state = prefs.stateFlow.first()
                        val mediaId = j.optString("mediaId")
                        val fileKeyB64 = j.optString("fileKeyB64")
                        val fileIvB64 = j.optString("fileIvB64")
                        val fileSha256 = j.optString("fileSha256")
                        val fileName = j.optString("fileName", "shared_file.bin")
                        val mimeType = j.optString("mimeType", "application/octet-stream")
                        val itemId = j.optString("itemId").ifBlank { mediaId }

                        var savedPath: String? = null
                        if (mediaId.isNotBlank() && fileKeyB64.isNotBlank() && fileIvB64.isNotBlank()) {
                            val dlTs = System.currentTimeMillis()
                            val dlSig = crypto.signData(
                                state.signingPrivateKeyWrappedB64,
                                "media-download|$mediaId|${state.installationId}|$dlTs".toByteArray()
                            )
                            val resp = runCatching {
                                apiService().downloadEncryptedMedia(mediaId, state.installationId, dlTs, dlSig).body()
                            }.getOrNull()
                            if (resp != null) {
                                val plainBytes = runCatching {
                                    crypto.decryptFileFromRelay(
                                        ciphertextBytes = crypto.decodeB64(resp.ciphertextB64),
                                        fileKeyB64 = fileKeyB64,
                                        ivB64 = fileIvB64,
                                        expectedSha256Hex = fileSha256
                                    )
                                }.getOrNull()
                                if (plainBytes != null) {
                                    savedPath = mediaManager.saveMediaBytes(plainBytes, fileName, mimeType)?.localFilePath
                                }
                            }
                        }

                        callManager.addSharedCallItem(
                            CallSharedItem(
                                itemId = itemId,
                                senderName = callManager.callState.value.peerName.ifBlank { "Peer" },
                                title = fileName,
                                mimeType = mimeType,
                                localPath = savedPath,
                                sizeBytes = j.optLong("sizeBytes", 0L),
                                isOutgoing = false
                            )
                        )
                    }
                    is IncomingSignalEvent.GroupCallEvent -> {
                        // Track dynamic group participant joins/leaves
                        val devId = event.json.optString("deviceId")
                        val name = event.json.optString("displayName", "Member")
                        if (event.type == "group_call_joined" && devId.isNotBlank()) {
                            callManager.addGroupParticipant(GroupParticipantState(devId, name))
                        } else if (event.type == "group_call_left" && devId.isNotBlank()) {
                            callManager.removeGroupParticipant(devId)
                        }
                    }
                }
    }

    private fun startEphemeralPurgeTicker() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000L)
                purgeExpiredMessagesNow()
            }
        }
    }

    private suspend fun logAudit(eventType: String, summary: String) {
        db.securityAuditDao().logEvent(SecurityAuditEntity(eventType = eventType, summary = summary))
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }
}
