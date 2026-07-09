// ICE servers for WebRTC NAT traversal.
//
// STUN alone works on the same LAN / simple NATs. For real cross-network use
// (symmetric NATs, restrictive firewalls) you need a TURN relay — stand up
// coturn (see infra/coturn/turnserver.conf) and fill in the entries below.
// Use turns:// (TLS) in production.
export const ICE_SERVERS = [
  { urls: 'stun:stun.l.google.com:19302' }
  // {
  //   urls: ['turn:YOUR_TURN_HOST:3478', 'turns:YOUR_TURN_HOST:5349'],
  //   username: 'updesk',
  //   credential: 'CHANGE_ME'
  // }
];
