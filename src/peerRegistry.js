// Tracks every authenticated connection by its identity id, so the server can
// route messages to either side of a session (device OR controller). This is
// separate from deviceRegistry, which is specifically about a device
// announcing itself as available to be controlled.
//
// In-memory only. TODO(step 4): this holds live ws handles and is inherently
// per-process; in a multi-instance deployment it becomes a presence lookup
// (e.g. Redis pub/sub) rather than a Postgres table.

class PeerRegistry {
  constructor() {
    this.peers = new Map(); // identityId -> ws
  }

  add(identityId, ws) {
    this.peers.set(identityId, ws);
  }

  get(identityId) {
    return this.peers.get(identityId);
  }

  remove(identityId) {
    this.peers.delete(identityId);
  }

  send(identityId, obj) {
    const ws = this.peers.get(identityId);
    if (ws && ws.readyState === 1 /* OPEN */) {
      ws.send(JSON.stringify(obj));
      return true;
    }
    return false;
  }
}

module.exports = new PeerRegistry();
