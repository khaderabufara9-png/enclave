# Enclave Setup, Cross-Country Cloud Deployment & Sharing Guide

## 1. Cross-Country Cloud Deployment (Different Networks / Different Countries)
To talk and message with friends across different Wi-Fi/4G/5G networks and countries, host the `/backend` folder on a public Linux VPS (Ubuntu/Debian) with a domain name pointing to the server IP.

### Step A: Firewall Ports on Your VPS
Open these ports:
- `80/tcp`, `443/tcp` (Caddy automatic HTTPS + WSS WebSocket signaling)
- `3478/tcp`, `3478/udp` (Coturn STUN/TURN server)
- `49152-65535/udp` (Coturn WebRTC media relay ports for symmetric NAT / mobile 4G/5G)

### Step B: Start Production Stack (Caddy HTTPS + Backend + Postgres + Redis + Coturn)
```bash
cd backend
export ENCLAVE_DOMAIN=enclave.yourdomain.com
export PUBLIC_SERVER_IP=<YOUR_VPS_PUBLIC_IP>
export TURN_SHARED_SECRET=$(openssl rand -hex 24)
export POSTGRES_PASSWORD=$(openssl rand -hex 16)
docker compose -f docker-compose.prod.yml up -d --build
```
Verify health at:
`https://enclave.yourdomain.com/api/v1/health`

### Step C (Optional): Closed-App Call Wake-Ups via FCM Push
1. Create a Firebase project in the Firebase Console and register Android app `com.aistudio.enclave.qxmpvr`.
2. Download `google-services.json` into `/app/google-services.json` before building the APK.
3. Download a Firebase Service Account JSON to `/backend/secrets/fcm-sa.json` and set `FCM_SERVICE_ACCOUNT_JSON_PATH=/app/secrets/fcm-sa.json` in `docker-compose.prod.yml`.
*(Alternatively, without Firebase/FCM, enable **Settings -> Notification Settings -> Keep Background Relay Active** on both phones.)*

---

## 2. Building & Sharing the APK With Your Friend
1. Optionally set your hosted domain in `.env` at the project root so the APK connects out of the box:
   ```properties
   ENCLAVE_BACKEND_URL=https://enclave.yourdomain.com
   ENCLAVE_TURN_URL=turn:enclave.yourdomain.com:3478
   ```
2. Build APK:
   ```bash
   gradle :app:assembleDebug
   ```
   *(Or configure `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` and run `gradle :app:assembleRelease`.)*
3. Send the APK to your friend. If you built without `.env`, configure your own phone in **Settings -> System Diagnostics -> Server & ICE Endpoints**, tap **Share**, and send the `enclave://server?...` link to your friend.
4. One person creates an invite under **Contacts -> Invite** and shares the 10-character code.

---

## 3. Running Locally (Emulator / Same LAN)
```bash
cd backend
cp .env.example .env
npm install
npm run dev
```
Run backend tests:
```bash
cd backend && npm test
```
Run Android unit & Robolectric tests:
```bash
gradle :app:testDebugUnitTest
```
