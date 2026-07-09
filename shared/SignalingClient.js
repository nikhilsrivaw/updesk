const WebSocket = require('ws');
const EventEmitter = require('events');
const { loadOrCreateKey, sign } = require('./identityKey');

// Reusable client for the UpDesk signaling server, shared by the host agent
// and the controller app. Handles the Ed25519 challenge/response auth
// handshake, then surfaces everything else as events so the app layer only
// deals with WebRTC. Transport-agnostic about what the peers negotiate.
//
// Events:
//   ready                         authenticated and good to go
//   registered                    (device) register acknowledged
//   request_sent  {sessionId}     (controller) connect_request acknowledged
//   incoming_request {sessionId, controllerId}   (device) someone wants in
//   session_response {sessionId, accepted}        (controller) host's decision
//   offer   {sessionId, sdp}
//   answer  {sessionId, sdp}
//   ice_candidate {sessionId, candidate}
//   session_ended    {sessionId}
//   peer_disconnected {sessionId}
//   error   {message}
//   close
class SignalingClient extends EventEmitter {
  constructor({ url, identityId, kind, enrollCode, keyDir }) {
    super();
    this.url = url;
    this.identityId = identityId;
    this.kind = kind; // 'device' | 'controller'
    this.enrollCode = enrollCode;
    this.key = loadOrCreateKey(keyDir, identityId);
    this.ws = null;
  }

  connect() {
    this.ws = new WebSocket(this.url);
    this.ws.on('open', () => {
      this._send({
        type: 'auth_init',
        identityId: this.identityId,
        kind: this.kind,
        publicKey: this.key.publicKeyB64,
        ...(this.key.firstTime ? { enrollCode: this.enrollCode } : {})
      });
    });
    this.ws.on('message', (raw) => this._onMessage(raw));
    this.ws.on('close', () => this.emit('close'));
    this.ws.on('error', (e) => this.emit('error', { message: e.message }));
    return this;
  }

  _onMessage(raw) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      return;
    }

    switch (msg.type) {
      case 'auth_challenge':
        this._send({ type: 'auth_response', signature: sign(this.key.privateKey, msg.nonce) });
        break;
      case 'auth_ok':
        this.emit('ready', msg);
        break;
      case 'auth_error':
      case 'error':
        this.emit('error', { message: msg.message });
        break;
      case 'registered':
      case 'request_sent':
      case 'incoming_request':
      case 'session_response':
      case 'offer':
      case 'answer':
      case 'ice_candidate':
      case 'session_ended':
      case 'peer_disconnected':
        this.emit(msg.type, msg);
        break;
      default:
        break;
    }
  }

  // --- device ---
  register(metadata = {}) {
    this._send({ type: 'register', deviceId: this.identityId, metadata });
  }

  respond(sessionId, accepted) {
    this._send({ type: 'session_response', sessionId, accepted });
  }

  // --- controller ---
  connectRequest(targetDeviceId) {
    this._send({ type: 'connect_request', targetDeviceId });
  }

  // --- both: WebRTC signaling, session-routed ---
  signal(type, sessionId, payload = {}) {
    this._send({ type, sessionId, ...payload });
  }

  end(sessionId) {
    this._send({ type: 'end_session', sessionId });
  }

  close() {
    if (this.ws) this.ws.close();
  }

  _send(obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj));
    }
  }
}

module.exports = SignalingClient;
