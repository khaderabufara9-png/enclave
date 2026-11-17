package com.example.ui.screens.invite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.crypto.QrCodeEngine
import com.example.core.database.InvitationEntity
import com.example.core.database.SpaceEntity
import com.example.data.ParsedInvitation
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrInvitationScreen(
    activeSpace: SpaceEntity?,
    invitations: List<InvitationEntity>,
    onBack: () -> Unit,
    onCreateInvite: (spaceId: String, onReady: (InvitationEntity, String) -> Unit) -> Unit,
    onRejectInvite: (String) -> Unit,
    onOpenScanner: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val spaceId = activeSpace?.spaceId ?: "space_personal"

    var currentInvite by remember { mutableStateOf<InvitationEntity?>(null) }
    var shareUri by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(spaceId) {
        onCreateInvite(spaceId) { entity, uri ->
            currentInvite = entity
            shareUri = uri
            qrBitmap = QrCodeEngine.generateQrBitmap(uri, 512)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enclave QR Invitation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenScanner) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "Space: ${activeSpace?.name ?: "Personal"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        qrBitmap?.let { bmp ->
                            Surface(
                                color = androidx.compose.ui.graphics.Color.White,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = "Your signed Enclave QR invitation",
                                    modifier = Modifier.size(220.dp).padding(12.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Invite Code: ${currentInvite?.code ?: "..."}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Appears as: ${currentInvite?.offeredDisplayName ?: "Anonymous"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "Connect with me privately on Enclave:\n$shareUri")
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Enclave Invite"))
                                },
                                modifier = Modifier.weight(1f).testTag("invite_share_button")
                            ) {
                                Icon(Icons.Filled.Share, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(shareUri)) },
                                modifier = Modifier.weight(1f).testTag("invite_copy_button")
                            ) {
                                Icon(Icons.Filled.ContentCopy, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Link")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onCreateInvite(spaceId) { entity, uri ->
                                    currentInvite = entity
                                    shareUri = uri
                                    qrBitmap = QrCodeEngine.generateQrBitmap(uri, 512)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Generate Fresh One-Time Code")
                        }
                    }
                }
            }

            if (invitations.isNotEmpty()) {
                item {
                    Text("Invitation Log", style = MaterialTheme.typography.titleMedium)
                }
                items(invitations, key = { it.invitationId }) { inv ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(14.dp).fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${inv.direction} • ${inv.code}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Status: ${inv.status} • Offered: ${inv.offeredDisplayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (inv.status == "PENDING") {
                                IconButton(onClick = { onRejectInvite(inv.invitationId) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Revoke invite")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    activeSpace: SpaceEntity?,
    onBack: () -> Unit,
    onVerifyInput: (String, (ParsedInvitation?) -> Unit) -> Unit,
    onAcceptInvitation: (ParsedInvitation, String, String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var manualCode by remember { mutableStateOf("") }
    var parsedInvite by remember { mutableStateOf<ParsedInvitation?>(null) }
    var customPersonaName by remember { mutableStateOf("") }
    var verifyError by remember { mutableStateOf<String?>(null) }

    val camPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasCameraPermission = it }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) camPermLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan or Enter Enclave Invite") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (hasCameraPermission && parsedInvite == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    CameraQrPreview(
                        onQrDetected = { raw ->
                            if (parsedInvite == null) {
                                onVerifyInput(raw) { res ->
                                    if (res != null && res.signatureValid) parsedInvite = res
                                    else verifyError = "Invalid or tampered QR signature"
                                }
                            }
                        }
                    )
                }
            } else if (!hasCameraPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Camera permission is needed for live QR scanning, or paste an invite code below.")
                        Button(onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant Camera Permission")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                label = { Text("Paste enclave://invite link or 10-char code") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("scanner_manual_input")
            )
            Button(
                onClick = {
                    verifyError = null
                    onVerifyInput(manualCode) { res ->
                        if (res == null) {
                            verifyError = "Invitation not found or malformed."
                        } else if (!res.signatureValid) {
                            verifyError = "ECDSA signature verification failed! Possible tampering."
                        } else {
                            parsedInvite = res
                        }
                    }
                },
                enabled = manualCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("scanner_verify_button")
            ) {
                Text("Verify Cryptographic Invitation")
            }

            verifyError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            parsedInvite?.let { invite ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Signature Verified", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Contact Name: ${invite.peerDisplayName}", style = MaterialTheme.typography.bodyLarge)
                        Text("Invite Code: ${invite.code}", style = MaterialTheme.typography.labelSmall)
                        OutlinedTextField(
                            value = customPersonaName,
                            onValueChange = { customPersonaName = it },
                            label = { Text("How should you appear to ${invite.peerDisplayName}? (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    onAcceptInvitation(
                                        invite,
                                        activeSpace?.spaceId ?: "space_personal",
                                        customPersonaName.takeIf { it.isNotBlank() }
                                    )
                                },
                                modifier = Modifier.weight(1f).testTag("scanner_accept_button")
                            ) {
                                Icon(Icons.Filled.Check, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Accept & Connect")
                            }
                            OutlinedButton(
                                onClick = { parsedInvite = null },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Close, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Reject")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(onQrDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val provider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val decoded = QrCodeEngine.decodeFromYuv(bytes, imageProxy.width, imageProxy.height)
                    if (!decoded.isNullOrBlank()) {
                        onQrDetected(decoded)
                    }
                    imageProxy.close()
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
