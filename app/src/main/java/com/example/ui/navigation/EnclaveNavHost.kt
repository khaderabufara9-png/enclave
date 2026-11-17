package com.example.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.webrtc.ActiveCallPhase
import com.example.ui.EnclaveViewModel
import com.example.ui.components.PrivacyCurtainOverlay
import com.example.ui.components.parseHexColorOrDefault
import com.example.ui.screens.call.AudioCallScreen
import com.example.ui.screens.call.GroupCallScreen
import com.example.ui.screens.call.IncomingCallScreen
import com.example.ui.screens.call.OutgoingCallScreen
import com.example.ui.screens.call.VideoCallScreen
import com.example.ui.screens.chat.ChatScreen
import com.example.ui.screens.home.MainHomeScreen
import com.example.ui.screens.invite.QrInvitationScreen
import com.example.ui.screens.invite.QrScannerScreen
import com.example.ui.screens.lock.AppLockScreen
import com.example.ui.screens.onboarding.OnboardingPhilosophyScreen
import com.example.ui.screens.onboarding.ProfileCreationScreen
import com.example.ui.screens.onboarding.SplashScreen
import com.example.ui.screens.onboarding.WelcomeScreen
import com.example.ui.screens.settings.AboutSupportScreen
import com.example.ui.screens.settings.ContactSettingsScreen
import com.example.ui.screens.settings.DiagnosticsScreen
import com.example.ui.screens.settings.NotificationSettingsScreen
import com.example.ui.screens.settings.PrivacySettingsScreen
import com.example.ui.screens.settings.ProfileEditorScreen
import com.example.ui.screens.settings.SecuritySettingsScreen
import com.example.ui.screens.spaces.SecretSpaceAccessScreen
import com.example.ui.screens.spaces.SpacesManagementScreen
import com.example.ui.theme.EnclaveTheme

