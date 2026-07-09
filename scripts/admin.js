// UpDesk admin CLI — drives the server's admin channel.
//
//   node scripts/admin.js identities
//   node scripts/admin.js devices
//   node scripts/admin.js sessions
//   node scripts/admin.js revoke <identityId>
//   node scripts/admin.js mint-code <device|controller>
//
// Env: UPDESK_ADMIN_URL (default wss://localhost:8080), ADMIN_TOKEN (from .env).

const WebSocket = require('ws');
require('dotenv').config();

const URL = process.env.UPDESK_ADMIN_URL || 'wss://localhost:8080';
const TOKEN = process.env.ADMIN_TOKEN;
const [, , cmd, arg] = process.argv;

if (!cmd) {
  console.error('Usage: node scripts/admin.js <identities|devices|sessions|revoke <id>|mint-code <device|controller>>');
  process.exit(1);
}
if (!TOKEN) {
  console.error('ADMIN_TOKEN not set (check .env)');
  process.exit(1);
}

// Self-signed dev cert on wss:// — skip verification for this local tool.
const ws = new WebSocket(URL, { rejectUnauthorized: false });
const send = (o) => ws.send(JSON.stringify(o));

const REQUEST = {
  identities: { type: 'admin_identities' },
  devices: { type: 'admin_devices' },
  sessions: { type: 'admin_sessions' },
  revoke: { type: 'admin_revoke', identityId: arg },
  'mint-code': { type: 'admin_mint_code', kind: arg }
};

ws.on('open', () => send({ type: 'admin_auth', token: TOKEN }));

ws.on('message', (raw) => {
  const m = JSON.parse(raw);
  if (m.type === 'admin_ok' && !('revoked' in m) && !('code' in m)) {
    const req = REQUEST[cmd];
    if (!req) { console.error(`unknown command: ${cmd}`); return ws.close(); }
    return send(req);
  }
  switch (m.type) {
    case 'admin_error':
      console.error('error:', m.message);
      break;
    case 'admin_identities':
      console.table(m.identities);
      break;
    case 'admin_devices':
      console.log('online devices:', m.devices.length ? m.devices.join(', ') : '(none)');
      break;
    case 'admin_sessions':
      console.table(m.sessions.map((s) => ({
        controller: s.controllerId, device: s.deviceId, status: s.status,
        duration_ms: s.durationMs, started: new Date(s.startMs).toLocaleString()
      })));
      break;
    case 'admin_ok':
      if ('revoked' in m) console.log(`revoked "${m.revoked}" (was enrolled: ${m.existed})`);
      if ('code' in m) console.log(`minted ${m.kind} enroll code: ${m.code}`);
      break;
    default:
      return; // ignore
  }
  ws.close();
});

ws.on('error', (e) => { console.error('connection error:', e.message); process.exit(1); });
ws.on('close', () => process.exit(0));
