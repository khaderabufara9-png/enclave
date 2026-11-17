import fs from 'fs';
import path from 'path';
import { Pool } from 'pg';
import Redis from 'ioredis';

export interface DeviceRecord {
  installationId: string;
  signingPublicKeyB64: string;
  ecdhPublicKeyB64: string;
  platform: string;
  lastSeenAt: number;
}

export interface InvitationRecord {
  invitationId: string;
  code: string;
  inviterDeviceId: string;
  inviterSigningPublicKeyB64: string;
  inviterEcdhPublicKeyB64: string;
  offeredDisplayName: string;
  expiresAtMs: number;
  signatureB64: string;
  status: string;
}

export interface RelationshipRecord {
  relationshipId: string;
  deviceAId: string;
  deviceBId: string;
  status: 'ACTIVE' | 'BLOCKED' | 'REVOKED';
}

export interface EncryptedEnvelopeRecord {
  messageId: string;
  relationshipId: string;
  senderDeviceId: string;
  recipientDeviceId: string;
  ivB64: string;
  ciphertextB64: string;
  sequence: number;
  ttlSeconds: number;
  signatureB64: string;
  sentAtMs: number;
  expiresAtMs: number;
}

export interface DeletionEventRecord {
  eventId: string;
  relationshipId: string;
  senderDeviceId: string;
  recipientDeviceId: string;
  scope: string;
  targetMessageId?: string;
  signatureB64: string;
  status: 'PENDING_ACK' | 'ACKNOWLEDGED';
  createdAtMs: number;
}

export interface TemporaryTokenRecord {
  token: string;
  purpose: string;
  deviceId: string;
  callId?: string;
  expiresAtMs: number;
  consumed: boolean;
}

export interface EncryptedMediaRecord {
  mediaId: string;
  uploaderDeviceId: string;
  ciphertextB64: string;
  sizeBytes: number;
  expiresAtMs: number;
}

export interface PendingSignalRecord {
  payloadJson: string;
  expiresAtMs: number;
}

/**
 * Storage abstraction backed by PostgreSQL + Redis when configured,
 * with automatic fallback to clean in-memory structures for local standalone verification.
 */
export class EnclaveStore {
  private pgPool: Pool | null = null;
  private redis: Redis | null = null;
  public postgresStatus = 'in-memory';
  public redisStatus = 'in-memory';

  private devices = new Map<string, DeviceRecord>();
  private invitations = new Map<string, InvitationRecord>();
  private relationships = new Map<string, RelationshipRecord>();
  private pendingEnvelopes = new Map<string, EncryptedEnvelopeRecord>();
  private seenMessageIds = new Set<string>();
  private deletions = new Map<string, DeletionEventRecord>();
  private tempTokens = new Map<string, TemporaryTokenRecord>();
  private pushTokens = new Map<string, string>();
  private pendingSignals = new Map<string, PendingSignalRecord[]>();
  private mediaBlobs = new Map<string, EncryptedMediaRecord>();
  private rateBuckets = new Map<string, { count: number; resetAt: number }>();

  async init(databaseUrl?: string, redisUrl?: string): Promise<void> {
    if (databaseUrl) {
      try {
        this.pgPool = new Pool({ connectionString: databaseUrl });
        await this.pgPool.query('SELECT 1');
        const migrationPath = path.join(__dirname, 'migrations', '001_init.sql');
        if (fs.existsSync(migrationPath)) {
          const sql = fs.readFileSync(migrationPath, 'utf8');
          await this.pgPool.query(sql);
        }
        this.postgresStatus = 'connected';
      } catch (err) {
        console.warn('[EnclaveStore] PostgreSQL unreachable, using in-memory fallback:', (err as Error).message);
        this.pgPool = null;
      }
    }

    if (redisUrl) {
      try {
        this.redis = new Redis(redisUrl, { lazyConnect: true, maxRetriesPerRequest: 1 });
        await this.redis.connect();
        await this.redis.ping();
        this.redisStatus = 'connected';
      } catch (err) {
        console.warn('[EnclaveStore] Redis unreachable, using in-memory fallback:', (err as Error).message);
        this.redis?.disconnect();
        this.redis = null;
      }
    }
  }

