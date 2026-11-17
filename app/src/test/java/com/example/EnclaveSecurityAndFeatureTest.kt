package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoEngine
import com.example.core.crypto.QrCodeEngine
import com.example.core.database.ConversationEntity
import com.example.core.database.EnclaveDatabase
import com.example.core.database.MessageEntity
import com.example.core.database.PendingOutboxEntity
import com.example.core.database.RelationshipEntity
import com.example.core.database.SpaceEntity
import com.example.core.media.VoiceMediaManager
import com.example.core.network.BackendClientFactory
import com.example.core.network.IncomingSignalEvent
import com.example.core.network.SignalingSocketClient
import com.example.core.notifications.EnclaveNotificationManager
import com.example.core.notifications.PushRegistrationHelper
import com.example.core.prefs.SecurityPreferences
import com.example.core.webrtc.CallSharedItem
import com.example.core.webrtc.WebRtcCallManager
import com.example.data.EnclaveRepository
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnclaveSecurityAndFeatureTest {

    private lateinit var context: Context
    private lateinit var crypto: CryptoEngine
    private lateinit var db: EnclaveDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        crypto = CryptoEngine()
        db = Room.inMemoryDatabaseBuilder(context, EnclaveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `anonymous device keys and e2ee roundtrip prevent unauthorized decryption`() {
        val alice = crypto.generateDeviceKeyMaterial()
        val bob = crypto.generateDeviceKeyMaterial()
        val mallory = crypto.generateDeviceKeyMaterial()

        assertTrue(alice.installationId.startsWith("enc_"))
        assertNotEquals(alice.installationId, bob.installationId)

        val aliceRoot = crypto.deriveRelationshipRootKey(
            alice.ecdhPrivateKeyWrappedB64,
            bob.ecdhPublicKeyB64,
            "INVITE_SALT_1"
        )
        val bobRoot = crypto.deriveRelationshipRootKey(
            bob.ecdhPrivateKeyWrappedB64,
            alice.ecdhPublicKeyB64,
            "INVITE_SALT_1"
        )
        val malloryRoot = crypto.deriveRelationshipRootKey(
            mallory.ecdhPrivateKeyWrappedB64,
            alice.ecdhPublicKeyB64,
            "INVITE_SALT_1"
        )

        val aad = "rel_1|${alice.installationId}|msg_1|1000"
        val envelope = crypto.encryptMessage(
            plaintext = "Top secret Enclave message".toByteArray(),
            rootKey = aliceRoot,
            sequence = 1L,
            aad = aad
        )

        // Bob decrypts cleanly
        val decrypted = crypto.decryptMessage(envelope, bobRoot, aad)
        assertEquals("Top secret Enclave message", String(decrypted))

        // Mallory / Backend cannot decrypt
        var malloryFailed = false
        try {
            crypto.decryptMessage(envelope, malloryRoot, aad)
        } catch (_: AEADBadTagException) {
            malloryFailed = true
        }
        assertTrue("Unauthorized key must fail GCM tag verification", malloryFailed)

        // Tampered AAD fails authentication
        var aadFailed = false
        try {
            crypto.decryptMessage(envelope, bobRoot, "rel_1|wrong_sender|msg_1|1000")
        } catch (_: AEADBadTagException) {
            aadFailed = true
        }
        assertTrue("Tampered AAD must fail GCM authentication", aadFailed)
    }

    @Test
    fun `pbkdf2 pin hashing and encrypted backup migration work securely`() {
        val (salt, hash) = crypto.deriveSecretHash("482910")
        assertTrue(crypto.verifySecretHash("482910", salt, hash))
        assertFalse(crypto.verifySecretHash("000000", salt, hash))

        val secretPayload = """{"installationId":"enc_abc","spaces":["Personal"]}"""
        val encryptedBundle = crypto.encryptRecoveryBackup(secretPayload, "strong_passphrase_99")
        assertFalse(encryptedBundle.contains("enc_abc"))

        val restored = crypto.decryptRecoveryBackup(encryptedBundle, "strong_passphrase_99")
        assertEquals(secretPayload, restored)

        val wrongPassResult = runCatching {
            crypto.decryptRecoveryBackup(encryptedBundle, "wrong_pass")
        }.getOrNull()
        assertNull(wrongPassResult)
    }

    @Test
    fun `qr code generation and bitmap decode roundtrip succeeds`() {
        val uri = "enclave://invite?id=inv_1&code=TESTCODE99&did=enc_123"
        val bmp = QrCodeEngine.generateQrBitmap(uri, 256)
        assertEquals(256, bmp.width)
        assertEquals(256, bmp.height)

        // Reconstruct Android Bitmap from pixel buffer to verify ZXing decoder
        val pixels = IntArray(256 * 256)
        bmp.readPixels(pixels)
        val androidBmp = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
        val decoded = QrCodeEngine.decodeFromBitmap(androidBmp)
        assertEquals(uri, decoded)
    }

    @Test
    fun `isolated spaces, per-contact personas, ephemeral expiration, and offline outbox work in Room`() = runTest {
        // Hidden Secret Space must not appear in observeVisibleSpaces()
        db.spaceDao().upsertSpace(SpaceEntity("space_personal", "Personal", "shield", "#00D2B8", isHidden = false, isDefault = true))
        db.spaceDao().upsertSpace(SpaceEntity("space_secret", "Vault9", "lock", "#6366F1", isHidden = true))

        val visible = db.spaceDao().observeVisibleSpaces().first()
        assertEquals(1, visible.size)
        assertEquals("space_personal", visible[0].spaceId)

        // Per-contact independent personas
        db.relationshipDao().upsertRelationship(
            RelationshipEntity(
                relationshipId = "rel_b",
                spaceId = "space_personal",
                peerDeviceId = "enc_b",
                peerSigningPublicKeyB64 = "spub_b",
                peerEcdhPublicKeyB64 = "epub_b",
                peerDisplayName = "User B",
                myDisplayName = "Ahmed",
                wrappedRootKeyB64 = "key_b",
                safetyFingerprint = "AA BB"
            )
        )
        db.relationshipDao().upsertRelationship(
            RelationshipEntity(
                relationshipId = "rel_c",
                spaceId = "space_personal",
                peerDeviceId = "enc_c",
                peerSigningPublicKeyB64 = "spub_c",
                peerEcdhPublicKeyB64 = "epub_c",
                peerDisplayName = "User C",
                myDisplayName = "Doctor",
                wrappedRootKeyB64 = "key_c",
                safetyFingerprint = "CC DD"
            )
        )

        val relB = db.relationshipDao().getRelationship("rel_b")!!
        val relC = db.relationshipDao().getRelationship("rel_c")!!
        assertEquals("Ahmed", relB.myDisplayName)
        assertEquals("Doctor", relC.myDisplayName)

        // Ephemeral expiration enforcement
        val now = System.currentTimeMillis()
        db.conversationDao().upsertConversation(ConversationEntity("conv_b", "rel_b", "space_personal"))
        db.messageDao().upsertMessage(
            MessageEntity(
                messageId = "msg_expired",
                conversationId = "conv_b",
                relationshipId = "rel_b",
                spaceId = "space_personal",
                senderDeviceId = "enc_a",
                isOutgoing = true,
                type = "TEXT",
                bodyText = "Disappear now",
                expiresAt = now - 5000L
            )
        )
        db.messageDao().upsertMessage(
            MessageEntity(
                messageId = "msg_valid",
                conversationId = "conv_b",
                relationshipId = "rel_b",
                spaceId = "space_personal",
                senderDeviceId = "enc_a",
                isOutgoing = true,
                type = "TEXT",
                bodyText = "Still here",
                expiresAt = now + 60_000L
            )
        )

        val purged = db.messageDao().purgeExpiredMessages(now)
        assertEquals(1, purged)
        val remaining = db.messageDao().observeMessages("conv_b").first()
        assertEquals(1, remaining.size)
        assertEquals("msg_valid", remaining[0].messageId)

        // Offline outbox queue
        db.outboxDao().enqueue(
            PendingOutboxEntity(
                outboxId = "out_1",
                kind = "MESSAGE",
                relationshipId = "rel_b",
                targetDeviceId = "enc_b",
                encryptedPayloadJson = """{"ciphertextB64":"abc"}"""
            )
        )
        assertEquals(1, db.outboxDao().observePendingCount().first())
        assertNotNull(db.outboxDao().getAllPending().firstOrNull())
    }

    @Test
    fun `encrypted media relay, at-rest storage, chain ratchet, duress wipe, and call sharing work`() = runTest {
        // 1. Per-file AES-256-GCM + SHA-256 integrity roundtrip
        val videoBytes = "fake_mp4_header_and_video_frames_123456789".toByteArray()
        val blob = crypto.encryptFileForRelay(videoBytes)
        val decrypted = crypto.decryptFileFromRelay(
            ciphertextBytes = blob.ciphertextBytes,
            fileKeyB64 = blob.fileKeyB64,
            ivB64 = blob.ivB64,
            expectedSha256Hex = blob.sha256Hex
        )
        assertEquals(String(videoBytes), String(decrypted))

        // Tampered SHA-256 must throw
        var integrityFailed = false
        try {
            crypto.decryptFileFromRelay(
                ciphertextBytes = blob.ciphertextBytes,
                fileKeyB64 = blob.fileKeyB64,
                ivB64 = blob.ivB64,
                expectedSha256Hex = "0000deadbeef"
            )
        } catch (_: IllegalArgumentException) {
            integrityFailed = true
        }
        assertTrue("Tampered SHA-256 must fail integrity check", integrityFailed)

        // 2. At-rest Keystore-wrapped file encryption
        val packed = crypto.encryptBytesForAtRest(videoBytes)
        val unpacked = crypto.decryptBytesFromAtRest(packed)
        assertEquals(String(videoBytes), String(unpacked))

        // 3. Forward chain ratchet produces distinct non-repeating keys
        val initialChain = ByteArray(32) { 7 }
        val (chain1, msgKey1) = crypto.advanceChainRatchet(initialChain)
        val (chain2, msgKey2) = crypto.advanceChainRatchet(chain1)
        assertFalse(msgKey1.contentEquals(msgKey2))
        assertFalse(chain1.contentEquals(chain2))

        // 4. Duress wipe removes only hidden Secret Spaces
        db.spaceDao().upsertSpace(SpaceEntity("space_personal", "Personal", "shield", "#00D2B8", isHidden = false, isDefault = true))
        db.spaceDao().upsertSpace(SpaceEntity("space_hidden_1", "BlackVault", "lock", "#EF4444", isHidden = true))
        assertEquals(1, db.spaceDao().getHiddenSpaces().size)
        db.spaceDao().deleteHiddenSpaces()
        assertEquals(0, db.spaceDao().getHiddenSpaces().size)
        assertEquals(1, db.spaceDao().observeVisibleSpaces().first().size)

        // 5. Call screen-sharing & shared file/video state transitions
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val okClient = BackendClientFactory.createOkHttpClient()
        val sigClient = SignalingSocketClient(okClient, scope)
        val callManager = WebRtcCallManager(context, sigClient, scope)

        callManager.handleIncomingScreenShareState(true)
        assertTrue(callManager.callState.value.isPeerScreenSharing)
        callManager.handleIncomingScreenShareState(false)
        assertFalse(callManager.callState.value.isPeerScreenSharing)

        callManager.addSharedCallItem(
            CallSharedItem(
                itemId = "item_vid_1",
                senderName = "Alice",
                title = "demo_clip.mp4",
                mimeType = "video/mp4",
                localPath = "/tmp/demo_clip.mp4",
                sizeBytes = 2048L,
                isOutgoing = false
            )
        )
        assertEquals(1, callManager.callState.value.sharedItems.size)
        assertEquals("demo_clip.mp4", callManager.callState.value.sharedItems[0].title)
    }

    @Test
    fun `server config sharing, safe fcm fallback, and call signal parsing work cleanly`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val okClient = BackendClientFactory.createOkHttpClient()
        val sigClient = SignalingSocketClient(okClient, scope)
        val prefs = SecurityPreferences(context)
        val callManager = WebRtcCallManager(context, sigClient, scope)
        val mediaManager = VoiceMediaManager(context, crypto)
        val notifManager = EnclaveNotificationManager(context)

        val repo = EnclaveRepository(
            context = context,
            db = db,
            crypto = crypto,
            prefs = prefs,
            okHttpClient = okClient,
            socketClient = sigClient,
            callManager = callManager,
            mediaManager = mediaManager,
            notificationManager = notifManager,
            scope = scope
        )

        // Safe when google-services.json is absent
        assertFalse(PushRegistrationHelper.isFirebaseConfigured(context))

        // Build & apply server config link (like sharing to a friend in another country)
        prefs.setNetworkEndpoints(
            backendUrl = "https://enclave.example.org",
            stunUrl = "stun:stun.l.google.com:19302",
            turnUrl = "turn:enclave.example.org:3478"
        )
        val shared = repo.buildServerShareLink()
        assertTrue(shared.startsWith("enclave://server?"))

        // Reset to local default, then apply shared link
        prefs.setNetworkEndpoints("http://10.0.2.2:8080", "stun:stun.l.google.com:19302", "")
        assertTrue(repo.applyServerConfigLink(shared))
        val updated = prefs.stateFlow.first()
        assertEquals("https://enclave.example.org", updated.backendUrl)
        assertEquals("turn:enclave.example.org:3478", updated.turnUrl)

        // Reject malformed server config input
        assertFalse(repo.applyServerConfigLink("not_a_url"))

        // Verify pong heartbeat & call_offer signal parsing
        assertNull(sigClient.parseSignalJson(JSONObject("""{"type":"pong","ts":123}""")))
        val callEvent = sigClient.parseSignalJson(
            JSONObject("""{"type":"call_offer","callId":"c_1","fromDeviceId":"enc_a","isVideo":true}""")
        )
        assertTrue(callEvent is IncomingSignalEvent.CallOffer)
    }
}
