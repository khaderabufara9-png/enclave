import http from 'http';
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { WebSocketServer, WebSocket } from 'ws';
import { z } from 'zod';
import { EnclaveStore } from './db/store';
import { generateRandomToken, generateTurnCredentials, verifyEcdsaSignature } from './crypto/verify';
import { isFcmConfigured, sendZeroKnowledgeWakeupPush } from './push/fcm';
import { renderGuestCallHtml } from './web/guestCallPage';

export interface BackendConfig {
  port: number;
  stunUrl: string;
  turnUrl: string;
  turnSharedSecret: string;
  turnUsername?: string;
  turnCredential?: string;
  publicBaseUrl: string;
  envelopeTtlHours: number;
}

export function createEnclaveServer(store: EnclaveStore, config: BackendConfig) {
  const app = express();
  app.use(helmet({ contentSecurityPolicy: false }));
  app.use(cors());
  app.use(express.json({ limit: '32mb' }));

  const socketsByDevice = new Map<string, WebSocket>();

  // Rate limiting middleware
  app.use(async (req, res, next) => {
    const key = (req.headers['x-enclave-device-id'] as string) || req.ip || 'anon';
    const allowed = await store.checkRateLimit(key);
    if (!allowed) {
      res.status(429).json({ error: 'Rate limit exceeded' });
      return;
    }
    next();
  });

  app.get('/api/v1/health', (_req, res) => {
    res.json({
      status: 'ok',
      postgres: store.postgresStatus,
      redis: store.redisStatus,
      stunServer: config.stunUrl,
      turnServer: config.turnUrl,
      turnConfigured: Boolean(config.turnUrl),
      fcmConfigured: isFcmConfigured(),
      publicBaseUrl: config.publicBaseUrl,
      timestampMs: Date.now(),
    });
  });

  // Anonymous device registration (no phone/email)
  app.post('/api/v1/devices/register', async (req, res) => {
    const schema = z.object({
      installationId: z.string().min(8).max(128),
      signingPublicKeyB64: z.string().min(16),
      ecdhPublicKeyB64: z.string().min(16),
      platform: z.string().default('android'),
      signatureB64: z.string().min(16),
      timestampMs: z.number(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid device registration payload' });
      return;
    }
    const d = parsed.data;
    const canonical = `register|${d.installationId}|${d.signingPublicKeyB64}|${d.ecdhPublicKeyB64}|${d.timestampMs}`;
    if (!verifyEcdsaSignature(d.signingPublicKeyB64, canonical, d.signatureB64)) {
      res.status(401).json({ error: 'Invalid ECDSA registration signature' });
      return;
    }
    await store.upsertDevice({
      installationId: d.installationId,
      signingPublicKeyB64: d.signingPublicKeyB64,
      ecdhPublicKeyB64: d.ecdhPublicKeyB64,
      platform: d.platform,
      lastSeenAt: Date.now(),
    });
    res.json({ registered: true, serverTimeMs: Date.now(), fcmConfigured: isFcmConfigured() });
  });

  // Publish invitation
  app.post('/api/v1/invitations', async (req, res) => {
    const schema = z.object({
      invitationId: z.string(),
      code: z.string().min(6),
      inviterDeviceId: z.string(),
      inviterSigningPublicKeyB64: z.string(),
      inviterEcdhPublicKeyB64: z.string(),
      offeredDisplayName: z.string().max(128),
      expiresAtMs: z.number(),
      signatureB64: z.string(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid invitation payload' });
      return;
    }
    const v = parsed.data;
    const canonical = `${v.invitationId}|${v.code}|${v.inviterDeviceId}|${v.inviterSigningPublicKeyB64}|${v.inviterEcdhPublicKeyB64}|${v.expiresAtMs}`;
    if (!verifyEcdsaSignature(v.inviterSigningPublicKeyB64, canonical, v.signatureB64)) {
      res.status(401).json({ error: 'Invalid invitation signature' });
      return;
    }
    await store.saveInvitation({ ...v, status: 'PENDING' });
    res.json({ ok: true, code: v.code });
  });

  app.get('/api/v1/invitations/:code', async (req, res) => {
    const inv = await store.getInvitation(req.params.code);
    if (!inv) {
      res.status(404).json({ error: 'Invitation not found or expired' });
      return;
    }
    res.json(inv);
  });

  app.post('/api/v1/invitations/:code/accept', async (req, res) => {
    const schema = z.object({
      relationshipId: z.string(),
      responderDeviceId: z.string(),
      responderSigningPublicKeyB64: z.string(),
      responderEcdhPublicKeyB64: z.string(),
      responderDisplayName: z.string(),
      signatureB64: z.string(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid accept payload' });
      return;
    }
    const d = parsed.data;
    const inv = await store.getInvitation(req.params.code);
    if (!inv) {
      res.status(404).json({ error: 'Invitation expired or unknown' });
      return;
    }
    const canonical = `accept|${req.params.code}|${d.relationshipId}|${d.responderDeviceId}`;
    if (!verifyEcdsaSignature(d.responderSigningPublicKeyB64, canonical, d.signatureB64)) {
      res.status(401).json({ error: 'Invalid responder signature' });
      return;
    }

    await store.upsertRelationship({
      relationshipId: d.relationshipId,
      deviceAId: inv.inviterDeviceId,
      deviceBId: d.responderDeviceId,
      status: 'ACTIVE',
    });

    const acceptedPayload = JSON.stringify({
      type: 'relationship_accepted',
      code: inv.code,
      relationshipId: d.relationshipId,
      responderDeviceId: d.responderDeviceId,
      responderSigningPublicKeyB64: d.responderSigningPublicKeyB64,
      responderEcdhPublicKeyB64: d.responderEcdhPublicKeyB64,
      responderDisplayName: d.responderDisplayName,
    });

    const inviterSocket = socketsByDevice.get(inv.inviterDeviceId);
    if (inviterSocket && inviterSocket.readyState === WebSocket.OPEN) {
      inviterSocket.send(acceptedPayload);
    } else {
      await store.savePendingSignal(inv.inviterDeviceId, acceptedPayload, 7 * 24 * 3600 * 1000);
      const fcmToken = await store.getPushToken(inv.inviterDeviceId);
      if (fcmToken) {
        await sendZeroKnowledgeWakeupPush(fcmToken, 'MESSAGE_PENDING');
      }
    }

    res.json({ ok: true, relationshipId: d.relationshipId });
  });

  // Zero-knowledge encrypted message relay (rejects revoked relationships & duplicate replay IDs)
  app.post('/api/v1/messages/relay', async (req, res) => {
    const schema = z.object({
      messageId: z.string(),
      relationshipId: z.string(),
      senderDeviceId: z.string(),
      recipientDeviceId: z.string(),
      ivB64: z.string().min(8),
      ciphertextB64: z.string().min(8),
      sequence: z.number().int().nonnegative(),
      ttlSeconds: z.number().int().nonnegative(),
      signatureB64: z.string().min(16),
      sentAtMs: z.number(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Malformed encrypted envelope' });
      return;
    }
    const env = parsed.data;

    // Replay check
    if (store.hasSeenMessage(env.messageId)) {
      res.status(409).json({ error: 'Replay rejected: duplicate messageId' });
      return;
    }

    // Verify sender device signature
    const senderDevice = await store.getDevice(env.senderDeviceId);
    if (!senderDevice) {
      res.status(403).json({ error: 'Unregistered sender device' });
      return;
    }
    const canonical = `${env.messageId}|${env.relationshipId}|${env.senderDeviceId}|${env.recipientDeviceId}|${env.ivB64}|${env.ciphertextB64}|${env.sequence}|${env.sentAtMs}`;
    if (!verifyEcdsaSignature(senderDevice.signingPublicKeyB64, canonical, env.signatureB64)) {
      res.status(401).json({ error: 'Envelope signature invalid' });
      return;
    }

    // Check relationship revocation/block status
    const rel = await store.getRelationship(env.relationshipId);
    if (rel && rel.status !== 'ACTIVE') {
      res.status(403).json({ error: `Relationship is ${rel.status}; delivery forbidden` });
      return;
    }

    store.markMessageSeen(env.messageId);

    const recipientWs = socketsByDevice.get(env.recipientDeviceId);
    if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
      recipientWs.send(JSON.stringify({ type: 'encrypted_message', ...env }));
      res.json({ messageId: env.messageId, status: 'DELIVERED_WS', serverTimestampMs: Date.now() });
      return;
    }

    // Store-and-forward ciphertext ONLY until recipient fetches & acknowledges
    const maxRetentionMs = config.envelopeTtlHours * 3600 * 1000;
    await store.queueEncryptedEnvelope({
      ...env,
      expiresAtMs: Date.now() + maxRetentionMs,
    });

    const fcmToken = await store.getPushToken(env.recipientDeviceId);
    if (fcmToken) {
      await sendZeroKnowledgeWakeupPush(fcmToken, 'MESSAGE_PENDING');
    }

    res.json({ messageId: env.messageId, status: 'QUEUED_OFFLINE', serverTimestampMs: Date.now() });
  });

  app.get('/api/v1/messages/pending/:deviceId', async (req, res) => {
    const deviceId = req.params.deviceId;
    const ts = Number(req.headers['x-enclave-timestamp'] || '0');
    const sig = String(req.headers['x-enclave-signature'] || '');
    const dev = await store.getDevice(deviceId);
    if (!dev || !verifyEcdsaSignature(dev.signingPublicKeyB64, `pending|${deviceId}|${ts}`, sig)) {
      res.status(401).json({ error: 'Unauthorized inbox fetch' });
      return;
    }
    const envelopes = await store.getPendingForDevice(deviceId);
    res.json({ envelopes });
  });

  app.post('/api/v1/messages/ack', async (req, res) => {
    const schema = z.object({
      messageId: z.string(),
      recipientDeviceId: z.string(),
      signatureB64: z.string(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid ack' });
      return;
    }
    const dev = await store.getDevice(parsed.data.recipientDeviceId);
    if (!dev || !verifyEcdsaSignature(dev.signingPublicKeyB64, `ack|${parsed.data.messageId}|${parsed.data.recipientDeviceId}`, parsed.data.signatureB64)) {
      res.status(401).json({ error: 'Invalid ack signature' });
      return;
    }
    await store.acknowledgeAndPurgeEnvelope(parsed.data.messageId, parsed.data.recipientDeviceId);
    res.json({ purged: true });
  });

  // Encrypted media & in-call file/video relay (ciphertext only; ECDSA-authenticated upload & download)
  app.post('/api/v1/media/upload', async (req, res) => {
    const schema = z.object({
      mediaId: z.string().min(4).max(128),
      uploaderDeviceId: z.string().min(4).max(128),
      ciphertextB64: z.string().min(8),
      sizeBytes: z.number().int().nonnegative(),
      timestampMs: z.number(),
      signatureB64: z.string().min(16),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid encrypted media payload' });
      return;
    }
    const m = parsed.data;
    const uploader = await store.getDevice(m.uploaderDeviceId);
    if (!uploader) {
      res.status(403).json({ error: 'Unregistered uploader device' });
      return;
    }
    const canonical = `media-upload|${m.mediaId}|${m.uploaderDeviceId}|${m.timestampMs}`;
    if (!verifyEcdsaSignature(uploader.signingPublicKeyB64, canonical, m.signatureB64)) {
      res.status(401).json({ error: 'Invalid media upload signature' });
      return;
    }
    const expiresAtMs = Date.now() + config.envelopeTtlHours * 3600 * 1000;
    await store.saveEncryptedMedia({
      mediaId: m.mediaId,
      uploaderDeviceId: m.uploaderDeviceId,
      ciphertextB64: m.ciphertextB64,
      sizeBytes: m.sizeBytes,
      expiresAtMs,
    });
    res.json({ mediaId: m.mediaId, uploaded: true, expiresAtMs });
  });

  app.get('/api/v1/media/:mediaId', async (req, res) => {
    const mediaId = req.params.mediaId;
    const deviceId = String(req.headers['x-enclave-device-id'] || '');
    const ts = Number(req.headers['x-enclave-timestamp'] || '0');
    const sig = String(req.headers['x-enclave-signature'] || '');
    const dev = await store.getDevice(deviceId);
    if (!dev || !verifyEcdsaSignature(dev.signingPublicKeyB64, `media-download|${mediaId}|${deviceId}|${ts}`, sig)) {
      res.status(401).json({ error: 'Unauthorized encrypted media fetch' });
      return;
    }
    const media = await store.getEncryptedMedia(mediaId);
    if (!media) {
      res.status(404).json({ error: 'Encrypted media not found or expired' });
      return;
    }
    res.json({
      mediaId: media.mediaId,
      ciphertextB64: media.ciphertextB64,
      sizeBytes: media.sizeBytes,
      expiresAtMs: media.expiresAtMs,
    });
  });

  app.post('/api/v1/deletions', async (req, res) => {
    const schema = z.object({
      eventId: z.string(),
      relationshipId: z.string(),
      senderDeviceId: z.string(),
      recipientDeviceId: z.string(),
      scope: z.string(),
      targetMessageId: z.string().optional().nullable(),
      signatureB64: z.string(),
      createdAtMs: z.number(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid deletion payload' });
      return;
    }
    const d = parsed.data;
    const sender = await store.getDevice(d.senderDeviceId);
    const canonical = `${d.eventId}|${d.relationshipId}|${d.scope}|${d.targetMessageId ?? ''}|${d.senderDeviceId}|${d.createdAtMs}`;
    if (!sender || !verifyEcdsaSignature(sender.signingPublicKeyB64, canonical, d.signatureB64)) {
      res.status(401).json({ error: 'Invalid deletion signature' });
      return;
    }
    await store.saveDeletionEvent({
      ...d,
      targetMessageId: d.targetMessageId ?? undefined,
      status: 'PENDING_ACK',
    });
    const peerWs = socketsByDevice.get(d.recipientDeviceId);
    if (peerWs && peerWs.readyState === WebSocket.OPEN) {
      peerWs.send(JSON.stringify({ type: 'deletion_event', ...d }));
    }
    res.json({ ok: true, status: 'PENDING_ACK' });
  });

  app.post('/api/v1/relationships/action', async (req, res) => {
    const schema = z.object({
      relationshipId: z.string(),
      actorDeviceId: z.string(),
      peerDeviceId: z.string(),
      action: z.enum(['REVOKE', 'BLOCK', 'UNBLOCK']),
      signatureB64: z.string(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid relationship action' });
      return;
    }
    const d = parsed.data;
    const actor = await store.getDevice(d.actorDeviceId);
    if (!actor || !verifyEcdsaSignature(actor.signingPublicKeyB64, `rel-action|${d.relationshipId}|${d.actorDeviceId}|${d.action}`, d.signatureB64)) {
      res.status(401).json({ error: 'Invalid action signature' });
      return;
    }
    const nextStatus = d.action === 'REVOKE' ? 'REVOKED' : d.action === 'BLOCK' ? 'BLOCKED' : 'ACTIVE';
    await store.upsertRelationship({
      relationshipId: d.relationshipId,
      deviceAId: d.actorDeviceId,
      deviceBId: d.peerDeviceId,
      status: nextStatus,
    });
    if (d.action === 'REVOKE') {
      const peerWs = socketsByDevice.get(d.peerDeviceId);
      if (peerWs && peerWs.readyState === WebSocket.OPEN) {
        peerWs.send(JSON.stringify({ type: 'relationship_revoked', relationshipId: d.relationshipId }));
      }
    }
    res.json({ ok: true, status: nextStatus });
  });

  app.post('/api/v1/tokens/temporary', async (req, res) => {
    const schema = z.object({
      purpose: z.enum(['CALL_TRANSFER', 'CLICK_TO_CALL']),
      deviceId: z.string(),
      callId: z.string().optional().nullable(),
      ttlSeconds: z.number().int().min(30).max(3600).default(300),
      signatureB64: z.string(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid token request' });
      return;
    }
    const d = parsed.data;
    const dev = await store.getDevice(d.deviceId);
    if (!dev || !verifyEcdsaSignature(dev.signingPublicKeyB64, `token|${d.purpose}|${d.deviceId}|${d.callId ?? ''}`, d.signatureB64)) {
      res.status(401).json({ error: 'Invalid token signature' });
      return;
    }
    const token = generateRandomToken('tok');
    const expiresAtMs = Date.now() + d.ttlSeconds * 1000;
    await store.saveTempToken({
      token,
      purpose: d.purpose,
      deviceId: d.deviceId,
      callId: d.callId ?? undefined,
      expiresAtMs,
      consumed: false,
    });
    res.json({
      token,
      url: `${config.publicBaseUrl}/call/${token}`,
      expiresAtMs,
    });
  });

  app.get('/call/:token', async (req, res) => {
    const rec = await store.getTempToken(req.params.token);
    if (!rec) {
      res.status(404).send('<h2>Temporary Enclave call link expired or invalid.</h2>');
      return;
    }
    res.type('html').send(renderGuestCallHtml(rec.token, rec.purpose, config.stunUrl));
  });

  app.post('/api/v1/push/register', async (req, res) => {
    const schema = z.object({
      deviceId: z.string().min(4).max(128),
      fcmToken: z.string().min(8),
      signatureB64: z.string().min(16),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: 'Invalid push token payload' });
      return;
    }
    const dev = await store.getDevice(parsed.data.deviceId);
    const canonical = `push-register|${parsed.data.deviceId}|${parsed.data.fcmToken}`;
    if (!dev || !verifyEcdsaSignature(dev.signingPublicKeyB64, canonical, parsed.data.signatureB64)) {
      res.status(401).json({ error: 'Unauthorized push token registration' });
      return;
    }
    await store.savePushToken(parsed.data.deviceId, parsed.data.fcmToken);
    res.json({ ok: true, fcmConfigured: isFcmConfigured() });
  });

  app.get('/api/v1/calls/pending/:deviceId', async (req, res) => {
    const deviceId = req.params.deviceId;
    const ts = Number(req.headers['x-enclave-timestamp'] || '0');
    const sig = String(req.headers['x-enclave-signature'] || '');
    const dev = await store.getDevice(deviceId);
    if (!dev || !verifyEcdsaSignature(dev.signingPublicKeyB64, `pending-calls|${deviceId}|${ts}`, sig)) {
      res.status(401).json({ error: 'Unauthorized pending signals fetch' });
      return;
    }
    const signals = await store.drainPendingSignals(deviceId);
    res.json({ signals });
  });

  app.get('/api/v1/rtc/ice-config/:deviceId', (req, res) => {
    if (config.turnUsername && config.turnCredential) {
      res.json({
        urls: [config.stunUrl, config.turnUrl].filter(Boolean),
        username: config.turnUsername,
        credential: config.turnCredential,
        ttlSeconds: 3600,
      });
      return;
    }
    const creds = generateTurnCredentials(req.params.deviceId, config.turnSharedSecret, 3600);
    res.json({
      urls: [config.stunUrl, config.turnUrl].filter(Boolean),
      username: creds.username,
      credential: creds.credential,
      ttlSeconds: creds.ttlSeconds,
    });
  });

  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: '/ws' });

  wss.on('connection', async (ws, req) => {
    const url = new URL(req.url || '/ws', 'http://localhost');
    const guestToken = url.searchParams.get('guestToken');
    let boundDeviceId: string | null = null;

    ws.on('message', async (raw) => {
      let msg: Record<string, unknown>;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }

      if (msg.type === 'ping') {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
        }
        return;
      }

      if (msg.type === 'auth' && typeof msg.deviceId === 'string') {
        const dev = await store.getDevice(msg.deviceId);
        const ts = Number(msg.timestampMs || 0);
        const sig = String(msg.signatureB64 || '');
        if (dev && verifyEcdsaSignature(dev.signingPublicKeyB64, `ws-auth|${msg.deviceId}|${ts}`, sig)) {
          boundDeviceId = msg.deviceId;
          socketsByDevice.set(boundDeviceId, ws);
          const buffered = await store.drainPendingSignals(boundDeviceId);
          for (const payload of buffered) {
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(payload);
            }
          }
        } else {
          ws.close(4001, 'Invalid WebSocket signature');
        }
        return;
      }

      if (msg.type === 'guest_click_to_call' && guestToken) {
        const tok = await store.getTempToken(guestToken);
        if (tok) {
          const payload = JSON.stringify({
            type: 'guest_click_to_call',
            callId: `guest_${Date.now()}`,
            guestName: msg.guestName || 'Browser Guest',
            token: guestToken,
          });
          const hostWs = socketsByDevice.get(tok.deviceId);
          if (hostWs && hostWs.readyState === WebSocket.OPEN) {
            hostWs.send(payload);
          } else {
            await store.savePendingSignal(tok.deviceId, payload, 60_000);
            const fcmToken = await store.getPushToken(tok.deviceId);
            if (fcmToken) {
              await sendZeroKnowledgeWakeupPush(fcmToken, 'INCOMING_CALL');
            }
          }
        }
        return;
      }

      // Forward signaling payloads (WebRTC offer/answer/ICE, typing, receipts, deletion_ack,
      // media_sync, screen_share_state, call_shared_item, edit_message, reaction_update)
      const toDeviceId = typeof msg.toDeviceId === 'string' ? msg.toDeviceId : null;
      if (toDeviceId) {
        const serialized = JSON.stringify(msg);
        const target = socketsByDevice.get(toDeviceId);
        if (target && target.readyState === WebSocket.OPEN) {
          target.send(serialized);
        } else {
          const signalType = typeof msg.type === 'string' ? msg.type : '';
          const isCallSignal =
            signalType === 'call_offer' ||
            signalType === 'call_answer' ||
            signalType === 'ice_candidate' ||
            signalType === 'call_hangup';
          const isDurableSync =
            signalType === 'deletion_event' ||
            signalType === 'relationship_revoked' ||
            signalType === 'edit_message' ||
            signalType === 'reaction_update';

          if (isCallSignal || isDurableSync) {
            const ttlMs = isCallSignal ? 60_000 : 24 * 3600 * 1000;
            await store.savePendingSignal(toDeviceId, serialized, ttlMs);
          }
          if (signalType === 'call_offer') {
            const fcmToken = await store.getPushToken(toDeviceId);
            if (fcmToken) {
              await sendZeroKnowledgeWakeupPush(fcmToken, 'INCOMING_CALL');
            }
          }
        }
      }
      if (Array.isArray(msg.toDeviceIds)) {
        const serialized = JSON.stringify(msg);
        for (const id of msg.toDeviceIds) {
          if (typeof id === 'string') {
            const target = socketsByDevice.get(id);
            if (target && target.readyState === WebSocket.OPEN) {
              target.send(serialized);
            }
          }
        }
      }
      if (msg.type === 'deletion_ack' && typeof msg.eventId === 'string') {
        await store.ackDeletionEvent(msg.eventId);
      }
    });

    ws.on('close', () => {
      if (boundDeviceId && socketsByDevice.get(boundDeviceId) === ws) {
        socketsByDevice.delete(boundDeviceId);
      }
    });
  });

  const purgeTimer = setInterval(() => {
    void store.purgeExpired();
  }, 60_000);

  return {
    app,
    server,
    wss,
    close: async () => {
      clearInterval(purgeTimer);
      wss.close();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}
