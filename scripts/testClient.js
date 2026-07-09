// End-to-end signaling test using the SHARED SignalingClient (the same module
// the Electron apps use). Proves the auth + consent + relay layer in Node,
// with fake SDP standing in for real WebRTC.
//
//   node scripts/testClient.js <identityId> <device|controller> [enrollCode] [targetDeviceId]

const path = require('path');
require('dotenv').config();
const SignalingClient = require('../shared/SignalingClient');

const [, , identityId, kind, enrollCode, targetDeviceId] = process.argv;
const PORT = process.env.PORT || 8080;

if (!identityId || (kind !== 'device' && kind !== 'controller')) {
  console.error('Usage: node scripts/testClient.js <identityId> <device|controller> [enrollCode] [targetDeviceId]');
  process.exit(1);
}

const client = new SignalingClient({
  url: `ws://localhost:${PORT}`,
  identityId,
  kind,
  enrollCode,
  keyDir: path.join(__dirname, '.keys')
});

const log = (...a) => console.log(`[${identityId}]`, ...a);

client.on('ready', () => {
  log('authenticated');
  if (kind === 'device') client.register({ os: 'demo' });
  else if (targetDeviceId) client.connectRequest(targetDeviceId);
  else log('controller ready; pass a targetDeviceId to connect.');
});

// host side
client.on('incoming_request', ({ sessionId, controllerId }) => {
  log(`auto-accepting session from ${controllerId}`);
  client.respond(sessionId, true);
});
client.on('offer', ({ sessionId }) => client.signal('answer', sessionId, { sdp: 'FAKE-ANSWER-SDP' }));

// controller side
client.on('session_response', ({ sessionId, accepted }) => {
  if (!accepted) return log('session REJECTED') || client.close();
  log('session accepted -> sending offer');
  client.signal('offer', sessionId, { sdp: 'FAKE-OFFER-SDP' });
});
client.on('answer', ({ sessionId }) => {
  log('got answer -> relay works BOTH ways. Ending session.');
  client.end(sessionId);
});

client.on('session_ended', () => log('session_ended') || client.close());
client.on('peer_disconnected', () => log('peer_disconnected') || client.close());
client.on('error', ({ message }) => console.error(`[${identityId}] error:`, message));
client.on('close', () => log('socket closed'));

client.connect();
