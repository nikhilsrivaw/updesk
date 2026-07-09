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
  }

  _emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  async connect() {
    this.deliberateClose = false;
    if (!this.key) this.key = await loadOrCreateKey(this.identityId);
    this._open();
  }

  _open() {
    this.openedThisAttempt = false;
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
    const delayMs = Math.min(1000 * 2 ** (this.reconnectAttempt - 1), 15000);
    this._emit('reconnecting', { attempt: this.reconnectAttempt, delayMs });
    clearTimeout(this._reconnectTimer);
    this._reconnectTimer = setTimeout(() => this._open(), delayMs);
  }

  async _onMessage(raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    switch (msg.type) {
      case 'auth_challenge':
        this._send({ type: 'auth_response', signature: await signNonce(this.key.privateKey, msg.nonce) });
        break;
      case 'auth_ok':
        this.reconnectAttempt = 0;
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

  close() {
    this.deliberateClose = true;
    clearTimeout(this._reconnectTimer);
    if (this.ws) this.ws.close();
  }

  _send(o) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(o));
  }
}
