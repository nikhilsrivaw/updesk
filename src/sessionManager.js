const { v4: uuidv4 } = require('uuid');

class SessionManager {
  constructor() {
    this.sessions = new Map(); // sessionId -> { controllerId, deviceId, startTime, status }
  }

  createSession(controllerId, deviceId) {
    const sessionId = uuidv4();
    this.sessions.set(sessionId, {
      sessionId,
      controllerId,
      deviceId,
      startTime: Date.now(),
      status: 'pending' // pending -> active -> ended
    });
    return sessionId;
  }

  activate(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session) session.status = 'active';
  }

  reject(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.status = 'rejected';
      session.endTime = Date.now();
    }
    return session;
  }

  // Pending or active sessions this identity participates in — used to tear
  // down and notify the other side when a peer disconnects.
  listOpenByParticipant(identityId) {
    return Array.from(this.sessions.values()).filter(
      (s) =>
        (s.controllerId === identityId || s.deviceId === identityId) &&
        (s.status === 'pending' || s.status === 'active')
    );
  }

  end(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.status = 'ended';
      session.endTime = Date.now();
      console.log(`Session ended: ${JSON.stringify(session)}`);
    }
    return session;
  }

  get(sessionId) {
    return this.sessions.get(sessionId);
  }
}

module.exports = new SessionManager();
