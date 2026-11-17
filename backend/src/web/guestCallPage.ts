/**
 * Lightweight browser WebRTC guest page for Click-to-Call & Desktop Call Transfer.
 */
export function renderGuestCallHtml(token: string, purpose: string, stunUrl: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Enclave Secure Browser Call</title>
  <style>
    body { font-family: system-ui, sans-serif; background: #090F1A; color: #F1F5F9; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
    .card { background: #0F172A; border: 1px solid #2B3952; border-radius: 20px; padding: 28px; max-width: 440px; width: 90%; box-shadow: 0 20px 50px rgba(0,0,0,0.45); }
    h1 { margin-top: 0; font-size: 1.4rem; color: #00D2B8; }
    input, button { width: 100%; padding: 12px; border-radius: 10px; border: 1px solid #2B3952; margin-top: 10px; box-sizing: border-box; font-size: 1rem; }
    input { background: #172338; color: #F1F5F9; }
    button { background: #00D2B8; color: #090F1A; font-weight: 700; border: none; cursor: pointer; }
    .status { margin-top: 14px; font-size: 0.9rem; color: #94A3B8; }
    video { width: 100%; border-radius: 12px; margin-top: 12px; background: #000; display: none; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Enclave ${purpose === 'CALL_TRANSFER' ? 'Call Transfer' : 'Click-to-Call'}</h1>
    <p>Temporary token: <code>${token}</code> (DTLS-SRTP encrypted WebRTC)</p>
    <input id="guestName" type="text" placeholder="Your display name" value="Browser Guest" />
    <button id="joinBtn">Request Secure Audio/Video Session</button>
    <div id="status" class="status">Ready. Host must approve incoming guest call.</div>
    <div id="sharedNotice" class="status"></div>
    <video id="remoteVideo" autoplay playsinline></video>
  </div>
  <script>
    const btn = document.getElementById('joinBtn');
    const statusEl = document.getElementById('status');
    const sharedEl = document.getElementById('sharedNotice');
    const remoteVideo = document.getElementById('remoteVideo');
    btn.addEventListener('click', async () => {
      const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const ws = new WebSocket(wsProto + '//' + location.host + '/ws?guestToken=${token}');
      statusEl.textContent = 'Connecting signaling...';
      const pc = new RTCPeerConnection({ iceServers: [{ urls: '${stunUrl}' }] });
      pc.ontrack = (ev) => {
        if (ev.streams && ev.streams[0]) {
          remoteVideo.srcObject = ev.streams[0];
          remoteVideo.style.display = 'block';
        }
      };
      ws.onopen = () => {
        ws.send(JSON.stringify({
          type: 'guest_click_to_call',
          token: '${token}',
          guestName: document.getElementById('guestName').value || 'Guest'
        }));
        statusEl.textContent = 'Waiting for Enclave host approval...';
      };
      ws.onmessage = async (ev) => {
        const msg = JSON.parse(ev.data);
        if (msg.type === 'call_offer' && msg.sdp) {
          await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
          const ans = await pc.createAnswer();
          await pc.setLocalDescription(ans);
          ws.send(JSON.stringify({ type: 'call_answer', callId: msg.callId, sdp: ans.sdp }));
          statusEl.textContent = 'Encrypted WebRTC call active.';
        } else if (msg.type === 'screen_share_state') {
          statusEl.textContent = msg.isSharing ? 'Peer is sharing screen (DTLS-SRTP).' : 'Encrypted WebRTC call active.';
        } else if (msg.type === 'call_shared_item') {
          sharedEl.textContent = 'Shared in call: ' + (msg.fileName || 'Encrypted media');
        }
      };
    });
  </script>
</body>
</html>`;
}
