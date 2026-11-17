package com.example.ui.screens.spaces

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.database.SpaceEntity
import com.example.ui.components.parseHexColorOrDefault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesManagementScreen(
    spaces: List<SpaceEntity>,
    activeSpaceId: String,
    onBack: () -> Unit,
    onSwitchSpace: (String) -> Unit,
    onCreateSpace: (name: String, accentHex: String, isHidden: Boolean, secretPass: String?, profileName: String, onDone: () -> Unit) -> Unit,
    onLockSecretSpaces: () -> Unit,
    onOpenSecretAccess: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spaces & Isolation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }, modifier = Modifier.testTag("create_space_btn")) {
                        Icon(Icons.Filled.Add, "Create Space")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Isolated Environments", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Each Space maintains an independent profile, contact graph, conversations, notifications, and accent theme.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showCreateDialog = true }) { Text("New Space") }
                            OutlinedButton(onClick = onOpenSecretAccess) {
                                Icon(Icons.Filled.VisibilityOff, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Unlock Secret")
                            }
                        }
                        if (spaces.any { it.isHidden }) {
                            OutlinedButton(onClick = onLockSecretSpaces, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Lock, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Re-Lock Hidden Secret Spaces")
                            }
                        }
                    }
                }
            }

            items(spaces, key = { it.spaceId }) { space ->
                val accent = parseHexColorOrDefault(space.accentHex, MaterialTheme.colorScheme.primary)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSwitchSpace(space.spaceId); onBack() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(space.name + if (space.isHidden) " (Unlocked Secret)" else "", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (space.spaceId == activeSpaceId) "Currently active" else "Tap to switch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var profileName by remember { mutableStateOf("") }
        var isHidden by remember { mutableStateOf(false) }
        var secretPass by remember { mutableStateOf("") }
        var selectedHex by remember { mutableStateOf("#00D2B8") }
        val colors = listOf("#00D2B8", "#6366F1", "#10B981", "#F59E0B", "#EC4899")

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Enclave Space") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Space Name (e.g., Consulting)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("create_space_name_input")
                    )
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Public Display Name in this Space") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        colors.forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColorOrDefault(hex, MaterialTheme.colorScheme.primary))
                                    .clickable { selectedHex = hex }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isHidden, onCheckedChange = { isHidden = it })
                        Text("Make Hidden Secret Space")
                    }
                    if (isHidden) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.secret_space_warning), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = secretPass,
                            onValueChange = { secretPass = it },
                            label = { Text("Secret Passphrase") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() && (!isHidden || secretPass.length >= 4),
                    onClick = {
                        onCreateSpace(name, selectedHex, isHidden, secretPass.takeIf { isHidden }, profileName) {
                            showCreateDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretSpaceAccessScreen(
    onBack: () -> Unit,
    onUnlock: (secretName: String, secretPass: String, onResult: (Boolean) -> Unit) -> Unit,
    onUnlockedSuccess: () -> Unit,
) {
    var secretName by remember { mutableStateOf("") }
    var secretPass by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secret Space Access") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.secret_space_warning), style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedTextField(
                value = secretName,
                onValueChange = { secretName = it; errorText = null },
                label = { Text("Exact Secret Space Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("secret_space_name_input")
            )
            OutlinedTextField(
                value = secretPass,
                onValueChange = { secretPass = it; errorText = null },
                label = { Text("Secret Passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("secret_space_pass_input")
            )
            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    onUnlock(secretName, secretPass) { ok ->
                        if (ok) onUnlockedSuccess()
                        else errorText = "No matching hidden Space found for those credentials."
                    }
                },
                enabled = secretName.isNotBlank() && secretPass.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("secret_space_unlock_button")
            ) {
                Icon(Icons.Filled.LockOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Unlock Hidden Space")
            }
        }
    }
}
