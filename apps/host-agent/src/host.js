import { SignalingClient } from './signaling.js';
import { ICE_SERVERS } from './rtcConfig.js';
import { attachFileReceiver, sendFile } from './fileTransfer.js';

// Tauri command bridge (present only inside the app; guarded for plain-browser).
const invoke = window.__TAURI__ && window.__TAURI__.core && window.__TAURI__.core.invoke;

const ICE = { iceServers: ICE_SERVERS };

const $ = (id) => document.getElementById(id);
let client = null;
let pc = null;
let pending = null; // { sessionId, controllerId }
let activeSession = null; // sessionId while a controller is connected
let stream = null; // the screen-capture MediaStream
let videoSender = null; // RTCRtpSender for the screen track (for quality + switch)
let controlChannel = null; // clipboard + quality control channel
let fileChannel = null; // file-transfer channel
let clipTimer = null; // clipboard poll interval
let lastClip = ''; // last clipboard text seen/applied (echo guard)
let perms = { input: true, clipboard: true, file: true }; // per-session grants

// Persist received files to disk via the native command.
const saveDownload = (name, data) => invoke('save_download', { name, data });

// Encoder profiles the controller can request. The host owns the encoder, so
// it holds the actual numbers; the controller just names a profile.
const QUALITY = {
  high:     { maxBitrate: 8_000_000, maxFramerate: 30, scaleResolutionDownBy: 1 },
  balanced: { maxBitrate: 2_500_000, maxFramerate: 20, scaleResolutionDownBy: 1 },
  saver:    { maxBitrate: 800_000,   maxFramerate: 12, scaleResolutionDownBy: 2 },
};

async function applyQuality(profile) {
  const q = QUALITY[profile];
  if (!q || !videoSender) return;
  const params = videoSender.getParameters();
  if (!params.encodings || !params.encodings.length) params.encodings = [{}];
  params.encodings[0].maxBitrate = q.maxBitrate;
  params.encodings[0].maxFramerate = q.maxFramerate;
  params.encodings[0].scaleResolutionDownBy = q.scaleResolutionDownBy;
  try {
    await videoSender.setParameters(params);
    log(`quality → ${profile}`);
  } catch (e) {
    log(`quality set failed: ${e}`);
  }
}

// Bidirectional clipboard: poll our OS clipboard and push changes; incoming
// text is written to the OS clipboard. lastClip guards against echo loops.
function startClipboardSync(channel) {
  stopClipboardSync();
  clipTimer = setInterval(async () => {
    if (!invoke || channel.readyState !== 'open') return;
    try {
      const txt = await invoke('get_clipboard');
      if (typeof txt === 'string' && txt && txt !== lastClip) {
        lastClip = txt;
        channel.send(JSON.stringify({ kind: 'clipboard', text: txt }));
      }
    } catch (_) {}
  }, 1200);
}
function stopClipboardSync() {
  if (clipTimer) { clearInterval(clipTimer); clipTimer = null; }
}

function log(s) {
  const li = document.createElement('li');
  li.textContent = s;
  $('log').prepend(li);
}
const setStatus = (s) => ($('status').textContent = s);

// --- in-session text chat (rides the control channel) ---
function appendChat(who, text) {
  const box = $('chatLog');
  if (!box) return;
  const row = document.createElement('div');
  row.className = 'chat-msg ' + (who === 'me' ? 'me' : 'them');
  row.textContent = text;
  box.appendChild(row);
  box.scrollTop = box.scrollHeight;
}
function sendChat() {
  const inp = $('chatInput');
  const text = (inp.value || '').trim();
  if (!text || !controlChannel || controlChannel.readyState !== 'open') return;
  controlChannel.send(JSON.stringify({ kind: 'chat', text }));
  appendChat('me', text);
  inp.value = '';
}

