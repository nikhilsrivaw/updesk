const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// Loads this identity's Ed25519 private key from `keyDir`, generating and
// persisting one on first run. The public key is returned as base64 SPKI DER
// (the wire format the signaling server expects). `firstTime` tells the caller
// whether an enroll code will be required.
function loadOrCreateKey(keyDir, identityId) {
  fs.mkdirSync(keyDir, { recursive: true });
  const file = path.join(keyDir, `${identityId}.pem`);
  const firstTime = !fs.existsSync(file);

  let privateKey;
  if (firstTime) {
    privateKey = crypto.generateKeyPairSync('ed25519').privateKey;
    fs.writeFileSync(file, privateKey.export({ type: 'pkcs8', format: 'pem' }));
  } else {
    privateKey = crypto.createPrivateKey(fs.readFileSync(file));
  }

  const publicKeyB64 = crypto
    .createPublicKey(privateKey)
    .export({ type: 'spki', format: 'der' })
    .toString('base64');

  return { privateKey, publicKeyB64, firstTime };
}

function sign(privateKey, nonce) {
  return crypto.sign(null, Buffer.from(nonce), privateKey).toString('base64');
}

module.exports = { loadOrCreateKey, sign };
