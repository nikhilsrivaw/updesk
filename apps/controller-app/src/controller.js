import { SignalingClient, verifySig, keyFingerprint } from './signaling.js';
import { ICE_SERVERS } from './rtcConfig.js';
import { attachFileReceiver, sendFile } from './fileTransfer.js';

// Tauri command bridge (present only inside the app; guarded for plain-browser).
const invoke = window.__TAURI__ && window.__TAURI__.core && window.__TAURI__.core.invoke;
const IS_TAURI = !!invoke;

// Platform-adaptive OS bridges. In the desktop app these call native Tauri
// commands; served as a plain web page they fall back to browser APIs — which is
// what lets the controller run in ANY browser on macOS/Linux/Chromebook.
async function saveDownload(name, dataB64) {
  if (IS_TAURI) return invoke('save_download', { name, data: dataB64 });
  // Browser: turn the bytes into a download.
  const bytes = Uint8Array.from(atob(dataB64), (c) => c.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([bytes]));
  const a = document.createElement('a');
  a.href = url; a.download = name;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  return `Downloads/${name}`;
}
async function osGetClipboard() {
  if (IS_TAURI) return invoke('get_clipboard');
  try { return await navigator.clipboard.readText(); } catch (_) { return ''; }
}
async function osSetClipboard(text) {
  if (IS_TAURI) { invoke('set_clipboard', { text }); return; }
  try { await navigator.clipboard.writeText(text); } catch (_) {}
}

const ICE = { iceServers: ICE_SERVERS };

const $ = (id) => document.getElementById(id);
let client = null;
let pc = null;
let sessionId = null;
let controlChannel = null; // clipboard + quality control channel
let fileChannel = null; // file-transfer channel
let inputChannel = null; // controller -> host input events
let videoReceiver = null; // remote video receiver (jitter-buffer tuned per path)
let clipTimer = null; // clipboard poll interval
let lastClip = ''; // last clipboard text seen/applied (echo guard)
let sessionPerms = { input: true, clipboard: true, file: true }; // host-granted
let drawMode = false; // annotation mode: strokes go to the host overlay, not input

