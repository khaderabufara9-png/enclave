-- Enclave Zero-Knowledge PostgreSQL Schema (Migration 001)
-- NOTE: Private encryption keys and plaintext messages are NEVER stored here.

CREATE TABLE IF NOT EXISTS schema_migrations (
  version INT PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS device_identities (
  installation_id VARCHAR(128) PRIMARY KEY,
  platform VARCHAR(32) NOT NULL DEFAULT 'android',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public_keys (
  key_id BIGSERIAL PRIMARY KEY,
  installation_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  signing_public_key_b64 TEXT NOT NULL,
  ecdh_public_key_b64 TEXT NOT NULL,
  rotated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (installation_id)
);

-- Encrypted opaque profile metadata only; no global searchable directory
CREATE TABLE IF NOT EXISTS profiles (
  profile_id VARCHAR(128) PRIMARY KEY,
  installation_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  encrypted_profile_blob TEXT NOT NULL DEFAULT '',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS spaces (
  space_id VARCHAR(128) PRIMARY KEY,
  installation_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS invitations (
  invitation_id VARCHAR(128) PRIMARY KEY,
  code VARCHAR(32) NOT NULL UNIQUE,
  inviter_device_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  inviter_signing_public_key_b64 TEXT NOT NULL,
  inviter_ecdh_public_key_b64 TEXT NOT NULL,
  offered_display_name VARCHAR(128) NOT NULL,
  signature_b64 TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invitations_expires ON invitations(expires_at);

CREATE TABLE IF NOT EXISTS relationships (
  relationship_id VARCHAR(128) PRIMARY KEY,
  device_a_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  device_b_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, BLOCKED, REVOKED
  revoked_by VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_relationships_devices ON relationships(device_a_id, device_b_id);

CREATE TABLE IF NOT EXISTS conversations (
  conversation_id VARCHAR(128) PRIMARY KEY,
  relationship_id VARCHAR(128) NOT NULL UNIQUE REFERENCES relationships(relationship_id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Store-and-forward encrypted relay queue ONLY; purged on ACK or expiration
CREATE TABLE IF NOT EXISTS encrypted_messages (
  message_id VARCHAR(128) PRIMARY KEY,
  relationship_id VARCHAR(128) NOT NULL REFERENCES relationships(relationship_id) ON DELETE CASCADE,
  sender_device_id VARCHAR(128) NOT NULL,
  recipient_device_id VARCHAR(128) NOT NULL,
  iv_b64 TEXT NOT NULL,
  ciphertext_b64 TEXT NOT NULL,
  sequence BIGINT NOT NULL,
  ttl_seconds BIGINT NOT NULL DEFAULT 0,
  signature_b64 TEXT NOT NULL,
  sent_at_ms BIGINT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_encrypted_messages_recipient ON encrypted_messages(recipient_device_id, created_at);
CREATE INDEX IF NOT EXISTS idx_encrypted_messages_expires ON encrypted_messages(expires_at);

CREATE TABLE IF NOT EXISTS delivery_receipts (
  receipt_id BIGSERIAL PRIMARY KEY,
  message_id VARCHAR(128) NOT NULL,
  recipient_device_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS deletion_events (
  event_id VARCHAR(128) PRIMARY KEY,
  relationship_id VARCHAR(128) NOT NULL REFERENCES relationships(relationship_id) ON DELETE CASCADE,
  sender_device_id VARCHAR(128) NOT NULL,
  recipient_device_id VARCHAR(128) NOT NULL,
  scope VARCHAR(32) NOT NULL, -- MESSAGE, CONVERSATION, RELATIONSHIP_RESET
  target_message_id VARCHAR(128),
  signature_b64 TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ACK',
  created_at_ms BIGINT NOT NULL,
  acknowledged_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_deletion_events_recipient ON deletion_events(recipient_device_id, status);

CREATE TABLE IF NOT EXISTS call_sessions (
  call_id VARCHAR(128) PRIMARY KEY,
  relationship_id VARCHAR(128),
  caller_device_id VARCHAR(128) NOT NULL,
  callee_device_id VARCHAR(128) NOT NULL,
  call_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ended_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS group_calls (
  group_call_id VARCHAR(128) PRIMARY KEY,
  host_device_id VARCHAR(128) NOT NULL,
  title VARCHAR(128) NOT NULL,
  is_video BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS group_participants (
  group_call_id VARCHAR(128) NOT NULL REFERENCES group_calls(group_call_id) ON DELETE CASCADE,
  device_id VARCHAR(128) NOT NULL,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  left_at TIMESTAMPTZ,
  PRIMARY KEY (group_call_id, device_id)
);

CREATE TABLE IF NOT EXISTS temporary_tokens (
  token VARCHAR(128) PRIMARY KEY,
  purpose VARCHAR(32) NOT NULL, -- CALL_TRANSFER, CLICK_TO_CALL
  device_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  call_id VARCHAR(128),
  expires_at TIMESTAMPTZ NOT NULL,
  consumed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_temporary_tokens_expires ON temporary_tokens(expires_at);

CREATE TABLE IF NOT EXISTS push_tokens (
  device_id VARCHAR(128) PRIMARY KEY REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  fcm_token TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS device_settings (
  device_id VARCHAR(128) PRIMARY KEY REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  hide_previews BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Zero-knowledge encrypted media & in-call attachment relay (ciphertext only; auto-expires)
CREATE TABLE IF NOT EXISTS encrypted_media (
  media_id VARCHAR(128) PRIMARY KEY,
  uploader_device_id VARCHAR(128) NOT NULL REFERENCES device_identities(installation_id) ON DELETE CASCADE,
  ciphertext_b64 TEXT NOT NULL,
  size_bytes BIGINT NOT NULL DEFAULT 0,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_encrypted_media_expires ON encrypted_media(expires_at);