@Composable
fun EnclaveRootApp(
    viewModel: EnclaveViewModel,
    widthSizeClass: WindowWidthSizeClass,
    isAppInRecentsBlur: Boolean,
    deepLinkUri: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()
    val activeSpace by viewModel.activeSpace.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val spaces by viewModel.visibleSpaces.collectAsStateWithLifecycle()
    val relationships by viewModel.relationships.collectAsStateWithLifecycle()
    val invitations by viewModel.invitations.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val calls by viewModel.calls.collectAsStateWithLifecycle()
    val pendingOutboxCount by viewModel.pendingOutboxCount.collectAsStateWithLifecycle()
    val socketState by viewModel.socketState.collectAsStateWithLifecycle()
    val activeCallState by viewModel.activeCallState.collectAsStateWithLifecycle()
    val typingStates by viewModel.typingStates.collectAsStateWithLifecycle()
    val securityAudits by viewModel.securityAudits.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val isRunningDiag by viewModel.isRunningDiagnostics.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusBanner) {
        statusBanner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBanner()
        }
    }

    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.CONVERSATIONS) }

    // Connect WebSocket once onboarded
    LaunchedEffect(settings.isOnboarded, settings.installationId) {
        if (settings.isOnboarded && settings.installationId.isNotBlank()) {
            viewModel.repo.connectRealtimeSocket()
        }
    }

    // Route incoming / outgoing active calls automatically
    LaunchedEffect(activeCallState.phase, activeCallState.isVideo, activeCallState.isGroupCall) {
        when (activeCallState.phase) {
            ActiveCallPhase.INCOMING_RINGING -> navController.navigate(EnclaveRoute.IncomingCall)
            ActiveCallPhase.OUTGOING_RINGING -> navController.navigate(EnclaveRoute.OutgoingCall)
            ActiveCallPhase.ACTIVE, ActiveCallPhase.CONNECTING, ActiveCallPhase.RECONNECTING -> {
                val target: EnclaveRoute = when {
                    activeCallState.isGroupCall -> EnclaveRoute.GroupCall
                    activeCallState.isVideo -> EnclaveRoute.VideoCall
                    else -> EnclaveRoute.AudioCall
                }
                navController.navigate(target) {
                    popUpTo(EnclaveRoute.MainHome) { inclusive = false }
                    launchSingleTop = true
                }
            }
            else -> Unit
        }
    }

    // Handle deep link URIs (invite or server config)
    LaunchedEffect(deepLinkUri, settings.isOnboarded, isLocked) {
        val uri = deepLinkUri
        if (!uri.isNullOrBlank()) {
            if (uri.startsWith("enclave://server", ignoreCase = true)) {
                viewModel.applyServerConfigLink(uri)
                onDeepLinkConsumed()
            } else if (settings.isOnboarded && !isLocked) {
                navController.navigate(EnclaveRoute.QrScanner)
                onDeepLinkConsumed()
            }
        }
    }

    val accentColor = activeSpace?.let { parseHexColorOrDefault(it.accentHex, androidx.compose.ui.graphics.Color(0xFF00D2B8)) }

    EnclaveTheme(
        themePreference = settings.themePreference,
        accentOverride = accentColor
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = EnclaveRoute.Splash,
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                ) {
                    composable<EnclaveRoute.Splash> {
                        SplashScreen(
                            isOnboarded = settings.isOnboarded,
                            onNavigateHome = {
                                navController.navigate(EnclaveRoute.MainHome) {
                                    popUpTo(EnclaveRoute.Splash) { inclusive = true }
                                }
                            },
                            onNavigateWelcome = {
                                navController.navigate(EnclaveRoute.Welcome) {
                                    popUpTo(EnclaveRoute.Splash) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable<EnclaveRoute.Welcome> {
                        WelcomeScreen(
                            currentBackendUrl = settings.backendUrl,
                            onConfigureServer = { raw, cb -> viewModel.applyServerConfigLink(raw, cb) },
                            onGetStarted = { navController.navigate(EnclaveRoute.OnboardingPhilosophy) },
                            onRestoreBackup = { bundle, pass, cb -> viewModel.restoreBackup(bundle, pass, cb) },
                            onRestoredSuccess = {
                                navController.navigate(EnclaveRoute.MainHome) {
                                    popUpTo(EnclaveRoute.Welcome) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable<EnclaveRoute.OnboardingPhilosophy> {
                        OnboardingPhilosophyScreen(
                            onBack = { navController.popBackStack() },
                            onContinue = { navController.navigate(EnclaveRoute.ProfileCreation) }
                        )
                    }

                    composable<EnclaveRoute.ProfileCreation> {
                        ProfileCreationScreen(
                            onBack = { navController.popBackStack() },
                            onCreateIdentity = { name, bio, avatar, pin, bioAuth, notif, callNotif ->
                                viewModel.completeOnboarding(name, bio, avatar, pin, bioAuth, notif, callNotif) {
                                    navController.navigate(EnclaveRoute.MainHome) {
                                        popUpTo(EnclaveRoute.Welcome) { inclusive = true }
                                    }
                                }
                            }
                        )
                    }

                    composable<EnclaveRoute.MainHome> {
                        MainHomeScreen(
                            widthSizeClass = widthSizeClass,
                            selectedTab = selectedTab,
                            onSelectTab = { selectedTab = it },
                            activeSpace = activeSpace,
                            activeProfile = activeProfile,
                            spaces = spaces,
                            conversations = conversations,
                            relationships = relationships,
                            calls = calls,
                            pendingOutboxCount = pendingOutboxCount,
                            socketState = socketState,
                            settings = settings,
                            onOpenChat = { convId, relId ->
                                navController.navigate(EnclaveRoute.Chat(convId, relId))
                            },
                            onOpenContactSettings = { relId ->
                                navController.navigate(EnclaveRoute.ContactSettings(relId))
                            },
                            onOpenQrInvite = { navController.navigate(EnclaveRoute.QrInvitation) },
                            onOpenQrScanner = { navController.navigate(EnclaveRoute.QrScanner) },
                            onStartCall = { relId, isVideo ->
                                viewModel.initiateCall(relId, isVideo) {}
                            },
                            onStartGroupCall = { title, relIds, isVideo ->
                                viewModel.startGroupCall(
                                    activeSpace?.spaceId ?: "space_personal",
                                    title,
                                    relIds,
                                    isVideo
                                ) {}
                            },
                            onGenerateClickToCall = { cb ->
                                viewModel.createTemporaryToken("CLICK_TO_CALL", null, cb)
                            },
                            onSwitchSpace = { viewModel.switchSpace(it) },
                            onOpenSpacesMgmt = { navController.navigate(EnclaveRoute.SpacesManagement) },
                            onOpenSecretSpaces = { navController.navigate(EnclaveRoute.SecretSpaceAccess) },
                            onOpenProfileEditor = { navController.navigate(EnclaveRoute.ProfileEditor) },
                            onOpenPrivacySettings = { navController.navigate(EnclaveRoute.PrivacySettings) },
                            onOpenSecuritySettings = { navController.navigate(EnclaveRoute.SecuritySettings) },
                            onOpenNotificationSettings = { navController.navigate(EnclaveRoute.NotificationSettings) },
                            onOpenDiagnostics = { navController.navigate(EnclaveRoute.Diagnostics) },
                            onOpenAbout = { navController.navigate(EnclaveRoute.AboutSupport) },
                            onSetTheme = { viewModel.setThemePreference(it) },
                            onSetLanguage = { viewModel.setLanguageCode(it) },
                            onLockNow = { viewModel.lockNow() }
                        )
                    }

                    composable<EnclaveRoute.Chat> { entry ->
                        val args = entry.toRoute<EnclaveRoute.Chat>()
                        val rel by viewModel.observeRelationship(args.relationshipId).collectAsStateWithLifecycle(null)
                        val msgs by viewModel.observeMessages(args.conversationId).collectAsStateWithLifecycle(emptyList())
                        ChatScreen(
                            relationship = rel,
                            messages = msgs,
                            allRelationships = relationships,
                            isPeerTyping = typingStates[args.relationshipId] == true,
                            mediaManager = viewModel.repo.mediaManager,
                            onBack = { navController.popBackStack() },
                            onOpenContactSettings = { navController.navigate(EnclaveRoute.ContactSettings(args.relationshipId)) },
                            onMarkRead = { viewModel.markConversationRead(args.conversationId) },
                            onStartCall = { isVideo -> viewModel.initiateCall(args.relationshipId, isVideo) {} },
                            onSendMessage = { type, body, path, mime, name, size, dur, lat, lon, replyId, replyPrev ->
                                viewModel.sendMessage(
                                    args.relationshipId, type, body, path, mime, name, size, dur, lat, lon, replyId, replyPrev
                                )
                            },
                            onEditMessage = { id, body -> viewModel.editMessage(id, body) },
                            onToggleReaction = { id, emoji -> viewModel.toggleReaction(id, emoji) },
                            onDeleteLocal = { id -> viewModel.deleteMessageLocally(id) },
                            onDeleteForBoth = { id -> viewModel.deleteMessageForBoth(id) },
                            onClearConversationForBoth = { viewModel.clearConversationForBoth(args.conversationId) },
                            onSetDisappearingTtl = { ttl ->
                                viewModel.updateRelationshipControls(args.relationshipId, disappearingTtlSeconds = ttl)
                            },
                            onForwardMessage = { targetRelId, msg ->
                                viewModel.forwardMessage(targetRelId, msg)
                            }
                        )
                    }

                    composable<EnclaveRoute.QrInvitation> {
                        QrInvitationScreen(
                            activeSpace = activeSpace,
                            invitations = invitations,
                            onBack = { navController.popBackStack() },
                            onCreateInvite = { spaceId, cb -> viewModel.createInvite(spaceId, cb) },
                            onRejectInvite = { viewModel.rejectInvitation(it) },
                            onOpenScanner = { navController.navigate(EnclaveRoute.QrScanner) }
                        )
                    }

                    composable<EnclaveRoute.QrScanner> {
                        QrScannerScreen(
                            activeSpace = activeSpace,
                            onBack = { navController.popBackStack() },
                            onVerifyInput = { raw, cb -> viewModel.verifyInviteInput(raw, cb) },
                            onAcceptInvitation = { parsed, spaceId, customName ->
                                viewModel.acceptInvitation(parsed, spaceId, customName) { rel ->
                                    navController.navigate(EnclaveRoute.Chat("conv_${rel.relationshipId}", rel.relationshipId)) {
                                        popUpTo(EnclaveRoute.MainHome)
                                    }
                                }
                            }
                        )
                    }

                    composable<EnclaveRoute.IncomingCall> {
                        IncomingCallScreen(
                            callState = activeCallState,
                            onAnswer = { viewModel.answerIncomingCall() },
                            onReject = {
                                viewModel.hangupCall(rejected = true)
                                navController.popBackStack()
                            }
                        )
                    }

                    composable<EnclaveRoute.OutgoingCall> {
                        OutgoingCallScreen(
                            callState = activeCallState,
                            onCancel = {
                                viewModel.hangupCall()
                                navController.popBackStack()
                            }
                        )
                    }

                    composable<EnclaveRoute.AudioCall> {
                        AudioCallScreen(
                            callState = activeCallState,
                            callManager = viewModel.repo.callManager,
                            onRequestTransferToken = { cid, cb -> viewModel.createTemporaryToken("CALL_TRANSFER", cid, cb) },
                            onShareFileOrVideo = { uri -> viewModel.shareCallAttachment(uri) },
                            onEndCall = {
                                viewModel.hangupCall()
                                navController.popBackStack(EnclaveRoute.MainHome, inclusive = false)
                            }
                        )
                    }

                    composable<EnclaveRoute.VideoCall> {
                        VideoCallScreen(
                            callState = activeCallState,
                            callManager = viewModel.repo.callManager,
                            onRequestTransferToken = { cid, cb -> viewModel.createTemporaryToken("CALL_TRANSFER", cid, cb) },
                            onShareFileOrVideo = { uri -> viewModel.shareCallAttachment(uri) },
                            onEndCall = {
                                viewModel.hangupCall()
                                navController.popBackStack(EnclaveRoute.MainHome, inclusive = false)
                            }
                        )
                    }

                    composable<EnclaveRoute.GroupCall> {
                        GroupCallScreen(
                            callState = activeCallState,
                            callManager = viewModel.repo.callManager,
                            onShareFileOrVideo = { uri -> viewModel.shareCallAttachment(uri) },
                            onEndCall = {
                                viewModel.hangupCall()
                                navController.popBackStack(EnclaveRoute.MainHome, inclusive = false)
                            }
                        )
                    }

                    composable<EnclaveRoute.SpacesManagement> {
                        SpacesManagementScreen(
                            spaces = spaces,
                            activeSpaceId = activeSpace?.spaceId ?: "space_personal",
                            onBack = { navController.popBackStack() },
                            onSwitchSpace = { viewModel.switchSpace(it) },
                            onCreateSpace = { name, hex, hidden, pass, profName, onDone ->
                                viewModel.createSpace(name, hex, hidden, pass, profName, onDone)
                            },
                            onLockSecretSpaces = { viewModel.lockSecretSpaces() },
                            onOpenSecretAccess = { navController.navigate(EnclaveRoute.SecretSpaceAccess) }
                        )
                    }

                    composable<EnclaveRoute.SecretSpaceAccess> {
                        SecretSpaceAccessScreen(
                            onBack = { navController.popBackStack() },
                            onUnlock = { name, pass, cb -> viewModel.unlockSecretSpace(name, pass, cb) },
                            onUnlockedSuccess = {
                                selectedTab = HomeTab.CONVERSATIONS
                                navController.popBackStack(EnclaveRoute.MainHome, inclusive = false)
                            }
                        )
                    }

                    composable<EnclaveRoute.ProfileEditor> {
                        ProfileEditorScreen(
                            profile = activeProfile,
                            onBack = { navController.popBackStack() },
                            onSave = { name, bio, uri ->
                                viewModel.updateProfile(activeSpace?.spaceId ?: "space_personal", name, bio, uri)
                            }
                        )
                    }

                    composable<EnclaveRoute.ContactSettings> { entry ->
                        val args = entry.toRoute<EnclaveRoute.ContactSettings>()
                        val rel by viewModel.observeRelationship(args.relationshipId).collectAsStateWithLifecycle(null)
                        ContactSettingsScreen(
                            relationship = rel,
                            onBack = { navController.popBackStack() },
                            onUpdatePersona = { name, bio, uri -> viewModel.updateRelationshipPersona(args.relationshipId, name, bio, uri) },
                            onUpdateControls = { msg, call, notif, ttl, status ->
                                viewModel.updateRelationshipControls(args.relationshipId, msg, call, notif, ttl, status)
                            },
                            onClearConversation = { viewModel.clearConversationForBoth("conv_${args.relationshipId}") },
                            onRevokeRelationship = { wipe ->
                                viewModel.revokeRelationship(args.relationshipId, wipe) {
                                    navController.popBackStack(EnclaveRoute.MainHome, inclusive = false)
                                }
                            }
                        )
                    }

                    composable<EnclaveRoute.PrivacySettings> {
                        PrivacySettingsScreen(
                            settings = settings,
                            onBack = { navController.popBackStack() },
                            onUpdate = { hide, shot, rr, typ -> viewModel.setPrivacyPrefs(hide, shot, rr, typ) }
                        )
                    }

                    composable<EnclaveRoute.SecuritySettings> {
                        SecuritySettingsScreen(
                            settings = settings,
                            audits = securityAudits,
                            onBack = { navController.popBackStack() },
                            onSavePin = { viewModel.savePin(it) },
                            onDisablePin = { viewModel.disablePin() },
                            onSaveDuressPin = { viewModel.saveDuressPin(it) },
                            onDisableDuressPin = { viewModel.disableDuressPin() },
                            onSetBiometric = { viewModel.setBiometric(it) },
                            onSetAutoLock = { viewModel.setAutoLockSeconds(it) },
                            onExportBackup = { pass, cb -> viewModel.exportBackup(pass, cb) }
                        )
                    }

                    composable<EnclaveRoute.NotificationSettings> {
                        NotificationSettingsScreen(
                            settings = settings,
                            onBack = { navController.popBackStack() },
                            onUpdate = { msg, call -> viewModel.setNotificationPrefs(msg, call) },
                            onToggleAlwaysOnRelay = { viewModel.setAlwaysOnBackgroundRelay(it) }
                        )
                    }

                    composable<EnclaveRoute.Diagnostics> {
                        DiagnosticsScreen(
                            settings = settings,
                            items = diagnostics,
                            isRunning = isRunningDiag,
                            onBack = { navController.popBackStack() },
                            onRunDiagnostics = { viewModel.runDiagnostics() },
                            onSaveEndpoints = { be, st, tu -> viewModel.saveNetworkEndpoints(be, st, tu) },
                            onShareServerLink = { cb -> viewModel.getServerShareLink(cb) },
                            onApplyServerLink = { raw -> viewModel.applyServerConfigLink(raw) }
                        )
                    }

                    composable<EnclaveRoute.AboutSupport> {
                        AboutSupportScreen(onBack = { navController.popBackStack() })
                    }
                }
            }

            if (isLocked && settings.isOnboarded) {
                AppLockScreen(
                    pinEnabled = settings.pinEnabled,
                    biometricEnabled = settings.biometricEnabled,
                    onVerifyPin = { viewModel.unlockWithPin(it) },
                    onBiometricAuthenticated = { viewModel.unlockWithBiometricSuccess() }
                )
            } else if (isAppInRecentsBlur && settings.hideMessagePreviews) {
                PrivacyCurtainOverlay()
            }
        }
    }
}
