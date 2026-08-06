import { SignalingClient, verifySig, keyFingerprint } from './signaling.js';
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
let currentPin = ''; // PIN a controller must supply to connect
// Unattended access: a permanent password + a pre-armed screen stream, so a
// controller with the password connects with no per-session Accept prompt.
let unattendedStream = null;
let unattendedSession = false; // is the current session an unattended one?
let peerE2E = null;            // {fp, sig, pub} the controller signed
let activeControllerId = null; // for TOFU identity-key pinning
const getUnattendedPw = () => (localStorage.getItem('updesk-unattended-pw') || '');

// A stable per-install device identity (auto-generated once, kept in localStorage).
// The human-facing 9-digit "Your ID" comes from the server, derived from this.
function deviceIdentity() {
  let id = localStorage.getItem('updesk-device-id');
  if (!id) {
    id = 'host-' + Math.random().toString(36).slice(2, 10);
    localStorage.setItem('updesk-device-id', id);
  }
  return id;
}
const genPin = () => String(Math.floor(100000 + Math.random() * 900000)); // 6 digits (harder to brute-force; pairs with server rate-limit)

// Persist received files to disk via the native command.
const saveDownload = (name, data) => invoke('save_download', { name, data });

// Encoder profiles the controller can request. The host owns the encoder, so
// it holds the actual numbers; the controller just names a profile.
const QUALITY = {
  high:     { maxBitrate: 8_000_000, maxFramerate: 30, scaleResolutionDownBy: 1 },
  balanced: { maxBitrate: 2_500_000, maxFramerate: 20, scaleResolutionDownBy: 1 },
  saver:    { maxBitrate: 800_000,   maxFramerate: 12, scaleResolutionDownBy: 2 },
};

// Adaptive bitrate state. The controller's chosen profile sets the CEILING; the
// adaptation loop only ever moves the live bitrate between a floor and that
// ceiling based on measured loss/RTT — so a manual "saver" choice is still
// honoured as the maximum.
const ADAPT_FLOOR = 300_000;
// Cap the ENCODED long edge. A 1440p/4K desktop sent full-res over a cellular
// relay congests and makes keyframes huge (a lost keyframe = a long freeze).
// Capping to ~1600px — like the native + Android hosts — keeps it smooth and lets
// the stream recover fast. baseScale is the divisor computed from the real screen.
const MAX_ENCODE_EDGE = 1600;
// Start every session at a modest live bitrate and let the adaptive loop CLIMB if
// the link is healthy — instead of blasting the 8 Mbps ceiling and overshooting
// down (which is the "stutters every few seconds" symptom over cellular).
const INITIAL_BITRATE = 2_500_000;
let baseScale = 1;
let qualityCeiling = 8_000_000;
let adaptTimer = null;
let lastLoss = null; // {lost, sent} snapshot for delta loss ratio

// Divisor so the encoded long edge is at most MAX_ENCODE_EDGE (screen kept sharp
// enough on a desktop viewer, but far lighter to encode + relay).
function computeBaseScale(stream) {
  baseScale = 1;
  try {
    const s = stream.getVideoTracks()[0]?.getSettings?.() || {};
    const longEdge = Math.max(s.width || 0, s.height || 0);
    if (longEdge > MAX_ENCODE_EDGE) baseScale = longEdge / MAX_ENCODE_EDGE;
  } catch (_) {}
}

