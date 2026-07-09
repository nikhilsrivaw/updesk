require('dotenv').config();
const WebSocket = require('ws');
const deviceRegistry = require('./deviceRegistry');
const sessionManager = require('./sessionManager');
const authManager = require('./authManager');
const peerRegistry = require('./peerRegistry');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

console.log(`Signaling server running on port ${PORT}`);

wss.on('connection', (ws) => {
  const state = { pending: null }; // outstanding auth challenge, if any
  let identity = null; // { id, type } once authenticated
  let registeredDeviceId = null;

  const send = (obj) => ws.send(JSON.stringify(obj));

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      return send({ type: 'error', message: 'Invalid JSON' });
    }

    // --- Authentication handshake (the only messages allowed pre-auth) ---
    if (msg.type === 'auth_init') {
      const result = authManager.startHandshake(state, msg);
      if (!result.ok) return send({ type: 'auth_error', message: result.error });
      return send({ type: 'auth_challenge', nonce: result.nonce });
    }

    if (msg.type === 'auth_response') {
      const result = authManager.completeHandshake(state, msg.signature);
      if (!result.ok) return send({ type: 'auth_error', message: result.error });
      identity = result.identity;
      peerRegistry.add(identity.id, ws); // routable by identity from now on
      return send({ type: 'auth_ok', identityId: identity.id, kind: identity.type });
    }

    // --- Everything below requires an authenticated identity ---
    if (!identity) {
      return send({ type: 'error', message: 'Not authenticated' });
    }

    switch (msg.type) {
      case 'register': {
        if (identity.type !== 'device') {
          return send({ type: 'error', message: 'Only device identities can register' });
        }
        // A device may only register under its own authenticated id — this is
        // what closes the impersonation hole from the old scaffold.
        const { deviceId, metadata } = msg;
        if (deviceId && deviceId !== identity.id) {
          return send({ type: 'error', message: 'deviceId must match authenticated identity' });
        }
        registeredDeviceId = identity.id;
        deviceRegistry.register(identity.id, ws, metadata);
        return send({ type: 'registered', deviceId: identity.id });
      }

      case 'connect_request': {
        if (identity.type !== 'controller') {
          return send({ type: 'error', message: 'Only controller identities can request connections' });
        }
        const { targetDeviceId } = msg;
        const target = deviceRegistry.get(targetDeviceId);
        if (!target) {
          return send({ type: 'error', message: 'Device offline or not found' });
        }
        // controllerId is bound to the authenticated identity, not client input.
        const controllerId = identity.id;
        const sessionId = sessionManager.createSession(controllerId, targetDeviceId);

        target.ws.send(JSON.stringify({
          type: 'incoming_request',
          sessionId,
          controllerId
        }));

        return send({ type: 'request_sent', sessionId });
      }

      case 'session_response': {
        const { sessionId, accepted } = msg;
        const session = sessionManager.get(sessionId);
        if (!session) return send({ type: 'error', message: 'Unknown session' });
        // Only the targeted device may accept/reject its own session.
        if (session.deviceId !== identity.id) {
          return send({ type: 'error', message: 'Not authorized for this session' });
        }
        if (accepted) sessionManager.activate(sessionId);
        else sessionManager.reject(sessionId);

        // Relay the decision back to the requesting controller (step 2).
        const delivered = peerRegistry.send(session.controllerId, {
          type: 'session_response',
          sessionId,
          accepted
        });
        if (!delivered) {
          // Controller vanished between request and response — nothing to do
          // beyond the state update already recorded.
        }
        break;
      }

      case 'offer':
      case 'answer':
      case 'ice_candidate': {
        // Session-routed: forward to the *other* participant, whichever side
        // this peer is. Requires a sessionId the peer belongs to.
        const { sessionId } = msg;
        const session = sessionManager.get(sessionId);
        if (!session) return send({ type: 'error', message: 'Unknown session' });
        if (identity.id !== session.controllerId && identity.id !== session.deviceId) {
          return send({ type: 'error', message: 'Not a participant of this session' });
        }
        if (session.status !== 'active') {
          return send({ type: 'error', message: 'Session is not active' });
        }
        const counterpartId =
          identity.id === session.controllerId ? session.deviceId : session.controllerId;
        peerRegistry.send(counterpartId, msg);
        break;
      }

      case 'end_session': {
        const session = sessionManager.get(msg.sessionId);
        // Only a participant may end the session.
        if (session && (session.controllerId === identity.id || session.deviceId === identity.id)) {
          sessionManager.end(msg.sessionId);
          const counterpartId =
            identity.id === session.controllerId ? session.deviceId : session.controllerId;
          peerRegistry.send(counterpartId, { type: 'session_ended', sessionId: msg.sessionId });
        }
        break;
      }

      default:
        send({ type: 'error', message: 'Unknown message type' });
    }
  });

  ws.on('close', () => {
    if (registeredDeviceId) {
      deviceRegistry.unregister(registeredDeviceId);
    }
    if (identity) {
      // Tear down this peer's open sessions and notify the other side.
      for (const session of sessionManager.listOpenByParticipant(identity.id)) {
        sessionManager.end(session.sessionId);
        const counterpartId =
          identity.id === session.controllerId ? session.deviceId : session.controllerId;
        peerRegistry.send(counterpartId, {
          type: 'peer_disconnected',
          sessionId: session.sessionId
        });
      }
      peerRegistry.remove(identity.id);
    }
  });
});
