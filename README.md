# Enclave — Anonymous Private Messenger & Real-Time Communications

Enclave is an anonymous-by-design, end-to-end encrypted private messenger inspired by the privacy philosophy of twinme and twinme+.

## Highlights
- **Zero Personal Identifiers**: No phone number, email, social login, or contact-book sync.
- **Per-Contact Independent Personas**: Present a different display name, avatar, and privacy rules to every contact.
- **True E2EE Cryptography**: Android Keystore AES-256-GCM wrapping + ECDSA P-256 auth + ECDH P-256 + HKDF-SHA256 + per-message HMAC-SHA256 key derivation + AES-256-GCM.
- **Spaces & Hidden Secret Spaces**: Isolated work/family/personal workspaces plus PBKDF2-protected hidden Secret Spaces.
- **Disappearing Messages & Both-Device Deletion**: Local timers (10s–7d or custom) and signed remote deletion events with explicit acknowledgment tracking.
- **WebRTC Voice, Video & Group Calls**: DTLS-SRTP media encryption, Bluetooth/speaker routing, Picture-in-Picture, Call Transfer to browser, Click-to-Call links, and Diagnostics.

## Documentation
- [Architecture](docs/ARCHITECTURE.md)
- [API Contracts](docs/API.md)
- [Security & Threat Model](docs/SECURITY.md)
- [Setup & Deployment](docs/DEPLOYMENT.md)
