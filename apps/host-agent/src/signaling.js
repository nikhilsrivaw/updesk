// Browser/webview SignalingClient for UpDesk.
//
// Same wire protocol as the Rust signaling server, native to the webview:
//   - transport: native WebSocket (auto-reconnect, ws->wss auto-upgrade)
//   - crypto:    WebCrypto Ed25519
//   - key store: IndexedDB (private key persisted as JWK)

const DB_NAME = 'updesk';
const STORE = 'keys';

function idbOpen() {
  return new Promise((res, rej) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => res(req.result);
    req.onerror = () => rej(req.error);
  });
}
async function idbGet(key) {
  const db = await idbOpen();
  return new Promise((res, rej) => {
    const r = db.transaction(STORE, 'readonly').objectStore(STORE).get(key);
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
async function idbPut(key, val) {
  const db = await idbOpen();
  return new Promise((res, rej) => {
    const tx = db.transaction(STORE, 'readwrite');
    tx.objectStore(STORE).put(val, key);
    tx.oncomplete = () => res();
    tx.onerror = () => rej(tx.error);
  });
}

function bufToB64(buf) {
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

async function loadOrCreateKey(identityId) {
  const stored = await idbGet(identityId);
  if (stored) {
    const privateKey = await crypto.subtle.importKey('jwk', stored.privateJwk, { name: 'Ed25519' }, false, ['sign']);
    return { privateKey, publicKeyB64: stored.publicKeyB64, firstTime: false };
  }
  const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const publicKeyB64 = bufToB64(await crypto.subtle.exportKey('spki', pair.publicKey));
  const privateJwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
  await idbPut(identityId, { privateJwk, publicKeyB64 });
  const privateKey = await crypto.subtle.importKey('jwk', privateJwk, { name: 'Ed25519' }, false, ['sign']);
  return { privateKey, publicKeyB64, firstTime: true };
}

async function signNonce(privateKey, nonce) {
  return bufToB64(await crypto.subtle.sign({ name: 'Ed25519' }, privateKey, new TextEncoder().encode(nonce)));
}

function b64ToBuf(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

// Verify an Ed25519 signature (base64) over `message` using a base64 SPKI public
// key. Used to bind a peer's DTLS fingerprint to its long-term identity, so a
// relay/server that rewrites the media handshake is detected.
export async function verifySig(publicKeyB64, message, sigB64) {
  try {
    const pub = await crypto.subtle.importKey('spki', b64ToBuf(publicKeyB64), { name: 'Ed25519' }, false, ['verify']);
    return await crypto.subtle.verify({ name: 'Ed25519' }, pub, b64ToBuf(sigB64), new TextEncoder().encode(message));
  } catch (_) { return false; }
}

// A human-verifiable fingerprint of an identity public key: SHA-256, first 16
// bytes (128-bit) as grouped hex. 128 bits is birthday-resistant, so it's safe
// for operators to read out-of-band to confirm no man-in-the-middle.
export async function keyFingerprint(publicKeyB64) {
  try {
    const buf = await crypto.subtle.digest('SHA-256', b64ToBuf(publicKeyB64));
    const hex = Array.from(new Uint8Array(buf).slice(0, 16)).map((x) => x.toString(16).padStart(2, '0').toUpperCase());
    return hex.join('').replace(/(.{4})(?=.)/g, '$1 ');
  } catch (_) { return ''; }
}

// Default to wss, and upgrade ws://<remote> to wss:// (webviews block insecure
// ws to non-localhost anyway — the #1 source of confusing failures).
export function normalizeUrl(url) {
  url = (url || '').trim();
  if (!/^wss?:\/\//i.test(url)) url = 'wss://' + url;
  const host = (url.match(/^ws:\/\/([^/:]+)/i) || [])[1];
  if (host && !/^(localhost|127\.0\.0\.1)$/i.test(host)) {
    url = url.replace(/^ws:\/\//i, 'wss://');
  }
  return url;
}

// Events: ready, reconnected, reconnecting{attempt,delayMs}, disconnected{reason},
//   error{kind,message}  (kind: connect|auth|server),
//   registered, request_sent, incoming_request, session_response, offer, answer,
//   ice_candidate, session_ended, peer_disconnected
export class SignalingClient extends EventTarget {
  constructor({ url, identityId, kind, enrollCode, autoReconnect = true }) {
    super();
    this.url = normalizeUrl(url);
    this.identityId = identityId;
    this.kind = kind;
    this.enrollCode = enrollCode;
    this.autoReconnect = autoReconnect;
    this.ws = null;
    this.key = null;
    this.deliberateClose = false;
    this.everReady = false;
    this.openedThisAttempt = false;
    this.reconnectAttempt = 0;
    this._reconnectTimer = null;
    // Keepalive / zombie detection (see _startHeartbeat).
    this._heartbeatTimer = null;
    this._watchdogTimer = null;
    this._lastActivity = 0;
    this._netListenersAttached = false;
  }

  // Tunables for the liveness layer.
  static get HEARTBEAT_MS() { return 20000; } // app-ping cadence
  static get WATCHDOG_MS() { return 5000; }   // how often we check for silence
  static get IDLE_LIMIT_MS() { return 40000; } // silence that means "dead link"

  _emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  async connect() {
    this.deliberateClose = false;
    this._attachNetListeners();
    if (!this.key) this.key = await loadOrCreateKey(this.identityId);
    this._open();
  }

  _open() {
    // Detach any previous socket so its late 'close' can't double-trigger the
    // reconnect/heartbeat machinery while we dial a fresh one.
    if (this.ws) {
      this.ws.onopen = this.ws.onmessage = this.ws.onerror = this.ws.onclose = null;
      try { this.ws.close(); } catch (_) {}
    }
    this.openedThisAttempt = false;
    this._lastActivity = Date.now();
    let ws;
    try {
      ws = new WebSocket(this.url);
    } catch (e) {
      this._emit('error', { kind: 'connect', message: `Invalid server address: ${this.url}` });
      return;
    }
    this.ws = ws;
    ws.onopen = () => {
      this.openedThisAttempt = true;
      this._sendAuthInit();
    };
    ws.onmessage = (e) => this._onMessage(e.data);
    ws.onerror = () => {}; // detail isn't exposed; classified in onclose
    ws.onclose = () => this._onClose();
  }

  _sendAuthInit() {
    this._send({
      type: 'auth_init',
      identityId: this.identityId,
      kind: this.kind,
      publicKey: this.key.publicKeyB64,
      ...(this.enrollCode ? { enrollCode: this.enrollCode } : {})
    });
  }

  _onClose() {
    this._stopHeartbeat();
    if (this.deliberateClose) {
      this._emit('disconnected', { reason: 'closed' });
      return;
    }
    if (!this.openedThisAttempt) {
      this._emit('error', { kind: 'connect', message: this._connectHint() });
    } else {
      this._emit('disconnected', { reason: 'dropped' });
    }
    if (this.autoReconnect) this._scheduleReconnect();
  }

  _connectHint() {
    if (this.url.startsWith('wss://')) {
      return `Couldn't reach ${this.url} — the server may be offline, on another network, or the firewall is blocking it.`;
    }
    return `Couldn't connect to ${this.url}.`;
  }

  _scheduleReconnect() {
    this.reconnectAttempt++;
    // Exponential backoff capped at 15s, with 50-100% jitter so many clients
    // recovering from the same outage don't stampede the server in lockstep.
    const base = Math.min(1000 * 2 ** (this.reconnectAttempt - 1), 15000);
    const delayMs = Math.round(base * (0.5 + Math.random() * 0.5));
    this._emit('reconnecting', { attempt: this.reconnectAttempt, delayMs });
    clearTimeout(this._reconnectTimer);
    this._reconnectTimer = setTimeout(() => this._open(), delayMs);
  }

  // ---- liveness: keepalive heartbeat + zombie watchdog + wake/online reconnect ----

  // A silently-dropped socket (NAT/router idle timeout, laptop sleep, Wi-Fi
  // flap) often never fires 'close'. We ping the server on a cadence and, if
  // *nothing* comes back for IDLE_LIMIT_MS, treat the link as dead and redial —
  // rather than sitting "online" on a connection that can't carry traffic.
  _startHeartbeat() {
    this._stopHeartbeat();
    this._lastActivity = Date.now();
    this._heartbeatTimer = setInterval(() => {
      this._send({ type: 'ping' });
    }, SignalingClient.HEARTBEAT_MS);
    this._watchdogTimer = setInterval(() => {
      if (Date.now() - this._lastActivity > SignalingClient.IDLE_LIMIT_MS) {
        this._stopHeartbeat();
        this._emit('disconnected', { reason: 'timeout' });
        this._open(); // dial fresh; auth_ok will restart the heartbeat
      }
    }, SignalingClient.WATCHDOG_MS);
  }

  _stopHeartbeat() {
    clearInterval(this._heartbeatTimer); this._heartbeatTimer = null;
    clearInterval(this._watchdogTimer); this._watchdogTimer = null;
  }

  _attachNetListeners() {
    if (this._netListenersAttached) return;
    this._netListenersAttached = true;
    if (typeof window !== 'undefined' && window.addEventListener) {
      window.addEventListener('online', () => this._wake());
    }
    if (typeof document !== 'undefined' && document.addEventListener) {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') this._wake();
      });
    }
  }

  // Network came back or the app was refocused/woke from sleep — the moments a
  // stale socket is most likely dead. Confirm with a ping if we look open;
  // otherwise reconnect immediately instead of waiting out the backoff.
  _wake() {
    if (this.deliberateClose) return;
    const rs = this.ws ? this.ws.readyState : WebSocket.CLOSED;
    if (rs === WebSocket.OPEN) { this._send({ type: 'ping' }); return; }
    if (rs === WebSocket.CONNECTING) return; // a dial is already in flight
    this.reconnectAttempt = 0;
    clearTimeout(this._reconnectTimer);
    this._open();
  }

  async _onMessage(raw) {
    // Any frame is proof of life — keep the watchdog satisfied.
    this._lastActivity = Date.now();
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    switch (msg.type) {
      case 'pong':
        return; // keepalive ack; activity already recorded above
      case 'auth_challenge':
        this._send({ type: 'auth_response', signature: await signNonce(this.key.privateKey, msg.nonce) });
        break;
      case 'auth_ok':
        this.reconnectAttempt = 0;
        this._startHeartbeat();
        if (this.everReady) this._emit('reconnected', msg);
        this.everReady = true;
        this._emit('ready', msg);
        break;
      case 'auth_error':
        // Enrollment/identity problems are terminal — don't hammer-reconnect.
        this.deliberateClose = true;
        clearTimeout(this._reconnectTimer);
        this._emit('error', { kind: 'auth', message: msg.message });
        break;
      case 'error':
        this._emit('error', { kind: 'server', message: msg.message });
        break;
      default:
        this._emit(msg.type, msg);
    }
  }

  register(metadata = {}) { this._send({ type: 'register', deviceId: this.identityId, metadata }); }
  respond(sessionId, accepted) { this._send({ type: 'session_response', sessionId, accepted }); }
  // Dial by 9-digit partnerId + pin (AnyDesk-style), or legacy targetDeviceId.
  connectRequest(arg) {
    if (typeof arg === 'string') return this._send({ type: 'connect_request', targetDeviceId: arg });
    const { partnerId, pin, targetDeviceId } = arg || {};
    this._send({ type: 'connect_request', partnerId, pin, targetDeviceId });
  }
  signal(type, sessionId, payload = {}) { this._send({ type, sessionId, ...payload }); }
  end(sessionId) { this._send({ type: 'end_session', sessionId }); }

  // E2E verification layer: expose the identity public key and an Ed25519 signer
  // so peers can sign their DTLS fingerprint and verify each other's.
  getPublicKey() { return this.key ? this.key.publicKeyB64 : ''; }
  async sign(message) {
    if (!this.key) return '';
    return bufToB64(await crypto.subtle.sign({ name: 'Ed25519' }, this.key.privateKey, new TextEncoder().encode(message)));
  }

  close() {
    this.deliberateClose = true;
    this._stopHeartbeat();
    clearTimeout(this._reconnectTimer);
    if (this.ws) this.ws.close();
  }

  _send(o) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(o));
  }
}
