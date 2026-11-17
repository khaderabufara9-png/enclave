package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.database.CallSessionEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.ProfileEntity
import com.example.core.database.RelationshipEntity
import com.example.core.database.SpaceEntity
import com.example.core.network.SocketConnectionState
import com.example.core.prefs.SecuritySettingsState
import com.example.ui.components.EmptySanctuaryView
import com.example.ui.components.EnclaveAvatar
import com.example.ui.components.SafetyShieldBadge
import com.example.ui.components.formatDuration
import com.example.ui.components.formatTimeShort
import com.example.ui.components.parseHexColorOrDefault
import com.example.ui.navigation.HomeTab
import com.example.ui.theme.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHomeScreen(
    widthSizeClass: WindowWidthSizeClass,
    selectedTab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    activeSpace: SpaceEntity?,
    activeProfile: ProfileEntity?,
    spaces: List<SpaceEntity>,
    conversations: List<ConversationEntity>,
    relationships: List<RelationshipEntity>,
    calls: List<CallSessionEntity>,
    pendingOutboxCount: Int,
    socketState: SocketConnectionState,
    settings: SecuritySettingsState,
    onOpenChat: (conversationId: String, relationshipId: String) -> Unit,
    onOpenContactSettings: (relationshipId: String) -> Unit,
    onOpenQrInvite: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onStartCall: (relationshipId: String, isVideo: Boolean) -> Unit,
    onStartGroupCall: (title: String, relIds: List<String>, isVideo: Boolean) -> Unit,
    onGenerateClickToCall: ((String, String) -> Unit) -> Unit,
    onSwitchSpace: (String) -> Unit,
    onOpenSpacesMgmt: () -> Unit,
    onOpenSecretSpaces: () -> Unit,
    onOpenProfileEditor: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onSetLanguage: (String) -> Unit,
    onLockNow: () -> Unit,
) {
    val isExpanded = widthSizeClass != WindowWidthSizeClass.Compact
    val spaceAccent = parseHexColorOrDefault(activeSpace?.accentHex ?: "#00D2B8", MaterialTheme.colorScheme.primary)
    val relMap = remember(relationships) { relationships.associateBy { it.relationshipId } }

    var showGroupCallDialog by remember { mutableStateOf(false) }
    var clickToCallLink by remember { mutableStateOf<Pair<String, String>?>(null) }

    val tabItems = listOf(
        Triple(HomeTab.CONVERSATIONS, stringResource(R.string.nav_conversations), Icons.AutoMirrored.Filled.Chat to Icons.Outlined.Chat),
        Triple(HomeTab.CONTACTS, stringResource(R.string.nav_contacts), Icons.Filled.Contacts to Icons.Outlined.Contacts),
        Triple(HomeTab.CALLS, stringResource(R.string.nav_calls), Icons.Filled.Call to Icons.Outlined.Call),
        Triple(HomeTab.SPACES, stringResource(R.string.nav_spaces), Icons.Filled.Layers to Icons.Outlined.Layers),
        Triple(HomeTab.SETTINGS, stringResource(R.string.nav_settings), Icons.Filled.Settings to Icons.Outlined.Settings),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(spaceAccent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = activeSpace?.name ?: "Personal",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = when (socketState) {
                                    SocketConnectionState.CONNECTED -> "Relay connected • E2EE"
                                    SocketConnectionState.CONNECTING,
                                    SocketConnectionState.RECONNECTING -> "Syncing relay..."
                                    SocketConnectionState.DISCONNECTED -> if (pendingOutboxCount > 0) "Offline ($pendingOutboxCount queued)" else "Local vault • E2EE"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenQrScanner,
                        modifier = Modifier.testTag("home_scan_qr_button")
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR Invitation")
                    }
                    IconButton(
                        onClick = onOpenQrInvite,
                        modifier = Modifier.testTag("home_my_qr_button")
                    ) {
                        Icon(Icons.Filled.QrCode2, contentDescription = "My QR Invitation")
                    }
                    if (settings.pinEnabled || settings.biometricEnabled) {
                        IconButton(
                            onClick = onLockNow,
                            modifier = Modifier.testTag("home_lock_now_button")
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock Enclave")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!isExpanded) {
                NavigationBar {
                    tabItems.forEach { (tab, label, icons) ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == tab) icons.first else icons.second,
                                    contentDescription = label
                                )
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("nav_tab_${tab.routeKey}")
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (selectedTab) {
                HomeTab.CONVERSATIONS, HomeTab.CONTACTS -> {
                    FloatingActionButton(
                        onClick = onOpenQrInvite,
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("fab_invite_contact")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Invite Contact")
                    }
                }
                HomeTab.CALLS -> {
                    FloatingActionButton(
                        onClick = { showGroupCallDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("fab_group_call")
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = "Start Group Call")
                    }
                }
                else -> Unit
            }
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isExpanded) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    tabItems.forEach { (tab, label, icons) ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == tab) icons.first else icons.second,
                                    contentDescription = label
                                )
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (selectedTab) {
                    HomeTab.CONVERSATIONS -> ConversationsTab(
                        conversations = conversations,
                        relMap = relMap,
                        spaceAccent = spaceAccent,
                        onOpenChat = onOpenChat,
                        onOpenQrInvite = onOpenQrInvite,
                        onOpenQrScanner = onOpenQrScanner
                    )
                    HomeTab.CONTACTS -> ContactsTab(
                        relationships = relationships,
                        spaceAccent = spaceAccent,
                        onOpenChat = { rel -> onOpenChat("conv_${rel.relationshipId}", rel.relationshipId) },
                        onOpenContactSettings = onOpenContactSettings,
                        onCall = onStartCall,
                        onInvite = onOpenQrInvite
                    )
                    HomeTab.CALLS -> CallsTab(
                        calls = calls,
                        relationships = relationships,
                        onStartCall = onStartCall,
                        onOpenGroupCallDialog = { showGroupCallDialog = true },
                        onGenerateClickToCall = {
                            onGenerateClickToCall { tok, url -> clickToCallLink = tok to url }
                        }
                    )
                    HomeTab.SPACES -> SpacesTab(
                        spaces = spaces,
                        activeSpaceId = activeSpace?.spaceId ?: "space_personal",
                        activeProfile = activeProfile,
                        onSwitchSpace = onSwitchSpace,
                        onOpenSpacesMgmt = onOpenSpacesMgmt,
                        onOpenSecretSpaces = onOpenSecretSpaces,
                        onOpenProfileEditor = onOpenProfileEditor
                    )
                    HomeTab.SETTINGS -> SettingsTab(
                        settings = settings,
                        activeProfile = activeProfile,
                        onOpenProfileEditor = onOpenProfileEditor,
                        onOpenPrivacy = onOpenPrivacySettings,
                        onOpenSecurity = onOpenSecuritySettings,
                        onOpenNotifications = onOpenNotificationSettings,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onOpenAbout = onOpenAbout,
                        onSetTheme = onSetTheme,
                        onSetLanguage = onSetLanguage
                    )
                }
            }
        }
    }

    if (showGroupCallDialog) {
        GroupCallCreatorDialog(
            relationships = relationships.filter { it.status == "ACTIVE" },
            onDismiss = { showGroupCallDialog = false },
            onStart = { title, selectedIds, isVideo ->
                showGroupCallDialog = false
                onStartGroupCall(title, selectedIds, isVideo)
            }
        )
    }

    clickToCallLink?.let { (token, url) ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { clickToCallLink = null },
            title = { Text("Click-to-Call Guest Link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Share this temporary expiring link with a browser guest. You must approve incoming guest calls before media connects.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Text("Token: $token (expires in 5m)", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(url))
                    clickToCallLink = null
                }) { Text("Copy URL") }
            },
            dismissButton = {
                TextButton(onClick = { clickToCallLink = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ConversationsTab(
    conversations: List<ConversationEntity>,
    relMap: Map<String, RelationshipEntity>,
    spaceAccent: Color,
    onOpenChat: (String, String) -> Unit,
    onOpenQrInvite: () -> Unit,
    onOpenQrScanner: () -> Unit,
) {
    if (conversations.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EmptySanctuaryView(
                title = "No Conversations Yet",
                subtitle = "Exchange an Enclave QR code or invite code to establish an end-to-end encrypted channel.",
                actionLabel = "Share My Enclave QR",
                onAction = onOpenQrInvite,
                testTag = "empty_share_qr_btn"
            )
            OutlinedButton(onClick = onOpenQrScanner, modifier = Modifier.testTag("empty_scan_qr_btn")) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan Contact QR")
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(conversations, key = { it.conversationId }) { conv ->
            val rel = relMap[conv.relationshipId]
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChat(conv.conversationId, conv.relationshipId) }
                    .testTag("conversation_card_${conv.conversationId}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp)
                ) {
                    EnclaveAvatar(
                        name = rel?.peerDisplayName ?: "Contact",
                        avatarPath = rel?.peerAvatarPath,
                        accentColor = spaceAccent
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = rel?.peerDisplayName ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatTimeShort(conv.lastMessageAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if ((rel?.disappearingTtlSeconds ?: 0L) > 0L) {
                                Icon(
                                    Icons.Filled.Timer,
                                    contentDescription = "Ephemeral active",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = conv.lastMessagePreview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (conv.unreadCount > 0) {
                                Badge { Text(conv.unreadCount.toString()) }
                            }
                        }
                        rel?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Appearing as: ${it.myDisplayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsTab(
    relationships: List<RelationshipEntity>,
    spaceAccent: Color,
    onOpenChat: (RelationshipEntity) -> Unit,
    onOpenContactSettings: (String) -> Unit,
    onCall: (String, Boolean) -> Unit,
    onInvite: () -> Unit,
) {
    if (relationships.isEmpty()) {
        EmptySanctuaryView(
            title = "Private Contacts Only",
            subtitle = "Enclave never reads your phone contacts or exposes a public directory. Invite trusted peers via QR.",
            actionLabel = "Create Invitation",
            onAction = onInvite
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(relationships, key = { it.relationshipId }) { rel ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenContactSettings(rel.relationshipId) }
                    .testTag("contact_card_${rel.relationshipId}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp)
                ) {
                    EnclaveAvatar(
                        name = rel.peerDisplayName,
                        avatarPath = rel.peerAvatarPath,
                        accentColor = spaceAccent
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rel.peerDisplayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "You appear as: ${rel.myDisplayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (rel.status == "BLOCKED") {
                            Text("Blocked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = { onOpenChat(rel) }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Message ${rel.peerDisplayName}")
                    }
                    IconButton(onClick = { onCall(rel.relationshipId, false) }) {
                        Icon(Icons.Filled.Call, contentDescription = "Voice call ${rel.peerDisplayName}")
                    }
                    IconButton(onClick = { onCall(rel.relationshipId, true) }) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Video call ${rel.peerDisplayName}")
                    }
                }
            }
        }
    }
}

@Composable
private fun CallsTab(
    calls: List<CallSessionEntity>,
    relationships: List<RelationshipEntity>,
    onStartCall: (String, Boolean) -> Unit,
    onOpenGroupCallDialog: () -> Unit,
    onGenerateClickToCall: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onGenerateClickToCall,
                    modifier = Modifier.weight(1f).testTag("click_to_call_button")
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Click-to-Call")
                }
                OutlinedButton(
                    onClick = onOpenGroupCallDialog,
                    modifier = Modifier.weight(1f).testTag("group_call_button")
                ) {
                    Icon(Icons.Filled.Groups, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Group Call")
                }
            }
        }

        if (calls.isEmpty()) {
            item {
                EmptySanctuaryView(
                    title = "No Call History",
                    subtitle = "Start an end-to-end encrypted WebRTC voice/video call or generate a temporary Click-to-Call browser link."
                )
            }
        } else {
            items(calls, key = { it.callId }) { call ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = if (call.callType == "VIDEO") Icons.Filled.Videocam else Icons.Filled.Call,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text(call.peerName) },
                        supportingContent = {
                            Text("${call.direction} • ${call.status} • ${formatDuration(call.durationSeconds)} • ${formatTimeShort(call.startedAt)}")
                        },
                        trailingContent = {
                            call.relationshipId?.let { relId ->
                                if (relationships.any { it.relationshipId == relId && it.status == "ACTIVE" }) {
                                    IconButton(onClick = { onStartCall(relId, call.callType == "VIDEO") }) {
                                        Icon(Icons.Filled.Call, contentDescription = "Call again")
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpacesTab(
    spaces: List<SpaceEntity>,
    activeSpaceId: String,
    activeProfile: ProfileEntity?,
    onSwitchSpace: (String) -> Unit,
    onOpenSpacesMgmt: () -> Unit,
    onOpenSecretSpaces: () -> Unit,
    onOpenProfileEditor: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().clickable { onOpenProfileEditor() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    EnclaveAvatar(
                        name = activeProfile?.displayName ?: "Anonymous",
                        avatarPath = activeProfile?.avatarLocalPath,
                        size = 54.dp
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(activeProfile?.displayName ?: "Anonymous", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Enclave Code: ${activeProfile?.enclaveCode ?: "---"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(onClick = onOpenProfileEditor) { Text("Edit") }
                }
            }
        }

        items(spaces, key = { it.spaceId }) { space ->
            val selected = space.spaceId == activeSpaceId
            val accent = parseHexColorOrDefault(space.accentHex, MaterialTheme.colorScheme.primary)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSwitchSpace(space.spaceId) }
                    .testTag("space_card_${space.spaceId}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = space.name + if (space.isHidden) " (Secret)" else "",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (selected) "Active workspace" else "Tap to switch isolated environment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) SafetyShieldBadge("Active")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onOpenSpacesMgmt, modifier = Modifier.weight(1f).testTag("manage_spaces_button")) {
                    Text("Manage Spaces")
                }
                OutlinedButton(onClick = onOpenSecretSpaces, modifier = Modifier.weight(1f).testTag("secret_spaces_button")) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Secret Vault")
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    settings: SecuritySettingsState,
    activeProfile: ProfileEntity?,
    onOpenProfileEditor: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onSetLanguage: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SettingsRow(Icons.Filled.Person, "Space Profile", activeProfile?.displayName ?: "Edit display name & avatar", onOpenProfileEditor)
            SettingsRow(Icons.Filled.PrivacyTip, "Privacy Controls", "Read receipts, typing, preview hiding, screenshots", onOpenPrivacy)
            SettingsRow(Icons.Filled.Security, "Security & Backup", "PIN, Biometrics, Auto-lock, Encrypted migration", onOpenSecurity)
            SettingsRow(Icons.Filled.Notifications, "Notifications", "Message alerts & full-screen call alerts", onOpenNotifications)
            SettingsRow(Icons.Filled.MedicalServices, "System Diagnostics", "Backend relay, WebSocket, STUN/TURN & permissions", onOpenDiagnostics)
            SettingsRow(Icons.Filled.Info, "About & Threat Model", "Cryptographic architecture & security limitations", onOpenAbout)
        }

        item {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemePreference.entries.forEach { pref ->
                            val selected = settings.themePreference == pref
                            if (selected) {
                                Button(onClick = { onSetTheme(pref) }) { Text(pref.name) }
                            } else {
                                OutlinedButton(onClick = { onSetTheme(pref) }) { Text(pref.name) }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Language (RTL / LTR)", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val isEn = settings.languageCode == "en"
                        if (isEn) Button(onClick = { onSetLanguage("en") }) { Text("English") }
                        else OutlinedButton(onClick = { onSetLanguage("en") }) { Text("English") }

                        if (!isEn) Button(onClick = { onSetLanguage("ar") }) { Text("العربية (RTL)") }
                        else OutlinedButton(onClick = { onSetLanguage("ar") }) { Text("العربية (RTL)") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick)
    ) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
            trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) }
        )
    }
}

@Composable
private fun GroupCallCreatorDialog(
    relationships: List<RelationshipEntity>,
    onDismiss: () -> Unit,
    onStart: (title: String, relIds: List<String>, isVideo: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("Private Room") }
    var isVideo by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start Encrypted Group Call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Room Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isVideo, onCheckedChange = { isVideo = it })
                    Text("Enable HD Video")
                }
                Text("Select contacts:", style = MaterialTheme.typography.labelMedium)
                if (relationships.isEmpty()) {
                    Text("No active contacts in this Space yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    relationships.forEach { rel ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected.contains(rel.relationshipId)) selected.remove(rel.relationshipId)
                                    else selected.add(rel.relationshipId)
                                }
                        ) {
                            Checkbox(
                                checked = selected.contains(rel.relationshipId),
                                onCheckedChange = { checked ->
                                    if (checked) selected.add(rel.relationshipId)
                                    else selected.remove(rel.relationshipId)
                                }
                            )
                            Text(rel.peerDisplayName)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(title.ifBlank { "Group Call" }, selected.toList(), isVideo) }) {
                Text("Launch SFU Call")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
