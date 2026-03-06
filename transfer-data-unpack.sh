#!/usr/bin/env bash
# transfer-data-unpack.sh
# Unpacks a zip created by transfer-data-pack.sh:
#   - Restores the PostgreSQL dump into banking_system
#   - Copies credential files to their correct locations
#
# Usage: ./transfer-data-unpack.sh <digitaltwin-transfer-TIMESTAMP.zip>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <digitaltwin-transfer-TIMESTAMP.zip>"
    exit 1
fi

ZIP_FILE="$1"

# Resolve to absolute path (zip may be passed relative to cwd)
if [[ "$ZIP_FILE" != /* ]]; then
    ZIP_FILE="$(pwd)/$ZIP_FILE"
fi

if [ ! -f "$ZIP_FILE" ]; then
    echo "ERROR: File not found: $ZIP_FILE"
    exit 1
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

echo "=== Digital Twin App — Data Unpack ==="
echo ""
echo "Source: $ZIP_FILE"
echo ""

# ---------------------------------------------------------------------------
# 1. Extract zip
# ---------------------------------------------------------------------------
echo "→ Extracting..."
unzip -q "$ZIP_FILE" -d "$STAGE"
echo "  Done"

# Show manifest if present
if [ -f "$STAGE/MANIFEST.txt" ]; then
    echo ""
    cat "$STAGE/MANIFEST.txt"
    echo ""
fi

# ---------------------------------------------------------------------------
# 2. Restore PostgreSQL dump
# ---------------------------------------------------------------------------
echo "→ Restoring banking_system database..."
echo "  (This will drop and recreate all objects — existing data will be replaced)"
echo ""
read -r -p "  Proceed with database restore? [y/N] " confirm
if [[ "$confirm" != [yY] ]]; then
    echo "  Skipped."
else
    docker exec -i global_banking_db psql -U admin banking_system \
        < "$STAGE/banking_system.sql"
    echo "  Done"
fi

# ---------------------------------------------------------------------------
# 3. Copy credential files
# ---------------------------------------------------------------------------
echo ""
echo "→ Restoring credential files..."

unpack_file() {
    local rel_src="$1"   # path inside zip (under credentials/)
    local dst="$2"       # absolute destination path
    local src="$STAGE/credentials/$rel_src"
    if [ -f "$src" ]; then
        mkdir -p "$(dirname "$dst")"
        cp "$src" "$dst"
        echo "  + $dst"
    else
        echo "  - $rel_src  (not in archive, skipping)"
    fi
}

# API credentials
unpack_file "api/src/main/resources/application-local.yml" \
            "$SCRIPT_DIR/api/src/main/resources/application-local.yml"

# Backoffice credentials
unpack_file "backoffice/src/main/resources/application-local.yml" \
            "$SCRIPT_DIR/backoffice/src/main/resources/application-local.yml"

# Cloudflare tunnel token (app-specific — cert.pem and tunnel *.json are shared
# across all apps on this machine and are not included in the archive)
unpack_file ".tunnel-token" \
            "$SCRIPT_DIR/.tunnel-token"

# ---------------------------------------------------------------------------
# 4. Done
# ---------------------------------------------------------------------------
echo ""
echo "✓ Unpack complete."
echo ""
echo "  Next steps:"
echo "    1. Ensure the global_banking_db Docker container is running"
echo "    2. Start mini-core:  cd <minicore-dir> && python app.py"
echo "    3. Start the API:    cd api && mvn spring-boot:run -Dspring-boot.run.profiles=local"
echo "    4. Start the tunnel: ./tunnel-deploy.sh"
echo "    5. Start the UI:     npm run dev"
