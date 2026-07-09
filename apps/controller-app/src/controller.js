import { SignalingClient } from './signaling.js';
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
  if (!sessionPerms.clipboard) stopClipboardSync();
  if ($('sendFileBtn')) $('sendFileBtn').disabled = !sessionPerms.file;
  const denied = [];
  if (!sessionPerms.input) denied.push('view-only');
  if (!sessionPerms.clipboard) denied.push('no clipboard');
  if (!sessionPerms.file) denied.push('no files');
  if (denied.length) setStatus(`connected — live (${denied.join(', ')})`);
}

// --- remote file browser (fs channel; e.g. an Android host's storage) ---
let fsChannel = null;
let fsIncoming = null; // { name, size, chunks: [] }
let fsCurrentPath = '/storage/emulated/0';
let examinerId = localStorage.getItem('updesk-examiner') || '';
let currentPartnerId = '';

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
function exportCustody(kind) {
  const log = loadCustody();
  if (!log.length) { fsStatus('nothing to export yet'); return; }
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
  saveDownload(name, btoa(unescape(encodeURIComponent(data)))).then((p) => fsStatus('exported → ' + p));
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
    $('fsPanel').hidden = true; // don't let the two panels stack
    const p = $('custodyPanel');
    p.hidden = !p.hidden;
    if (!p.hidden) renderCustody();
  });
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
    sessionId = e.detail.sessionId;
    pc = new RTCPeerConnection(ICE);

    const gotKinds = new Set();
    pc.ontrack = (ev) => {
      const v = $('screen');
      v.srcObject = ev.streams[0];
      v.muted = false; // let host audio through (if shared)
      v.play().catch(() => {});
      gotKinds.add(ev.track.kind);
      // Visible proof of what arrived: "connected — live (video+audio)".
      setStatus(`connected — live (${[...gotKinds].sort().join('+')})`);
      if (ev.track.kind === 'audio') $('enableAudioBtn').hidden = false;
      log(`remote ${ev.track.kind} track received`);
    };
    // Host-created data channels: 'input' (we send input events) and 'control'
    // (clipboard sync + quality requests).
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
          }
        };
        $('quality').disabled = false;
        $('chat').hidden = false;
      } else if (ch.label === 'file') {
        fileChannel = ch;
        attachFileReceiver(ch, { log, save: saveDownload });
        $('sendFileBtn').disabled = false;
      } else if (ch.label === 'fs') {
        setupFileBrowser(ch);
      } else {
        inputChannel = ch;
        window.__inputChannel = ch;
        attachInputCapture($('screen'), ch);
      }
    };
    pc.onicecandidate = (ev) => {
      if (ev.candidate) client.signal('ice_candidate', sessionId, { candidate: ev.candidate });
    };
    pc.onconnectionstatechange = () => log(`pc: ${pc.connectionState}`);

    await pc.setRemoteDescription({ type: 'offer', sdp: e.detail.sdp });
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    client.signal('answer', sessionId, { sdp: answer.sdp });
    log('answer sent');
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
  if (client) client.close();
  client = null;
  if (pc) { pc.close(); pc = null; }
  localStorage.removeItem('updesk-ctl-config');
  $('screen').srcObject = null;
  $('live').hidden = true;
  $('config').hidden = false;
  renderRecents();
}

function teardown() {
  stopClipboardSync();
  controlChannel = null;
  fileChannel = null;
  inputChannel = null;
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