async function applyQuality(profile) {
  const q = QUALITY[profile];
  if (!q || !videoSender) return;
  qualityCeiling = q.maxBitrate;
  lastLoss = null;
  const params = videoSender.getParameters();
  if (!params.encodings || !params.encodings.length) params.encodings = [{}];
  // Start at a conservative live bitrate (adaptTick climbs toward the ceiling if
  // the link is healthy) so a cellular/relay path isn't blasted on connect.
  params.encodings[0].maxBitrate = Math.min(q.maxBitrate, INITIAL_BITRATE);
  params.encodings[0].maxFramerate = q.maxFramerate;
  // Cap resolution to MAX_ENCODE_EDGE (baseScale) on top of the profile's own
  // downscale — the biggest single win for smoothness + fast recovery.
  params.encodings[0].scaleResolutionDownBy = baseScale * q.scaleResolutionDownBy;
  // Latency tuning: under CPU/network pressure, shed *resolution* not frame
  // rate, so pointer movement and scrolling stay responsive instead of stuttery
  // (a stutter reads as "lag" far more than a momentary softness). And mark the
  // screen stream high priority so it wins bandwidth over data channels.
  params.degradationPreference = 'maintain-framerate';
  params.encodings[0].networkPriority = 'high';
  params.encodings[0].priority = 'high';
  try {
    await videoSender.setParameters(params);
    log(`quality → ${profile}`);
  } catch (e) {
    log(`quality set failed: ${e}`);
  }
}

// Adaptive bitrate — RustDesk-style congestion response, our own logic. Every
// few seconds we read the receiver's loss + round-trip time from getStats and
// nudge the encoder's max bitrate: back off hard when the link is hurting,
// recover gently when it's healthy. Hysteresis (asymmetric step sizes +
// requiring a clean sample to climb) keeps it from oscillating.
function startAdaptive() {
  stopAdaptive();
  lastLoss = null;
  adaptTimer = setInterval(adaptTick, 2500);
}
function stopAdaptive() {
  clearInterval(adaptTimer);
  adaptTimer = null;
}
async function adaptTick() {
  if (!videoSender || !pc || pc.connectionState !== 'connected') return;
  let outbound = null, remote = null;
  try {
    const stats = await pc.getStats();
    stats.forEach((r) => {
      if (r.type === 'outbound-rtp' && r.kind === 'video') outbound = r;
      if (r.type === 'remote-inbound-rtp' && r.kind === 'video') remote = r;
    });
  } catch (_) { return; }
  if (!remote) return;

  const rttMs = remote.roundTripTime != null ? remote.roundTripTime * 1000 : null;
  let loss = 0;
  const lost = remote.packetsLost || 0;
  const sent = outbound ? (outbound.packetsSent || 0) : 0;
  if (lastLoss) {
    const dLost = lost - lastLoss.lost;
    const dSent = sent - lastLoss.sent;
    if (dSent > 0) loss = Math.max(0, dLost) / dSent;
  }
  lastLoss = { lost, sent };

  const params = videoSender.getParameters();
  if (!params.encodings || !params.encodings.length) return;
  const cur = params.encodings[0].maxBitrate || qualityCeiling;
  let next = cur;
  if (loss > 0.03 || (rttMs != null && rttMs > 300)) {
    next = Math.max(ADAPT_FLOOR, Math.round(cur * 0.6));      // hurting → back off HARD
  } else if (loss < 0.01 && (rttMs == null || rttMs < 180)) {
    next = Math.min(qualityCeiling, Math.round(cur * 1.08));  // healthy → recover GENTLY
  }
  if (next !== cur) {
    params.encodings[0].maxBitrate = next;
    try { await videoSender.setParameters(params); } catch (_) {}
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
  // Regenerate the PIN on demand.
  $('newPinBtn').addEventListener('click', () => {
    currentPin = genPin();
    $('myPin').textContent = currentPin;
  });

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

  // Prefill the unattended password and wire arm/disarm.
  $('unattendedPw').value = getUnattendedPw();
  $('armBtn').addEventListener('click', armUnattended);
});

async function armUnattended() {
  if (unattendedStream) { // already armed -> disarm
    if (unattendedStream !== stream) { // don't kill a live session's stream
      unattendedStream.getTracks().forEach((t) => t.stop());
    }
    unattendedStream = null;
    $('armBtn').textContent = 'Enable unattended (pick screen)';
    setStatus('online — waiting for a controller');
    log('unattended access disabled');
    return;
  }
  const pw = $('unattendedPw').value.trim();
  if (!pw) { setStatus('set an unattended password first'); return; }
  localStorage.setItem('updesk-unattended-pw', pw);
  try {
    unattendedStream = await captureScreen(); // gesture from this button click
  } catch (_) {
    setStatus('screen pick cancelled — unattended not armed');
    return;
  }
  $('armBtn').textContent = 'Disable unattended';
  setStatus('online — unattended armed (controllers can connect silently)');
  log('unattended access armed');
}

