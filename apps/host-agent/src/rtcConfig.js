// ICE servers for WebRTC NAT traversal.
//
// STUN alone works on the same LAN / simple NATs. For real cross-network use
// (symmetric NATs, restrictive firewalls) you need a TURN relay — stand up
// coturn (see infra/coturn/turnserver.conf) and fill in the entries below.
// Use turns:// (TLS) in production.
export const ICE_SERVERS = [
  // Several STUN servers so a direct (low-latency) path is found more often —
  // every direct connection we win is one that doesn't pay the relay's extra
  // round-trip. TURN below stays as the fallback for symmetric NATs/firewalls.
  { urls: [
    'stun:stun.l.google.com:19302',
    'stun:stun1.l.google.com:19302',
    'stun:stun2.l.google.com:19302',
  ] },
  {
    urls: ['turn:up-desk.online:3478?transport=udp', 'turn:up-desk.online:3478?transport=tcp'],
    username: 'updesk',
    credential: 'updesk_turn_9fKq2mXz7L'
  }
];
