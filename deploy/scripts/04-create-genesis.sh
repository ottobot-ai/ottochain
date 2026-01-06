#!/bin/bash

# 04-create-genesis.sh
# Create genesis snapshot on the first node remotely
#
# This script:
# 1. Connects to the first node via SSH
# 2. Runs create-genesis command with appropriate environment variables
# 3. Verifies genesis.snapshot and genesis.address were created
# 4. Copies genesis files to other nodes
# 5. Reports genesis creation status

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "04-create-genesis.sh" \
    "./deploy/scripts/04-create-genesis.sh" \
    "Create genesis snapshot on the first metagraph node"
  exit 0
fi

print_title "Ottochain Metagraph - Create Genesis"

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Check required commands
require_cmd ssh
require_cmd scp

# Validate SSH key
if ! check_ssh_key; then
  exit 1
fi

# Get first node details
LEAD_NODE_IP="${NODE_IPS[0]}"
LEAD_NODE_KEYSTORE_FILE="${KEYSTORE_FILES[0]}"
LEAD_NODE_KEYSTORE_ALIAS="${KEYSTORE_ALIAS[0]}"
LEAD_NODE_KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD[0]}"

print_status "Lead node configuration:"
print_status "  IP: $LEAD_NODE_IP"
print_status "  Keystore: $LEAD_NODE_KEYSTORE_FILE"
print_status "  Alias: $LEAD_NODE_KEYSTORE_ALIAS"

# Test SSH connectivity to lead node
print_title "Testing Connectivity"
if ! test_ssh_connectivity "$LEAD_NODE_IP" "1"; then
  print_error "Cannot connect to lead node"
  exit 1
fi

# Create genesis on lead node
print_title "Creating Genesis on Lead Node"

print_status "Running create-genesis command..."
print_status "  Environment: dev (required for genesis creation)"
print_status "  Collateral: $CL_COLLATERAL"
print_status "  Ports: public=$ML0_PUBLIC_PORT, p2p=$ML0_P2P_PORT, cli=$ML0_CLI_PORT"

# Build the create-genesis command (CL_APP_ENV=dev for genesis creation)
# Note: GL0 peer params are required by the command but not actually used during genesis creation
CREATE_GENESIS_CMD="cd $REMOTE_ML0_DIR && \
  CL_PUBLIC_HTTP_PORT=$ML0_PUBLIC_PORT \
  CL_P2P_HTTP_PORT=$ML0_P2P_PORT \
  CL_CLI_HTTP_PORT=$ML0_CLI_PORT \
  CL_APP_ENV=dev \
  CL_COLLATERAL=$CL_COLLATERAL \
  CL_KEYSTORE=metagraph.p12 \
  CL_KEYALIAS=$LEAD_NODE_KEYSTORE_ALIAS \
  CL_PASSWORD='$LEAD_NODE_KEYSTORE_PASSWORD' \
  CL_GLOBAL_L0_PEER_HTTP_HOST=$GL0_PEER_IP \
  CL_GLOBAL_L0_PEER_HTTP_PORT=$GL0_PEER_PORT \
  CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  java -jar metagraph-l0.jar create-genesis genesis.csv"

# Execute create-genesis command
print_status "Executing: java -jar metagraph-l0.jar create-genesis genesis.csv"

if ! ssh_exec "$LEAD_NODE_IP" "$CREATE_GENESIS_CMD"; then
  print_error "Failed to create genesis on lead node"
  exit 1
fi

print_success "Genesis creation command completed"

# Verify genesis files were created
print_title "Verifying Genesis Files"

print_status "Checking for genesis.snapshot..."
if ! ssh_exec "$LEAD_NODE_IP" "test -f $REMOTE_ML0_DIR/genesis.snapshot" >/dev/null 2>&1; then
  print_error "genesis.snapshot not found on lead node"
  exit 1
fi
print_success "  genesis.snapshot found"

print_status "Checking for genesis.address..."
if ! ssh_exec "$LEAD_NODE_IP" "test -f $REMOTE_ML0_DIR/genesis.address" >/dev/null 2>&1; then
  print_error "genesis.address not found on lead node"
  exit 1
fi
print_success "  genesis.address found"

# Get genesis info
print_status "Retrieving genesis information..."
METAGRAPH_ID=$(ssh_exec "$LEAD_NODE_IP" "cat $REMOTE_ML0_DIR/genesis.address" 2>/dev/null | tr -d '\r\n')

if [[ -z "$METAGRAPH_ID" ]]; then
  print_error "Failed to retrieve metagraph ID from genesis.address"
  exit 1
fi

print_success "Metagraph ID: $METAGRAPH_ID"

# Get file sizes
SNAPSHOT_SIZE=$(ssh_exec "$LEAD_NODE_IP" "du -h $REMOTE_ML0_DIR/genesis.snapshot | cut -f1" 2>/dev/null)
print_status "Genesis snapshot size: $SNAPSHOT_SIZE"

# Copy genesis files to other nodes
if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  print_title "Copying Genesis Files to Other Nodes"

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))

    print_status "Copying to Node $node_num ($node_ip)..."

    # Copy genesis.snapshot
    print_status "  Copying genesis.snapshot..."
    if ! ssh_exec "$LEAD_NODE_IP" "cat $REMOTE_ML0_DIR/genesis.snapshot" | \
         ssh_exec "$node_ip" "cat > $REMOTE_ML0_DIR/genesis.snapshot"; then
      print_error "Failed to copy genesis.snapshot to Node $node_num"
      exit 1
    fi

    # Copy genesis.address
    print_status "  Copying genesis.address..."
    if ! ssh_exec "$LEAD_NODE_IP" "cat $REMOTE_ML0_DIR/genesis.address" | \
         ssh_exec "$node_ip" "cat > $REMOTE_ML0_DIR/genesis.address"; then
      print_error "Failed to copy genesis.address to Node $node_num"
      exit 1
    fi

    # Verify files exist on target node
    if ! ssh_exec "$node_ip" "test -f $REMOTE_ML0_DIR/genesis.snapshot && test -f $REMOTE_ML0_DIR/genesis.address" >/dev/null 2>&1; then
      print_error "Genesis files verification failed on Node $node_num"
      exit 1
    fi

    print_success "  Genesis files copied to Node $node_num"
  done

  print_success "Genesis files copied to all nodes"
fi

# Save metagraph ID locally for use by other scripts
print_title "Saving Metagraph ID Locally"

METAGRAPH_ID_FILE="$DEPLOY_ROOT/metagraph-id.txt"
echo "$METAGRAPH_ID" > "$METAGRAPH_ID_FILE"
print_success "Metagraph ID saved to: $METAGRAPH_ID_FILE"

# Summary
print_title "Genesis Creation Complete"

print_success "Genesis created successfully!"
print_status ""
print_status "Genesis information:"
print_status "  Metagraph ID: $METAGRAPH_ID"
print_status "  Snapshot size: $SNAPSHOT_SIZE"
print_status "  Created on: Node 1 ($LEAD_NODE_IP)"
print_status "  Copied to: ${#NODE_IPS[@]} node(s)"
print_status ""
print_status "Genesis files locations:"
for i in "${!NODE_IPS[@]}"; do
  print_status "  Node $((i+1)) (${NODE_IPS[$i]}): $REMOTE_ML0_DIR/genesis.{snapshot,address}"
done
print_status ""
print_status "Next steps:"
print_status "  Create owner message: ./deploy/scripts/05-create-owner-message.sh"