function start() {
  const server = $('server').value.trim();
  const deviceId = deviceIdentity(); // stable auto-generated identity
  currentPin = genPin();
  $('myPin').textContent = currentPin;

  // No enroll code — the server runs open enrollment; the ID+PIN is the gate.
  client = new SignalingClient({ url: server, identityId: deviceId, kind: 'device' });

  client.addEventListener('ready', () => {
    setStatus('online — waiting for a connection');
    client.register({ os: 'windows', app: 'updesk-host' });
  });

  // Server assigns the human-facing 9-digit connect ID.
  client.addEventListener('registered', (e) => {
    const id = e.detail.connectId || '';
    $('myId').textContent = id.replace(/(\d{3})(\d{3})(\d{3})/, '$1 $2 $3') || '—';
    $('idCard').hidden = false;
  });

  client.addEventListener('incoming_request', (e) => {
    const supplied = e.detail.pin || '';
    const unattendedPw = getUnattendedPw();
    // Unattended: if the password matches AND the screen is armed, auto-accept
    // silently — no Accept prompt, full permissions, reusing the armed stream.
    if (unattendedStream && unattendedPw && supplied === unattendedPw) {
      log(`unattended connect from ${e.detail.controllerId}`);
      startSharing(e.detail.sessionId, e.detail.controllerId, unattendedStream,
        { input: true, clipboard: true, file: true }, true);
      return;
    }
    // Attended: PIN gate, then the normal Accept prompt.
    if (supplied !== currentPin) {
      client.respond(e.detail.sessionId, false);
      setStatus('a connection was rejected (wrong PIN)');
      log(`rejected ${e.detail.controllerId}: wrong PIN`);
      return;
    }
    pending = e.detail;
    $('requester').textContent = 'Someone with your PIN';
    $('request').hidden = false;
    setStatus('connection request — PIN correct');
    log(`request from ${pending.controllerId} (PIN ok)`);
  });

  client.addEventListener('answer', async (e) => {
    if (pc) await pc.setRemoteDescription({ type: 'answer', sdp: e.detail.sdp });
    log('answer applied');
    verifyE2E(activeControllerId);
  });

  // Controller's signed DTLS fingerprint for the end-to-end check.
  client.addEventListener('e2e', (e) => {
    peerE2E = { fp: (e.detail.fp || '').toUpperCase(), sig: e.detail.sig, pub: e.detail.pub };
    verifyE2E(activeControllerId);
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

function reconfigure() { // "Go offline"
  if (client) client.close();
  client = null;
  $('idCard').hidden = true;
  $('live').hidden = true;
  $('config').hidden = false;
  $('log').innerHTML = '';
}

// Must run from a user gesture (button click) so getDisplayMedia is allowed.
async function acceptAndShare() {
  if (!pending) return;
  const { sessionId, controllerId } = pending;
  const grantedPerms = {
    input: $('permInput').checked,
    clipboard: $('permClipboard').checked,
    file: $('permFile').checked,
  };
  $('request').hidden = true;
  let captured;
  try {
    captured = await captureScreen();
  } catch (err) {
    client.respond(sessionId, false);
    setStatus('screen share cancelled');
    return;
  }
  startSharing(sessionId, controllerId, captured, grantedPerms, false);
}

// getDisplayMedia must run from a user gesture (button click / the arm button).
function captureScreen() {
  return navigator.mediaDevices.getDisplayMedia({
    video: { frameRate: 30 },
    audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    systemAudio: 'include'
  });
}

// Set up the peer connection from an already-captured screen stream. Shared by
// the attended path (acceptAndShare) and the unattended path (auto-accept with
// a matching unattended password, reusing a pre-armed stream).
// ---- End-to-end verification ----
// Pull the DTLS certificate fingerprint out of an SDP. WebRTC binds the media
// encryption keys to this cert, so signing it with our long-term identity key
// lets the peer confirm the encrypted channel really terminates at us — and not
// at a relay/server that quietly rewrote the handshake to wiretap.
function extractDtlsFp(sdp) {
  const m = (sdp || '').match(/a=fingerprint:sha-256\s+([0-9A-Fa-f:]+)/i);
  return m ? m[1].toUpperCase() : '';
}

// Verify once we hold both the peer's signed fingerprint and its actual SDP.
async function verifyE2E(peerId) {
  if (!peerE2E || !pc || !pc.remoteDescription) return; // need both halves
  const sdpFp = extractDtlsFp(pc.remoteDescription.sdp);
  const { fp, sig, pub } = peerE2E;
  peerE2E = null; // consume
  const matches = !!fp && fp === sdpFp;                 // signed fp == real fp
  const signed = matches && (await verifySig(pub, fp, sig)); // by claimed identity
  const pinKey = 'updesk-pin-' + (peerId || 'peer');
  const pinned = localStorage.getItem(pinKey);
  const keyOk = signed && (!pinned || pinned === pub);  // TOFU: unchanged identity
  if (signed && !pinned) localStorage.setItem(pinKey, pub);
  if (matches && signed && keyOk) {
    const fpr = await keyFingerprint(pub);
    setStatus('🔒 connection end-to-end verified');
    log('E2E VERIFIED — controller key: ' + fpr);
  } else {
    const why = !matches ? 'media handshake was altered in transit'
      : !signed ? 'identity signature invalid'
      : 'controller identity key CHANGED since last time';
    setStatus('⚠ NOT end-to-end verified — ' + why);
    log('⚠ SECURITY WARNING: E2E verification FAILED — ' + why);
  }
}

async function startSharing(sessionId, controllerId, capturedStream, grantedPerms, unattended) {
  activeControllerId = controllerId;
  perms = grantedPerms;
  stream = capturedStream;
  unattendedSession = !!unattended;
  activeSession = sessionId;
  client.respond(sessionId, true);
  $('controllerName').textContent = controllerId;
  $('banner').hidden = false;
  $('chat').hidden = false;
  setStatus(unattended ? `unattended session with ${controllerId}` : `sharing screen with ${controllerId}`);
  const nAudio = stream.getAudioTracks().length;
  const nVideo = stream.getVideoTracks().length;
  log(`sharing ${nVideo} video + ${nAudio} audio track(s)`);

  pc = new RTCPeerConnection(ICE);
  // Tell the encoder this is screen content: 'detail' keeps text/edges crisp
  // when the frame is static, while degradationPreference (below) still favours
  // frame rate when things move — the balance a remote desktop wants.
  stream.getVideoTracks().forEach((t) => { try { t.contentHint = 'detail'; } catch (_) {} });
  stream.getTracks().forEach((t) => pc.addTrack(t, stream));
  videoSender = pc.getSenders().find((s) => s.track && s.track.kind === 'video');
  computeBaseScale(stream);   // cap encoded resolution to the screen's real size
  applyQuality('high'); // ceiling; live bitrate starts modest and climbs if healthy

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
    } else if (m.kind === 'netstat') {
      // Controller asked for our active connections — snapshot and reply.
      if (invoke) invoke('net_connections').then((res) => {
        try { controlChannel.send(JSON.stringify({ kind: 'netstat-result', ...res })); } catch (_) {}
      }).catch(() => {});
    } else if (m.kind === 'vpn') {
      if (invoke) invoke('vpn_status').then((res) => {
        try { controlChannel.send(JSON.stringify({ kind: 'vpn-result', ...res })); } catch (_) {}
      }).catch(() => {});
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

  // Remote file browser (forensic extraction from this PC), if files permitted.
  if (perms.file && invoke) {
    const fs = pc.createDataChannel('fs');
    attachFsHost(fs);
  }


  pc.onicecandidate = (e) => {
    if (e.candidate) client.signal('ice_candidate', sessionId, { candidate: e.candidate });
  };

  // Mid-session recovery: if the P2P path drops (Wi-Fi flap, network switch,
  // NAT rebinding) we renegotiate ICE in place instead of killing the session.
  // The host is the offerer, so it drives the restart; the controller answers
  // on its existing connection. A guard + cooldown prevents restart storms.
  let iceRestarting = false;
  let disconnectTimer = null;
  const restartIce = async () => {
    if (iceRestarting || !pc) return;
    iceRestarting = true;
    log('media link degraded — restarting ICE');
    setStatus('reconnecting to controller…');
    try {
      const o = await pc.createOffer({ iceRestart: true });
      await pc.setLocalDescription(o);
      client.signal('offer', sessionId, { sdp: o.sdp });
    } catch (err) {
      log('ICE restart failed: ' + err);
    }
    setTimeout(() => { iceRestarting = false; }, 8000); // cooldown before another try
  };
  pc.onconnectionstatechange = () => {
    log(`pc: ${pc.connectionState}`);
    if (pc.connectionState === 'failed') {
      restartIce();
    } else if (pc.connectionState === 'connected') {
      clearTimeout(disconnectTimer);
      setStatus(unattended ? `unattended session with ${controllerId}` : `sharing screen with ${controllerId}`);
      startAdaptive();
    }
  };
  pc.oniceconnectionstatechange = () => {
    const s = pc.iceConnectionState;
    if (s === 'failed') {
      restartIce();
    } else if (s === 'disconnected') {
      // 'disconnected' often self-heals within a few seconds — only restart if
      // it's still broken after a short grace period.
      clearTimeout(disconnectTimer);
      disconnectTimer = setTimeout(() => {
        if (pc && (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed')) restartIce();
      }, 4000);
    }
  };

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  client.signal('offer', sessionId, { sdp: offer.sdp });
  log('offer sent');
  // Sign our DTLS fingerprint so the controller can confirm the channel is ours.
  const ownFp = extractDtlsFp(pc.localDescription.sdp);
  if (ownFp) client.signal('e2e', sessionId, { fp: ownFp, sig: await client.sign(ownFp), pub: client.getPublicKey() });
}

// Serve the remote file browser: handle list/get requests from the controller,
// reading the filesystem via native commands and streaming files with a
// source-side SHA-256 (computed in Rust) for forensic chain-of-custody.
function attachFsHost(channel) {
  channel.binaryType = 'arraybuffer';
  channel.onmessage = async (e) => {
    if (typeof e.data !== 'string') return; // host only receives JSON requests
    let m; try { m = JSON.parse(e.data); } catch (_) { return; }
    try {
      if (m.t === 'list') {
        const res = await invoke('fs_list', { path: m.path || '' });
        channel.send(JSON.stringify({ t: 'list-result', ...res }));
      } else if (m.t === 'get') {
        await sendFsFile(channel, m.path);
      }
    } catch (err) {
      channel.send(JSON.stringify({ t: 'error', message: String(err) }));
    }
  };
}

async function sendFsFile(channel, path) {
  const meta = await invoke('fs_get_meta', { path }); // { name, size, mtime, sha256 }
  channel.send(JSON.stringify({ t: 'file-begin', name: meta.name, size: meta.size, path, mtime: meta.mtime }));
  const CHUNK = 16 * 1024;
  let offset = 0;
  while (offset < meta.size) {
    if (channel.bufferedAmount > 8 * 1024 * 1024) { await new Promise((r) => setTimeout(r, 10)); continue; }
    const b64 = await invoke('fs_read_chunk', { path, offset, len: CHUNK });
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    if (bytes.length === 0) break;
    channel.send(bytes.buffer);
    offset += bytes.length;
  }
  channel.send(JSON.stringify({ t: 'file-end', sha256: meta.sha256 }));
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
  stopAdaptive();
  peerE2E = null;
  activeControllerId = null;
  if (invoke) invoke('annotate_hide').catch(() => {}); // close the overlay
  // Keep the armed unattended stream alive for the next unattended connection;
  // only stop a normal (attended) capture.
  if (stream && stream !== unattendedStream) {
    stream.getTracks().forEach((t) => t.stop());
  }
  stream = null;
  unattendedSession = false;
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
  setStatus(unattendedStream ? 'online — unattended armed' : 'online — waiting for a controller');
  log('session ended');
}
