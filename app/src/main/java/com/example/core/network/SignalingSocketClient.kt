package com.example.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class SocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

sealed class IncomingSignalEvent {
    data class EncryptedMessage(val json: JSONObject) : IncomingSignalEvent()
    data class DeliveryReceipt(val messageId: String, val status: String, val relationshipId: String) : IncomingSignalEvent()
    data class TypingIndicator(val relationshipId: String, val isTyping: Boolean) : IncomingSignalEvent()
    data class DeletionSync(val json: JSONObject) : IncomingSignalEvent()
    data class DeletionAck(val eventId: String, val relationshipId: String) : IncomingSignalEvent()
    data class RelationshipAccepted(val json: JSONObject) : IncomingSignalEvent()
    data class RelationshipRevoked(val relationshipId: String) : IncomingSignalEvent()
    data class CallOffer(val json: JSONObject) : IncomingSignalEvent()
    data class CallAnswer(val json: JSONObject) : IncomingSignalEvent()
    data class IceCandidate(val json: JSONObject) : IncomingSignalEvent()
    data class CallHangup(val callId: String, val reason: String) : IncomingSignalEvent()
    data class GroupCallEvent(val type: String, val json: JSONObject) : IncomingSignalEvent()
    data class GuestClickToCall(val callId: String, val guestName: String, val token: String) : IncomingSignalEvent()
    data class MediaSync(val callId: String, val mediaTitle: String, val positionMs: Long, val isPlaying: Boolean) : IncomingSignalEvent()
    data class EditMessage(val messageId: String, val relationshipId: String, val newBody: String, val editedAtMs: Long) : IncomingSignalEvent()
    data class ReactionUpdate(val messageId: String, val relationshipId: String, val emoji: String, val active: Boolean) : IncomingSignalEvent()
    data class ScreenShareState(val callId: String, val fromDeviceId: String, val isSharing: Boolean) : IncomingSignalEvent()
    data class CallSharedItem(val json: JSONObject) : IncomingSignalEvent()
}

class SignalingSocketClient(
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<IncomingSignalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<IncomingSignalEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentUrl: String = ""
    private var currentDeviceId: String = ""
    private var currentSigProvider: ((Long) -> String)? = null
    private var reconnectAttempt = 0
    private var manuallyClosed = false

    fun connect(
        backendBaseUrl: String,
        deviceId: String,
        signTimestampProvider: (Long) -> String,
    ) {
        if (deviceId.isBlank() || backendBaseUrl.isBlank()) return
        manuallyClosed = false
        currentUrl = backendBaseUrl
        currentDeviceId = deviceId
        currentSigProvider = signTimestampProvider
        openSocket()
    }

    private fun openSocket() {
        webSocket?.close(1000, "Reconnect")
        val wsBase = currentUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .removeSuffix("/")
        val ts = System.currentTimeMillis()
        val sig = currentSigProvider?.invoke(ts) ?: ""
        val req = Request.Builder()
            .url("$wsBase/ws?deviceId=$currentDeviceId&ts=$ts")
            .addHeader("X-Enclave-Signature", sig)
            .build()

        _connectionState.value = if (reconnectAttempt == 0) SocketConnectionState.CONNECTING else SocketConnectionState.RECONNECTING

        webSocket = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _connectionState.value = SocketConnectionState.CONNECTED
                sendJson(JSONObject().apply {
                    put("type", "auth")
                    put("deviceId", currentDeviceId)
                    put("timestampMs", ts)
                    put("signatureB64", sig)
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = SocketConnectionState.DISCONNECTED
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = SocketConnectionState.DISCONNECTED
                if (!manuallyClosed) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manuallyClosed || currentDeviceId.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
            val delayMs = (1500L * (1 shl (reconnectAttempt - 1))).coerceAtMost(30_000L)
            delay(delayMs)
            if (!manuallyClosed) openSocket()
        }
    }

    fun sendJson(payload: JSONObject): Boolean {
        val ws = webSocket ?: return false
        return ws.send(payload.toString())
    }

    fun sendPing(): Boolean {
        return sendJson(JSONObject().apply {
            put("type", "ping")
            put("ts", System.currentTimeMillis())
        })
    }

    fun disconnect() {
        manuallyClosed = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = SocketConnectionState.DISCONNECTED
    }

    private fun handleIncomingText(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        parseSignalJson(obj)?.let { _events.tryEmit(it) }
    }

    fun parseSignalJson(obj: JSONObject): IncomingSignalEvent? {
        val type = obj.optString("type")
        return when (type) {
            "pong" -> null
            "encrypted_message" -> IncomingSignalEvent.EncryptedMessage(obj)
            "receipt" -> IncomingSignalEvent.DeliveryReceipt(
                messageId = obj.optString("messageId"),
                status = obj.optString("status"),
                relationshipId = obj.optString("relationshipId")
            )
            "typing" -> IncomingSignalEvent.TypingIndicator(
                relationshipId = obj.optString("relationshipId"),
                isTyping = obj.optBoolean("isTyping", false)
            )
            "deletion_event" -> IncomingSignalEvent.DeletionSync(obj)
            "deletion_ack" -> IncomingSignalEvent.DeletionAck(
                eventId = obj.optString("eventId"),
                relationshipId = obj.optString("relationshipId")
            )
            "relationship_accepted" -> IncomingSignalEvent.RelationshipAccepted(obj)
            "relationship_revoked" -> IncomingSignalEvent.RelationshipRevoked(
                relationshipId = obj.optString("relationshipId")
            )
            "call_offer" -> IncomingSignalEvent.CallOffer(obj)
            "call_answer" -> IncomingSignalEvent.CallAnswer(obj)
            "ice_candidate" -> IncomingSignalEvent.IceCandidate(obj)
            "call_hangup" -> IncomingSignalEvent.CallHangup(
                callId = obj.optString("callId"),
                reason = obj.optString("reason", "ended")
            )
            "group_call_invite", "group_call_joined", "group_call_left" ->
                IncomingSignalEvent.GroupCallEvent(type, obj)
            "guest_click_to_call" -> IncomingSignalEvent.GuestClickToCall(
                callId = obj.optString("callId"),
                guestName = obj.optString("guestName", "Browser Guest"),
                token = obj.optString("token")
            )
            "media_sync" -> IncomingSignalEvent.MediaSync(
                callId = obj.optString("callId"),
                mediaTitle = obj.optString("mediaTitle"),
                positionMs = obj.optLong("positionMs"),
                isPlaying = obj.optBoolean("isPlaying")
            )
            "edit_message" -> IncomingSignalEvent.EditMessage(
                messageId = obj.optString("messageId"),
                relationshipId = obj.optString("relationshipId"),
                newBody = obj.optString("newBody"),
                editedAtMs = obj.optLong("editedAtMs", System.currentTimeMillis())
            )
            "reaction_update" -> IncomingSignalEvent.ReactionUpdate(
                messageId = obj.optString("messageId"),
                relationshipId = obj.optString("relationshipId"),
                emoji = obj.optString("emoji"),
                active = obj.optBoolean("active", true)
            )
            "screen_share_state" -> IncomingSignalEvent.ScreenShareState(
                callId = obj.optString("callId"),
                fromDeviceId = obj.optString("fromDeviceId"),
                isSharing = obj.optBoolean("isSharing", false)
            )
            "call_shared_item" -> IncomingSignalEvent.CallSharedItem(obj)
            else -> null
        }
    }
}
