#!/usr/bin/env bash
# transfer-data-pack.sh
# Packs everything NOT in git into a single zip for transfer to another machine:
#   - Full PostgreSQL dump of banking_system (digitaltwinapp + minicore schemas)
#   - API and backoffice credential files (application-local.yml)
#   - Cloudflare tunnel token and credentials
#
# Usage: ./transfer-data-pack.sh
# Output: digitaltwin-transfer-<timestamp>.zip (in the project root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
ZIP_NAME="digitaltwin-transfer-${TIMESTAMP}.zip"
STAGE=$(mktemp -d)

trap 'rm -rf "$STAGE"' EXIT

echo "=== Digital Twin App — Data Pack ==="
echo ""

# ---------------------------------------------------------------------------
# 1. PostgreSQL dump
# ---------------------------------------------------------------------------
echo "→ Dumping banking_system database..."
docker exec global_banking_db pg_dump \
    --clean --if-exists \
    -U admin banking_system \
    > "$STAGE/banking_system.sql"
echo "  Done ($(du -sh "$STAGE/banking_system.sql" | cut -f1))"

# ---------------------------------------------------------------------------
# 2. Credential files
# ---------------------------------------------------------------------------
echo ""
echo "→ Packing credentials..."

pack_file() {
    local src="$1"
    local rel_dst="$2"
    if [ -f "$src" ]; then
        mkdir -p "$STAGE/$(dirname "$rel_dst")"
        cp "$src" "$STAGE/$rel_dst"
        echo "  + $rel_dst"
    else
        echo "  - $rel_dst  (not found, skipping)"
    fi
}

# API credentials
pack_file "$SCRIPT_DIR/api/src/main/resources/application-local.yml" \
          "credentials/api/src/main/resources/application-local.yml"

# Backoffice credentials
pack_file "$SCRIPT_DIR/backoffice/src/main/resources/application-local.yml" \
          "credentials/backoffice/src/main/resources/application-local.yml"

# Cloudflare tunnel token (app-specific — cert.pem and tunnel *.json are shared
# across all apps on this machine and are not included)
pack_file "$SCRIPT_DIR/.tunnel-token" \
          "credentials/.tunnel-token"

# ---------------------------------------------------------------------------
# 3. Write a manifest so the unpack script knows what's inside
# ---------------------------------------------------------------------------
cat > "$STAGE/MANIFEST.txt" << EOF
Digital Twin App — Transfer Package
Created: $(date -u '+%Y-%m-%d %H:%M:%S UTC')
Host:    $(hostname)

Contents:
  banking_system.sql          — full pg_dump (--clean --if-exists)
  credentials/                — gitignored credential files
    api/.../application-local.yml
    backoffice/.../application-local.yml
    .tunnel-token             — digitaltwin-app Cloudflare tunnel token

Unpack with:
  ./transfer-data-unpack.sh $ZIP_NAME
EOF

# ---------------------------------------------------------------------------
# 4. Create zip
# ---------------------------------------------------------------------------
echo ""
echo "→ Creating $ZIP_NAME..."
(cd "$STAGE" && zip -r "$SCRIPT_DIR/$ZIP_NAME" . --quiet)

echo ""
echo "✓ Done: $ZIP_NAME  ($(du -sh "$SCRIPT_DIR/$ZIP_NAME" | cut -f1))"
echo ""
echo "  ⚠  This zip contains credentials — transfer it securely (scp, encrypted drive, etc.)"
echo "  On the target machine run:  ./transfer-data-unpack.sh $ZIP_NAME"