function startClipboardSync(channel) {
  stopClipboardSync();
  clipTimer = setInterval(async () => {
    if (channel.readyState !== 'open') return;
    try {
      const txt = await osGetClipboard();
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

// The on-screen log was removed from the UI; keep the calls but route them to
// the dev console so nothing breaks.
function log(s) {
  console.log('[updesk]', s);
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

// Reflect the host's per-session grants in the UI and stop sending anything
// that isn't allowed. (The host also enforces these, so this is cosmetic +
// polite, not the security boundary.)
function applyPerms(p) {
  sessionPerms = { input: !!p.input, clipboard: !!p.clipboard, file: !!p.file };
  // A Field host (camera/mic/GPS bridge) gets the dedicated Field Monitor view.
  if (p.device === 'field') { enterFieldView(); return; }
  if (!sessionPerms.clipboard) stopClipboardSync();
  if ($('sendFileBtn')) $('sendFileBtn').disabled = !sessionPerms.file;
  // Phone Back/Home/Recents only make sense for an Android host that granted input.
  const nav = $('navGroup');
  if (nav) nav.hidden = !(p.os === 'android' && sessionPerms.input);
  const denied = [];
  if (!sessionPerms.input) denied.push('view-only');
  if (!sessionPerms.clipboard) denied.push('no clipboard');
  if (!sessionPerms.file) denied.push('no files');
  if (denied.length) setStatus(`connected — live (${denied.join(', ')})`);
}

// Send a system-navigation command to an Android host over the input channel.
function sendNav(action) {
  if (inputChannel && inputChannel.readyState === 'open') {
    inputChannel.send(JSON.stringify({ kind: 'nav', action }));
  }
}

// --- remote file browser (fs channel; e.g. an Android host's storage) ---
let fsChannel = null;
let fsIncoming = null; // { name, size, chunks: [] }
let fsCurrentPath = '/storage/emulated/0';
let examinerId = localStorage.getItem('updesk-examiner') || '';
let currentPartnerId = '';
let peerE2E = null; // {fp, sig, pub} the host signed

// ---- End-to-end verification (mirror of the host side) ----
function extractDtlsFp(sdp) {
  const m = (sdp || '').match(/a=fingerprint:sha-256\s+([0-9A-Fa-f:]+)/i);
  return m ? m[1].toUpperCase() : '';
}
// Confirm the host's DTLS fingerprint is signed by its identity key AND matches
// the SDP we actually received — so a relay/server that rewrote the handshake to
// wiretap is caught. TOFU-pins the host's identity key to catch later swaps.
async function verifyE2E() {
  if (!peerE2E || !pc || !pc.remoteDescription) return;
  const sdpFp = extractDtlsFp(pc.remoteDescription.sdp);
  const { fp, sig, pub } = peerE2E;
  peerE2E = null;
  const matches = !!fp && fp === sdpFp;
  const signed = matches && (await verifySig(pub, fp, sig));
  const pinKey = 'updesk-pin-' + (currentPartnerId || 'peer');
  const pinned = localStorage.getItem(pinKey);
  const keyOk = signed && (!pinned || pinned === pub);
  if (signed && !pinned) localStorage.setItem(pinKey, pub);
  if (matches && signed && keyOk) {
    const fpr = await keyFingerprint(pub);
    setStatus('🔒 connected — end-to-end verified');
    log('E2E VERIFIED — host key: ' + fpr);
  } else {
    const why = !matches ? 'media handshake was altered in transit'
      : !signed ? 'identity signature invalid'
      : 'host identity key CHANGED since last time';
    setStatus('⚠ NOT end-to-end verified — ' + why);
    log('⚠ SECURITY WARNING: E2E verification FAILED — ' + why);
  }
}

function setupFileBrowser(ch) {
  fsChannel = ch;
  ch.binaryType = 'arraybuffer';
  ch.onmessage = (e) => {
    if (typeof e.data === 'string') {
      let m; try { m = JSON.parse(e.data); } catch (_) { return; }
      if (m.t === 'list-result') renderFsListing(m);
      else if (m.t === 'file-begin') { fsIncoming = { name: m.name, size: m.size, path: m.path, mtime: m.mtime, chunks: [] }; fsStatus(`extracting "${m.name}"…`); }
      else if (m.t === 'file-end' && fsIncoming) { finishFsFile(fsIncoming, m.sha256); fsIncoming = null; }
      else if (m.t === 'error') { clearTimeout(fsTimer); fsStatus('error: ' + m.message); }
    } else if (fsIncoming) {
      fsIncoming.chunks.push(e.data);
    }
  };
  $('filesBtn').hidden = false;
}
const fsStatus = (s) => { if ($('fsStatus')) $('fsStatus').textContent = s; };
let fsTimer = null;
function fsList(path) {
  fsCurrentPath = path;
  if (!fsChannel || fsChannel.readyState !== 'open') {
    fsStatus('file channel not ready: ' + (fsChannel ? fsChannel.readyState : 'missing — reconnect'));
    return;
  }
  fsChannel.send(JSON.stringify({ t: 'list', path }));
  fsStatus('loading ' + path + ' …');
  clearTimeout(fsTimer);
  fsTimer = setTimeout(() => fsStatus('no reply from phone for ' + path + ' — is "File access" ON there?'), 5000);
}
function fsGet(path) { if (fsChannel && fsChannel.readyState === 'open') fsChannel.send(JSON.stringify({ t: 'get', path })); }

function renderFsListing(m) {
  clearTimeout(fsTimer);
  fsCurrentPath = m.path;
  $('fsPath').value = m.path;
  fsStatus((m.entries || []).length + ' items');
  const box = $('fsList');
  box.innerHTML = '';
  for (const ent of m.entries || []) {
    const row = document.createElement('div');
    row.className = 'fs-row';
    const full = (m.path.endsWith('/') ? m.path : m.path + '/') + ent.name;
    if (ent.dir) {
      row.innerHTML = `<span class="fs-ic">📁</span><span class="fs-name">${ent.name}</span>`;
      row.addEventListener('click', () => fsList(full));
    } else {
      row.innerHTML = `<span class="fs-ic">📄</span><span class="fs-name">${ent.name}</span><span class="fs-sz">${fmtBytes(ent.size)}</span><span class="fs-dl">⬇</span>`;
      row.addEventListener('click', () => { fsStatus(`requesting "${ent.name}"…`); fsGet(full); });
    }
    box.appendChild(row);
  }
  $('fsUp').dataset.parent = m.parent || '';
}
function fmtBytes(n) {
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}
// Forensic-grade save: verify the destination SHA-256 against the source hash
// the device computed, then record a chain-of-custody entry.
async function finishFsFile(f, sourceHash) {
  const blob = new Blob(f.chunks);
  const bytes = new Uint8Array(await blob.arrayBuffer());

  // Hash what we actually received, and compare to the on-device hash.
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  const destHash = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  const verified = !!sourceHash && destHash === sourceHash;

  // Save to disk.
  let bin = '';
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
  const savedPath = await saveDownload(f.name, btoa(bin));

  // Chain-of-custody record.
  logCustody({
    name: f.name,
    sourcePath: f.path || '',
    size: bytes.length,
    modifiedUtc: f.mtime ? new Date(f.mtime).toISOString() : '',
    sha256Source: sourceHash || '(none)',
    sha256Dest: destHash,
    verified,
    savedPath,
  });

  fsStatus(verified
    ? `✓ VERIFIED & logged: "${f.name}"  (SHA-256 ${destHash.slice(0, 12)}…)`
    : `⚠ HASH MISMATCH — "${f.name}" may be corrupted/tampered`);
}

// ---- chain of custody ----
function loadCustody() {
  try { return JSON.parse(localStorage.getItem('updesk-custody') || '[]'); } catch (_) { return []; }
}
function logCustody(rec) {
  const entry = {
    timestampUtc: new Date().toISOString(),
    examiner: examinerId || '(unset)',
    deviceId: currentPartnerId || '',
    ...rec,
  };
  const log = loadCustody();
  log.push(entry);
  localStorage.setItem('updesk-custody', JSON.stringify(log));
  renderCustody();
}
function renderCustody() {
  const box = $('custodyList');
  if (!box) return;
  const log = loadCustody();
  $('custodyCount').textContent = log.length + ' item' + (log.length === 1 ? '' : 's');
  box.innerHTML = '';
  for (const e of [...log].reverse()) {
    const row = document.createElement('div');
    row.className = 'coc-row';
    const badge = e.verified ? '<span class="coc-ok">✓ verified</span>' : '<span class="coc-bad">⚠ mismatch</span>';
    row.innerHTML =
      `<div class="coc-name">${e.name} ${badge}</div>` +
      `<div class="coc-meta">${e.sourcePath}</div>` +
      `<div class="coc-hash">SHA-256 ${e.sha256Dest}</div>` +
      `<div class="coc-meta">${e.timestampUtc} · ${e.size} bytes · examiner: ${e.examiner}</div>`;
    box.appendChild(row);
  }
}
// ---- network connection monitor ----
let netTimer = null;
let lastNetstat = [];
let lastVpn = null;
let lastNetinfo = null;
function requestNet() {
  if (controlChannel && controlChannel.readyState === 'open') {
    controlChannel.send(JSON.stringify({ kind: 'netstat' }));
    controlChannel.send(JSON.stringify({ kind: 'vpn' }));
    controlChannel.send(JSON.stringify({ kind: 'netinfo' }));
  }
}
function requestNetstat() { requestNet(); } // back-compat name

function renderVpn(d) {
  lastVpn = d;
  const el = $('netVpn');
  if (!el) return;
  el.hidden = false;
  const detail = [...(d.processes || []), ...(d.adapters || [])].join(', ');
  const suffix = (detail ? ' — ' + detail : '') + (d.server ? ' (server ' + d.server + ')' : '');
  if (d.connected) {
    // A tunnel is actually up — traffic is being masked right now.
    el.className = 'net-vpn on';
    el.textContent = '🔒 VPN CONNECTED' + suffix;
  } else if (d.active) {
    // VPN software is installed/running but no tunnel is established.
    el.className = 'net-vpn warn';
    el.textContent = '⚠ VPN software present (not connected)' + suffix;
  } else {
    el.className = 'net-vpn off';
    el.textContent = '✓ No VPN detected';
  }
}

// ---- Field Monitor: a dedicated view for an UpDesk Field host (camera + mic +
// GPS bridge) where live video, audio, and exact location are shown at once. ----
let fieldMode = false;
let lastLocation = null;

// Leaflet map state (interactive live map with a breadcrumb route trail).
let fmap = null, ftrail = null, fmarker = null, faccuracy = null;
let trailPts = [];       // [[lat,lon], ...] the route so far
let trailMeters = 0;     // cumulative distance travelled
let followMode = true;   // keep the map centered on the device until the user pans

// Great-circle distance between two [lat,lon] points, in metres (haversine).
function haversine(a, b) {
  const R = 6371000, toRad = (x) => (x * Math.PI) / 180;
  const dLat = toRad(b[0] - a[0]), dLon = toRad(b[1] - a[1]);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a[0])) * Math.cos(toRad(b[0])) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

// Create the Leaflet map lazily, once the field view (and its container) is visible.
function ensureFieldMap() {
  if (fmap || !window.L || $('fieldView').hidden) return;
  const L = window.L;
  fmap = L.map('fieldMap', { zoomControl: true, attributionControl: true }).setView([20, 0], 2);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '© OpenStreetMap',
  }).addTo(fmap);
  faccuracy = L.circle([0, 0], { radius: 0, color: '#396cd8', weight: 1, fillColor: '#396cd8', fillOpacity: 0.12 }).addTo(fmap);
  ftrail = L.polyline([], { color: '#6ea8fe', weight: 4, opacity: 0.9 }).addTo(fmap);
  fmarker = L.circleMarker([0, 0], { radius: 7, color: '#fff', weight: 2, fillColor: '#396cd8', fillOpacity: 1 }).addTo(fmap);
  // A manual pan drops follow-mode; the Center button turns it back on.
  fmap.on('dragstart', () => { followMode = false; });
  setTimeout(() => { if (fmap) fmap.invalidateSize(); }, 0);
}

// Plot one fix: extend the trail, move the marker/accuracy circle, follow if enabled.
function updateFieldMap(d) {
  ensureFieldMap();
  if (!fmap) return;
  const ll = [d.lat, d.lon];
  if (trailPts.length) trailMeters += haversine(trailPts[trailPts.length - 1], ll);
  trailPts.push(ll);
  if (trailPts.length > 5000) trailPts.shift(); // cap memory on long shifts
  ftrail.setLatLngs(trailPts);
  fmarker.setLatLng(ll);
  if (d.accuracy != null) { faccuracy.setLatLng(ll); faccuracy.setRadius(d.accuracy); }
  if (followMode) {
    if (fmap.getZoom() < 4) fmap.setView(ll, 16); else fmap.panTo(ll, { animate: true });
  }
  if ($('fieldMapEmpty')) $('fieldMapEmpty').hidden = true;
}

function fmtDistance(m) {
  return m >= 1000 ? `${(m / 1000).toFixed(2)} km` : `${Math.round(m)} m`;
}

function destroyFieldMap() {
  if (fmap) { fmap.remove(); fmap = null; }
  ftrail = fmarker = faccuracy = null;
  trailPts = []; trailMeters = 0; followMode = true;
}

// Switch the controller into the Field Monitor layout. Idempotent — triggered by
// either the field device's perms announcement or its first location fix.
function enterFieldView() {
  if (fieldMode) return;
  fieldMode = true;
  log('entering Field Monitor (camera + audio + map + recording)');
  // Move the single live <video> into the field layout (keeps playback/srcObject).
  const wrap = $('fieldVideoWrap');
  const video = $('screen');
  if (wrap && video) { $('fieldNoVideo')?.remove(); wrap.appendChild(video); }
  // Move the floating chat panel in too, so it still works here (start closed).
  if ($('chat')) { $('fieldView').appendChild($('chat')); $('chat').hidden = true; }
  $('live').hidden = true;
  $('fieldView').hidden = false;
  $('fieldStatus').textContent = '📡 Field Monitor — live';
  ensureFieldMap();                                  // container is visible now
  setTimeout(() => { if (fmap) fmap.invalidateSize(); }, 60); // settle layout
  tryListen(); // start audio; if the browser blocks it, the Listen button covers it
}

// Restore the normal live layout (move nodes back) when the session ends.
function exitFieldView() {
  if (!fieldMode) return;
  fieldMode = false;
  audioRec.stop(); videoRec.stop(); // flush + save any in-progress recordings first
  const live = $('live');
  const video = $('screen');
  if (live && video) live.insertBefore(video, $('fsPanel'));
  if (live && $('chat')) live.appendChild($('chat'));
  $('fieldView').hidden = true;
  destroyFieldMap();
  if ($('fieldMapEmpty')) $('fieldMapEmpty').hidden = false;
}

// Try to play the remote stream WITH sound. A field stream carries an audio track,
// so the browser blocks unmuted autoplay until a user gesture — in that case we
// fall back to MUTED playback so the video never freezes, and the Listen button
// (a real click) unmutes it.
function tryListen() {
  const v = $('screen');
  if (!v) return;
  const btn = $('fieldListenBtn');
  v.muted = false;
  v.play().then(() => {
    if (btn) { btn.textContent = '🔊 Listening'; btn.classList.add('active'); }
  }).catch(() => {
    v.muted = true;                 // keep the VIDEO playing even if audio is blocked
    v.play().catch(() => {});
    if (btn) { btn.textContent = '🔊 Tap to listen'; btn.classList.remove('active'); }
  });
}

// ---- Media recording (save live audio / video as local evidence) ----
// One generic recorder, instantiated per media type below. Each records the
// chosen tracks off the live remote stream to a local file (via saveDownload)
// and logs the file + its SHA-256 into the chain of custody.
const setFieldStatus = (s) => { const el = $('fieldStatus'); if (el) el.textContent = s; };

function newRecorder({ kind, btnId, idleLabel, icon, filePrefix, mimes, getTracks }) {
  let rec = null, chunks = [], startTs = 0, timer = null;

  function label() {
    const btn = $(btnId); if (!btn) return;
    if (!rec || rec.state === 'inactive') { btn.textContent = idleLabel; return; }
    const s = Math.floor((Date.now() - startTs) / 1000);
    btn.textContent = `■ Stop • ${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
  }

  function start() {
    const tracks = getTracks();
    if (!tracks.length) { setFieldStatus(`no ${kind} to record yet`); return; }
    if (typeof MediaRecorder === 'undefined') { setFieldStatus('recording not supported here'); return; }
    const mime = mimes.find((m) => { try { return MediaRecorder.isTypeSupported(m); } catch (_) { return false; } }) || '';
    try {
      rec = mime ? new MediaRecorder(new MediaStream(tracks), { mimeType: mime }) : new MediaRecorder(new MediaStream(tracks));
    } catch (e) { setFieldStatus(`${kind} recording failed: ${e}`); return; }
    chunks = [];
    rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    rec.onstop = () => finalize().catch((e) => setFieldStatus(`${kind} save failed: ${e}`));
    rec.start(1000); // flush in ~1s slices so a crash loses at most a second
    startTs = Date.now();
    $(btnId)?.classList.add('recording');
    timer = setInterval(label, 500); label();
  }

  function stop() {
    if (rec && rec.state !== 'inactive') rec.stop();
    if (timer) { clearInterval(timer); timer = null; }
  }

  async function finalize() {
    const btn = $(btnId); if (btn) { btn.classList.remove('recording'); btn.textContent = idleLabel; }
    if (timer) { clearInterval(timer); timer = null; }
    if (!chunks.length) return;
    const type = chunks[0].type || (kind === 'video' ? 'video/webm' : 'audio/webm');
    const ext = type.includes('ogg') ? 'ogg' : type.includes('mp4') ? 'mp4' : 'webm';
    const blob = new Blob(chunks, { type });
    chunks = [];
    const durSec = Math.max(1, Math.round((Date.now() - startTs) / 1000));
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const idPart = (currentPartnerId || 'field').replace(/\s/g, '');
    const name = `${filePrefix}-${idPart}-${stamp}.${ext}`;
    const buf = await blob.arrayBuffer();
    const b64 = arrayBufferToBase64(buf);
    const sha256 = await sha256Hex(buf);
    const path = await saveDownload(name, b64);
    // Chain-of-custody: record the file + its integrity hash at capture time.
    logCustody({ type: `${kind}-recording`, name, path, durationSec: durSec, bytes: buf.byteLength, sha256, verified: true });
    setFieldStatus(`${icon} ${kind} saved → ${name}  (${durSec}s)`);
  }

  return { toggle: () => (rec && rec.state !== 'inactive' ? stop() : start()), stop };
}

// Audio only.
const audioRec = newRecorder({
  kind: 'audio', btnId: 'fieldRecBtn', idleLabel: '● Record audio', icon: '🎙', filePrefix: 'field-audio',
  mimes: ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/webm'],
  getTracks: () => { const s = $('screen') && $('screen').srcObject; return s ? s.getAudioTracks() : []; },
});
// Full A/V clip (camera + mic together).
const videoRec = newRecorder({
  kind: 'video', btnId: 'fieldVidBtn', idleLabel: '⬤ Record video', icon: '🎥', filePrefix: 'field-video',
  mimes: ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm', 'video/mp4'],
  getTracks: () => { const s = $('screen') && $('screen').srcObject; return s ? s.getTracks() : []; },
});

function arrayBufferToBase64(buf) {
  const bytes = new Uint8Array(buf);
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

async function sha256Hex(buf) {
  const h = await crypto.subtle.digest('SHA-256', buf);
  return [...new Uint8Array(h)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

// Live field-device location (from the UpDesk Field host's `location` channel).
function renderLocation(d) {
  if (typeof d.lat !== 'number' || typeof d.lon !== 'number') return;
  lastLocation = d;
  if (!fieldMode) enterFieldView(); // first fix confirms this is a field device
  const set = (id, v) => { const el = $(id); if (el) el.textContent = v; };
  set('fLocCoords', `${d.lat.toFixed(6)}, ${d.lon.toFixed(6)}`);
  set('fLocAcc', d.accuracy != null ? `±${Math.round(d.accuracy)} m` : '—');
  set('fLocSpeed', d.speed != null ? `${(d.speed * 3.6).toFixed(1)} km/h` : '—');
  set('fLocHeading', d.bearing != null ? `${Math.round(d.bearing)}°` : '—');
  set('fLocAlt', d.altitude != null ? `${Math.round(d.altitude)} m` : '—');
  set('fLocProvider', d.provider || 'gps');
  set('fLocTime', d.time ? new Date(d.time).toLocaleTimeString() : '—');
  updateFieldMap(d); // extend the trail + move the live marker
  set('fLocDistance', trailPts.length > 1 ? fmtDistance(trailMeters) : '—');
  const maps = $('fLocMaps');
  if (maps) maps.href = `https://www.google.com/maps?q=${d.lat},${d.lon}`;
}

function renderNetinfo(d) {
  lastNetinfo = d;
  const el = $('netInfo');
  if (!el) return;
  const parts = [];
  if (d.type) parts.push('Network: ' + d.type);
  if (d.ssid) parts.push('WiFi: ' + d.ssid);
  if (d.ip) parts.push('IP: ' + d.ip);
  el.hidden = parts.length === 0;
  el.textContent = parts.join('  ·  ');
}
function renderNetstat(conns) {
  const box = $('netList');
  if (!box) return;
  lastNetstat = conns;
  $('netCount').textContent = conns.length + ' connection' + (conns.length === 1 ? '' : 's');
  box.innerHTML = '';
  for (const c of conns) {
    const row = document.createElement('div');
    row.className = 'net-row';
    row.innerHTML =
      `<div class="net-remote">→ ${c.remote}</div>` +
      `<div class="net-meta">${c.proto} ${c.state || ''} · ${c.process || '?'} (pid ${c.pid})</div>` +
      `<div class="net-meta">local ${c.local}</div>`;
    box.appendChild(row);
  }
}

// Export the current network snapshot as a timestamped evidence CSV.
async function exportNetstat() {
  if (!lastNetstat.length) { $('netCount').textContent = 'nothing to export yet'; return; }
  const ts = new Date().toISOString();
  const cols = ['proto', 'local', 'remote', 'state', 'pid', 'process'];
  const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const vpnLine = lastVpn ? (lastVpn.active ? 'DETECTED (' + [...(lastVpn.processes || []), ...(lastVpn.adapters || [])].join('; ') + ')' : 'none') : 'unknown';
  const infoLine = lastNetinfo ? [lastNetinfo.type, lastNetinfo.ssid, lastNetinfo.ip].filter(Boolean).join(' / ') : '';
  const header = `# UpDesk network capture\n# captured: ${ts}\n# examiner: ${examinerId || '(unset)'}\n# device: ${currentPartnerId || ''}\n# VPN: ${vpnLine}\n# network: ${infoLine}\n`;
  const rows = cols.join(',') + '\n' + lastNetstat.map((c) => cols.map((k) => esc(c[k])).join(',')).join('\n');
  const name = `network-capture-${ts.replace(/[:.]/g, '-')}.csv`;
  try {
    const p = await saveDownload(name, btoa(unescape(encodeURIComponent(header + rows))));
    $('netCount').textContent = 'exported → ' + p;
  } catch (e) {
    $('netCount').textContent = 'export failed: ' + e;
  }
}

const custodyStatus = (s) => { if ($('custodyStatus')) $('custodyStatus').textContent = s; };
async function exportCustody(kind) {
  const log = loadCustody();
  if (!log.length) { custodyStatus('No evidence yet — download a file first, then export.'); return; }
  let data, name;
  if (kind === 'csv') {
    const cols = ['timestampUtc', 'examiner', 'deviceId', 'name', 'sourcePath', 'size', 'modifiedUtc', 'sha256Source', 'sha256Dest', 'verified', 'savedPath'];
    const esc = (v) => `"${String(v).replace(/"/g, '""')}"`;
    data = cols.join(',') + '\n' + log.map((e) => cols.map((c) => esc(e[c] ?? '')).join(',')).join('\n');
    name = 'chain-of-custody.csv';
  } else {
    data = JSON.stringify(log, null, 2);
    name = 'chain-of-custody.json';
  }
  custodyStatus('exporting…');
  try {
    const b64 = btoa(unescape(encodeURIComponent(data)));
    const p = await saveDownload(name, b64);
    custodyStatus('exported → ' + p);
  } catch (e) {
    custodyStatus('export failed: ' + e);
  }
}

// --- address book: recently-used connection targets ---
function loadRecents() {
  try { return JSON.parse(localStorage.getItem('updesk-ctl-recents') || '[]'); }
  catch (_) { return []; }
}
function saveRecent(partnerId) {
  if (!partnerId) return;
  const recents = loadRecents().filter((p) => p !== partnerId);
  recents.unshift(partnerId);
  localStorage.setItem('updesk-ctl-recents', JSON.stringify(recents.slice(0, 6)));
}
function renderRecents() {
  const box = $('recents');
  if (!box) return;
  const recents = loadRecents();
  box.innerHTML = '';
  if (!recents.length) { box.hidden = true; return; }
  box.hidden = false;
  for (const p of recents) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'recent-chip';
    chip.innerHTML = `<b>${String(p).replace(/(\d{3})(\d{3})(\d{3})/, '$1 $2 $3')}</b><span>recent</span>`;
    chip.addEventListener('click', () => { $('partnerId').value = p; $('pin').focus(); });
    box.appendChild(chip);
  }
}

window.addEventListener('DOMContentLoaded', () => {
  renderRecents();
  $('connectBtn').addEventListener('click', start);
  $('chatSend').addEventListener('click', sendChat);
  $('navBack').addEventListener('click', () => sendNav('back'));
  $('navHome').addEventListener('click', () => sendNav('home'));
  $('navRecents').addEventListener('click', () => sendNav('recents'));
  $('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); sendChat(); }
  });
  $('drawToggle').addEventListener('click', () => {
    drawMode = !drawMode;
    $('drawToggle').classList.toggle('active', drawMode);
    $('drawClear').hidden = !drawMode;
    $('screen').classList.toggle('draw-cursor', drawMode);
    if (!drawMode && controlChannel && controlChannel.readyState === 'open') {
      controlChannel.send(JSON.stringify({ kind: 'annotate', op: 'clear' }));
    }
  });
  $('drawClear').addEventListener('click', () => {
    if (controlChannel && controlChannel.readyState === 'open') {
      controlChannel.send(JSON.stringify({ kind: 'annotate', op: 'clear' }));
    }
  });
  $('chatToggle').addEventListener('click', () => {
    const c = $('chat');
    c.hidden = !c.hidden;
    if (!c.hidden) { c.classList.remove('minimized'); $('chatInput').focus(); }
  });
  $('filesBtn').addEventListener('click', () => {
    $('custodyPanel').hidden = true; // don't let the two panels stack
    const p = $('fsPanel');
    p.hidden = !p.hidden;
    if (!p.hidden) { fsStatus('loading…'); fsList(fsCurrentPath || '/storage/emulated/0'); }
  });
  if ($('examiner')) $('examiner').value = examinerId;
  $('custodyBtn').addEventListener('click', () => {
    $('fsPanel').hidden = true; $('netPanel').hidden = true;
    const p = $('custodyPanel');
    p.hidden = !p.hidden;
    if (!p.hidden) renderCustody();
  });
  $('netBtn').addEventListener('click', () => {
    $('fsPanel').hidden = true; $('custodyPanel').hidden = true;
    const p = $('netPanel');
    p.hidden = !p.hidden;
    clearInterval(netTimer);
    if (!p.hidden) { $('netCount').textContent = 'loading…'; requestNetstat(); netTimer = setInterval(requestNetstat, 3000); }
  });
  $('netClose').addEventListener('click', () => { $('netPanel').hidden = true; clearInterval(netTimer); });
  $('netExport').addEventListener('click', exportNetstat);
  $('custodyClose').addEventListener('click', () => { $('custodyPanel').hidden = true; });
  $('custodyCsv').addEventListener('click', () => exportCustody('csv'));
  $('custodyJson').addEventListener('click', () => exportCustody('json'));
  $('fsUp').addEventListener('click', () => {
    let parent = $('fsUp').dataset.parent;
    if (!parent && fsCurrentPath) {
      // Fall back: strip the last path segment (handles / and \ separators).
      parent = fsCurrentPath.replace(/[\/\\][^\/\\]*[\/\\]?$/, '') || '/';
    }
    if (parent) fsList(parent);
  });
  // Editable address bar: type a path and Go (or Enter) to jump there.
  const gotoPath = () => { const p = $('fsPath').value.trim(); if (p) fsList(p); };
  $('fsGo').addEventListener('click', gotoPath);
  $('fsPath').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); gotoPath(); } });
  $('fsClose').addEventListener('click', () => { $('fsPanel').hidden = true; });
  // Collapse the panel to just its header (and back).
  const toggleMin = () => {
    const c = $('chat');
    const min = c.classList.toggle('minimized');
    $('chatMin').textContent = min ? '▢' : '–';
    $('chatMin').title = min ? 'Expand' : 'Minimize';
  };
  $('chatMin').addEventListener('click', (e) => { e.stopPropagation(); toggleMin(); });
  // Clicking the header while minimized re-opens it.
  $('chatHeader').addEventListener('click', () => {
    if ($('chat').classList.contains('minimized')) toggleMin();
  });
  $('reconfigureBtn').addEventListener('click', reconfigure);
  $('endBtn').addEventListener('click', endSession);
  $('quality').addEventListener('change', (e) => {
    if (controlChannel && controlChannel.readyState === 'open') {
      controlChannel.send(JSON.stringify({ kind: 'quality', profile: e.target.value }));
      log(`requested quality: ${e.target.value}`);
    }
  });
  $('sendFileBtn').addEventListener('click', () => $('fileInput').click());
  $('fileInput').addEventListener('change', async (e) => {
    const files = [...e.target.files];
    e.target.value = '';
    for (const f of files) await sendFile(fileChannel, f, { log }); // one at a time
  });

  // Keyboard is captured at the window level (not on the <video>, which is hard
  // to keep focused) so typing works as long as a session is live. Skip when
  // the user is in one of our own form controls.
  const inFormControl = (t) =>
    t && ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(t.tagName);
  const sendKey = (kind, e) => {
    if (!sessionPerms.input) return;
    if (!inputChannel || inputChannel.readyState !== 'open') return;
    if (inFormControl(e.target)) return;
    e.preventDefault();
    inputChannel.send(JSON.stringify({ kind, key: e.key }));
  };
  window.addEventListener('keydown', (e) => sendKey('keydown', e));
  window.addEventListener('keyup', (e) => sendKey('keyup', e));

  // "Enable sound" — a real click satisfies the browser's autoplay policy.
  $('enableAudioBtn').addEventListener('click', () => {
    const v = $('screen');
    v.muted = false;
    v.play().catch(() => {});
    $('enableAudioBtn').hidden = true;
  });
  // Any click on the video also unmutes (belt-and-suspenders).
  $('screen').addEventListener('click', () => {
    const v = $('screen');
    if (v.muted || v.paused) { v.muted = false; v.play().catch(() => {}); }
    $('enableAudioBtn').hidden = true;
  });

  // ---- Field Monitor controls ----
  $('fieldListenBtn')?.addEventListener('click', tryListen);
  $('fieldEndBtn')?.addEventListener('click', endSession);
  $('fieldReconfigureBtn')?.addEventListener('click', reconfigure);
  $('fieldChatToggle')?.addEventListener('click', () => {
    const c = $('chat'); if (c) c.hidden = !c.hidden;
  });
  // Record live audio / full A/V clip to a local file (saved + hashed into the
  // evidence log).
  $('fieldRecBtn')?.addEventListener('click', () => audioRec.toggle());
  $('fieldVidBtn')?.addEventListener('click', () => videoRec.toggle());
  // Flip the field device's camera (front/back) — commanded from the controller.
  $('fieldFlipBtn')?.addEventListener('click', () => {
    if (controlChannel && controlChannel.readyState === 'open') {
      controlChannel.send(JSON.stringify({ kind: 'switchCamera' }));
    }
  });
  $('fieldQuality')?.addEventListener('change', (e) => {
    if (controlChannel && controlChannel.readyState === 'open') {
      controlChannel.send(JSON.stringify({ kind: 'quality', profile: e.target.value }));
    }
  });
  // Recenter + resume follow-mode on the latest fix.
  $('fieldCenterBtn')?.addEventListener('click', () => {
    followMode = true;
    if (fmap && trailPts.length) fmap.setView(trailPts[trailPts.length - 1], 16);
  });
  // Wipe the route trail (keeps the live marker).
  $('fieldClearBtn')?.addEventListener('click', () => {
    trailPts = trailPts.length ? [trailPts[trailPts.length - 1]] : [];
    trailMeters = 0;
    if (ftrail) ftrail.setLatLngs(trailPts);
    const el = $('fLocDistance'); if (el) el.textContent = '—';
  });
});

