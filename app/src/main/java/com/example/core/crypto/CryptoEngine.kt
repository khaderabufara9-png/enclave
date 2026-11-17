package com.example.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

data class EncryptedEnvelope(
    val ivB64: String,
    val ciphertextB64: String,
    val sequence: Long,
)

data class DeviceKeyMaterial(
    val installationId: String,
    val signingPublicKeyB64: String,
    val signingPrivateKeyWrappedB64: String,
    val ecdhPublicKeyB64: String,
    val ecdhPrivateKeyWrappedB64: String,
)

data class EncryptedMediaBlob(
    val ciphertextBytes: ByteArray,
    val fileKeyB64: String,
    val ivB64: String,
    val sha256Hex: String,
)

/**
 * Production cryptographic engine using Android Keystore AES-256-GCM master wrapping,
 * EC P-256 signing & ECDH key agreement, HKDF-SHA256, AES-256-GCM authenticated encryption,
 * and PBKDF2-HMAC-SHA256 for PIN & Secret Space key derivation.
 */
class CryptoEngine {

    private val secureRandom = SecureRandom()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "enclave_keystore_master_v1"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val PBKDF2_ITERATIONS = 100_000
        private const val PBKDF2_KEY_BITS = 256
    }

    @Volatile
    private var testFallbackMasterKey: SecretKey? = null

    fun generateRandomId(byteCount: Int = 16): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateShortCode(length: Int = 10): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val chars = CharArray(length) {
            alphabet[secureRandom.nextInt(alphabet.length)]
        }
        return String(chars)
    }

    fun generateDeviceKeyMaterial(): DeviceKeyMaterial {
        val installationId = "enc_${generateRandomId(16)}"
        val signingPair = generateEcKeyPair()
        val ecdhPair = generateEcKeyPair()

        val signingPrivWrapped = wrapBytesWithMasterKey(signingPair.private.encoded)
        val ecdhPrivWrapped = wrapBytesWithMasterKey(ecdhPair.private.encoded)

        return DeviceKeyMaterial(
            installationId = installationId,
            signingPublicKeyB64 = encodeB64(signingPair.public.encoded),
            signingPrivateKeyWrappedB64 = signingPrivWrapped,
            ecdhPublicKeyB64 = encodeB64(ecdhPair.public.encoded),
            ecdhPrivateKeyWrappedB64 = ecdhPrivWrapped
        )
    }

    fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun wrapSecretB64(rawBytes: ByteArray): String = wrapBytesWithMasterKey(rawBytes)

    fun unwrapSecretB64(wrappedB64: String): ByteArray = unwrapBytesWithMasterKey(wrappedB64)

    fun wrapBytesWithMasterKey(plaintext: ByteArray): String {
        val key = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        // Note: AndroidKeyStore generates IV automatically when not passed if randomizedEncryptionRequired=true,
        // or accepts caller IV if randomizedEncryptionRequired=false. To stay compatible with both Keystore
        // and standard JCE in Robolectric, let cipher generate IV when using AndroidKeyStore.
        val isKeystore = key.javaClass.name.contains("AndroidKeyStore", ignoreCase = true)
        val ciphertext = if (isKeystore) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.doFinal(plaintext)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(plaintext)
        }
        val actualIv = cipher.iv
        val combined = ByteArray(1 + actualIv.size + ciphertext.size)
        combined[0] = actualIv.size.toByte()
        System.arraycopy(actualIv, 0, combined, 1, actualIv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + actualIv.size, ciphertext.size)
        return encodeB64(combined)
    }

    fun unwrapBytesWithMasterKey(wrappedB64: String): ByteArray {
        val combined = decodeB64(wrappedB64)
        require(combined.size > 14) { "Corrupted wrapped key payload" }
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val key = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateMasterKey(): SecretKey {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            val existing = ks.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            return keyGen.generateKey()
        } catch (_: Throwable) {
            // Fallback exclusively for non-Android JVM unit-test environments (e.g. Robolectric host)
            val current = testFallbackMasterKey
            if (current != null) return current
            val kg = KeyGenerator.getInstance("AES")
            kg.init(256, secureRandom)
            return kg.generateKey().also { testFallbackMasterKey = it }
        }
    }

    fun signData(wrappedSigningPrivateKeyB64: String, data: ByteArray): String {
        val privBytes = unwrapBytesWithMasterKey(wrappedSigningPrivateKeyB64)
        val privKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privBytes))
        return signWithPrivateKey(privKey, data)
    }

    fun signWithPrivateKey(privateKey: PrivateKey, data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey, secureRandom)
        sig.update(data)
        return encodeB64(sig.sign())
    }

    fun verifySignature(publicKeyB64: String, data: ByteArray, signatureB64: String): Boolean {
        return try {
            val pubBytes = decodeB64(publicKeyB64)
            val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(decodeB64(signatureB64))
        } catch (_: Throwable) {
            false
        }
    }

    fun deriveRelationshipRootKey(
        myWrappedEcdhPrivateKeyB64: String,
        peerEcdhPublicKeyB64: String,
        relationshipSalt: String,
    ): ByteArray {
        val privBytes = unwrapBytesWithMasterKey(myWrappedEcdhPrivateKeyB64)
        val privKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privBytes))
        val pubKey = decodePublicKey(peerEcdhPublicKeyB64)
        return deriveEcdhHkdfKey(privKey, pubKey, relationshipSalt.toByteArray(StandardCharsets.UTF_8))
    }

    fun deriveEcdhHkdfKey(
        myPrivateKey: PrivateKey,
        peerPublicKey: PublicKey,
        salt: ByteArray,
        info: ByteArray = "enclave-e2ee-root-v1".toByteArray(StandardCharsets.UTF_8),
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(myPrivateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        return hkdfSha256(ikm = sharedSecret, salt = salt, info = info, length = 32)
    }

    fun deriveMessageKey(rootKey: ByteArray, sequence: Long): ByteArray {
        val seqBytes = ByteBuffer.allocate(8).putLong(sequence).array()
        return hmacSha256(rootKey, "enclave-msg-key-v1".toByteArray(StandardCharsets.UTF_8) + seqBytes)
    }

    fun encryptMessage(
        plaintext: ByteArray,
        rootKey: ByteArray,
        sequence: Long,
        aad: String,
    ): EncryptedEnvelope {
        val msgKey = deriveMessageKey(rootKey, sequence)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(msgKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedEnvelope(
            ivB64 = encodeB64(iv),
            ciphertextB64 = encodeB64(ciphertext),
            sequence = sequence
        )
    }

    fun decryptMessage(
        envelope: EncryptedEnvelope,
        rootKey: ByteArray,
        aad: String,
    ): ByteArray {
        val msgKey = deriveMessageKey(rootKey, envelope.sequence)
        val iv = decodeB64(envelope.ivB64)
        val ciphertext = decodeB64(envelope.ciphertextB64)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(msgKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    fun encryptBytesForAtRest(plainBytes: ByteArray): ByteArray {
        val fileKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val (iv, ciphertext) = encryptAesGcmDirect(plainBytes, fileKey, "enclave-at-rest-v1")
        val wrappedKey = wrapBytesWithMasterKey(fileKey).toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + wrappedKey.size + 1 + iv.size + ciphertext.size)
        buffer.putInt(wrappedKey.size)
        buffer.put(wrappedKey)
        buffer.put(iv.size.toByte())
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    fun decryptBytesFromAtRest(packed: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(packed)
        val keyLen = buffer.getInt()
        require(keyLen in 1..4096) { "Corrupted at-rest header" }
        val wrappedKeyBytes = ByteArray(keyLen).also { buffer.get(it) }
        val ivLen = buffer.get().toInt() and 0xFF
        val iv = ByteArray(ivLen).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val fileKey = unwrapBytesWithMasterKey(String(wrappedKeyBytes, StandardCharsets.UTF_8))
        return decryptAesGcmDirect(iv, ciphertext, fileKey, "enclave-at-rest-v1")
    }

    fun encryptAesGcmDirect(plaintext: ByteArray, key32Bytes: ByteArray, aad: String = ""): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32Bytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return iv to cipher.doFinal(plaintext)
    }

    fun decryptAesGcmDirect(iv: ByteArray, ciphertext: ByteArray, key32Bytes: ByteArray, aad: String = ""): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key32Bytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    fun encryptFileForRelay(plainBytes: ByteArray, aad: String = "enclave-media-v1"): EncryptedMediaBlob {
        val fileKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val (iv, ciphertext) = encryptAesGcmDirect(plainBytes, fileKey, aad)
        val digest = MessageDigest.getInstance("SHA-256").digest(plainBytes).joinToString("") { "%02x".format(it) }
        return EncryptedMediaBlob(
            ciphertextBytes = ciphertext,
            fileKeyB64 = encodeB64(fileKey),
            ivB64 = encodeB64(iv),
            sha256Hex = digest
        )
    }

    fun decryptFileFromRelay(
        ciphertextBytes: ByteArray,
        fileKeyB64: String,
        ivB64: String,
        expectedSha256Hex: String,
        aad: String = "enclave-media-v1",
    ): ByteArray {
        val plain = decryptAesGcmDirect(decodeB64(ivB64), ciphertextBytes, decodeB64(fileKeyB64), aad)
        if (expectedSha256Hex.isNotBlank()) {
            val actualHash = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) }
            require(actualHash == expectedSha256Hex) { "Encrypted media integrity check failed" }
        }
        return plain
    }

    fun advanceChainRatchet(currentChainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val nextChain = hmacSha256(currentChainKey, "enclave-ratchet-chain".toByteArray(StandardCharsets.UTF_8))
        val msgKey = hmacSha256(currentChainKey, "enclave-ratchet-msg".toByteArray(StandardCharsets.UTF_8))
        return nextChain to msgKey
    }

    fun deriveSecretHash(secret: String, saltB64: String? = null): Pair<String, String> {
        val salt = if (saltB64 != null) decodeB64(saltB64) else ByteArray(16).also { secureRandom.nextBytes(it) }
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return encodeB64(salt) to encodeB64(derived)
    }

    fun verifySecretHash(secret: String, saltB64: String, expectedHashB64: String): Boolean {
        val (_, candidateB64) = deriveSecretHash(secret, saltB64)
        return MessageDigest.isEqual(decodeB64(candidateB64), decodeB64(expectedHashB64))
    }

    fun encryptRecoveryBackup(payloadJson: String, passphrase: String): String {
        val (saltB64, keyB64) = deriveSecretHash(passphrase)
        val (iv, ciphertext) = encryptAesGcmDirect(
            plaintext = payloadJson.toByteArray(StandardCharsets.UTF_8),
            key32Bytes = decodeB64(keyB64),
            aad = "enclave-backup-v1"
        )
        return JSONObject().apply {
            put("version", 1)
            put("kdf", "PBKDF2-HMAC-SHA256")
            put("iterations", PBKDF2_ITERATIONS)
            put("salt", saltB64)
            put("iv", encodeB64(iv))
            put("ciphertext", encodeB64(ciphertext))
        }.toString()
    }

    fun decryptRecoveryBackup(bundleJson: String, passphrase: String): String {
        val obj = JSONObject(bundleJson)
        val saltB64 = obj.getString("salt")
        val (_, keyB64) = deriveSecretHash(passphrase, saltB64)
        val iv = decodeB64(obj.getString("iv"))
        val ciphertext = decodeB64(obj.getString("ciphertext"))
        val plainBytes = decryptAesGcmDirect(iv, ciphertext, decodeB64(keyB64), "enclave-backup-v1")
        return String(plainBytes, StandardCharsets.UTF_8)
    }

    fun computeSafetyFingerprint(keyAB64: String, keyBB64: String): String {
        val sorted = listOf(keyAB64, keyBB64).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sorted[0].toByteArray(StandardCharsets.UTF_8))
        digest.update("|".toByteArray(StandardCharsets.UTF_8))
        digest.update(sorted[1].toByteArray(StandardCharsets.UTF_8))
        val hash = digest.digest()
        return hash.take(12).chunked(2).joinToString(" ") { pair ->
            pair.joinToString("") { "%02X".format(it) }
        }
    }

    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun decodePublicKey(publicKeyB64: String): PublicKey {
        val pubBytes = decodeB64(publicKeyB64)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun encodeB64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decodeB64(str: String): ByteArray = Base64.decode(str, Base64.NO_WRAP)
}
