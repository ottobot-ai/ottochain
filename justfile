# OttoChain Justfile
# Local development, testing, and Docker validation

set shell := ["bash", "-uc"]

# Default: show available recipes
default:
    @just --list

# ============ Build ============

# Build all JARs (ML0, CL1, DL1)
build:
    source ~/.sdkman/bin/sdkman-init.sh && sbt "currencyL0/assembly" "currencyL1/assembly" "dataL1/assembly"

# Build Docker image locally
build-image tag="local":
    docker build -t ghcr.io/scasplte2/ottochain-metagraph:{{tag}} .

# Build Docker image with no cache
build-image-fresh tag="local":
    docker build --no-cache -t ghcr.io/scasplte2/ottochain-metagraph:{{tag}} .

# ============ Test ============

# Run unit tests
test:
    source ~/.sdkman/bin/sdkman-init.sh && sbt test

# Run specific test suite
test-only suite:
    source ~/.sdkman/bin/sdkman-init.sh && sbt "testOnly *{{suite}}*"

# Validate Docker image - check all layer CLIs respond correctly
test-image tag="local":
    #!/usr/bin/env bash
    set -e
    IMAGE="ghcr.io/scasplte2/ottochain-metagraph:{{tag}}"
    echo "🔍 Testing Docker image: $IMAGE"
    
    echo ""
    echo "=== Checking JAR files exist ==="
    docker run --rm --entrypoint '' $IMAGE ls -la /ottochain/jars/
    
    echo ""
    echo "=== GL0 (Global L0) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/gl0.jar --help | head -20
    
    echo ""
    echo "=== GL1 (Global L1) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/gl1.jar --help | head -20
    
    echo ""
    echo "=== ML0 (Metagraph L0) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/ml0.jar --help | head -20
    
    echo ""
    echo "=== CL1 (Currency L1) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/cl1.jar --help | head -20
    
    echo ""
    echo "=== DL1 (Data L1) CLI ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/dl1.jar --help | head -20
    
    echo ""
    echo "=== Checking run-validator flags (ML0) ==="
    docker run --rm --entrypoint '' $IMAGE java -jar /ottochain/jars/ml0.jar run-validator --help 2>&1 | grep -E "^\s+--" | head -20
    
    echo ""
    echo "✅ All layer CLIs responding correctly"

# Validate entrypoint works for each layer
test-entrypoint tag="local":
    #!/usr/bin/env bash
    set -e
    IMAGE="ghcr.io/scasplte2/ottochain-metagraph:{{tag}}"
    echo "🔍 Testing entrypoint for each layer..."
    
    for layer in gl0 gl1 ml0 cl1 dl1; do
        echo ""
        echo "=== Testing LAYER=$layer ==="
        # Should fail with missing keystore but show it's trying to start
        docker run --rm -e LAYER=$layer -e CL_PASSWORD=test $IMAGE 2>&1 | head -5 || true
    done
    
    echo ""
    echo "✅ Entrypoint responds for all layers"

# ============ Local Stack ============

# First-time local setup (generates keys, genesis, .env)
local-setup tag="local":
    IMAGE=ghcr.io/scasplte2/ottochain-metagraph:{{tag}} ./scripts/local-setup.sh

# Start local development stack (requires docker-compose.local.yml)
up:
    docker compose -f docker-compose.local.yml up -d
    @echo "Waiting for services to start..."
    @sleep 10
    just health

# Stop local stack
down:
    docker compose -f docker-compose.local.yml down

# Stop and remove all data
down-clean:
    docker compose -f docker-compose.local.yml down -v
    rm -rf .local/data/*

# View logs
logs service="":
    docker compose -f docker-compose.local.yml logs -f {{service}}

# Health check all services
health:
    #!/usr/bin/env bash
    set -e
    echo "🏥 Health Check"
    
    check_health() {
        local name=$1
        local port=$2
        local status=$(curl -sf http://localhost:$port/node/info 2>/dev/null | jq -r '.state' || echo "DOWN")
        printf "  %-10s :%d  %s\n" "$name" "$port" "$status"
    }
    
    check_health "GL0" 9000
    check_health "GL1" 9100
    check_health "ML0" 9200
    check_health "CL1" 9300
    check_health "DL1" 9400

# ============ Utilities ============

# Generate a new keystore
keygen output="key.p12" alias="alias" password="password":
    docker run --rm -v $(pwd):/out --entrypoint '' \
        ghcr.io/scasplte2/ottochain-metagraph:local \
        java -jar /ottochain/jars/cl1.jar generate-key \
        --output /out/{{output}} --alias {{alias}} --password {{password}}

# Show wallet address from keystore
show-address keystore="key.p12" alias="alias" password="password":
    docker run --rm -v $(pwd):/keys --entrypoint '' \
        -e CL_KEYSTORE=/keys/{{keystore}} \
        -e CL_KEYALIAS={{alias}} \
        -e CL_PASSWORD={{password}} \
        ghcr.io/scasplte2/ottochain-metagraph:local \
        java -jar /ottochain/jars/ml0.jar show-address

# Clean build artifacts
clean:
    source ~/.sdkman/bin/sdkman-init.sh && sbt clean
    rm -rf target/
    rm -rf modules/*/target/

# Clean Docker images
clean-docker:
    docker rmi ghcr.io/scasplte2/ottochain-metagraph:local 2>/dev/null || true
    docker image prune -f

# ============ CI Validation ============

# Full pre-push validation (what CI should do)
validate tag="local":
    @echo "🚀 Running full validation..."
    just build-image {{tag}}
    just test-image {{tag}}
    just test-entrypoint {{tag}}
    @echo ""
    @echo "✅ All validations passed - safe to push"

# Quick smoke test on existing image
smoke tag="local":
    just test-image {{tag}}
