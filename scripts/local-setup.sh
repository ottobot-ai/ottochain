#!/bin/bash
set -e

# OttoChain Local Development Setup
# Creates keys and genesis for local testing

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
LOCAL_DIR="$ROOT_DIR/.local"

IMAGE="${IMAGE:-ghcr.io/scasplte2/ottochain-metagraph:local}"
PASSWORD="${CL_PASSWORD:-password}"

echo "🔧 OttoChain Local Setup"
echo "========================"
echo "Image: $IMAGE"
echo "Local dir: $LOCAL_DIR"
echo ""

# Create directories
mkdir -p "$LOCAL_DIR/keys" "$LOCAL_DIR/data" "$LOCAL_DIR/genesis"

# Check if image exists
if ! docker image inspect "$IMAGE" &>/dev/null; then
    echo "❌ Docker image not found: $IMAGE"
    echo "   Run: just build-image"
    exit 1
fi

# Generate keystore if not exists
if [ ! -f "$LOCAL_DIR/keys/key.p12" ]; then
    echo "🔑 Generating keystore..."
    docker run --rm \
        -v "$LOCAL_DIR/keys:/out" \
        --entrypoint '' \
        "$IMAGE" \
        java -jar /ottochain/jars/cl1.jar generate-key \
        --output /out/key.p12 --alias alias --password "$PASSWORD"
    echo "   Created: $LOCAL_DIR/keys/key.p12"
else
    echo "🔑 Keystore exists: $LOCAL_DIR/keys/key.p12"
fi

# Get wallet address
echo ""
echo "📍 Getting wallet address..."
WALLET=$(docker run --rm \
    -v "$LOCAL_DIR/keys:/keys:ro" \
    --entrypoint '' \
    -e CL_KEYSTORE=/keys/key.p12 \
    -e CL_KEYALIAS=alias \
    -e CL_PASSWORD="$PASSWORD" \
    "$IMAGE" \
    java -jar /ottochain/jars/ml0.jar show-address 2>&1 | grep -oP 'DAG[a-zA-Z0-9]+' | head -1)

if [ -z "$WALLET" ]; then
    echo "❌ Failed to get wallet address"
    exit 1
fi
echo "   Wallet: $WALLET"

# Get peer ID for GL0
echo ""
echo "🆔 Getting peer ID..."
PEER_ID=$(docker run --rm \
    -v "$LOCAL_DIR/keys:/keys:ro" \
    --entrypoint '' \
    -e CL_KEYSTORE=/keys/key.p12 \
    -e CL_KEYALIAS=alias \
    -e CL_PASSWORD="$PASSWORD" \
    "$IMAGE" \
    java -jar /ottochain/jars/gl0.jar show-id 2>&1 | grep -oP '[a-f0-9]{128}' | head -1)

if [ -z "$PEER_ID" ]; then
    echo "❌ Failed to get peer ID"
    exit 1
fi
echo "   Peer ID: ${PEER_ID:0:16}..."

# Create genesis.csv
echo ""
echo "📄 Creating genesis.csv..."
echo "${WALLET},1000000000000000" > "$LOCAL_DIR/genesis/genesis.csv"
echo "   Created: $LOCAL_DIR/genesis/genesis.csv"

# Create genesis snapshot
echo ""
echo "📦 Creating genesis snapshot..."
docker run --rm \
    -v "$LOCAL_DIR/keys:/keys:ro" \
    -v "$LOCAL_DIR/genesis:/genesis" \
    --entrypoint '' \
    -e CL_KEYSTORE=/keys/key.p12 \
    -e CL_KEYALIAS=alias \
    -e CL_PASSWORD="$PASSWORD" \
    "$IMAGE" \
    java -jar /ottochain/jars/ml0.jar create-genesis /genesis/genesis.csv --output /genesis

echo "   Created: $LOCAL_DIR/genesis/genesis.snapshot"

# Create .env file for docker-compose
echo ""
echo "📝 Creating .env file..."
cat > "$ROOT_DIR/.env" <<EOF
CL_PASSWORD=$PASSWORD
GL0_PEER_ID=$PEER_ID
ML0_PEER_ID=$PEER_ID
EOF
echo "   Created: $ROOT_DIR/.env"

echo ""
echo "✅ Local setup complete!"
echo ""
echo "Next steps:"
echo "  1. Start the stack:  just up"
echo "  2. Check health:     just health"
echo "  3. View logs:        just logs"
echo "  4. Stop:             just down"
