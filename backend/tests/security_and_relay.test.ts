import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { EnclaveStore } from '../src/db/store';
import { createEnclaveServer } from '../src/server';

function generateDeviceIdentity(id: string) {
  const signing = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const ecdh = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return {
    id,
    signingPubB64: (signing.publicKey.export({ format: 'der', type: 'spki' }) as Buffer).toString('base64'),
    signingPriv: signing.privateKey,
    ecdhPubB64: (ecdh.publicKey.export({ format: 'der', type: 'spki' }) as Buffer).toString('base64'),
  };
}

function sign(priv: crypto.KeyObject, data: string): string {
  const s = crypto.createSign('SHA256');
  s.update(Buffer.from(data, 'utf8'));
  s.end();
  return s.sign(priv).toString('base64');
}

test('Enclave backend enforces ECDSA auth, zero-knowledge offline relay, anti-replay, and revocation', async () => {
  const store = new EnclaveStore();
  await store.init();
  const srv = createEnclaveServer(store, {
    port: 0,
    stunUrl: 'stun:stun.l.google.com:19302',
    turnUrl: '',
    turnSharedSecret: 'test_secret',
    publicBaseUrl: 'http://localhost',
    envelopeTtlHours: 24,
  });

  await new Promise<void>((resolve) => srv.server.listen(0, '127.0.0.1', resolve));
  const addr = srv.server.address() as { port: number };
  const baseUrl = `http://127.0.0.1:${addr.port}`;

  try {
    const alice = generateDeviceIdentity('enc_alice_01');
    const bob = generateDeviceIdentity('enc_bob_02');

    // 1. Register both anonymous devices
    for (const dev of [alice, bob]) {
      const ts = Date.now();
      const res = await fetch(`${baseUrl}/api/v1/devices/register`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          installationId: dev.id,
          signingPublicKeyB64: dev.signingPubB64,
          ecdhPublicKeyB64: dev.ecdhPubB64,
          signatureB64: sign(dev.signingPriv, `register|${dev.id}|${dev.signingPubB64}|${dev.ecdhPubB64}|${ts}`),
          timestampMs: ts,
        }),
      });
      assert.equal(res.status, 200);
    }

    // 2. Reject forged signature
    const forgedRes = await fetch(`${baseUrl}/api/v1/devices/register`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        installationId: 'enc_forged_99',
        signingPublicKeyB64: alice.signingPubB64,
        ecdhPublicKeyB64: alice.ecdhPubB64,
        signatureB64: Buffer.from('forged_ecdsa_signature_bytes_0001').toString('base64'),
        timestampMs: Date.now(),
      }),
    });
    assert.equal(forgedRes.status, 401);

    // 3. Publish & accept invitation
    const exp = Date.now() + 3600_000;
    const invSig = sign(alice.signingPriv, `inv_1|CODE1234|${alice.id}|${alice.signingPubB64}|${alice.ecdhPubB64}|${exp}`);
    const invRes = await fetch(`${baseUrl}/api/v1/invitations`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        invitationId: 'inv_1',
        code: 'CODE1234',
        inviterDeviceId: alice.id,
        inviterSigningPublicKeyB64: alice.signingPubB64,
        inviterEcdhPublicKeyB64: alice.ecdhPubB64,
        offeredDisplayName: 'Alice',
        expiresAtMs: exp,
        signatureB64: invSig,
      }),
    });
    assert.equal(invRes.status, 200);

    const accRes = await fetch(`${baseUrl}/api/v1/invitations/CODE1234/accept`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        relationshipId: 'rel_1',
        responderDeviceId: bob.id,
        responderSigningPublicKeyB64: bob.signingPubB64,
        responderEcdhPublicKeyB64: bob.ecdhPubB64,
        responderDisplayName: 'Bob',
        signatureB64: sign(bob.signingPriv, `accept|CODE1234|rel_1|${bob.id}`),
      }),
    });
    assert.equal(accRes.status, 200);

    // 4. Send encrypted offline envelope from Alice -> Bob (server sees ciphertext only)
    const sentAt = Date.now();
    const ivB64 = crypto.randomBytes(12).toString('base64');
    const ciphertextB64 = Buffer.from('opaque_aes_gcm_ciphertext').toString('base64');
    const msgSig = sign(alice.signingPriv, `msg_1|rel_1|${alice.id}|${bob.id}|${ivB64}|${ciphertextB64}|1|${sentAt}`);
    const relayRes = await fetch(`${baseUrl}/api/v1/messages/relay`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        messageId: 'msg_1',
        relationshipId: 'rel_1',
        senderDeviceId: alice.id,
        recipientDeviceId: bob.id,
        ivB64,
        ciphertextB64,
        sequence: 1,
        ttlSeconds: 30,
        signatureB64: msgSig,
        sentAtMs: sentAt,
      }),
    });
    assert.equal(relayRes.status, 200);
    const relayBody = (await relayRes.json()) as { status: string };
    assert.equal(relayBody.status, 'QUEUED_OFFLINE');

    // 5. Replay attack with identical messageId must be rejected (409)
    const replayRes = await fetch(`${baseUrl}/api/v1/messages/relay`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        messageId: 'msg_1',
        relationshipId: 'rel_1',
        senderDeviceId: alice.id,
        recipientDeviceId: bob.id,
        ivB64,
        ciphertextB64,
        sequence: 1,
        ttlSeconds: 30,
        signatureB64: msgSig,
        sentAtMs: sentAt,
      }),
    });
    assert.equal(replayRes.status, 409);

    // 6. Bob fetches pending inbox & acks to purge ciphertext
    const fetchTs = Date.now();
    const pendingRes = await fetch(`${baseUrl}/api/v1/messages/pending/${bob.id}`, {
      headers: {
        'x-enclave-timestamp': String(fetchTs),
        'x-enclave-signature': sign(bob.signingPriv, `pending|${bob.id}|${fetchTs}`),
      },
    });
    assert.equal(pendingRes.status, 200);
    const pendingData = (await pendingRes.json()) as { envelopes: Array<{ messageId: string }> };
    assert.equal(pendingData.envelopes.length, 1);

    const ackRes = await fetch(`${baseUrl}/api/v1/messages/ack`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        messageId: 'msg_1',
        recipientDeviceId: bob.id,
        signatureB64: sign(bob.signingPriv, `ack|msg_1|${bob.id}`),
      }),
    });
    assert.equal(ackRes.status, 200);
    assert.equal((await store.getPendingForDevice(bob.id)).length, 0);

    // 7. Bob revokes relationship; Alice can no longer relay messages on rel_1
    const revokeRes = await fetch(`${baseUrl}/api/v1/relationships/action`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        relationshipId: 'rel_1',
        actorDeviceId: bob.id,
        peerDeviceId: alice.id,
        action: 'REVOKE',
        signatureB64: sign(bob.signingPriv, `rel-action|rel_1|${bob.id}|REVOKE`),
      }),
    });
    assert.equal(revokeRes.status, 200);

    const blockedRes = await fetch(`${baseUrl}/api/v1/messages/relay`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        messageId: 'msg_2',
        relationshipId: 'rel_1',
        senderDeviceId: alice.id,
        recipientDeviceId: bob.id,
        ivB64,
        ciphertextB64,
        sequence: 2,
        ttlSeconds: 0,
        signatureB64: sign(alice.signingPriv, `msg_2|rel_1|${alice.id}|${bob.id}|${ivB64}|${ciphertextB64}|2|${sentAt}`),
        sentAtMs: sentAt,
      }),
    });
    assert.equal(blockedRes.status, 403);

    // 8. Temporary Click-to-Call token & guest HTML check
    const tokRes = await fetch(`${baseUrl}/api/v1/tokens/temporary`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        purpose: 'CLICK_TO_CALL',
        deviceId: alice.id,
        ttlSeconds: 300,
        signatureB64: sign(alice.signingPriv, `token|CLICK_TO_CALL|${alice.id}|`),
      }),
    });
    assert.equal(tokRes.status, 200);
    const tokBody = (await tokRes.json()) as { token: string };
    const guestPage = await fetch(`${baseUrl}/call/${tokBody.token}`);
    assert.equal(guestPage.status, 200);

    // 9. Encrypted media relay upload & ECDSA-authenticated download
    const mediaId = 'med_video_001';
    const upTs = Date.now();
    const mediaCipherB64 = Buffer.from('aes_gcm_encrypted_video_bytes_payload').toString('base64');
    const upRes = await fetch(`${baseUrl}/api/v1/media/upload`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        mediaId,
        uploaderDeviceId: alice.id,
        ciphertextB64: mediaCipherB64,
        sizeBytes: 27,
        timestampMs: upTs,
        signatureB64: sign(alice.signingPriv, `media-upload|${mediaId}|${alice.id}|${upTs}`),
      }),
    });
    assert.equal(upRes.status, 200);

    // Unauthorized download without valid ECDSA signature must fail with 401
    const badDlRes = await fetch(`${baseUrl}/api/v1/media/${mediaId}`, {
      headers: {
        'x-enclave-device-id': bob.id,
        'x-enclave-timestamp': String(Date.now()),
        'x-enclave-signature': Buffer.from('forged_media_download_sig_0001').toString('base64'),
      },
    });
    assert.equal(badDlRes.status, 401);

    // Authorized peer download succeeds and returns exact ciphertext
    const dlTs = Date.now();
    const dlRes = await fetch(`${baseUrl}/api/v1/media/${mediaId}`, {
      headers: {
        'x-enclave-device-id': bob.id,
        'x-enclave-timestamp': String(dlTs),
        'x-enclave-signature': sign(bob.signingPriv, `media-download|${mediaId}|${bob.id}|${dlTs}`),
      },
    });
    assert.equal(dlRes.status, 200);
    const dlBody = (await dlRes.json()) as { ciphertextB64: string };
    assert.equal(dlBody.ciphertextB64, mediaCipherB64);

    // 10. Signed FCM push token registration + offline call signal buffering
    const badPushRes = await fetch(`${baseUrl}/api/v1/push/register`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        deviceId: bob.id,
        fcmToken: 'fcm_tok_test_1234567890',
        signatureB64: Buffer.from('bad_push_signature_0001').toString('base64'),
      }),
    });
    assert.equal(badPushRes.status, 401);

    const pushToken = 'fcm_tok_valid_bob_1234567890';
    const goodPushRes = await fetch(`${baseUrl}/api/v1/push/register`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        deviceId: bob.id,
        fcmToken: pushToken,
        signatureB64: sign(bob.signingPriv, `push-register|${bob.id}|${pushToken}`),
      }),
    });
    assert.equal(goodPushRes.status, 200);

    // Simulate offline incoming call_offer queued for Bob while Bob's WS is closed
    await store.savePendingSignal(
      bob.id,
      JSON.stringify({ type: 'call_offer', callId: 'call_offline_1', fromDeviceId: alice.id, isVideo: true }),
      60_000
    );
    const callTs = Date.now();
    const pendingCallsRes = await fetch(`${baseUrl}/api/v1/calls/pending/${bob.id}`, {
      headers: {
        'x-enclave-timestamp': String(callTs),
        'x-enclave-signature': sign(bob.signingPriv, `pending-calls|${bob.id}|${callTs}`),
      },
    });
    assert.equal(pendingCallsRes.status, 200);
    const pendingCallsBody = (await pendingCallsRes.json()) as { signals: string[] };
    assert.equal(pendingCallsBody.signals.length, 1);
    assert.equal(JSON.parse(pendingCallsBody.signals[0]).callId, 'call_offline_1');

    // Drained signals should not repeat
    const secondCallTs = Date.now();
    const drainedRes = await fetch(`${baseUrl}/api/v1/calls/pending/${bob.id}`, {
      headers: {
        'x-enclave-timestamp': String(secondCallTs),
        'x-enclave-signature': sign(bob.signingPriv, `pending-calls|${bob.id}|${secondCallTs}`),
      },
    });
    const drainedBody = (await drainedRes.json()) as { signals: string[] };
    assert.equal(drainedBody.signals.length, 0);

    // Health endpoint exposes TURN/FCM status
    const healthRes = await fetch(`${baseUrl}/api/v1/health`);
    assert.equal(healthRes.status, 200);
    const healthBody = (await healthRes.json()) as { turnConfigured: boolean; fcmConfigured: boolean };
    assert.equal(healthBody.turnConfigured, false);
    assert.equal(healthBody.fcmConfigured, false);

    // Static TURN credentials override works for free external TURN services
    const staticTurnSrv = createEnclaveServer(store, {
      port: 0,
      stunUrl: 'stun:stun.l.google.com:19302',
      turnUrl: 'turn:free-relay.example:3478',
      turnSharedSecret: 'unused',
      turnUsername: 'free_user',
      turnCredential: 'free_pass',
      publicBaseUrl: 'http://localhost',
      envelopeTtlHours: 24,
    });
    await new Promise<void>((resolve) => staticTurnSrv.server.listen(0, '127.0.0.1', resolve));
    try {
      const sAddr = staticTurnSrv.server.address() as { port: number };
      const iceRes = await fetch(`http://127.0.0.1:${sAddr.port}/api/v1/rtc/ice-config/${bob.id}`);
      assert.equal(iceRes.status, 200);
      const iceBody = (await iceRes.json()) as { urls: string[]; username: string; credential: string };
      assert.equal(iceBody.username, 'free_user');
      assert.equal(iceBody.credential, 'free_pass');
      assert.ok(iceBody.urls.includes('turn:free-relay.example:3478'));
    } finally {
      await staticTurnSrv.close();
    }
  } finally {
    await srv.close();
    await store.close();
  }
});
