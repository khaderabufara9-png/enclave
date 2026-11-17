package com.example.ui.screens.call

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.core.crypto.QrCodeEngine
import com.example.core.webrtc.ActiveCallState
import com.example.core.webrtc.AudioRoute
import com.example.core.webrtc.CallSharedItem
import com.example.core.webrtc.WebRtcCallManager
import com.example.ui.components.EnclaveAvatar
import com.example.ui.components.SafetyShieldBadge
import com.example.ui.components.formatDuration
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File

@Composable
fun IncomingCallScreen(
    callState: ActiveCallState,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(32.dp)
            .testTag("incoming_call_screen")
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SafetyShieldBadge("DTLS-SRTP Verified")
            Spacer(Modifier.height(24.dp))
            EnclaveAvatar(name = callState.peerName.ifBlank { "Caller" }, avatarPath = null, size = 104.dp)
            Spacer(Modifier.height(20.dp))
            Text(callState.peerName, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (callState.pendingGuestToken != null) "Incoming Browser Click-to-Call (Requires Approval)"
                else if (callState.isVideo) "Incoming Encrypted Video Call..."
                else "Incoming Encrypted Voice Call...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onReject,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.size(72.dp).testTag("incoming_reject_button")
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "Reject Call", modifier = Modifier.size(32.dp))
            }

            FilledIconButton(
                onClick = onAnswer,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF10B981)),
                modifier = Modifier.size(72.dp).testTag("incoming_answer_button")
            ) {
                Icon(Icons.Filled.Call, contentDescription = "Answer Call", modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun OutgoingCallScreen(
    callState: ActiveCallState,
    onCancel: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(32.dp)
            .testTag("outgoing_call_screen")
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SafetyShieldBadge("Key Exchange Active")
            Spacer(Modifier.height(28.dp))
            EnclaveAvatar(name = callState.peerName.ifBlank { "Contact" }, avatarPath = null, size = 104.dp)
            Spacer(Modifier.height(20.dp))
            Text(callState.peerName, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (callState.isVideo) "Ringing encrypted video..." else "Ringing encrypted voice...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(callState.connectionQuality, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        FilledIconButton(
            onClick = onCancel,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.size(72.dp).testTag("outgoing_cancel_button")
        ) {
            Icon(Icons.Filled.CallEnd, contentDescription = "End Call", modifier = Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCallScreen(
    callState: ActiveCallState,
    callManager: WebRtcCallManager,
    onRequestTransferToken: (callId: String, onReady: (String, String) -> Unit) -> Unit,
    onShareFileOrVideo: (Uri) -> Unit,
    onEndCall: () -> Unit,
) {
    val context = LocalContext.current
    var transferModal by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showShareSheet by remember { mutableStateOf(false) }
    var mediaShareDialog by remember { mutableStateOf(false) }
    var previewSharedItem by remember { mutableStateOf<CallSharedItem?>(null) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            callManager.startScreenShare(res.data!!)
        }
    }
    val photoVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onShareFileOrVideo(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onShareFileOrVideo(uri)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(20.dp)
            .testTag("audio_call_screen")
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SafetyShieldBadge(callState.connectionQuality)
                if (callState.isScreenSharing) {
                    SafetyShieldBadge("Screen Sharing On")
                } else if (callState.isPeerScreenSharing) {
                    SafetyShieldBadge("Peer Sharing Screen")
                }
            }
            Spacer(Modifier.height(16.dp))

            if (callState.remoteVideoTrack != null && callManager.eglBase != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                ) {
                    WebRtcVideoView(
                        track = callState.remoteVideoTrack,
                        eglBase = callManager.eglBase!!,
                        mirror = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(12.dp))
                EnclaveAvatar(name = callState.peerName.ifBlank { "Contact" }, avatarPath = null, size = 104.dp)
                Spacer(Modifier.height(16.dp))
            }

            Text(callState.peerName, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDuration(callState.durationSeconds),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )

            callState.mediaSync?.let { sync ->
                Spacer(Modifier.height(10.dp))
                MediaSyncControlBanner(
                    title = sync.mediaTitle,
                    isPlaying = sync.isPlaying,
                    onTogglePlay = {
                        callManager.syncCallMedia(sync.mediaTitle, sync.positionMs, !sync.isPlaying)
                    }
                )
            }

            if (callState.sharedItems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                InCallSharedItemsStrip(
                    items = callState.sharedItems,
                    onOpenItem = { previewSharedItem = it }
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        if (callState.isScreenSharing) {
                            callManager.stopScreenShare()
                        } else {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier.testTag("audio_screen_share_btn")
                ) {
                    Icon(if (callState.isScreenSharing) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (callState.isScreenSharing) "Stop Screen" else "Share Screen")
                }

                OutlinedButton(
                    onClick = { showShareSheet = true },
                    modifier = Modifier.testTag("audio_share_media_btn")
                ) {
                    Icon(Icons.Filled.AttachFile, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Files & Video")
                }

                OutlinedButton(
                    onClick = {
                        onRequestTransferToken(callState.callId) { tok, url -> transferModal = tok to url }
                    },
                    modifier = Modifier.testTag("call_transfer_button")
                ) {
                    Icon(Icons.Filled.DesktopWindows, null)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = { callManager.toggleMute() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callState.isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute")
                }

                FilledIconButton(
                    onClick = { callManager.cycleAudioRoute() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callState.audioRoute != AudioRoute.EARPIECE) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        when (callState.audioRoute) {
                            AudioRoute.EARPIECE -> Icons.Filled.PhoneInTalk
                            AudioRoute.SPEAKER -> Icons.Filled.VolumeUp
                            AudioRoute.BLUETOOTH -> Icons.Filled.BluetoothAudio
                        },
                        "Audio Route: ${callState.audioRoute.name}"
                    )
                }

                FilledIconButton(
                    onClick = onEndCall,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.size(68.dp).testTag("audio_end_call_button")
                ) {
                    Icon(Icons.Filled.CallEnd, "End Call")
                }
            }
        }
    }

    if (showShareSheet) {
        InCallShareBottomSheet(
            isScreenSharing = callState.isScreenSharing,
            onDismiss = { showShareSheet = false },
            onToggleScreenShare = {
                showShareSheet = false
                if (callState.isScreenSharing) {
                    callManager.stopScreenShare()
                } else {
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
            },
            onPickPhotoOrVideo = {
                showShareSheet = false
                photoVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickDocument = {
                showShareSheet = false
                filePicker.launch(arrayOf("*/*"))
            },
            onOpenSyncDialog = {
                showShareSheet = false
                mediaShareDialog = true
            }
        )
    }

    transferModal?.let { (token, url) ->
        CallTransferDialog(token = token, url = url, onDismiss = { transferModal = null })
    }
    if (mediaShareDialog) {
        MediaSyncShareDialog(
            onDismiss = { mediaShareDialog = false },
            onShare = { title ->
                callManager.syncCallMedia(title, 0L, true)
                mediaShareDialog = false
            }
        )
    }
    previewSharedItem?.let { item ->
        InCallSharedPreviewDialog(item = item, onDismiss = { previewSharedItem = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCallScreen(
    callState: ActiveCallState,
    callManager: WebRtcCallManager,
    onRequestTransferToken: (callId: String, onReady: (String, String) -> Unit) -> Unit,
    onShareFileOrVideo: (Uri) -> Unit,
    onEndCall: () -> Unit,
) {
    val context = LocalContext.current
    var transferModal by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showShareSheet by remember { mutableStateOf(false) }
    var mediaShareDialog by remember { mutableStateOf(false) }
    var previewSharedItem by remember { mutableStateOf<CallSharedItem?>(null) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            callManager.startScreenShare(res.data!!)
        }
    }
    val photoVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onShareFileOrVideo(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onShareFileOrVideo(uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .testTag("video_call_screen")
    ) {
        if (callState.remoteVideoTrack != null && callManager.eglBase != null) {
            WebRtcVideoView(
                track = callState.remoteVideoTrack,
                eglBase = callManager.eglBase!!,
                mirror = false,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                EnclaveAvatar(name = callState.peerName.ifBlank { "Video" }, avatarPath = null, size = 104.dp)
                Spacer(Modifier.height(14.dp))
                Text(callState.peerName, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    "${formatDuration(callState.durationSeconds)} • ${callState.connectionQuality}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray
                )
            }
        }

        // Top Bar with E2EE, Screen Share status, Transfer & PiP
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SafetyShieldBadge("HD • DTLS-SRTP")
                if (callState.isScreenSharing) {
                    SafetyShieldBadge("Sharing Screen")
                } else if (callState.isPeerScreenSharing) {
                    SafetyShieldBadge("Viewing Peer Screen")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(
                    onClick = {
                        onRequestTransferToken(callState.callId) { tok, url -> transferModal = tok to url }
                    }
                ) {
                    Icon(Icons.Filled.DesktopWindows, "Transfer to Desktop")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    FilledIconButton(
                        onClick = {
                            val act = context as? ComponentActivity
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(9, 16))
                                .build()
                            act?.enterPictureInPictureMode(params)
                        }
                    ) {
                        Icon(Icons.Filled.PictureInPictureAlt, "Picture in Picture")
                    }
                }
            }
        }

        // Local Camera / Screen preview PiP
        if ((callState.isCameraEnabled || callState.isScreenSharing) && callState.localVideoTrack != null && callManager.eglBase != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp)
                    .size(width = 108.dp, height = 156.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            ) {
                WebRtcVideoView(
                    track = callState.localVideoTrack,
                    eglBase = callManager.eglBase!!,
                    mirror = !callState.isScreenSharing && callState.isFrontCamera,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Shared files/videos drawer above bottom controls
        if (callState.sharedItems.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp, start = 14.dp, end = 14.dp)
                    .fillMaxWidth()
            ) {
                InCallSharedItemsStrip(
                    items = callState.sharedItems,
                    onOpenItem = { previewSharedItem = it }
                )
            }
        }

        // Bottom Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            FilledIconButton(onClick = { callManager.toggleMute() }, modifier = Modifier.size(50.dp)) {
                Icon(if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute")
            }
            FilledIconButton(onClick = { callManager.toggleCamera() }, modifier = Modifier.size(50.dp)) {
                Icon(if (callState.isCameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Toggle Camera")
            }
            FilledIconButton(
                onClick = {
                    if (callState.isScreenSharing) {
                        callManager.stopScreenShare()
                    } else {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (callState.isScreenSharing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.size(50.dp).testTag("video_screen_share_btn")
            ) {
                Icon(
                    if (callState.isScreenSharing) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                    "Share Screen"
                )
            }
            FilledIconButton(
                onClick = { showShareSheet = true },
                modifier = Modifier.size(50.dp).testTag("video_share_media_btn")
            ) {
                Icon(Icons.Filled.AttachFile, "Share File or Video")
            }
            FilledIconButton(onClick = { callManager.switchCamera() }, modifier = Modifier.size(50.dp)) {
                Icon(Icons.Filled.Cameraswitch, "Switch Camera")
            }
            FilledIconButton(
                onClick = onEndCall,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.size(58.dp).testTag("video_end_call_button")
            ) {
                Icon(Icons.Filled.CallEnd, "End Call")
            }
        }
    }

    if (showShareSheet) {
        InCallShareBottomSheet(
            isScreenSharing = callState.isScreenSharing,
            onDismiss = { showShareSheet = false },
            onToggleScreenShare = {
                showShareSheet = false
                if (callState.isScreenSharing) callManager.stopScreenShare()
                else {
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
            },
            onPickPhotoOrVideo = {
                showShareSheet = false
                photoVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickDocument = {
                showShareSheet = false
                filePicker.launch(arrayOf("*/*"))
            },
            onOpenSyncDialog = {
                showShareSheet = false
                mediaShareDialog = true
            }
        )
    }

    transferModal?.let { (token, url) ->
        CallTransferDialog(token = token, url = url, onDismiss = { transferModal = null })
    }
    if (mediaShareDialog) {
        MediaSyncShareDialog(
            onDismiss = { mediaShareDialog = false },
            onShare = { title ->
                callManager.syncCallMedia(title, 0L, true)
                mediaShareDialog = false
            }
        )
    }
    previewSharedItem?.let { item ->
        InCallSharedPreviewDialog(item = item, onDismiss = { previewSharedItem = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCallScreen(
    callState: ActiveCallState,
    callManager: WebRtcCallManager,
    onShareFileOrVideo: (Uri) -> Unit,
    onEndCall: () -> Unit,
) {
    val context = LocalContext.current
    var showShareSheet by remember { mutableStateOf(false) }
    var mediaShareDialog by remember { mutableStateOf(false) }
    var previewSharedItem by remember { mutableStateOf<CallSharedItem?>(null) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            callManager.startScreenShare(res.data!!)
        }
    }
    val photoVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onShareFileOrVideo(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onShareFileOrVideo(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp)
            .testTag("group_call_screen")
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(callState.peerName.ifBlank { "Group Call" }, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${callState.participants.size} participants • ${formatDuration(callState.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (callState.isScreenSharing) SafetyShieldBadge("Sharing")
                SafetyShieldBadge("SFU • E2EE")
            }
        }

        Spacer(Modifier.height(10.dp))
        if (callState.remoteVideoTrack != null && callManager.eglBase != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
            ) {
                WebRtcVideoView(
                    track = callState.remoteVideoTrack,
                    eglBase = callManager.eglBase!!,
                    mirror = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        callState.mediaSync?.let { sync ->
            MediaSyncControlBanner(
                title = sync.mediaTitle,
                isPlaying = sync.isPlaying,
                onTogglePlay = { callManager.syncCallMedia(sync.mediaTitle, sync.positionMs, !sync.isPlaying) }
            )
            Spacer(Modifier.height(10.dp))
        }

        if (callState.sharedItems.isNotEmpty()) {
            InCallSharedItemsStrip(
                items = callState.sharedItems,
                onOpenItem = { previewSharedItem = it }
            )
            Spacer(Modifier.height(10.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(callState.participants, key = { it.deviceId }) { member ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .height(160.dp)
                        .border(
                            width = if (member.isSpeaking) 2.dp else 0.dp,
                            color = if (member.isSpeaking) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                    ) {
                        EnclaveAvatar(name = member.displayName, avatarPath = null, size = 56.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(member.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (member.isSpeaking) {
                                Icon(Icons.Filled.GraphicEq, "Speaking", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                            if (member.isMuted) {
                                Icon(Icons.Filled.MicOff, "Muted", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            FilledIconButton(onClick = { callManager.toggleMute() }, modifier = Modifier.size(52.dp)) {
                Icon(if (callState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute")
            }
            FilledIconButton(onClick = { callManager.toggleCamera() }, modifier = Modifier.size(52.dp)) {
                Icon(if (callState.isCameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Camera")
            }
            FilledIconButton(
                onClick = {
                    if (callState.isScreenSharing) callManager.stopScreenShare()
                    else {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (callState.isScreenSharing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.size(52.dp).testTag("group_screen_share_btn")
            ) {
                Icon(if (callState.isScreenSharing) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare, "Share Screen")
            }
            FilledIconButton(
                onClick = { showShareSheet = true },
                modifier = Modifier.size(52.dp).testTag("group_share_media_btn")
            ) {
                Icon(Icons.Filled.AttachFile, "Share File or Video")
            }
            FilledIconButton(
                onClick = onEndCall,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.size(60.dp).testTag("group_leave_call_button")
            ) {
                Icon(Icons.Filled.CallEnd, "Leave Call")
            }
        }
    }

    if (showShareSheet) {
        InCallShareBottomSheet(
            isScreenSharing = callState.isScreenSharing,
            onDismiss = { showShareSheet = false },
            onToggleScreenShare = {
                showShareSheet = false
                if (callState.isScreenSharing) callManager.stopScreenShare()
                else {
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
            },
            onPickPhotoOrVideo = {
                showShareSheet = false
                photoVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickDocument = {
                showShareSheet = false
                filePicker.launch(arrayOf("*/*"))
            },
            onOpenSyncDialog = {
                showShareSheet = false
                mediaShareDialog = true
            }
        )
    }

    if (mediaShareDialog) {
        MediaSyncShareDialog(
            onDismiss = { mediaShareDialog = false },
            onShare = { title ->
                callManager.syncCallMedia(title, 0L, true)
                mediaShareDialog = false
            }
        )
    }
    previewSharedItem?.let { item ->
        InCallSharedPreviewDialog(item = item, onDismiss = { previewSharedItem = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InCallShareBottomSheet(
    isScreenSharing: Boolean,
    onDismiss: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onPickPhotoOrVideo: () -> Unit,
    onPickDocument: () -> Unit,
    onOpenSyncDialog: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        ) {
            Text("Share in Encrypted Call", style = MaterialTheme.typography.titleLarge)
            ShareSheetOption(
                icon = if (isScreenSharing) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                title = if (isScreenSharing) "Stop Screen Sharing" else "Share Device Screen",
                subtitle = "Live WebRTC MediaProjection stream (DTLS-SRTP)",
                onClick = onToggleScreenShare
            )
            ShareSheetOption(
                icon = Icons.Filled.Movie,
                title = "Share Photo or Video",
                subtitle = "Encrypted per-file transfer via DataChannel / Relay",
                onClick = onPickPhotoOrVideo
            )
            ShareSheetOption(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                title = "Share File or Document",
                subtitle = "Send PDF, archive, or document inside the call",
                onClick = onPickDocument
            )
            ShareSheetOption(
                icon = Icons.Filled.Share,
                title = "Sync Media Playback Title",
                subtitle = "Synchronize playback controls across call peers",
                onClick = onOpenSyncDialog
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ShareSheetOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InCallSharedItemsStrip(
    items: List<CallSharedItem>,
    onOpenItem: (CallSharedItem) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                "Shared Files & Media (${items.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 4.dp)
            ) {
                items(items, key = { it.itemId }) { item ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .width(168.dp)
                            .clickable { onOpenItem(item) }
                            .testTag("call_shared_item_${item.itemId}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            val icon = when {
                                item.mimeType.startsWith("video") -> Icons.Filled.Movie
                                item.mimeType.startsWith("image") -> Icons.Filled.Image
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            }
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.senderName} • ${item.sizeBytes / 1024}KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InCallSharedPreviewDialog(item: CallSharedItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            val path = item.localPath
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From: ${item.senderName} • ${item.mimeType}", style = MaterialTheme.typography.labelSmall)
                if (path != null && item.mimeType.startsWith("video")) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(path)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                } else if (path != null && item.mimeType.startsWith("image")) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                } else {
                    Text(
                        "Encrypted file received and verified (${item.sizeBytes} bytes).\nStored in Enclave sandbox.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun WebRtcVideoView(
    track: VideoTrack,
    eglBase: EglBase,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(track) {
        onDispose {
            rendererRef?.let { r ->
                runCatching { track.removeSink(r) }
                runCatching { r.release() }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                track.addSink(this)
                rendererRef = this
            }
        },
        modifier = modifier
    )
}

@Composable
private fun CallTransferDialog(token: String, url: String, onDismiss: () -> Unit) {
    val qr = remember(url) { QrCodeEngine.generateQrBitmap(url, 384) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer Call to Desktop Browser") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Scan this expiring single-use QR or open the URL on your desktop browser to hand off this call without exposing device keys.",
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    Image(bitmap = qr, contentDescription = "Call transfer QR", modifier = Modifier.size(180.dp).padding(8.dp))
                }
                Text("Token: $token (5m expiry)", style = MaterialTheme.typography.labelSmall)
                Text(url, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun MediaSyncControlBanner(title: String, isPlaying: Boolean, onTogglePlay: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Synchronized Media", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            FilledIconButton(onClick = onTogglePlay) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Toggle playback")
            }
        }
    }
}

@Composable
private fun MediaSyncShareDialog(onDismiss: () -> Unit, onShare: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share Media Playback in Call") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Media title or stream URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank()) onShare(title) }) { Text("Sync Playback") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
