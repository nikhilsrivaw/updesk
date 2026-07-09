#!/usr/bin/env bash
# Prints the SPKI pin for a cert (default: the dev CA).
# Paste the value into each app's tauri.conf.json:
#   "additionalBrowserArgs": "... --ignore-certificate-errors-spki-list=<PIN>"
# This pins the webview to accept ONLY certs chaining to this key — secure
# cert pinning for a self-signed setup (a MITM with a different cert is rejected).
set -euo pipefail
CERT="${1:-certs/ca.pem}"
openssl x509 -in "$CERT" -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl base64