  async close(): Promise<void> {
    await this.pgPool?.end();
    this.redis?.disconnect();
  }

  async checkRateLimit(key: string, maxRequests = 120, windowMs = 60_000): Promise<boolean> {
    if (this.redis) {
      const redisKey = `rl:${key}`;
      const count = await this.redis.incr(redisKey);
      if (count === 1) await this.redis.pexpire(redisKey, windowMs);
      return count <= maxRequests;
    }
    const now = Date.now();
    const bucket = this.rateBuckets.get(key);
    if (!bucket || bucket.resetAt <= now) {
      this.rateBuckets.set(key, { count: 1, resetAt: now + windowMs });
      return true;
    }
    bucket.count += 1;
    return bucket.count <= maxRequests;
  }

  async upsertDevice(rec: DeviceRecord): Promise<void> {
    this.devices.set(rec.installationId, rec);
    if (this.pgPool) {
      await this.pgPool.query(
        `INSERT INTO device_identities(installation_id, platform, last_seen_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (installation_id) DO UPDATE SET last_seen_at = NOW()`,
        [rec.installationId, rec.platform]
      );
      await this.pgPool.query(
        `INSERT INTO public_keys(installation_id, signing_public_key_b64, ecdh_public_key_b64)
         VALUES ($1, $2, $3)
         ON CONFLICT (installation_id) DO UPDATE SET
           signing_public_key_b64 = EXCLUDED.signing_public_key_b64,
           ecdh_public_key_b64 = EXCLUDED.ecdh_public_key_b64,
           rotated_at = NOW()`,
        [rec.installationId, rec.signingPublicKeyB64, rec.ecdhPublicKeyB64]
      );
    }
  }

  async getDevice(installationId: string): Promise<DeviceRecord | null> {
    const cached = this.devices.get(installationId);
    if (cached) return cached;
    if (this.pgPool) {
      const res = await this.pgPool.query(
        `SELECT d.installation_id, d.platform, p.signing_public_key_b64, p.ecdh_public_key_b64
         FROM device_identities d
         JOIN public_keys p ON d.installation_id = p.installation_id
         WHERE d.installation_id = $1`,
        [installationId]
      );
      if (res.rows[0]) {
        const r = res.rows[0];
        const rec: DeviceRecord = {
          installationId: r.installation_id,
          platform: r.platform,
          signingPublicKeyB64: r.signing_public_key_b64,
          ecdhPublicKeyB64: r.ecdh_public_key_b64,
          lastSeenAt: Date.now(),
        };
        this.devices.set(installationId, rec);
        return rec;
      }
    }
    return null;
  }

  async saveInvitation(inv: InvitationRecord): Promise<void> {
    this.invitations.set(inv.code.toUpperCase(), inv);
    if (this.pgPool) {
      await this.pgPool.query(
        `INSERT INTO invitations(invitation_id, code, inviter_device_id, inviter_signing_public_key_b64,
           inviter_ecdh_public_key_b64, offered_display_name, signature_b64, status, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, to_timestamp($9 / 1000.0))
         ON CONFLICT (code) DO NOTHING`,
        [
          inv.invitationId,
          inv.code.toUpperCase(),
          inv.inviterDeviceId,
          inv.inviterSigningPublicKeyB64,
          inv.inviterEcdhPublicKeyB64,
          inv.offeredDisplayName,
          inv.signatureB64,
          inv.status,
          inv.expiresAtMs,
        ]
      );
    }
  }

  async getInvitation(code: string): Promise<InvitationRecord | null> {
    const key = code.toUpperCase();
    const cached = this.invitations.get(key);
    if (cached && cached.expiresAtMs > Date.now()) return cached;
    return null;
  }

  async upsertRelationship(rel: RelationshipRecord): Promise<void> {
    this.relationships.set(rel.relationshipId, rel);
    if (this.pgPool) {
      await this.pgPool.query(
        `INSERT INTO relationships(relationship_id, device_a_id, device_b_id, status)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (relationship_id) DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()`,
        [rel.relationshipId, rel.deviceAId, rel.deviceBId, rel.status]
      );
    }
  }