function endSession() {
  if (sessionId && client) client.end(sessionId);
  teardown();
}

function start() {
  const server = $('server').value.trim();
  const partnerId = ($('partnerId').value || '').replace(/\D/g, ''); // digits only
  const pin = ($('pin').value || '').trim();
  if (partnerId.length < 9) { setStatus('enter the 9-digit Partner ID'); return; }
  if (!pin) { setStatus('enter the PIN'); return; }
  currentPartnerId = partnerId;
  examinerId = ($('examiner') ? $('examiner').value.trim() : '') || examinerId;
  localStorage.setItem('updesk-examiner', examinerId);
  saveRecent(partnerId);

  // Controller uses a stable self-generated identity (open enrollment, no code).
  let cid = localStorage.getItem('updesk-controller-id');
  if (!cid) { cid = 'ctl-' + Math.random().toString(36).slice(2, 10); localStorage.setItem('updesk-controller-id', cid); }

  client = new SignalingClient({ url: server, identityId: cid, kind: 'controller' });

  client.addEventListener('ready', () => {
    setStatus('dialing…');
    client.connectRequest({ partnerId, pin });
  });

  client.addEventListener('session_response', (e) => {
    if (!e.detail.accepted) {
      setStatus('rejected — wrong PIN, or the host declined');
      return;
    }
    sessionId = e.detail.sessionId;
    setStatus('accepted — negotiating media…');
    log('session accepted');
    // Host sends the offer next (it owns the screen track).
  });

  client.addEventListener('offer', async (e) => {
    // Renegotiation (e.g. the host restarting ICE mid-session): apply it to the
    // EXISTING connection so the live session recovers in place, rather than
    // tearing everything down and rebuilding a fresh pc.
    if (pc && e.detail.sessionId === sessionId) {
      try {
        await pc.setRemoteDescription({ type: 'offer', sdp: e.detail.sdp });
        const ans = await pc.createAnswer();
        await pc.setLocalDescription(ans);
        client.signal('answer', sessionId, { sdp: ans.sdp });
        log('re-answered host (ICE restart)');
      } catch (err) { log('renegotiation failed: ' + err); }
      return;
    }
    sessionId = e.detail.sessionId;
    pc = new RTCPeerConnection(ICE);

    const gotKinds = new Set();
    pc.ontrack = (ev) => {
      // Latency: WebRTC buffers incoming media to smooth jitter, but for an
      // interactive remote desktop that buffer *is* the lag. Ask for the
      // smallest playout delay the receiver supports. jitterBufferTarget is the
      // current API; playoutDelayHint is the older one — set whichever exists.
      try {
        if (ev.track.kind === 'video') {
          videoReceiver = ev.receiver; // link monitor tunes its buffer per path
          if ('jitterBufferTarget' in ev.receiver) ev.receiver.jitterBufferTarget = 0;
          if ('playoutDelayHint' in ev.receiver) ev.receiver.playoutDelayHint = 0;
        }
      } catch (_) {}
      const v = $('screen');
      v.srcObject = ev.streams[0];
      v.muted = false; // let host audio through (if shared)
      // A stream WITH audio can't autoplay unmuted (no user gesture yet); if the
      // browser blocks it, fall back to muted so the video still plays instead of
      // freezing on the first frame. The Listen / video-click handlers unmute.
      v.play().catch(() => { v.muted = true; v.play().catch(() => {}); });
      gotKinds.add(ev.track.kind);
      // Visible proof of what arrived: "connected — live (video+audio)".
      setStatus(`connected — live (${[...gotKinds].sort().join('+')})`);
      if (ev.track.kind === 'audio') $('enableAudioBtn').hidden = false;
      log(`remote ${ev.track.kind} track received`);
    };
    // ---- Host types & their data channels (KEEP THIS DISTINCTION INTACT) ----
    // The controller serves four host kinds; they are told apart ONLY by which
    // data channels the host opens + its `perms.device` value. Do not collapse
    // these — the UI (input vs Field Monitor) depends on it:
    //   • Android screen host  → 'input','fs','control' ; perms.os='android'      → remote-control UI
    //   • Desktop host (Tauri) → 'input','fs','control' ; perms.os='windows'/...  → remote-control UI
    //   • Desktop NATIVE host  → 'input','control'      ; perms.os='windows'      → remote-control UI
    //   • UpDesk FIELD host    → 'location','control'   ; perms.device='field'    → Field Monitor (map+A/V+record)
    // Field is identified by the 'location' channel (primary, always present) AND
    // perms.device==='field' (secondary). Screen hosts never open a 'location'
    // channel; the Field host never opens an 'input' channel.
    pc.ondatachannel = (ev) => {
      const ch = ev.channel;
      log(`data channel open: ${ch.label}`);
      if (ch.label === 'control') {
        controlChannel = ch;
        ch.onopen = () => startClipboardSync(ch);
        ch.onmessage = (m) => {
          let d; try { d = JSON.parse(m.data); } catch (_) { return; }
          if (d.kind === 'clipboard') {
            lastClip = d.text;
            osSetClipboard(d.text);
          } else if (d.kind === 'chat') {
            appendChat('them', d.text);
          } else if (d.kind === 'perms') {
            applyPerms(d);
          } else if (d.kind === 'netstat-result') {
            renderNetstat(d.connections || []);
          } else if (d.kind === 'vpn-result') {
            renderVpn(d);
          } else if (d.kind === 'netinfo-result') {
            renderNetinfo(d);
          }
        };
        $('quality').disabled = false;
        $('chat').hidden = false;
        $('netBtn').hidden = false;
      } else if (ch.label === 'file') {
        fileChannel = ch;
        attachFileReceiver(ch, { log, save: saveDownload });
        $('sendFileBtn').disabled = false;
      } else if (ch.label === 'fs') {
        setupFileBrowser(ch);
      } else if (ch.label === 'location') {
        // The `location` channel is created ONLY by an UpDesk Field host, so its
        // very presence identifies a field device — switch to the Field Monitor
        // view immediately, without waiting for a perms message or a GPS lock
        // (indoors / location-off, a fix may never arrive). This is the robust,
        // primary signal that distinguishes Field from the screen hosts.
        log('field host detected (location channel) → Field Monitor');
        enterFieldView();
        ch.onmessage = (m) => {
          let d; try { d = JSON.parse(m.data); } catch (_) { return; }
          if (d.kind === 'location') renderLocation(d);
        };
      } else {
        inputChannel = ch;
        window.__inputChannel = ch;
        attachInputCapture($('screen'), ch);
      }
    };
    pc.onicecandidate = (ev) => {
      if (ev.candidate) client.signal('ice_candidate', sessionId, { candidate: ev.candidate });
    };
    pc.onconnectionstatechange = () => {
      log(`pc: ${pc.connectionState}`);
      const s = pc.connectionState;
      if (s === 'failed' || s === 'disconnected') {
        setStatus('connection interrupted — recovering…');
      } else if (s === 'connected') {
        setStatus('connected — live');
        startLinkMonitor(pc);
      }
    };

    await pc.setRemoteDescription({ type: 'offer', sdp: e.detail.sdp });
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    client.signal('answer', sessionId, { sdp: answer.sdp });
    log('answer sent');
    // Sign our DTLS fingerprint so the host can verify us too.
    const ownFp = extractDtlsFp(pc.localDescription.sdp);
    if (ownFp) client.signal('e2e', sessionId, { fp: ownFp, sig: await client.sign(ownFp), pub: client.getPublicKey() });
    verifyE2E();
  });

  // Host's signed DTLS fingerprint for the end-to-end check.
  client.addEventListener('e2e', (e) => {
    peerE2E = { fp: (e.detail.fp || '').toUpperCase(), sig: e.detail.sig, pub: e.detail.pub };
    verifyE2E();
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
    setStatus('reconnected — re-requesting session…');
    log('reconnected to server');
  });
  client.addEventListener('disconnected', () => setStatus('disconnected'));

  client.addEventListener('error', (e) => {
    const { kind, message } = e.detail;
    if (kind === 'auth') setStatus(`sign-in failed: ${message}`);
    else if (kind === 'connect') setStatus(message);
    else setStatus(`server: ${message}`);
    log(`${kind} error: ${message}`);
  });

  $('config').hidden = true;
  $('live').hidden = false;
  setStatus('connecting…');
  client.connect();
}

