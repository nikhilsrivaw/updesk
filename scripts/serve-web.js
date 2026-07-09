// Serve the browser controller (dist/web-controller/) over HTTPS using the dev
// cert, so any browser on the LAN can be a controller. A secure origin is
// required for WebCrypto + WebRTC; https provides it.
//
//   node scripts/serve-web.js           # https://<this-ip>:8443/
//   WEB_PORT=9443 node scripts/serve-web.js
//
// For production use a real cert (see infra/caddy/Caddyfile) and the browser
// warnings disappear.

const https = require('https');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', 'dist', 'web-controller');
const PORT = process.env.WEB_PORT || 8443;
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
};

const opts = {
  key: fs.readFileSync(path.join(__dirname, '..', 'certs', 'key.pem')),
  cert: fs.readFileSync(path.join(__dirname, '..', 'certs', 'cert.pem')),
};

https
  .createServer(opts, (req, res) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p === '/') p = '/index.html';
    const file = path.normalize(path.join(ROOT, p));
    if (!file.startsWith(ROOT)) {
      res.writeHead(403);
      return res.end('forbidden');
    }
    fs.readFile(file, (err, data) => {
      if (err) {
        res.writeHead(404);
        return res.end('not found');
      }
      res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
      res.end(data);
    });
  })
  .listen(PORT, () => {
    console.log(`Web controller served at https://<this-machine-ip>:${PORT}/`);
    console.log('Open it in any browser on the LAN (accept the dev-cert warning).');
  });