  async getRelationship(relationshipId: string): Promise<RelationshipRecord | null> {
    return this.relationships.get(relationshipId) ?? null;
  }

  hasSeenMessage(messageId: string): boolean {
    return this.seenMessageIds.has(messageId);
  }

  markMessageSeen(messageId: string): void {
    this.seenMessageIds.add(messageId);
  }

  async queueEncryptedEnvelope(env: EncryptedEnvelopeRecord): Promise<void> {
    this.pendingEnvelopes.set(env.messageId, env);
    if (this.pgPool) {
      await this.pgPool.query(
        `INSERT INTO encrypted_messages(message_id, relationship_id, sender_device_id, recipient_device_id,
           iv_b64, ciphertext_b64, sequence, ttl_seconds, signature_b64, sent_at_ms, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, to_timestamp($11 / 1000.0))
         ON CONFLICT (message_id) DO NOTHING`,
        [
          env.messageId,
          env.relationshipId,
          env.senderDeviceId,
          env.recipientDeviceId,
          env.ivB64,
          env.ciphertextB64,
          env.sequence,
          env.ttlSeconds,
          env.signatureB64,
          env.sentAtMs,
          env.expiresAtMs,
        ]
      );
    }
  }

  async getPendingForDevice(deviceId: string): Promise<EncryptedEnvelopeRecord[]> {
    const now = Date.now();
    const list: EncryptedEnvelopeRecord[] = [];
    for (const env of this.pendingEnvelopes.values()) {
      if (env.recipientDeviceId === deviceId && env.expiresAtMs > now) {
        list.push(env);
      }
    }
    return list.sort((a, b) => a.sentAtMs - b.sentAtMs);
  }

  async acknowledgeAndPurgeEnvelope(messageId: string, recipientDeviceId: string): Promise<boolean> {
    const existing = this.pendingEnvelopes.get(messageId);
    if (existing && existing.recipientDeviceId !== recipientDeviceId) return false;
    this.pendingEnvelopes.delete(messageId);
    if (this.pgPool) {
      await this.pgPool.query(
        `DELETE FROM encrypted_messages WHERE message_id = $1 AND recipient_device_id = $2`,
        [messageId, recipientDeviceId]
      );
    }
    return true;
  }

  async purgeExpired(): Promise<number> {
    const now = Date.now();
    let purged = 0;
    for (const [id, env] of this.pendingEnvelopes.entries()) {
      if (env.expiresAtMs <= now) {
        this.pendingEnvelopes.delete(id);
        purged++;
      }
    }
    for (const [tok, rec] of this.tempTokens.entries()) {
      if (rec.expiresAtMs <= now) this.tempTokens.delete(tok);
    }
    for (const [mediaId, media] of this.mediaBlobs.entries()) {
      if (media.expiresAtMs <= now) this.mediaBlobs.delete(mediaId);
    }
    for (const [devId, items] of this.pendingSignals.entries()) {
      const valid = items.filter((item) => item.expiresAtMs > now);
      if (valid.length === 0) this.pendingSignals.delete(devId);
      else this.pendingSignals.set(devId, valid);
    }
    if (this.pgPool) {
      await this.pgPool.query(`DELETE FROM encrypted_media WHERE expires_at <= NOW()`).catch(() => {});
    }
    return purged;
  }

  async saveEncryptedMedia(rec: EncryptedMediaRecord): Promise<void> {
    this.mediaBlobs.set(rec.mediaId, rec);
    if (this.pgPool) {
      await this.pgPool.query(
        `INSERT INTO encrypted_media(media_id, uploader_device_id, ciphertext_b64, size_bytes, expires_at)
         VALUES ($1, $2, $3, $4, to_timestamp($5 / 1000.0))
         ON CONFLICT (media_id) DO NOTHING`,
        [rec.mediaId, rec.uploaderDeviceId, rec.ciphertextB64, rec.sizeBytes, rec.expiresAtMs]
      );
    }
  }