function reconfigure() {
  exitFieldView(); // move the video/chat back into #live before hiding it
  if (client) client.close();
  client = null;
  if (pc) { pc.close(); pc = null; }
  localStorage.removeItem('updesk-ctl-config');
  $('screen').srcObject = null;
  $('live').hidden = true;
  $('config').hidden = false;
  renderRecents();
}

// Live link readout: every 2s inspect the active ICE candidate pair and surface
// whether we're on a DIRECT path or the TURN RELAY, plus round-trip time. Makes
// latency measurable ("direct • 24 ms") and flags when a session fell back to
// relay (the usual reason a connection feels slower).
let linkTimer = null;
let lastVidStats = null; // {t, bytes} for computing live video bitrate
function startLinkMonitor(pc) {
  clearInterval(linkTimer);
  lastVidStats = null;
  let last = '';
  linkTimer = setInterval(async () => {
    if (!pc || pc.connectionState !== 'connected') return;
    try {
      const stats = await pc.getStats();
      let pair = null;
      stats.forEach((r) => {
        if (r.type !== 'candidate-pair' || r.state !== 'succeeded') return;
        if (r.nominated || r.selected || !pair || (r.bytesReceived || 0) > (pair.bytesReceived || 0)) pair = r;
      });
      if (!pair) return;
      let local = null, remote = null;
      stats.forEach((r) => {
        if (r.id === pair.localCandidateId) local = r;
        if (r.id === pair.remoteCandidateId) remote = r;
      });
      const relayed = (local && local.candidateType === 'relay') || (remote && remote.candidateType === 'relay');
      const rtt = pair.currentRoundTripTime != null ? Math.round(pair.currentRoundTripTime * 1000) : null;
      // When relayed, show the transport to the TURN server. 'udp' = good/fast;
      // 'tcp'/'tls' = the slow fallback (usually means the UDP relay port range
      // isn't open on the server firewall) and explains a laggy nearby relay.
      let path = relayed ? 'relay' : 'direct';
      if (relayed) {
        const rp = (local && local.relayProtocol) || (remote && remote.relayProtocol);
        if (rp) path = `relay(${rp})`;
      }
      // Live video stats — codec, actual resolution, fps, and bitrate. This is
      // ground truth: blurry = low res/bitrate; we can now SEE which.
      let codec = '', vin = null;
      stats.forEach((r) => { if (r.type === 'inbound-rtp' && r.kind === 'video') vin = r; });
      if (vin && vin.codecId) {
        const c = stats.get(vin.codecId);
        if (c && c.mimeType) codec = c.mimeType.replace('video/', '');
      }
      let vidInfo = '';
      if (vin) {
        const w = vin.frameWidth, h = vin.frameHeight, fps = vin.framesPerSecond;
        let kbps = null;
        const now = performance.now();
        if (lastVidStats && vin.bytesReceived != null) {
          const dt = (now - lastVidStats.t) / 1000;
          if (dt > 0) kbps = Math.round(((vin.bytesReceived - lastVidStats.bytes) * 8) / 1000 / dt);
        }
        lastVidStats = { t: now, bytes: vin.bytesReceived || 0 };
        const parts = [];
        if (w && h) parts.push(`${w}×${h}`);
        if (fps != null) parts.push(`${Math.round(fps)}fps`);
        if (kbps != null && kbps >= 0) parts.push(`${kbps} kbps`);
        vidInfo = parts.length ? '  ▸ ' + parts.join(' · ') : '';
      }
      const label = `${path}${rtt != null ? ` • ${rtt} ms` : ''}${codec ? ` • ${codec}` : ''}${vidInfo}`;
      if (label !== last) {
        last = label;
        log(`link: ${label}`);
        // Direct/LAN: zero buffer for minimum latency. Relay (mobile/cross-net):
        // a small buffer absorbs jitter so it feels smooth instead of stuttery.
        if (videoReceiver) {
          const ms = relayed ? 150 : 0;
          try {
            if ('jitterBufferTarget' in videoReceiver) videoReceiver.jitterBufferTarget = ms;
            if ('playoutDelayHint' in videoReceiver) videoReceiver.playoutDelayHint = ms / 1000;
          } catch (_) {}
        }
      }
      const el = $('linkStat'); if (el) el.textContent = label;
    } catch (_) {}
  }, 2000);
}

