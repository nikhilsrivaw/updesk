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

// The 9-digit connect ID the server assigns — same FNV-1a as the server/native
// host, so we can show it here from just the identity id (no server change).
function connectId(identityId) {
  const mask = (1n << 64n) - 1n;
  let h = 1469598103934665603n & mask;
  for (const b of Buffer.from(String(identityId), 'utf8')) {
    h = (h ^ BigInt(b)) & mask;
    h = (h * 1099511628211n) & mask;
  }
  return (h % 1000000000n).toString().padStart(9, '0');
}
const fmtId = (id) => `${id.slice(0, 3)} ${id.slice(3, 6)} ${id.slice(6, 9)}`;

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
      console.table(m.identities.map((i) => ({
        identityId: i.identityId, kind: i.kind, connectId: fmtId(connectId(i.identityId))
      })));
      break;
    case 'admin_devices':
      if (!m.devices.length) { console.log('online devices: (none)'); break; }
      console.table(m.devices.map((d) => ({ device: d, connectId: fmtId(connectId(d)) })));
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