  async getEncryptedMedia(mediaId: string): Promise<EncryptedMediaRecord | null> {
    const now = Date.now();
    const cached = this.mediaBlobs.get(mediaId);
    if (cached && cached.expiresAtMs > now) return cached;
    if (this.pgPool) {
      const res = await this.pgPool.query(
        `SELECT media_id, uploader_device_id, ciphertext_b64, size_bytes,
                EXTRACT(EPOCH FROM expires_at) * 1000 AS expires_at_ms
         FROM encrypted_media WHERE media_id = $1 AND expires_at > NOW()`,
        [mediaId]
      );
      if (res.rows[0]) {
        const r = res.rows[0];
        const rec: EncryptedMediaRecord = {
          mediaId: r.media_id,
          uploaderDeviceId: r.uploader_device_id,
          ciphertextB64: r.ciphertext_b64,
          sizeBytes: Number(r.size_bytes),
          expiresAtMs: Number(r.expires_at_ms),
        };
        this.mediaBlobs.set(mediaId, rec);
        return rec;
      }
    }
    return null;
  }

  async saveDeletionEvent(ev: DeletionEventRecord): Promise<void> {
    this.deletions.set(ev.eventId, ev);
  }

  async ackDeletionEvent(eventId: string): Promise<void> {
    const ev = this.deletions.get(eventId);
    if (ev) ev.status = 'ACKNOWLEDGED';
  }

  async saveTempToken(tok: TemporaryTokenRecord): Promise<void> {
    this.tempTokens.set(tok.token, tok);
  }

  async getTempToken(token: string): Promise<TemporaryTokenRecord | null> {
    const rec = this.tempTokens.get(token);
    if (!rec || rec.expiresAtMs <= Date.now()) return null;
    return rec;
  }

  async savePushToken(deviceId: string, fcmToken: string): Promise<void> {
    this.pushTokens.set(deviceId, fcmToken);
    if (this.redis) {
      await this.redis.set(`push:${deviceId}`, fcmToken).catch(() => {});
    }
  }

  async getPushToken(deviceId: string): Promise<string | null> {
    const cached = this.pushTokens.get(deviceId);
    if (cached) return cached;
    if (this.redis) {
      const fromRedis = await this.redis.get(`push:${deviceId}`).catch(() => null);
      if (fromRedis) {
        this.pushTokens.set(deviceId, fromRedis);
        return fromRedis;
      }
    }
    return null;
  }

  async savePendingSignal(recipientDeviceId: string, payloadJson: string, ttlMs = 60_000): Promise<void> {
    const expiresAtMs = Date.now() + ttlMs;
    const list = this.pendingSignals.get(recipientDeviceId) ?? [];
    list.push({ payloadJson, expiresAtMs });
    if (list.length > 50) list.shift();
    this.pendingSignals.set(recipientDeviceId, list);
    if (this.redis) {
      const key = `sig:${recipientDeviceId}`;
      await this.redis.rpush(key, JSON.stringify({ payloadJson, expiresAtMs })).catch(() => {});
      await this.redis.pexpire(key, ttlMs).catch(() => {});
    }
  }

  async drainPendingSignals(deviceId: string): Promise<string[]> {
    const now = Date.now();
    const out: string[] = [];
    const mem = this.pendingSignals.get(deviceId);
    if (mem) {
      this.pendingSignals.delete(deviceId);
      for (const item of mem) {
        if (item.expiresAtMs > now) out.push(item.payloadJson);
      }
    } else if (this.redis) {
      const key = `sig:${deviceId}`;
      const raw = await this.redis.lrange(key, 0, -1).catch(() => []);
      if (raw.length > 0) {
        await this.redis.del(key).catch(() => {});
        for (const entry of raw) {
          try {
            const parsed = JSON.parse(entry) as PendingSignalRecord;
            if (parsed.expiresAtMs > now) out.push(parsed.payloadJson);
          } catch {
            // ignore malformed entry
          }
        }
      }
    }
    return out;
  }
}