function teardown() {
  stopClipboardSync();
  clearInterval(linkTimer);
  clearInterval(netTimer);
  exitFieldView();
  peerE2E = null;
  if ($('navGroup')) $('navGroup').hidden = true;
  if ($('netBtn')) $('netBtn').hidden = true;
  if ($('netPanel')) $('netPanel').hidden = true;
  controlChannel = null;
  fileChannel = null;
  inputChannel = null;
  videoReceiver = null;
  lastClip = '';
  sessionPerms = { input: true, clipboard: true, file: true };
  drawMode = false;
  if ($('drawToggle')) $('drawToggle').classList.remove('active');
  if ($('drawClear')) $('drawClear').hidden = true;
  if ($('screen')) $('screen').classList.remove('draw-cursor');
  if ($('quality')) $('quality').disabled = true;
  if ($('sendFileBtn')) $('sendFileBtn').disabled = true;
  if ($('chat')) $('chat').hidden = true;
  if ($('chatLog')) $('chatLog').innerHTML = '';
  if ($('enableAudioBtn')) $('enableAudioBtn').hidden = true;
  if ($('filesBtn')) $('filesBtn').hidden = true;
  if ($('fsPanel')) $('fsPanel').hidden = true;
  fsChannel = null; fsIncoming = null;
  if (pc) { pc.close(); pc = null; }
  $('screen').srcObject = null;
  setStatus('session ended');
  log('session ended');
}

