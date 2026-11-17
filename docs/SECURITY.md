# Enclave Security Model & Threat Analysis

## 1. Key Hierarchy
- **Android Keystore Master Key (`enclave_keystore_master_v1`)**: Hardware-backed AES-256-GCM wrapping key that never leaves the TEE/StrongBox where supported.
- **Device Identity Keys**:
  - `ECDSA P-256` signing keypair (used to sign device registrations, invitations, message envelopes, deletion events, and WebSocket auth challenges).
  - `ECDH P-256` key-agreement keypair. Private keys are AES-256-GCM wrapped by the Keystore master key before persistence.
- **Per-Relationship Root & Message Keys**:
  - `rootKey = HKDF-SHA256(ECDH(myPriv, peerPub), salt = inviteCode, info = "enclave-e2ee-root-v1")`
  - `msgKey_n = HMAC-SHA256(rootKey, "enclave-msg-key-v1" || uint64(n))`
  - Payload encrypted with `AES-256-GCM` with 96-bit random IV and `AAD = relationshipId|senderDeviceId|messageId|timestamp`.
- **PIN, Duress PIN & Secret Space Derivation**:
  - `PBKDF2-HMAC-SHA256` (100,000 iterations, 128-bit random salt) with constant-time comparison (`MessageDigest.isEqual`). Plaintext PINs and Secret Space passphrases are never stored.
  - **Emergency Duress Wipe PIN**: Entering the configured Duress PIN at the lock screen immediately wipes all hidden Secret Spaces (`isHidden = 1`) and unlocks cleanly into Personal Space.
- **Per-File Encrypted Media & In-Call Sharing**:
  - Images, videos, voice notes, and in-call shared files are encrypted client-side with a fresh 256-bit `AES-256-GCM` file key + `SHA-256` integrity digest before upload (`/api/v1/media/upload`) or transferred over a DTLS-encrypted WebRTC `DataChannel` (`enclave_call_data`). Locally stored media also maintains Keystore-wrapped at-rest encrypted vault copies (`.encvault`).
  - Live device screen sharing uses Android `MediaProjection` (`FOREGROUND_SERVICE_MEDIA_PROJECTION`) streamed over WebRTC DTLS-SRTP.

## 2. Threat Mitigations
- **Backend Compromise**: Server database stores only public keys and opaque AES-GCM ciphertexts. No server operator can decrypt messages, media blobs, or private keys.
- **Replay Attacks**: Every envelope carries a unique `messageId` and monotonic `sequence`, plus an ECDSA signature over all metadata fields; the server rejects duplicate `messageId`s (`409 Conflict`).
- **Revoked Relationships**: Revoking a relationship sends a signed `REVOKE` command to the server (`403 Forbidden` on subsequent relay attempts) and closes the local channel.
- **Screenshot & App-Switcher Leaks**: Enforces `WindowManager.LayoutParams.FLAG_SECURE` when enabled and renders a Privacy Curtain overlay on `onPause`.

## 3. Explicit Security Limitations
1. **Disappearing Messages**: Local timers & signed deletion events cannot prevent a recipient from photographing the screen with an external device or bypassing `FLAG_SECURE` on a rooted OS.
2. **Metadata Minimization**: While message bodies are E2EE, the relay server temporarily observes network routing metadata (sender/recipient `installationId` and timestamp) while delivering envelopes.
3. **Secret Spaces**: If a user forgets their Secret Space name or passphrase, local recovery is intentionally impossible.
