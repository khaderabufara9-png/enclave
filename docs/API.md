# Enclave Backend & WebSocket API Contracts

## REST Endpoints (`/api/v1`)
- `GET /api/v1/health` — Returns PostgreSQL, Redis, STUN, and TURN status.
- `POST /api/v1/devices/register` — Registers anonymous `installationId`, `signingPublicKeyB64`, `ecdhPublicKeyB64` verified by ECDSA signature.
- `POST /api/v1/invitations` — Publishes signed Space invitation (`code`, `expiresAtMs`, `signatureB64`).
- `GET /api/v1/invitations/:code` — Looks up active invitation by short code.
- `POST /api/v1/invitations/:code/accept` — Establishes relationship & pushes `relationship_accepted` to inviter via WebSocket.
- `POST /api/v1/messages/relay` — Verifies ECDSA signature, checks replay & relationship status, delivers via WS or queues ciphertext in `encrypted_messages`.
- `GET /api/v1/messages/pending/:deviceId` — Signed fetch of queued offline encrypted envelopes.
- `POST /api/v1/messages/ack` — Signed acknowledgment that immediately purges server ciphertext.
- `POST /api/v1/deletions` — Relays signed `MESSAGE` / `CONVERSATION` / `RELATIONSHIP_RESET` deletion tombstones.
- `POST /api/v1/relationships/action` — Signed `REVOKE`, `BLOCK`, or `UNBLOCK`.
- `POST /api/v1/tokens/temporary` — Creates expiring `CALL_TRANSFER` or `CLICK_TO_CALL` token (`GET /call/:token`).
- `POST /api/v1/media/upload` — ECDSA-signed upload of per-file AES-256-GCM encrypted media/video/document ciphertext blobs (`encrypted_media` table, TTL auto-purged).
- `GET /api/v1/media/:mediaId` — ECDSA-authenticated fetch of opaque encrypted media ciphertext.
- `GET /api/v1/rtc/ice-config/:deviceId` — Issues STUN URL + RFC 5766 HMAC-SHA1 TURN credentials.

## WebSocket Signaling (`/ws`)
- Client sends `{ type: "auth", deviceId, timestampMs, signatureB64 }` upon connect.
- Relays `call_offer` (including dynamic renegotiation), `call_answer`, `ice_candidate`, `call_hangup`, `typing`, `receipt`, `edit_message`, `reaction_update`, `deletion_ack`, `group_call_*`, `guest_click_to_call`, `media_sync`, `screen_share_state`, and `call_shared_item` (supports both `toDeviceId` and group `toDeviceIds`).
