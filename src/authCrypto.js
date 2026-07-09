const crypto = require('crypto');

// Ed25519 signing primitives. Stateless helpers — no keys are stored here.
// Public keys are represented on the wire as base64-encoded SPKI DER (compact,
// JSON-safe). Signatures are base64 over the raw nonce bytes.

function importPublicKey(publicKeyB64) {
  return crypto.createPublicKey({
    key: Buffer.from(publicKeyB64, 'base64'),
    format: 'der',
    type: 'spki'
  });
}

// Verify that `signatureB64` is a valid Ed25519 signature of `message`
// produced by the private key matching `publicKeyB64`. Returns false on any
// malformed input rather than throwing.
function verify(publicKeyB64, message, signatureB64) {
  try {
    const keyObj = importPublicKey(publicKeyB64);
    return crypto.verify(
      null, // Ed25519 uses no separate digest
      Buffer.from(message),
      keyObj,
      Buffer.from(signatureB64, 'base64')
    );
  } catch (e) {
    return false;
  }
}

// Short, stable identifier for a public key (sha256 of the DER, hex).
// Useful for logging / future "the fingerprint IS the deviceId" schemes.
function fingerprint(publicKeyB64) {
  return crypto
    .createHash('sha256')
    .update(Buffer.from(publicKeyB64, 'base64'))
    .digest('hex')
    .slice(0, 16);
}

module.exports = { verify, fingerprint, importPublicKey };
