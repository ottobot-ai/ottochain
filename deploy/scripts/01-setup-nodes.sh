#!/bin/bash

# 01-setup-nodes.sh
# Setup directory structure and upload keystores to all metagraph nodes
#
# This script:
# 1. Tests SSH connectivity to all nodes
# 2. Creates directory structure on each node
# 3. Uploads keystores to appropriate locations
# 4. Uploads genesis.csv to metagraph-l0 directory
# 5. Downloads Tessellation tools to each node
# 6. Verifies setup completion

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "01-setup-nodes.sh" \
    "./deploy/scripts/01-setup-nodes.sh" \
    "Setup directory structure and keystores on all metagraph nodes"
  exit 0
fi

print_title "Ottochain Metagraph - Node Setup"

# Validate configuration
print_status "Validating configuration..."
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi
print_success "Configuration validated"

# Check required commands
require_cmd ssh
require_cmd scp
require_cmd curl
require_cmd jq

# Validate SSH key
if ! check_ssh_key; then
  exit 1
fi

# Validate node IPs
if ! validate_node_ips; then
  exit 1
fi

print_status "Node configuration:"
for i in "${!NODE_IPS[@]}"; do
  print_status "  Node $((i+1)): ${NODE_IPS[$i]} (keystore: ${KEYSTORE_FILES[$i]})"
done

# Test SSH connectivity to all nodes
print_title "Testing SSH Connectivity"
CONNECTIVITY_SUCCESS=true
for i in "${!NODE_IPS[@]}"; do
  if ! test_ssh_connectivity "${NODE_IPS[$i]}" "$((i+1))"; then
    CONNECTIVITY_SUCCESS=false
  fi
done

if [[ "$CONNECTIVITY_SUCCESS" != "true" ]]; then
  print_error "SSH connectivity test failed for one or more nodes"
  exit 1
fi
print_success "All nodes are reachable via SSH"

# Download Tessellation tools locally first (will be uploaded to nodes later)
print_title "Downloading Tessellation Tools"
if ! download_tessellation_jars "$JARS_DIR"; then
  print_error "Failed to download Tessellation tools"
  exit 1
fi

# Setup each node
print_title "Setting Up Nodes"

setup_node() {
  local node_ip="$1"
  local node_num="$2"
  local keystore_file="$3"
  local keystore_alias="$4"

  print_status "Setting up Node $node_num ($node_ip)..."

  # Install jq if not present
  print_status "  Installing jq..."
  if ! ssh_exec "$node_ip" "which jq >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y jq >/dev/null 2>&1)"; then
    print_warning "Failed to install jq on Node $node_num (may already be installed)"
  fi

  # Create directory structure
  print_status "  Creating directory structure..."
  if ! ssh_exec "$node_ip" "mkdir -p $REMOTE_ML0_DIR $REMOTE_CL1_DIR $REMOTE_DL1_DIR $REMOTE_KEYS_DIR"; then
    print_error "Failed to create directories on Node $node_num"
    return 1
  fi

  # Upload keystore to keys directory
  print_status "  Uploading keystore ($keystore_file)..."
  if ! scp_to_node "$KEYS_DIR/$keystore_file" "$node_ip" "$REMOTE_KEYS_DIR/metagraph.p12"; then
    print_error "Failed to upload keystore to Node $node_num"
    return 1
  fi

  # Copy keystore to all module directories
  print_status "  Copying keystore to module directories..."
  if ! ssh_exec "$node_ip" "cp $REMOTE_KEYS_DIR/metagraph.p12 $REMOTE_ML0_DIR/ && \
                              cp $REMOTE_KEYS_DIR/metagraph.p12 $REMOTE_CL1_DIR/ && \
                              cp $REMOTE_KEYS_DIR/metagraph.p12 $REMOTE_DL1_DIR/"; then
    print_error "Failed to copy keystore to module directories on Node $node_num"
    return 1
  fi

  # Upload genesis.csv to metagraph-l0 directory
  print_status "  Uploading genesis.csv..."
  if ! scp_to_node "$GENESIS_DIR/genesis.csv" "$node_ip" "$REMOTE_ML0_DIR/genesis.csv"; then
    print_error "Failed to upload genesis.csv to Node $node_num"
    return 1
  fi

  # Upload Tessellation tools
  print_status "  Uploading Tessellation tools..."
  for jar in "${TESSELLATION_JARS[@]}"; do
    if ! scp_to_node "$JARS_DIR/$jar" "$node_ip" "$REMOTE_ML0_DIR/$jar"; then
      print_error "Failed to upload $jar to Node $node_num"
      return 1
    fi
  done

  # Verify setup
  print_status "  Verifying setup..."
  local verification_passed=true

  # Check keystore exists in all directories
  for dir in "$REMOTE_ML0_DIR" "$REMOTE_CL1_DIR" "$REMOTE_DL1_DIR"; do
    if ! ssh_exec "$node_ip" "test -f $dir/metagraph.p12" >/dev/null 2>&1; then
      print_error "Keystore not found in $dir on Node $node_num"
      verification_passed=false
    fi
  done

  # Check genesis.csv exists
  if ! ssh_exec "$node_ip" "test -f $REMOTE_ML0_DIR/genesis.csv" >/dev/null 2>&1; then
    print_error "genesis.csv not found on Node $node_num"
    verification_passed=false
  fi

  # Check Tessellation tools exist
  for jar in "${TESSELLATION_JARS[@]}"; do
    if ! ssh_exec "$node_ip" "test -f $REMOTE_ML0_DIR/$jar" >/dev/null 2>&1; then
      print_error "$jar not found on Node $node_num"
      verification_passed=false
    fi
  done

  if [[ "$verification_passed" != "true" ]]; then
    print_error "Verification failed on Node $node_num"
    return 1
  fi

  print_success "Node $node_num setup completed successfully"
  return 0
}

# Setup all nodes
SETUP_SUCCESS=true
for i in "${!NODE_IPS[@]}"; do
  if ! setup_node "${NODE_IPS[$i]}" "$((i+1))" "${KEYSTORE_FILES[$i]}" "${KEYSTORE_ALIAS[$i]}"; then
    SETUP_SUCCESS=false
    print_error "Setup failed on Node $((i+1))"
  fi
  echo ""
done

if [[ "$SETUP_SUCCESS" != "true" ]]; then
  print_error "Node setup failed for one or more nodes"
  exit 1
fi

print_title "Node Setup Complete"
print_success "All nodes have been set up successfully!"
print_status "Directory structure created:"
print_status "  - $REMOTE_ML0_DIR/ (metagraph L0)"
print_status "  - $REMOTE_CL1_DIR/ (currency L1)"
print_status "  - $REMOTE_DL1_DIR/ (data L1)"
print_status "  - $REMOTE_KEYS_DIR/ (keystores)"
print_status ""
print_status "Files uploaded to each node:"
print_status "  - metagraph.p12 (keystore in all module dirs)"
print_status "  - genesis.csv (in metagraph-l0)"
print_status "  - cl-keytool.jar (in metagraph-l0)"
print_status "  - cl-wallet.jar (in metagraph-l0)"
print_status ""
print_status "Next steps:"
print_status "  1. Build JARs:            ./deploy/scripts/02-build-jars.sh"
print_status "  2. Deploy JARs:           ./deploy/scripts/03-deploy-jars.sh"
print_status "  3. Create genesis:        ./deploy/scripts/04-create-genesis.sh"
print_status "  4. Create owner message:  ./deploy/scripts/05-create-owner-message.sh"
print_status "  5. Start nodes:           ./deploy/scripts/06-start-nodes.sh"
