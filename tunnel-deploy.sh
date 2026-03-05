#!/usr/bin/env bash
# Starts the digitaltwinapp-api Cloudflare Tunnel → localhost:8081.
# Usage: ./tunnel-deploy.sh
# Prerequisite: Java API must already be running on port 8081.
#
# First-time setup — store your tunnel token (gitignored):
#   echo "YOUR_TOKEN" > .tunnel-token

set -euo pipefail

TOKEN_FILE="$(dirname "$0")/.tunnel-token"

if [ ! -f "$TOKEN_FILE" ]; then
  echo "ERROR: .tunnel-token not found."
  echo "Run: echo 'YOUR_TOKEN' > .tunnel-token"
  exit 1
fi

TOKEN=$(cat "$TOKEN_FILE")

echo "Starting digitaltwinapp-api tunnel → localhost:8081 ..."
cloudflared tunnel run --token "$TOKEN" --url http://localhost:8081
