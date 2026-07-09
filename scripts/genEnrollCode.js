
// Mint a one-time enroll code to hand out-of-band to a new device/controller.
//   node scripts/genEnrollCode.js [device|controller]
// Paste the printed CODE:type into ENROLL_CODES in .env, then restart the server.

const crypto = require('crypto');

const type = process.argv[2] || 'device';
if (type !== 'device' && type !== 'controller') {
  console.error('type must be "device" or "controller"');
  process.exit(1);
}

const code = crypto.randomBytes(4).toString('hex').toUpperCase().match(/.{1,4}/g).join('-');
console.log(`${code}:${type}`);
console.error('\nAdd this to ENROLL_CODES in .env (comma-separated), then restart the server.');