window.addEventListener('DOMContentLoaded', () => {
  // Prefill from last run.
  const saved = JSON.parse(localStorage.getItem('updesk-host-config') || '{}');
  if (saved.server) $('server').value = saved.server;
  if (saved.deviceId) $('deviceId').value = saved.deviceId;

  // Reflect the real launch-at-login state, and let the user toggle it.
  if (invoke) {
    invoke('get_autostart').then((on) => { $('autostart').checked = !!on; }).catch(() => {});
    $('autostart').addEventListener('change', (e) => {
      invoke('set_autostart', { enabled: e.target.checked }).catch((err) => log(`autostart: ${err}`));
    });
  }

  // Safe self-test: show the overlay, draw a test line, auto-hide after 3s so it
  // can never permanently cover the screen. Verifies transparency + click-through.
  $('testOverlayBtn').addEventListener('click', async () => {
    if (!invoke) return;
    try {
      await invoke('annotate_show');
      // A red diagonal + a horizontal line, so transparency is obvious.
      const strokes = [
        { x0: 0.2, y0: 0.2, x1: 0.8, y1: 0.8, color: '#ff2d55' },
        { x0: 0.2, y0: 0.5, x1: 0.8, y1: 0.5, color: '#00e0ff' },
      ];
      for (const s of strokes) await invoke('annotate_draw', { stroke: s });
      setStatus('overlay test: you should see red/blue lines — try clicking through them');
      setTimeout(() => invoke('annotate_hide').catch(() => {}), 3000);
    } catch (e) {
      setStatus(`overlay test failed (safe): ${e}`);
    }
  });

  $('connectBtn').addEventListener('click', start);
  $('acceptBtn').addEventListener('click', acceptAndShare);
  $('rejectBtn').addEventListener('click', reject);
  $('reconfigureBtn').addEventListener('click', reconfigure);
  $('stopBtn').addEventListener('click', stopSharing);
  $('switchBtn').addEventListener('click', switchScreen);
  $('sendFileBtn').addEventListener('click', () => $('fileInput').click());
  $('fileInput').addEventListener('change', async (e) => {
    const files = [...e.target.files];
    e.target.value = ''; // allow re-selecting the same files
    for (const f of files) await sendFile(fileChannel, f, { log }); // one at a time
  });
  $('chatSend').addEventListener('click', sendChat);
  $('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); sendChat(); }
  });
});

function start() {
  const server = $('server').value.trim();
  const deviceId = $('deviceId').value.trim();
  const enroll = $('enroll').value.trim() || undefined;
  localStorage.setItem('updesk-host-config', JSON.stringify({ server, deviceId }));

  client = new SignalingClient({ url: server, identityId: deviceId, kind: 'device', enrollCode: enroll });

  client.addEventListener('ready', async () => {
    setStatus('online — waiting for a controller');
    client.register({ os: 'windows', app: 'updesk-host' });
    // Show the controller exactly what to connect to on THIS network.
    if (invoke) {
      try {
        const ip = await invoke('local_ip');
        if (ip) {
          $('connectHint').innerHTML =
            `On the other PC's controller, connect to:<br><b>wss://${ip}:8080</b> &nbsp; (device: <b>${deviceId}</b>)`;
          $('connectHint').hidden = false;
        }
      } catch (_) {}
    }
  });

  client.addEventListener('incoming_request', (e) => {
    pending = e.detail;
    $('requester').textContent = pending.controllerId;
    $('request').hidden = false;
    setStatus('connection request received');
    log(`request from ${pending.controllerId}`);
  });

  client.addEventListener('answer', async (e) => {
    if (pc) await pc.setRemoteDescription({ type: 'answer', sdp: e.detail.sdp });
    log('answer applied');
  });

  client.addEventListener('ice_candidate', async (e) => {
    if (pc && e.detail.candidate) {
      try { await pc.addIceCandidate(e.detail.candidate); } catch (_) {}
    }
  });

  client.addEventListener('session_ended', teardown);
  client.addEventListener('peer_disconnected', teardown);

  client.addEventListener('reconnecting', (e) => {
    setStatus(`connection lost — reconnecting (try ${e.detail.attempt})…`);
  });
  client.addEventListener('reconnected', () => {
    setStatus('reconnected — online');
    log('reconnected to server');
  });
  client.addEventListener('disconnected', () => setStatus('disconnected'));

  client.addEventListener('error', (e) => {
    const { kind, message } = e.detail;
    if (kind === 'auth') {
      setStatus(`sign-in failed: ${message}`);
    } else if (kind === 'connect') {
      setStatus(message); // already a full, human hint
    } else {
      setStatus(`server: ${message}`);
    }
    log(`${kind} error: ${message}`);
  });

  $('config').hidden = true;
  $('live').hidden = false;
  setStatus('connecting…');
  client.connect();
}

function reconfigure() {
  if (client) client.close();
  client = null;
  localStorage.removeItem('updesk-host-config');
  $('server').value = 'wss://localhost:8080';
  $('enroll').value = '';
  $('live').hidden = true;
  $('config').hidden = false;
  $('log').innerHTML = '';
}

