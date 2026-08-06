#!/usr/bin/env bash
# UpDesk — DuckDNS keep-alive
#
# Keeps updesk.duckdns.org pointed at this server's current public IP, and stops
# DuckDNS from expiring the free subdomain (it deactivates after ~30 days with no
# update). Run this on the EC2 box on a schedule.
#
# One-time setup on the server:
#   1) Get your token from https://www.duckdns.org (log in → it's shown at the top).
#   2) Save it:   echo "YOUR_DUCKDNS_TOKEN" | sudo tee /etc/updesk-duckdns.token
#      chmod 600 /etc/updesk-duckdns.token
#   3) Copy this script:  sudo install -m 755 duckdns-keepalive.sh /usr/local/bin/
#   4) Cron every 5 min:
#        ( sudo crontab -l 2>/dev/null; \
#          echo "*/5 * * * * /usr/local/bin/duckdns-keepalive.sh >>/var/log/updesk-duckdns.log 2>&1" \
#        ) | sudo crontab -
#
# Verify:   tail -f /var/log/updesk-duckdns.log   (should print "OK")

set -euo pipefail

DOMAIN="updesk"                       # the subdomain part of updesk.duckdns.org
TOKEN_FILE="/etc/updesk-duckdns.token"

if [[ ! -r "$TOKEN_FILE" ]]; then
  echo "$(date -u +%FT%TZ) ERROR: token file $TOKEN_FILE missing/unreadable" >&2
  exit 1
fi
TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"

# Blank ip= lets DuckDNS auto-detect the caller's public IP.
RESP="$(curl -fsS "https://www.duckdns.org/update?domains=${DOMAIN}&token=${TOKEN}&ip=")"

echo "$(date -u +%FT%TZ) duckdns: ${RESP}"
[[ "$RESP" == "OK" ]] || { echo "$(date -u +%FT%TZ) ERROR: DuckDNS returned '${RESP}'" >&2; exit 1; }
