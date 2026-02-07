#!/bin/bash
# Ottochain Metagraph Deployment Configuration
# This file contains all configuration for deploying the ottochain metagraph to Digital Ocean

#######################
# Node Configuration
#######################

# Digital Ocean droplet IPs (from euclid hosts.ansible.yml)
NODE_IPS=(
  "146.190.151.138"
  "147.182.254.23"
  "144.126.217.197"
)

# SSH Configuration (from euclid hosts.ansible.yml)
SSH_USER="root"
SSH_KEY_PATH="$HOME/.ssh/do-ottochain-intnet"
SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10"

#######################
# Keystore Configuration
#######################

# Keystore files (from euclid configuration)
KEYSTORE_FILES=("kd5ujc-01.p12" "kd5ujc-02.p12" "kd5ujc-03.p12")
KEYSTORE_ALIAS=("kd5ujc-01" "kd5ujc-02" "kd5ujc-03")
KEYSTORE_PASSWORD=(
  "Refusing-Avenge8-Repose-Garnish"
  "Italicize6-Drippy-Stool-Trash"
  "Huntress-Gossip6-Deflected-Jitters"
)

# Owner wallet (using first keystore as owner)
OWNER_KEYSTORE_FILE="kd5ujc-03.p12"
OWNER_KEYSTORE_ALIAS="kd5ujc-03"
OWNER_KEYSTORE_PASSWORD="Huntress-Gossip6-Deflected-Jitters"

#######################
# Hypergraph Network Configuration
#######################

# Integrationnet Global L0 peer (from euclid.json)
GL0_PEER_IP="52.9.216.57"
GL0_PEER_ID="46daea11ca239cb8c0c8cdeb27db9dbe9c03744908a8a389a60d14df2ddde409260a93334d74957331eec1af323f458b12b3a6c3b8e05885608aae7e3a77eac7"
GL0_PEER_PORT="9000"

# Constellation Network environment
CL_APP_ENV="integrationnet"
CL_COLLATERAL="0"

#######################
# Port Configuration
#######################

# Metagraph L0 Ports
ML0_PUBLIC_PORT=9100
ML0_P2P_PORT=9101
ML0_CLI_PORT=9102

# Currency L1 Ports
CL1_PUBLIC_PORT=9200
CL1_P2P_PORT=9201
CL1_CLI_PORT=9202

# Data L1 Ports
DL1_PUBLIC_PORT=9300
DL1_P2P_PORT=9301
DL1_CLI_PORT=9302

#######################
# Module Configuration
#######################

# Module mapping: sbt_module:remote_directory:jar_pattern:target_jar_name
MODULES=(
  "currencyL0:metagraph-l0:ottochain-currency-l0-assembly:metagraph-l0.jar"
  "currencyL1:currency-l1:ottochain-currency-l1-assembly:currency-l1.jar"
  "dataL1:data-l1:ottochain-data-l1-assembly:data-l1.jar"
)

#######################
# Path Configuration
#######################

# Project paths (auto-detected)
CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd "$CONFIG_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$DEPLOY_ROOT/.." && pwd)"

# Deployment paths
GENESIS_DIR="$DEPLOY_ROOT/genesis"
KEYS_DIR="$DEPLOY_ROOT/keys"
JARS_DIR="$DEPLOY_ROOT/jars"
SCRIPTS_DIR="$DEPLOY_ROOT/scripts"

# Remote paths on nodes
REMOTE_CODE_DIR="/home/root/code"
REMOTE_ML0_DIR="$REMOTE_CODE_DIR/metagraph-l0"
REMOTE_CL1_DIR="$REMOTE_CODE_DIR/currency-l1"
REMOTE_DL1_DIR="$REMOTE_CODE_DIR/data-l1"
REMOTE_KEYS_DIR="$REMOTE_CODE_DIR/keys"

#######################
# Tessellation Configuration
#######################

# Tessellation version (matches euclid.json)
TESSELLATION_VERSION="3.5.0-rc.8"

# Tessellation JARs to download
TESSELLATION_JARS=("cl-keytool.jar" "cl-wallet.jar")

# GitHub release URL
TESSELLATION_RELEASES_URL="https://api.github.com/repos/Constellation-Labs/tessellation/releases"

#######################
# JVM Configuration
#######################

# JVM memory settings (adjust based on node RAM and load)
# For 4GB nodes with light load: 512m/512m (absolute minimum - testnet only)
# For 4GB nodes with medium load: 800m/800m (not recommended)
# For 8GB nodes: 2g/2g (minimum for production)
# For 16GB nodes: 6g/6g (recommended for production)