// Must run from a user gesture (button click) so getDisplayMedia is allowed.
async function acceptAndShare() {
  if (!pending) return;
  const { sessionId, controllerId } = pending;
  // Capture the host's per-session grants; these are enforced below (the host
  // is authoritative — a controller that ignores them is still blocked here).
  perms = {
    input: $('permInput').checked,
    clipboard: $('permClipboard').checked,
    file: $('permFile').checked,
  };
  $('request').hidden = true;
  client.respond(sessionId, true);
  activeSession = sessionId;
  $('controllerName').textContent = controllerId;
  $('banner').hidden = false;
  $('chat').hidden = false;
  setStatus(`sharing screen with ${controllerId}`);

  // audio: true asks Windows to include "share system audio" — if the user
  // ticks it in the picker, an audio track rides along and plays on the
  // controller's <video>.
  stream = await navigator.mediaDevices.getDisplayMedia({
    video: { frameRate: 30 },
    audio: true
  });

  pc = new RTCPeerConnection(ICE);
  stream.getTracks().forEach((t) => pc.addTrack(t, stream));
  videoSender = pc.getSenders().find((s) => s.track && s.track.kind === 'video');
  applyQuality('high'); // sensible LAN default; controller can change it

  // controller -> host input — dropped entirely if input isn't granted.
  const input = pc.createDataChannel('input');
  input.onmessage = (e) => {
    if (!perms.input) return;
    try { if (invoke) invoke('input_event', { event: JSON.parse(e.data) }); } catch (_) {}
  };

  // clipboard sync + quality + chat + the permission announcement
  controlChannel = pc.createDataChannel('control');
  controlChannel.onopen = () => {
    // Tell the controller what's allowed so it can reflect it in the UI.
    controlChannel.send(JSON.stringify({ kind: 'perms', ...perms }));
    if (perms.clipboard) startClipboardSync(controlChannel);
  };
  controlChannel.onmessage = (e) => {
    let m; try { m = JSON.parse(e.data); } catch (_) { return; }
    if (m.kind === 'clipboard') {
      if (!perms.clipboard) return;
      lastClip = m.text;
      if (invoke) invoke('set_clipboard', { text: m.text });
    } else if (m.kind === 'quality') {
      applyQuality(m.profile);
    } else if (m.kind === 'chat') {
      appendChat('them', m.text);
    } else if (m.kind === 'annotate') {
      // Controller is drawing on our screen. Lazily create the overlay on the
      // first draw so a normal session never spawns it.
      if (invoke) {
        if (m.op === 'draw') {
          invoke('annotate_show').then(() => invoke('annotate_draw', { stroke: m.stroke }));
        } else if (m.op === 'clear') {
          invoke('annotate_clear');
        }
      }
    }
  };

  // file transfer — only wire the receiver + expose the send button if granted.
  fileChannel = pc.createDataChannel('file');
  if (perms.file) attachFileReceiver(fileChannel, { log, save: saveDownload });
  $('sendFileBtn').hidden = !perms.file;


  pc.onicecandidate = (e) => {
    if (e.candidate) client.signal('ice_candidate', sessionId, { candidate: e.candidate });
  };
  pc.onconnectionstatechange = () => log(`pc: ${pc.connectionState}`);

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  client.signal('offer', sessionId, { sdp: offer.sdp });
  log('offer sent');
}

// Re-pick which screen is shared (multi-monitor) without renegotiating: swap
// the outgoing track in place via replaceTrack.
async function switchScreen() {
  if (!pc || !videoSender) return;
  try {
    const next = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: 30 },
      audio: true
    });
    // Replace each track (video, and audio if the new pick has it) in place.
    for (const track of next.getTracks()) {
      const sender = pc.getSenders().find((s) => s.track && s.track.kind === track.kind);
      if (sender) await sender.replaceTrack(track);
      else pc.addTrack(track, next);
    }
    if (stream) stream.getTracks().forEach((t) => t.stop()); // release the old screen
    stream = next;
    log('switched shared screen');
  } catch (_) {
    log('screen switch cancelled');
  }
}

function reject() {
  if (pending) client.respond(pending.sessionId, false);
  $('request').hidden = true;
  pending = null;
  setStatus('online — waiting for a controller');
}

function stopSharing() {
  if (activeSession && client) client.end(activeSession);
  teardown();
}

function teardown() {
  stopClipboardSync();
  if (invoke) invoke('annotate_hide').catch(() => {}); // close the overlay
  if (stream) { stream.getTracks().forEach((t) => t.stop()); stream = null; } // stop the OS screen share
  if (pc) { pc.close(); pc = null; }
  videoSender = null;
  controlChannel = null;
  fileChannel = null;
  lastClip = '';
  pending = null;
  activeSession = null;
  $('banner').hidden = true;
  $('chat').hidden = true;
  $('chatLog').innerHTML = '';
  setStatus('online — waiting for a controller');
  log('session ended');
}
