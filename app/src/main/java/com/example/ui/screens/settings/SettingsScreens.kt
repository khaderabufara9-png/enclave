package com.example.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.database.ProfileEntity
import com.example.core.database.RelationshipEntity
import com.example.core.database.SecurityAuditEntity
import com.example.core.prefs.SecuritySettingsState
import com.example.data.DiagnosticItem
import com.example.ui.components.EnclaveAvatar
import com.example.ui.components.formatTimeShort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profile: ProfileEntity?,
    onBack: () -> Unit,
    onSave: (displayName: String, bio: String, avatarUri: Uri?) -> Unit,
) {
    var name by remember(profile) { mutableStateOf(profile?.displayName ?: "") }
    var bio by remember(profile) { mutableStateOf(profile?.bio ?: "") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pickedUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Space Profile Editor") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            EnclaveAvatar(
                name = name.ifBlank { "Anonymous" },
                avatarPath = pickedUri?.toString() ?: profile?.avatarLocalPath,
                size = 88.dp
            )
            OutlinedButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text("Change Avatar via Photo Picker")
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Default Display Name in this Space") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("Bio / Status Note") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Enclave Code: ${profile?.enclaveCode ?: "---"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = { onSave(name, bio, pickedUri); onBack() },
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("profile_editor_save_btn")
            ) {
                Text("Save Space Profile")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSettingsScreen(
    relationship: RelationshipEntity?,
    onBack: () -> Unit,
    onUpdatePersona: (name: String, bio: String, avatarUri: Uri?) -> Unit,
    onUpdateControls: (allowMsg: Boolean?, allowCalls: Boolean?, notif: Boolean?, ttlSec: Long?, status: String?) -> Unit,
    onClearConversation: () -> Unit,
    onRevokeRelationship: (wipeHistory: Boolean) -> Unit,
) {
    if (relationship == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Contact not found")
        }
        return
    }

    var myName by remember(relationship) { mutableStateOf(relationship.myDisplayName) }
    var myBio by remember(relationship) { mutableStateOf(relationship.myBio) }
    var pickedAvatar by remember { mutableStateOf<Uri?>(null) }
    var showRevokeDialog by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pickedAvatar = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact Privacy & Identity") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Peer & Safety Fingerprint
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EnclaveAvatar(relationship.peerDisplayName, relationship.peerAvatarPath, size = 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(relationship.peerDisplayName, style = MaterialTheme.typography.titleMedium)
                            Text("Status: ${relationship.status}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Safety Number: ${relationship.safetyFingerprint}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Independent Public-Facing Identity ONLY for this contact
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your Identity to ${relationship.peerDisplayName}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Changes here apply ONLY to this relationship and do not affect any other contact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = myName,
                        onValueChange = { myName = it },
                        label = { Text("Display Name Shown to This Contact") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("contact_persona_name_input")
                    )
                    OutlinedTextField(
                        value = myBio,
                        onValueChange = { myBio = it },
                        label = { Text("Relationship Note") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("Custom Avatar") }
                        Button(
                            onClick = { onUpdatePersona(myName, myBio, pickedAvatar) },
                            modifier = Modifier.testTag("contact_persona_save_btn")
                        ) { Text("Save Persona") }
                    }
                }
            }

            // Permissions & Controls
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Interaction Permissions", style = MaterialTheme.typography.titleMedium)
                    ToggleRow("Allow Messaging", relationship.allowMessaging) {
                        onUpdateControls(it, null, null, null, null)
                    }
                    ToggleRow("Allow Incoming Calls", relationship.allowIncomingCalls) {
                        onUpdateControls(null, it, null, null, null)
                    }
                    ToggleRow("Contact Notifications", relationship.notificationsEnabled) {
                        onUpdateControls(null, null, it, null, null)
                    }
                    ToggleRow("Block Contact", relationship.status == "BLOCKED") { blocked ->
                        onUpdateControls(null, null, null, null, if (blocked) "BLOCKED" else "ACTIVE")
                    }
                }
            }

            OutlinedButton(
                onClick = onClearConversation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Conversation on Both Devices")
            }

            Button(
                onClick = { showRevokeDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("revoke_relationship_btn")
            ) {
                Icon(Icons.Filled.DeleteForever, null)
                Spacer(Modifier.width(8.dp))
                Text("Revoke Relationship Permanently")
            }
        }
    }

    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text("Revoke Relationship?") },
            text = {
                Text("Revoking severs cryptographic trust and blocks ${relationship.peerDisplayName} from contacting you again through this Enclave relationship.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { showRevokeDialog = false; onRevokeRelationship(true) }
                ) { Text("Revoke & Wipe") }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    settings: SecuritySettingsState,
    onBack: () -> Unit,
    onUpdate: (hidePreviews: Boolean?, screenshotProt: Boolean?, readReceipts: Boolean?, typing: Boolean?) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow("Send Read Receipts", settings.readReceiptsEnabled) { onUpdate(null, null, it, null) }
                    ToggleRow("Show Typing Indicators", settings.typingIndicatorsEnabled) { onUpdate(null, null, null, it) }
                    ToggleRow("Hide Message Previews in Alerts", settings.hideMessagePreviews) { onUpdate(it, null, null, null) }
                    ToggleRow("Screenshot & App Switcher Protection (FLAG_SECURE)", settings.screenshotProtection) {
                        onUpdate(null, it, null, null)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Zero Contact-Book Sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Enclave does not declare READ_CONTACTS and cannot discover users by phone number or email.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    settings: SecuritySettingsState,
    audits: List<SecurityAuditEntity>,
    onBack: () -> Unit,
    onSavePin: (String) -> Unit,
    onDisablePin: () -> Unit,
    onSaveDuressPin: (String) -> Unit,
    onDisableDuressPin: () -> Unit,
    onSetBiometric: (Boolean) -> Unit,
    onSetAutoLock: (Int) -> Unit,
    onExportBackup: (passphrase: String, onReady: (String) -> Unit) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var newPin by remember { mutableStateOf("") }
    var newDuressPin by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var exportedBundle by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security & Recovery") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Device Identity", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Installation ID: ${settings.installationId}", style = MaterialTheme.typography.labelSmall)
                        Text("Keys stored in Android Keystore (AES-256-GCM + EC P-256)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("App Lock & Biometrics", style = MaterialTheme.typography.titleMedium)
                        ToggleRow("Biometric / Fingerprint Unlock", settings.biometricEnabled) { onSetBiometric(it) }
                        OutlinedTextField(
                            value = newPin,
                            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) newPin = it },
                            label = { Text(if (settings.pinEnabled) "Change PIN (4-6 digits)" else "Set PIN (4-6 digits)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (newPin.length >= 4) { onSavePin(newPin); newPin = "" } }) {
                                Text("Save PIN")
                            }
                            if (settings.pinEnabled) {
                                OutlinedButton(onClick = onDisablePin) { Text("Disable PIN") }
                            }
                        }
                        Text("Auto-lock inactivity timeout: ${settings.autoLockSeconds}s", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 30, 60, 300).forEach { sec ->
                                val label = if (sec == 0) "Immediate" else "${sec}s"
                                if (settings.autoLockSeconds == sec) Button(onClick = { onSetAutoLock(sec) }) { Text(label) }
                                else OutlinedButton(onClick = { onSetAutoLock(sec) }) { Text(label) }
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Security, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Emergency Duress Wipe PIN", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "If entered at the lock screen under coercion, Enclave immediately wipes all hidden Secret Spaces and unlocks cleanly into Personal Space.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = newDuressPin,
                            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) newDuressPin = it },
                            label = { Text(if (settings.duressPinEnabled) "Update Duress PIN (4-6 digits)" else "Set Duress PIN (4-6 digits)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("duress_pin_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = newDuressPin.length >= 4,
                                onClick = {
                                    onSaveDuressPin(newDuressPin)
                                    newDuressPin = ""
                                },
                                modifier = Modifier.testTag("duress_pin_save_btn")
                            ) { Text("Save Duress PIN") }
                            if (settings.duressPinEnabled) {
                                OutlinedButton(onClick = onDisableDuressPin) { Text("Remove Duress PIN") }
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Encrypted Device Migration Backup", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Exports an AES-256-GCM bundle encrypted with PBKDF2-HMAC-SHA256. Never stored unencrypted on any server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = backupPass,
                            onValueChange = { backupPass = it },
                            label = { Text("Backup Passphrase (min 6 chars)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = backupPass.length >= 6,
                            onClick = { onExportBackup(backupPass) { exportedBundle = it } }
                        ) { Text("Generate Encrypted Backup") }
                    }
                }
            }

            item { Text("Local Security Audit Log", style = MaterialTheme.typography.titleMedium) }
            items(audits) { audit ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("${audit.eventType} • ${formatTimeShort(audit.timestamp)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(audit.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    exportedBundle?.let { bundle ->
        AlertDialog(
            onDismissRequest = { exportedBundle = null },
            title = { Text("Encrypted Recovery Bundle Ready") },
            text = { Text(bundle.take(360) + if (bundle.length > 360) "..." else "", style = MaterialTheme.typography.labelSmall) },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(bundle))
                    exportedBundle = null
                }) { Text("Copy Full Bundle") }
            },
            dismissButton = { TextButton(onClick = { exportedBundle = null }) { Text("Close") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    settings: SecuritySettingsState,
    onBack: () -> Unit,
    onUpdate: (Boolean, Boolean) -> Unit,
    onToggleAlwaysOnRelay: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow("Encrypted Message Notifications", settings.notificationsEnabled) {
                        onUpdate(it, settings.callNotificationsEnabled)
                    }
                    ToggleRow("Full-Screen Incoming Call Rings", settings.callNotificationsEnabled) {
                        onUpdate(settings.notificationsEnabled, it)
                    }
                    ToggleRow("Keep Background Relay Active", settings.alwaysOnBackgroundRelay) {
                        onToggleAlwaysOnRelay(it)
                    }
                    Text(
                        "Push payloads contain zero plaintext message content — only a wake-up signal. Enabling 'Keep Background Relay Active' maintains a low-power WebSocket listener so calls ring even if FCM is not configured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Battery Optimization Settings")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    settings: SecuritySettingsState,
    items: List<DiagnosticItem>,
    isRunning: Boolean,
    onBack: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onSaveEndpoints: (backend: String, stun: String, turn: String) -> Unit,
    onShareServerLink: ((String) -> Unit) -> Unit = {},
    onApplyServerLink: (String) -> Unit = {},
) {
    var backendUrl by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    var stunUrl by remember(settings.stunUrl) { mutableStateOf(settings.stunUrl) }
    var turnUrl by remember(settings.turnUrl) { mutableStateOf(settings.turnUrl) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(Unit) { onRunDiagnostics() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network & RTC Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onRunDiagnostics) { Icon(Icons.Filled.Refresh, "Re-run checks") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Server & ICE Endpoints", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = backendUrl, onValueChange = { backendUrl = it }, label = { Text("Backend Relay URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = stunUrl, onValueChange = { stunUrl = it }, label = { Text("STUN URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = turnUrl, onValueChange = { turnUrl = it }, label = { Text("TURN URL (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { onSaveEndpoints(backendUrl, stunUrl, turnUrl) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Save & Reconnect")
                            }
                            OutlinedButton(
                                onClick = {
                                    onShareServerLink { link ->
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, link)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share Enclave Server Config"))
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = "Share server link")
                                Spacer(Modifier.width(4.dp))
                                Text("Share")
                            }
                        }
                        OutlinedButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Paste Friend's Server Link")
                        }
                    }
                }
            }

            if (isRunning) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Running live probes...")
                    }
                }
            }

            items(items, key = { it.id }) { diag ->
                val icon = when (diag.status) {
                    "PASS" -> Icons.Filled.CheckCircle
                    "WARN" -> Icons.Filled.Warning
                    else -> Icons.Filled.Error
                }
                val tint = when (diag.status) {
                    "PASS" -> MaterialTheme.colorScheme.primary
                    "WARN" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(icon, null, tint = tint)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("${diag.title} (${diag.status})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(diag.detail, style = MaterialTheme.typography.bodySmall)
                            Text(diag.recommendation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Server Config") },
            text = {
                OutlinedTextField(
                    value = importInput,
                    onValueChange = { importInput = it },
                    label = { Text("enclave://server?... or https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onApplyServerLink(importInput)
                    showImportDialog = false
                    onRunDiagnostics()
                }) { Text("Apply & Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSupportScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About & Security Model") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enclave Private Messenger v1.0.0", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Cryptographic Stack: ECDSA P-256 device authentication, ECDH P-256 + HKDF-SHA256 relationship key agreement, per-message HMAC-SHA256 ratchet, AES-256-GCM authenticated encryption, Android Keystore master wrapping, PBKDF2-HMAC-SHA256 (100k iterations) for PIN & Secret Spaces, and WebRTC DTLS-SRTP for voice/video.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Explicit Security Limitations", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "• Disappearing messages and remote deletion cannot prevent a recipient from photographing the screen with an external camera or bypassing OS screenshot flags on rooted devices.\n" +
                            "• Push notifications rely on FCM wake-up delivery; no plaintext message content is included in push payloads.\n" +
                            "• Secret Spaces rely on passphrase entropy; forgetting a Secret Space passphrase makes local recovery impossible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
