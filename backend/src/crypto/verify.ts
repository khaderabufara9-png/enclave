import crypto from 'crypto';

/**
 * Verifies an ECDSA P-256 signature produced by Android's SHA256withECDSA
 * over a canonical UTF-8 payload using the device's X.509 SPKI Base64 public key.
 */
export function verifyEcdsaSignature(
  signingPublicKeyB64: string,
  canonicalData: string,
  signatureB64: string
): boolean {
  try {
    const spkiDer = Buffer.from(signingPublicKeyB64, 'base64');
    const publicKey = crypto.createPublicKey({
      key: spkiDer,
      format: 'der',
      type: 'spki',
    });
    const verifier = crypto.createVerify('SHA256');
    verifier.update(Buffer.from(canonicalData, 'utf8'));
    verifier.end();
    return verifier.verify(publicKey, Buffer.from(signatureB64, 'base64'));
  } catch {
    return false;
  }
}

/**
 * Generates RFC 5766 / Coturn time-limited HMAC-SHA1 TURN credentials.
 */
export function generateTurnCredentials(
  deviceId: string,
  sharedSecret: string,
  ttlSeconds = 3600
): { username: string; credential: string; ttlSeconds: number } {
  const expiry = Math.floor(Date.now() / 1000) + ttlSeconds;
  const username = `${expiry}:${deviceId}`;
  const credential = crypto
    .createHmac('sha1', sharedSecret)
    .update(username)
    .digest('base64');
  return { username, credential, ttlSeconds };
}

export function generateRandomToken(prefix = 'tok'): string {
  return `${prefix}_${crypto.randomBytes(16).toString('hex')}`;
}
