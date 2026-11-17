# Enclave System Architecture

## 1. High-Level Overview
Enclave is an anonymous-by-design, end-to-end encrypted messaging and real-time communications platform. It strictly avoids phone numbers, emails, social logins, and address-book discovery.

## 2. Four-Plane Communication Model
1. **Direct Peer-to-Peer Media (WebRTC DTLS-SRTP)**:
   - 1:1 voice and video streams flow directly peer-to-peer over ICE/STUN whenever NAT traversal permits, or via Coturn TURN relay when symmetric NAT blocks direct UDP.
   - Media packets are encrypted end-to-end via DTLS-SRTP.
   - Group calls scale via SFU topology with end-to-end media encryption (SFrame / Insertable Streams key framing).
2. **Real-Time Signaling Plane (WebSockets + Redis)**:
   - Delivers WebRTC SDP offers/answers, ICE candidates, typing indicators, delivery/read receipts, and live encrypted message envelopes when recipients are online.
3. **Encrypted Store-and-Forward Relay (PostgreSQL)**:
   - When a recipient is offline, encrypted envelopes (`ivB64`, `ciphertextB64`, `sequence`, `signatureB64`) are queued in `encrypted_messages` with a strict TTL (`ENVELOPE_TTL_HOURS`) and immediately deleted once the recipient fetches and sends a signed `POST /api/v1/messages/ack`.
4. **Zero-Knowledge Push Wake-Up (FCM)**:
   - Push notifications carry **only** `{ eventType: "MESSAGE_PENDING" | "INCOMING_CALL" }`. No sender name, conversation ID, or message text ever transits FCM/APNs.

## 3. Client Architecture (Android)
- **Clean Architecture + MVVM**: `core/crypto`, `core/database` (Room + Migrations), `core/network` (Retrofit + OkHttp + WS), `core/prefs` (DataStore), `core/webrtc` (WebRTC SDK + Foreground Service), `core/workers` (WorkManager), `data` (`EnclaveRepository`), and `ui` (Jetpack Compose Material 3).