# ULTRA-MINIMAL SETTINGS for 4GB nodes (3 processes)
# CRITICAL: 4GB is insufficient for production - this is testnet-only
#
# Memory breakdown per process:
#   Heap: 320MB
#   Metaspace: 160MB max (reduced from 256MB)
#   Direct memory: 64MB (reduced from 128MB)
#   Code cache: 48MB (reduced from 64MB)
#   Thread stacks: ~30MB (assuming 150 threads × 200KB)
#   Native memory: ~50MB (JVM overhead, GC, etc)
#   Total per process: ~650MB
#
# With 3 processes (ML0, CL1, DL1):
#   Total JVM memory: 1.95GB
#   OS + buffers: 1.5GB
#   Total: 3.45GB (leaves 550MB buffer)
#
# Trade-offs:
#   - More frequent GC pauses (higher CPU usage)
#   - May OOM under heavy load
#   - Reduced performance
#   - NOT suitable for production
JVM_MIN_HEAP="512m"
JVM_MAX_HEAP="512m"

JVM_OPTS="-Xms${JVM_MIN_HEAP} -Xmx${JVM_MAX_HEAP} \
  -XX:MetaspaceSize=96m \
  -XX:MaxMetaspaceSize=160m \
  -XX:MaxDirectMemorySize=64m \
  -XX:ReservedCodeCacheSize=48m \
  -XX:ThreadStackSize=256 \
  -XX:+UseSerialGC \
  -XX:MaxGCPauseMillis=200 \
  -XX:GCTimeRatio=4 \
  -XX:MinHeapFreeRatio=20 \
  -XX:MaxHeapFreeRatio=40 \
  -XX:+UseStringDeduplication \
  -XX:StringTableSize=5000 \
  -XX:+OptimizeStringConcat \
  -XX:+UseCompressedOops \
  -XX:+UseCompressedClassPointers \
  -XX:+AlwaysPreTouch \
  -XX:+ExitOnOutOfMemoryError \
  -XX:MaxRAM=1400m"

#######################
# Health Check Configuration
#######################

# Health check settings
HEALTH_CHECK_MAX_ATTEMPTS=120
HEALTH_CHECK_DELAY=2
HEALTH_CHECK_TIMEOUT=240

#######################
# Validation
#######################

validate_config() {
  local errors=0

  # Check if node IPs are set
  for ip in "${NODE_IPS[@]}"; do
    if [[ "$ip" == "YOUR_NODE_"*"_IP" ]]; then
      echo "[ERROR] Node IPs not configured. Please update NODE_IPS in deploy-config.sh"
      ((errors++))
      break
    fi
  done

  # Check if SSH key exists
  if [[ ! -f "$SSH_KEY_PATH" ]]; then
    echo "[WARNING] SSH key not found at $SSH_KEY_PATH"
    echo "[WARNING] Please update SSH_KEY_PATH in deploy-config.sh"
  fi

  # Check if keystore files exist
  for keyfile in "${KEYSTORE_FILES[@]}"; do
    if [[ ! -f "$KEYS_DIR/$keyfile" ]]; then
      echo "[ERROR] Keystore file not found: $KEYS_DIR/$keyfile"
      ((errors++))
    fi
  done

  if [[ $errors -gt 0 ]]; then
    echo "[ERROR] Configuration validation failed with $errors error(s)"
    return 1
  fi

  return 0
}

# Export all configuration variables
export NODE_IPS SSH_USER SSH_KEY_PATH SSH_OPTS
export KEYSTORE_FILES KEYSTORE_ALIAS KEYSTORE_PASSWORD
export OWNER_KEYSTORE_FILE OWNER_KEYSTORE_ALIAS OWNER_KEYSTORE_PASSWORD
export GL0_PEER_IP GL0_PEER_ID GL0_PEER_PORT
export CL_APP_ENV CL_COLLATERAL
export ML0_PUBLIC_PORT ML0_P2P_PORT ML0_CLI_PORT
export CL1_PUBLIC_PORT CL1_P2P_PORT CL1_CLI_PORT
export DL1_PUBLIC_PORT DL1_P2P_PORT DL1_CLI_PORT
export MODULES
export CONFIG_DIR DEPLOY_ROOT PROJECT_ROOT
export GENESIS_DIR KEYS_DIR JARS_DIR SCRIPTS_DIR
export REMOTE_CODE_DIR REMOTE_ML0_DIR REMOTE_CL1_DIR REMOTE_DL1_DIR REMOTE_KEYS_DIR
export TESSELLATION_VERSION TESSELLATION_JARS TESSELLATION_RELEASES_URL
export JVM_MIN_HEAP JVM_MAX_HEAP JVM_OPTS
export HEALTH_CHECK_MAX_ATTEMPTS HEALTH_CHECK_DELAY HEALTH_CHECK_TIMEOUT
