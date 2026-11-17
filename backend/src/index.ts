import { EnclaveStore } from './db/store';
import { createEnclaveServer } from './server';
import { initFcm } from './push/fcm';

async function main() {
  const port = Number(process.env.PORT || 8080);
  const host = process.env.HOST || '0.0.0.0';
  const store = new EnclaveStore();

  await store.init(process.env.DATABASE_URL, process.env.REDIS_URL);
  initFcm(process.env.FCM_SERVICE_ACCOUNT_JSON_PATH);

  const instance = createEnclaveServer(store, {
    port,
    stunUrl: process.env.STUN_URL || 'stun:stun.l.google.com:19302',
    turnUrl: process.env.TURN_URL || '',
    turnSharedSecret: process.env.TURN_SHARED_SECRET || 'dev_turn_secret_change_me',
    turnUsername: process.env.TURN_USERNAME || '',
    turnCredential: process.env.TURN_CREDENTIAL || '',
    publicBaseUrl: process.env.PUBLIC_BASE_URL || process.env.RENDER_EXTERNAL_URL || `http://localhost:${port}`,
    envelopeTtlHours: Number(process.env.ENVELOPE_TTL_HOURS || 168),
  });

  instance.server.listen(port, host, () => {
    console.log(`[Enclave Backend] Listening on http://${host}:${port} (Postgres: ${store.postgresStatus}, Redis: ${store.redisStatus})`);
  });
}

main().catch((err) => {
  console.error('[Enclave Backend] Fatal startup error:', err);
  process.exit(1);
});
