import admin from 'firebase-admin';

let initialized = false;

export function isFcmConfigured(): boolean {
  return initialized;
}

export function initFcm(serviceAccountPath?: string): boolean {
  if (!serviceAccountPath) return false;
  try {
    if (!admin.apps.length) {
      admin.initializeApp({
        credential: admin.credential.cert(serviceAccountPath),
      });
    }
    initialized = true;
    return true;
  } catch {
    return false;
  }
}

/**
 * Sends a zero-knowledge wake-up push notification.
 * Strictly NEVER includes message text, sender name, or media metadata.
 */
export async function sendZeroKnowledgeWakeupPush(
  fcmToken: string,
  eventType: 'MESSAGE_PENDING' | 'INCOMING_CALL'
): Promise<boolean> {
  if (!initialized || !fcmToken) return false;
  try {
    await admin.messaging().send({
      token: fcmToken,
      data: {
        eventType,
        ts: String(Date.now()),
      },
      android: {
        priority: 'high',
      },
    });
    return true;
  } catch {
    return false;
  }
}