// ---- Milestone B: capture local input and send to the host ----

const BUTTONS = { 0: 'left', 1: 'middle', 2: 'right' };

// Normalize a pointer position to 0..1 over the actual video content, undoing
// the letterboxing from object-fit: contain.
function normCoords(video, clientX, clientY) {
  const rect = video.getBoundingClientRect();
  const vw = video.videoWidth;
  const vh = video.videoHeight;
  if (!vw || !vh) return null;
  const scale = Math.min(rect.width / vw, rect.height / vh);
  const dispW = vw * scale;
  const dispH = vh * scale;
  const offX = rect.left + (rect.width - dispW) / 2;
  const offY = rect.top + (rect.height - dispH) / 2;
  const x = (clientX - offX) / dispW;
  const y = (clientY - offY) / dispH;
  if (x < 0 || x > 1 || y < 0 || y > 1) return null; // outside the screen area
  return { x, y };
}

function attachInputCapture(video, channel) {
  const send = (ev) => {
    if (!sessionPerms.input) return;
    if (channel.readyState === 'open') channel.send(JSON.stringify(ev));
  };

  video.tabIndex = 0;
  video.addEventListener('click', () => video.focus());

  // Send one annotation line segment (normalized coords) to the host overlay.
  const sendStroke = (p0, p1) => {
    if (!controlChannel || controlChannel.readyState !== 'open') return;
    controlChannel.send(JSON.stringify({
      kind: 'annotate', op: 'draw',
      stroke: { x0: p0.x, y0: p0.y, x1: p1.x, y1: p1.y, color: '#ff2d55' },
    }));
  };
  let drawing = false;
  let lastPt = null;

  // Throttle mouse-moves to ~60/s. Un-throttled, a fast drag floods the host
  // with events and can freeze it; 60/s is smooth and safe.
  let lastMove = 0;
  video.addEventListener('mousemove', (e) => {
    const now = performance.now();
    if (now - lastMove < 16) return;
    lastMove = now;
    const p = normCoords(video, e.clientX, e.clientY);
    if (!p) return;
    if (drawMode) {
      if (drawing && lastPt) sendStroke(lastPt, p);
      lastPt = p;
    } else {
      send({ kind: 'move', x: p.x, y: p.y });
    }
  });
  video.addEventListener('mousedown', (e) => {
    const p = normCoords(video, e.clientX, e.clientY);
    if (!p) return;
    if (drawMode) { drawing = true; lastPt = p; return; }
    send({ kind: 'mousedown', button: BUTTONS[e.button] || 'left', x: p.x, y: p.y });
  });
  video.addEventListener('mouseup', (e) => {
    const p = normCoords(video, e.clientX, e.clientY);
    if (drawMode) { drawing = false; lastPt = null; return; }
    if (p) send({ kind: 'mouseup', button: BUTTONS[e.button] || 'left', x: p.x, y: p.y });
  });
  video.addEventListener('contextmenu', (e) => e.preventDefault()); // let right-click pass through
  video.addEventListener('wheel', (e) => {
    e.preventDefault();
    send({ kind: 'wheel', dy: Math.sign(e.deltaY) });
  }, { passive: false });

  // Keyboard is handled globally (see DOMContentLoaded) so it doesn't depend on
  // the <video> keeping focus.
  log('input capture attached — click the screen to control; type anywhere');
}
