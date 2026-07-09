// ICE servers for WebRTC NAT traversal.
//
// STUN alone works on the same LAN / simple NATs. For real cross-network use
// (symmetric NATs, restrictive firewalls) you need a TURN relay — stand up
// coturn (see infra/coturn/turnserver.conf) and fill in the entries below.
// Use turns:// (TLS) in production.
export const ICE_SERVERS = [
  { urls: 'stun:stun.l.google.com:19302' },
  {
    urls: ['turn:updesk.duckdns.org:3478?transport=udp', 'turn:updesk.duckdns.org:3478?transport=tcp'],
    username: 'updesk',
    credential: 'updesk_turn_9fKq2mXz7L'
  }
];
