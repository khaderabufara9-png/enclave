package com.example.ui.screens.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.core.database.MessageEntity
import com.example.core.database.RelationshipEntity
import com.example.core.media.VoiceMediaManager
import com.example.ui.components.EnclaveAvatar
import com.example.ui.components.SafetyShieldBadge
import com.example.ui.components.formatDuration
import com.example.ui.components.formatTimeShort
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    relationship: RelationshipEntity?,
    messages: List<MessageEntity>,
    allRelationships: List<RelationshipEntity>,
    isPeerTyping: Boolean,
    mediaManager: VoiceMediaManager,
    onBack: () -> Unit,
    onOpenContactSettings: () -> Unit,
    onMarkRead: () -> Unit,
    onStartCall: (Boolean) -> Unit,
    onSendMessage: (
        type: String,
        bodyText: String,
        mediaLocalPath: String?,
        mediaMimeType: String?,
        mediaFileName: String?,
        mediaSizeBytes: Long,
        mediaDurationMs: Long,
        latitude: Double?,
        longitude: Double?,
        replyToMessageId: String?,
        replyPreviewText: String?,
    ) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onToggleReaction: (String, String) -> Unit,
    onDeleteLocal: (String) -> Unit,
    onDeleteForBoth: (String) -> Unit,
    onClearConversationForBoth: () -> Unit,
    onSetDisappearingTtl: (Long) -> Unit,
    onForwardMessage: (targetRelationshipId: String, msg: MessageEntity) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showEphemeralDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var activePlayingMsgId by remember { mutableStateOf<String?>(null) }

    var replyTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editingTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardingTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var previewMediaTarget by remember { mutableStateOf<MessageEntity?>(null) }

    LaunchedEffect(messages.size) {
        onMarkRead()
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val filteredMessages = remember(messages, searchQuery, searchMode) {
        if (!searchMode || searchQuery.isBlank()) messages
        else messages.filter { it.bodyText.contains(searchQuery, ignoreCase = true) }
    }

    // Launchers for attachments
    val photoVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val stored = mediaManager.importUriToPrivateStorage(uri) ?: return@rememberLauncherForActivityResult
            val isVid = stored.mimeType.startsWith("video")
            onSendMessage(
                if (isVid) "VIDEO" else "IMAGE",
                if (isVid) "Encrypted Video" else "Encrypted Photo",
                stored.localFilePath,
                stored.mimeType,
                stored.fileName,
                stored.sizeBytes,
                0L,
                null,
                null,
                replyTarget?.messageId,
                replyTarget?.bodyText
            )
            replyTarget = null
        }
    )

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val stored = mediaManager.importUriToPrivateStorage(uri, "dat") ?: return@rememberLauncherForActivityResult
            onSendMessage(
                "DOCUMENT",
                stored.fileName,
                stored.localFilePath,
                stored.mimeType,
                stored.fileName,
                stored.sizeBytes,
                0L,
                null,
                null,
                replyTarget?.messageId,
                replyTarget?.bodyText
            )
            replyTarget = null
        }
    )

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                isRecordingVoice = mediaManager.startVoiceRecording()
            }
        }
    )

    val locationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                val loc = fetchLastCoarseLocation(context)
                if (loc != null) {
                    onSendMessage(
                        "LOCATION",
                        "Shared location (${"%.4f".format(loc.first)}, ${"%.4f".format(loc.second)})",
                        null, null, null, 0L, 0L,
                        loc.first, loc.second, null, null
                    )
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onOpenContactSettings() }
                    ) {
                        EnclaveAvatar(
                            name = relationship?.peerDisplayName ?: "Contact",
                            avatarPath = relationship?.peerAvatarPath,
                            size = 38.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(relationship?.peerDisplayName ?: "Chat", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    isPeerTyping -> "Typing..."
                                    (relationship?.disappearingTtlSeconds ?: 0L) > 0L ->
                                        "Disappearing: ${formatDuration(relationship!!.disappearingTtlSeconds)} • As ${relationship.myDisplayName}"
                                    else -> "E2EE • As ${relationship?.myDisplayName ?: "You"}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onStartCall(false) }, modifier = Modifier.testTag("chat_voice_call_btn")) {
                        Icon(Icons.Filled.Call, contentDescription = "Voice Call")
                    }
                    IconButton(onClick = { onStartCall(true) }, modifier = Modifier.testTag("chat_video_call_btn")) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Video Call")
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Search History") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            onClick = {
                                showMoreMenu = false
                                searchMode = !searchMode
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Disappearing Messages") },
                            leadingIcon = { Icon(Icons.Filled.Timer, null) },
                            onClick = {
                                showMoreMenu = false
                                showEphemeralDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Contact Persona & Controls") },
                            onClick = {
                                showMoreMenu = false
                                onOpenContactSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Chat on Both Devices") },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                            onClick = {
                                showMoreMenu = false
                                showClearConfirm = true
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            AnimatedVisibility(visible = searchMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search encrypted local messages...") },
                    trailingIcon = {
                        IconButton(onClick = { searchMode = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if ((relationship?.disappearingTtlSeconds ?: 0L) > 0L) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Disappearing messages active (${relationship!!.disappearingTtlSeconds}s). Cannot prevent external screenshots.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        SafetyShieldBadge("Key Fingerprint: ${relationship?.safetyFingerprint ?: "Verified"}")
                    }
                }

                items(filteredMessages, key = { it.messageId }) { msg ->
                    MessageBubble(
                        msg = msg,
                        isPlayingAudio = activePlayingMsgId == msg.messageId,
                        onPlayAudio = { path ->
                            if (activePlayingMsgId == msg.messageId) {
                                mediaManager.stopPlayback()
                                activePlayingMsgId = null
                            } else {
                                activePlayingMsgId = msg.messageId
                                mediaManager.playAudio(path) { activePlayingMsgId = null }
                            }
                        },
                        onOpenMedia = { previewMediaTarget = msg },
                        onLongOrTapActions = { actionTarget = msg }
                    )
                }
            }

            // Reply / Edit Banner
            val bannerTarget = replyTarget ?: editingTarget
            if (bannerTarget != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            if (editingTarget != null) Icons.Filled.Edit else Icons.Filled.Reply,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = (if (editingTarget != null) "Editing: " else "Replying to: ") + bannerTarget.bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { replyTarget = null; editingTarget = null; inputText = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                }
            }

            // Composer Bar
            val messagingAllowed = relationship?.status == "ACTIVE" && relationship.allowMessaging
            if (!messagingAllowed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (relationship?.status == "REVOKED") "Relationship revoked. Channel permanently closed."
                        else "Messaging disabled for this contact.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    IconButton(
                        onClick = { showAttachmentSheet = true },
                        modifier = Modifier.testTag("chat_attach_button")
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach media or file")
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(if (isRecordingVoice) "Recording voice note..." else "Encrypted message") },
                        maxLines = 4,
                        enabled = !isRecordingVoice,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )

                    Spacer(Modifier.width(4.dp))

                    if (inputText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val textToSend = inputText.trim()
                                val editMsg = editingTarget
                                if (editMsg != null) {
                                    onEditMessage(editMsg.messageId, textToSend)
                                    editingTarget = null
                                } else {
                                    onSendMessage(
                                        "TEXT", textToSend, null, null, null, 0L, 0L,
                                        null, null, replyTarget?.messageId, replyTarget?.bodyText
                                    )
                                    replyTarget = null
                                }
                                inputText = ""
                            },
                            modifier = Modifier.testTag("chat_send_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send encrypted message",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (isRecordingVoice) {
                                    isRecordingVoice = false
                                    val rec = mediaManager.stopVoiceRecording(cancel = false)
                                    if (rec != null) {
                                        onSendMessage(
                                            "VOICE",
                                            "Voice message (${formatDuration(rec.durationMs / 1000)})",
                                            rec.localFilePath,
                                            rec.mimeType,
                                            rec.fileName,
                                            rec.sizeBytes,
                                            rec.durationMs,
                                            null, null, null, null
                                        )
                                    }
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        isRecordingVoice = mediaManager.startVoiceRecording()
                                    } else {
                                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            modifier = Modifier.testTag("chat_voice_record_button")
                        ) {
                            Icon(
                                imageVector = if (isRecordingVoice) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = if (isRecordingVoice) "Stop recording" else "Record voice message",
                                tint = if (isRecordingVoice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // Attachment Picker Sheet (Screen 8)
    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(20.dp)
            ) {
                Text("Encrypted Attachment", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                AttachmentOptionRow(Icons.Filled.Image, "Photo or Video", "Zero-permission Android Photo Picker") {
                    showAttachmentSheet = false
                    photoVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                AttachmentOptionRow(Icons.AutoMirrored.Filled.InsertDriveFile, "Document or File", "PDF, archives, or any encrypted file") {
                    showAttachmentSheet = false
                    documentPicker.launch(arrayOf("*/*"))
                }
                AttachmentOptionRow(Icons.Filled.LocationOn, "Share Current Location", "Explicitly initiated coarse location") {
                    showAttachmentSheet = false
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        fetchLastCoarseLocation(context)?.let { loc ->
                            onSendMessage(
                                "LOCATION",
                                "Location: ${"%.4f".format(loc.first)}, ${"%.4f".format(loc.second)}",
                                null, null, null, 0L, 0L, loc.first, loc.second, null, null
                            )
                        }
                    } else {
                        locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Message Context Menu Modal
    actionTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("Message Options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("👍", "❤️", "🔒", "🔥", "😂").forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.clickable {
                                    onToggleReaction(msg.messageId, emoji)
                                    actionTarget = null
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { replyTarget = msg; actionTarget = null }) {
                        Icon(Icons.Filled.Reply, null); Spacer(Modifier.width(8.dp)); Text("Reply")
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(msg.bodyText))
                        actionTarget = null
                    }) {
                        Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy Text")
                    }
                    TextButton(onClick = { forwardingTarget = msg; actionTarget = null }) {
                        Icon(Icons.Filled.Forward, null); Spacer(Modifier.width(8.dp)); Text("Forward to Contact")
                    }
                    if (msg.isOutgoing && msg.type == "TEXT") {
                        TextButton(onClick = {
                            editingTarget = msg
                            inputText = msg.bodyText
                            actionTarget = null
                        }) {
                            Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit")
                        }
                    }
                    TextButton(onClick = { onDeleteLocal(msg.messageId); actionTarget = null }) {
                        Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(8.dp)); Text("Delete Locally")
                    }
                    TextButton(onClick = { onDeleteForBoth(msg.messageId); actionTarget = null }) {
                        Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete for Both Devices", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Close") }
            }
        )
    }

    // Forward Message Dialog
    forwardingTarget?.let { msg ->
        val candidates = allRelationships.filter { it.status == "ACTIVE" && it.relationshipId != relationship?.relationshipId }
        AlertDialog(
            onDismissRequest = { forwardingTarget = null },
            title = { Text("Forward Encrypted Message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (candidates.isEmpty()) {
                        Text("No other active contacts in this Space.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        candidates.forEach { targetRel ->
                            OutlinedButton(
                                onClick = {
                                    onForwardMessage(targetRel.relationshipId, msg)
                                    forwardingTarget = null
                                },
                                modifier = Modifier.fillMaxWidth().testTag("forward_to_${targetRel.relationshipId}")
                            ) {
                                Text(targetRel.peerDisplayName)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { forwardingTarget = null }) { Text("Close") } }
        )
    }

    previewMediaTarget?.let { mediaMsg ->
        val path = mediaMsg.mediaLocalPath
        AlertDialog(
            onDismissRequest = { previewMediaTarget = null },
            title = { Text(mediaMsg.mediaFileName ?: mediaMsg.type) },
            text = {
                if (path != null && mediaMsg.type == "VIDEO") {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoPath(path)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                } else if (path != null && mediaMsg.type == "IMAGE") {
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Full encrypted image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(280.dp)
                    )
                } else {
                    Text(
                        "File: ${mediaMsg.mediaFileName ?: mediaMsg.bodyText}\nSize: ${mediaMsg.mediaSizeBytes} bytes\nSaved privately inside Enclave sandbox.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewMediaTarget = null }) { Text("Close") }
            }
        )
    }

    // Ephemeral Message Timer Dialog
    if (showEphemeralDialog) {
        val presets = listOf(
            0L to "Off",
            10L to "10 seconds",
            30L to "30 seconds",
            60L to "1 minute",
            300L to "5 minutes",
            3600L to "1 hour",
            86400L to "24 hours",
            604800L to "7 days"
        )
        var customSec by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEphemeralDialog = false },
            title = { Text("Disappearing Messages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ephemeral_warning), style = MaterialTheme.typography.bodySmall)
                    }
                    presets.forEach { (sec, label) ->
                        val active = (relationship?.disappearingTtlSeconds ?: 0L) == sec
                        TextButton(onClick = {
                            onSetDisappearingTtl(sec)
                            showEphemeralDialog = false
                        }) {
                            Text(if (active) "✓ $label" else label, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    OutlinedTextField(
                        value = customSec,
                        onValueChange = { if (it.all(Char::isDigit)) customSec = it },
                        label = { Text("Custom seconds") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val custom = customSec.toLongOrNull()
                    if (custom != null && custom > 0L) {
                        onSetDisappearingTtl(custom)
                    }
                    showEphemeralDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showEphemeralDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Conversation on Both Devices?") },
            text = {
                Text("This clears all messages locally and sends a signed deletion event to your contact's device. Remote deletion shows pending until acknowledged.")
            },
            confirmButton = {
                Button(onClick = {
                    onClearConversationForBoth()
                    showClearConfirm = false
                }) { Text("Clear Both") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MessageBubble(
    msg: MessageEntity,
    isPlayingAudio: Boolean,
    onPlayAudio: (String) -> Unit,
    onOpenMedia: () -> Unit,
    onLongOrTapActions: () -> Unit,
) {
    val align = if (msg.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (msg.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val reactions = remember(msg.reactionsJson) {
        runCatching {
            val obj = JSONObject(msg.reactionsJson)
            obj.keys().asSequence().toList()
        }.getOrDefault(emptyList())
    }

    Column(horizontalAlignment = align, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (msg.isOutgoing) 18.dp else 4.dp,
                bottomEnd = if (msg.isOutgoing) 4.dp else 18.dp
            ),
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clickable { onLongOrTapActions() }
                .testTag("message_bubble_${msg.messageId}")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                msg.replyPreviewText?.takeIf { it.isNotBlank() }?.let { reply ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "↩ $reply",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                if (msg.type == "IMAGE" && !msg.mediaLocalPath.isNullOrBlank()) {
                    AsyncImage(
                        model = File(msg.mediaLocalPath),
                        contentDescription = "Encrypted image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenMedia() }
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (msg.type == "VIDEO" && !msg.mediaLocalPath.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMedia() }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play encrypted video", tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(msg.mediaFileName ?: "Encrypted Video", style = MaterialTheme.typography.titleSmall)
                                Text("Tap to play", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else if (msg.type == "DOCUMENT") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onOpenMedia() }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = "Document", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(msg.mediaFileName ?: msg.bodyText, style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (msg.type == "VOICE" && !msg.mediaLocalPath.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onPlayAudio(msg.mediaLocalPath) }
                    ) {
                        Icon(
                            if (isPlayingAudio) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = "Play voice note",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(msg.bodyText, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(text = msg.bodyText, style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (msg.isEdited) {
                        Text("edited", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                    }
                    if (msg.ttlSeconds > 0L) {
                        Icon(Icons.Filled.Timer, null, modifier = Modifier.size(12.dp))
                    }
                    Text(
                        text = formatTimeShort(msg.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (msg.isOutgoing) {
                        val icon = when (msg.deliveryStatus) {
                            "DELIVERED", "READ" -> Icons.Filled.DoneAll
                            "QUEUED_OFFLINE" -> Icons.Filled.Schedule
                            else -> Icons.Filled.DoneAll
                        }
                        Icon(
                            icon,
                            contentDescription = msg.deliveryStatus,
                            tint = if (msg.deliveryStatus == "QUEUED_OFFLINE") MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                if (msg.remoteDeleteStatus == "PENDING_PEER_ACK") {
                    Text(
                        "Deletion pending peer device ack",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(reactions.joinToString(" "), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptionRow(
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

@SuppressLint("MissingPermission")
private fun fetchLastCoarseLocation(context: Context): Pair<Double, Double>? {
    return runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        loc?.let { it.latitude to it.longitude }
    }.getOrNull()
}
