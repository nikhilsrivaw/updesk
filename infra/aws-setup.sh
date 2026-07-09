#!/usr/bin/env bash
# UpDesk — one-paste AWS/Ubuntu server setup.
#
# Stands up all three services on one EC2 box:
#   1. signaling-server (plain ws on 127.0.0.1:8080)
#   2. Caddy            (public HTTPS/wss on :443, auto Let's Encrypt cert)
#   3. coturn           (TURN relay for cross-network WebRTC)
#
# HOW TO RUN (on the EC2 instance, after `ssh ubuntu@<elastic-ip>`):
#   1. Edit the three CONFIG values just below.
#   2. Paste the whole script into the terminal, or:
#        curl -sL <url-to-this> -o setup.sh && bash setup.sh
#
# Re-running is safe (idempotent-ish): it updates configs and restarts services.

set -euo pipefail

# ─────────────────────────── CONFIG — EDIT THESE ───────────────────────────
DOMAIN="updesk-nikhil.duckdns.org"     # your DuckDNS domain (points at this box)
TURN_SECRET="change-this-to-a-long-random-string"   # shared TURN password
REPO="https://github.com/nikhilsrivaw/updesk.git"   # your GitHub repo
# ───────────────────────────────────────────────────────────────────────────

echo "==> UpDesk server setup for $DOMAIN"
PUBLIC_IP="$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 || curl -s ifconfig.me)"
echo "==> Detected public IP: $PUBLIC_IP"

# ---- 1. system packages --------------------------------------------------
echo "==> Installing packages…"
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -y
sudo apt-get install -y curl git build-essential coturn debian-keyring debian-archive-keyring apt-transport-https

# Rust (for the signaling server)
if ! command -v cargo >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
  source "$HOME/.cargo/env"
fi
source "$HOME/.cargo/env" 2>/dev/null || true

# Caddy (official apt repo)
if ! command -v caddy >/dev/null 2>&1; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y caddy
fi

# ---- 2. get the code + build the signaling server ------------------------
cd "$HOME"
if [ ! -d updesk ]; then
  git clone "$REPO" updesk
fi
cd updesk
echo "==> Building signaling-server (first build takes a few minutes)…"
cargo build -p signaling-server --release

# ---- 3. signaling server as a systemd service (plain ws, localhost) -------
echo "==> Installing signaling-server service…"
SERVER_BIN="$HOME/updesk/target/release/signaling-server"
sudo tee /etc/systemd/system/updesk-signaling.service >/dev/null <<EOF
[Unit]
Description=UpDesk signaling server
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$HOME/updesk
# No TLS_CERT/TLS_KEY: Caddy terminates TLS and proxies to plain ws.
Environment=PORT=8080
Environment=ENROLL_CODES=DEV0-TEST:device,CTL0-TEST:controller
Environment=ADMIN_TOKEN=$TURN_SECRET
ExecStart=$SERVER_BIN
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

# ---- 4. Caddy: public wss with an auto cert ------------------------------
echo "==> Configuring Caddy for $DOMAIN…"
sudo tee /etc/caddy/Caddyfile >/dev/null <<EOF
$DOMAIN {
	reverse_proxy 127.0.0.1:8080
}
EOF

# ---- 5. coturn (TURN relay) ----------------------------------------------
echo "==> Configuring coturn…"
sudo tee /etc/turnserver.conf >/dev/null <<EOF
listening-port=3478
fingerprint
lt-cred-mech
user=updesk:$TURN_SECRET
realm=$DOMAIN
min-port=49152
max-port=65535
external-ip=$PUBLIC_IP
no-cli
EOF
# enable the service form of coturn
echo 'TURNSERVER_ENABLED=1' | sudo tee /etc/default/coturn >/dev/null

# ---- 6. start everything -------------------------------------------------
echo "==> Starting services…"
sudo systemctl daemon-reload
sudo systemctl enable --now updesk-signaling
sudo systemctl enable --now caddy
sudo systemctl restart caddy
sudo systemctl enable --now coturn
sudo systemctl restart coturn

echo ""
echo "==================================================================="
echo " UpDesk server is up."
echo "   Signaling (wss):  wss://$DOMAIN"
echo "   TURN:             turn:$DOMAIN:3478   user=updesk  pass=$TURN_SECRET"
echo ""
echo " Next: point the apps at these (rtcConfig.js + server field), rebuild"
echo " the installers, and connect from anywhere."
echo "==================================================================="
