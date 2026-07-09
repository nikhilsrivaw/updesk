const crypto = require('crypto');
const authCrypto = require('./authCrypto');
const keyStore = require('./keyStore');

// Orchestrates the two-step challenge/response handshake.
//
//   client                         server
//   ------                         ------
//   auth_init {identityId,type,      ->  startHandshake()
//     publicKey?, enrollCode?}       <-  auth_challenge {nonce}
//   auth_response {signature}        ->  completeHandshake()
//                                    <-  auth_ok {identityId,kind} | auth_error
//
// Handshake state lives on a per-connection `state` object supplied by the
// caller (state.pending), so this module stays a stateless singleton.

const CHALLENGE_TTL_MS = 30_000;

class AuthManager {
  // Validate the auth_init and issue a nonce. Returns { ok, nonce } or
  // { ok:false, error }. Does NOT authenticate anything yet — enrollment is
  // only finalized once the signature is proven in completeHandshake().
  startHandshake(state, msg) {
    // `kind` is the requested identity kind (device|controller). Note it is
    // deliberately NOT called `type` — that name is taken by the message
    // envelope (`type: 'auth_init'`).
    const { identityId, kind, publicKey, enrollCode } = msg;
    if (!identityId) return { ok: false, error: 'identityId required' };

    const existing = keyStore.getKey(identityId);
    let challengeKey;
    let resolvedType;
    let isEnrollment;

    if (existing) {
      // Returning identity — challenge against the enrolled key only.
      // A mismatched publicKey means someone is trying to rebind the id.
      if (publicKey && publicKey !== existing.publicKey) {
        return { ok: false, error: 'public key does not match enrolled identity' };
      }
      challengeKey = existing.publicKey;
      resolvedType = existing.type;
      isEnrollment = false;
    } else {
      // First contact (TOFU) — requires a public key and a valid enroll code.
      if (!publicKey) return { ok: false, error: 'publicKey required for enrollment' };
      if (!enrollCode) return { ok: false, error: 'enrollCode required for enrollment' };

      const code = keyStore.getEnrollCode(enrollCode);
      if (!code || code.used) return { ok: false, error: 'invalid or already-used enroll code' };
      if (code.expiresAt && Date.now() > code.expiresAt) {
        return { ok: false, error: 'enroll code expired' };
      }
      if (kind && kind !== code.type) {
        return { ok: false, error: `enroll code is for a ${code.type}, not a ${kind}` };
      }

      challengeKey = publicKey;
      resolvedType = code.type; // type is fixed by the code, not client-chosen
      isEnrollment = true;
    }

    const nonce = crypto.randomBytes(32).toString('base64');
    state.pending = {
      identityId,
      type: resolvedType,
      publicKey: challengeKey,
      nonce,
      isEnrollment,
      enrollCode: isEnrollment ? enrollCode : null,
      expiresAt: Date.now() + CHALLENGE_TTL_MS
    };
    return { ok: true, nonce };
  }

  // Verify the signature over the outstanding nonce. On success, finalizes
  // enrollment (TOFU) if this was a first contact, and returns the identity.
  completeHandshake(state, signature) {
    const p = state.pending;
    if (!p) return { ok: false, error: 'no handshake in progress' };

    // Single-use, time-boxed: clear the pending challenge no matter the outcome.
    state.pending = null;

    if (Date.now() > p.expiresAt) return { ok: false, error: 'challenge expired' };
    if (!signature) return { ok: false, error: 'signature required' };
    if (!authCrypto.verify(p.publicKey, p.nonce, signature)) {
      return { ok: false, error: 'signature verification failed' };
    }

    if (p.isEnrollment) {
      keyStore.enroll(p.identityId, p.publicKey, p.type);
      keyStore.consumeCode(p.enrollCode);
    }
    return { ok: true, identity: { id: p.identityId, type: p.type } };
  }
}

module.exports = new AuthManager();
