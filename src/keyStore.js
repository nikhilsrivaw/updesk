// Storage seam for authentication material.
//
// Holds two things, both in-memory for now to match deviceRegistry /
// sessionManager. TODO(step 4): back these with Postgres tables
// (enrolled_keys, enroll_codes) behind this same interface so callers
// don't change.
//
//   - enrolled keys: identityId -> { publicKey, type, enrolledAt }
//   - enroll codes:  code       -> { type, used, expiresAt }

class KeyStore {
  constructor() {
    this.keys = new Map();
    this.enrollCodes = new Map();
    this._loadEnrollCodesFromEnv();
  }

  // ENROLL_CODES="ABCD-1234:device,WXYZ-9876:controller"
  _loadEnrollCodesFromEnv() {
    const raw = process.env.ENROLL_CODES;
    if (!raw) return;
    for (const entry of raw.split(',').map((s) => s.trim()).filter(Boolean)) {
      const [code, type] = entry.split(':');
      if (code && (type === 'device' || type === 'controller')) {
        this.enrollCodes.set(code, { type, used: false, expiresAt: null });
      } else {
        console.warn(`Ignoring malformed ENROLL_CODES entry: "${entry}"`);
      }
    }
    console.log(`Loaded ${this.enrollCodes.size} enroll code(s) from env`);
  }

  // --- enrolled keys ---
  getKey(identityId) {
    return this.keys.get(identityId);
  }

  hasKey(identityId) {
    return this.keys.has(identityId);
  }

  enroll(identityId, publicKey, type) {
    this.keys.set(identityId, { publicKey, type, enrolledAt: Date.now() });
    console.log(`Enrolled ${type} identity: ${identityId}`);
  }

  // --- enroll codes ---
  getEnrollCode(code) {
    return this.enrollCodes.get(code);
  }

  consumeCode(code) {
    const entry = this.enrollCodes.get(code);
    if (entry) entry.used = true;
  }
}

module.exports = new KeyStore();
