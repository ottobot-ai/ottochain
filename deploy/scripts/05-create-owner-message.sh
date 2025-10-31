#!/bin/bash

# 05-create-owner-message.sh
# Create multi-signed owner message for genesis locally
#
# This script:
# 1. Downloads Tessellation tools if not present
# 2. Gets owner address from first keystore
# 3. Retrieves metagraph ID from node or local file
# 4. Creates owner signing messages with all 3 keystores
# 5. Combines signatures into single owner message
# 6. Uploads owner message to first node

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "05-create-owner-message.sh" \
    "./deploy/scripts/05-create-owner-message.sh" \
    "Create multi-signed owner message for genesis using all keystores"
  exit 0
fi

print_title "Workchain Metagraph - Create Owner Message"

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Check required commands
require_cmd java
require_cmd jq
require_cmd ssh

# Ensure Tessellation tools are downloaded
print_title "Checking Tessellation Tools"

if [[ ! -f "$JARS_DIR/cl-wallet.jar" ]]; then
  print_status "cl-wallet.jar not found, downloading..."
  if ! download_tessellation_jars "$JARS_DIR"; then
    print_error "Failed to download Tessellation tools"
    exit 1
  fi
else
  print_success "Tessellation tools found"
fi

# Get metagraph ID
print_title "Retrieving Metagraph ID"

METAGRAPH_ID_FILE="$DEPLOY_ROOT/metagraph-id.txt"
METAGRAPH_ID=""

# Try to read from local file first
if [[ -f "$METAGRAPH_ID_FILE" ]]; then
  METAGRAPH_ID=$(cat "$METAGRAPH_ID_FILE" | tr -d '\r\n')
  print_status "Read metagraph ID from local file"
fi

# If not found locally, try to get from first node
if [[ -z "$METAGRAPH_ID" ]]; then
  print_status "Retrieving metagraph ID from Node 1..."
  LEAD_NODE_IP="${NODE_IPS[0]}"

  METAGRAPH_ID=$(ssh_exec "$LEAD_NODE_IP" "cat $REMOTE_ML0_DIR/genesis.address 2>/dev/null" | tr -d '\r\n')

  if [[ -z "$METAGRAPH_ID" ]]; then
    print_error "Failed to retrieve metagraph ID"
    print_status "Make sure genesis has been created first:"
    print_status "  ./deploy/scripts/04-create-genesis.sh"
    exit 1
  fi

  # Save locally for future use
  echo "$METAGRAPH_ID" > "$METAGRAPH_ID_FILE"
  print_status "Saved metagraph ID to $METAGRAPH_ID_FILE"
fi

print_success "Metagraph ID: $METAGRAPH_ID"

# Get owner address from first keystore
print_title "Getting Owner Address"

OWNER_KEYSTORE="$KEYS_DIR/$OWNER_KEYSTORE_FILE"

print_status "Using keystore: $OWNER_KEYSTORE_FILE"

OWNER_ADDRESS=$(get_wallet_address "$OWNER_KEYSTORE" "$OWNER_KEYSTORE_ALIAS" "$OWNER_KEYSTORE_PASSWORD")

if [[ $? -ne 0 ]] || [[ -z "$OWNER_ADDRESS" ]]; then
  print_error "Failed to retrieve owner address"
  exit 1
fi

print_success "Owner address: $OWNER_ADDRESS"

# Create owner message with each keystore
print_title "Creating Owner Signing Messages"

# Parent ordinal for genesis
PARENT_ORDINAL="0"

declare -a OWNER_MESSAGES

for i in "${!KEYSTORE_FILES[@]}"; do
  keystore_file="$KEYS_DIR/${KEYSTORE_FILES[$i]}"
  keystore_alias="${KEYSTORE_ALIAS[$i]}"
  keystore_password="${KEYSTORE_PASSWORD[$i]}"
  signer_num=$((i + 1))

  print_status "Creating message with signer $signer_num (${KEYSTORE_FILES[$i]})..."

  owner_message=$(CL_KEYSTORE="$keystore_file" \
    CL_KEYALIAS="$keystore_alias" \
    CL_PASSWORD="$keystore_password" \
    java -jar "$JARS_DIR/cl-wallet.jar" create-owner-signing-message \
    --address "$OWNER_ADDRESS" \
    --parentOrdinal "$PARENT_ORDINAL" \
    --metagraphId "$METAGRAPH_ID" 2>/dev/null)

  if [[ $? -ne 0 ]] || [[ -z "$owner_message" ]]; then
    print_error "Failed to create owner message with signer $signer_num"
    exit 1
  fi

  OWNER_MESSAGES+=("$owner_message")
  print_success "  Signer $signer_num message created"
done

# Combine messages (merge proofs from all signers)
print_title "Combining Owner Messages"

print_status "Merging ${#OWNER_MESSAGES[@]} signatures..."

# Build jq command to combine all proofs
COMBINED_MESSAGE="${OWNER_MESSAGES[0]}"

for i in $(seq 1 $((${#OWNER_MESSAGES[@]} - 1))); do
  COMBINED_MESSAGE=$(echo "$COMBINED_MESSAGE" | \
    jq --argjson msg "${OWNER_MESSAGES[$i]}" '. + {proofs: (.proofs + $msg.proofs)}')

  if [[ $? -ne 0 ]]; then
    print_error "Failed to combine owner messages"
    exit 1
  fi
done

print_success "Successfully created combined owner message with ${#OWNER_MESSAGES[@]} signatures!"

# Validate combined message
PROOF_COUNT=$(echo "$COMBINED_MESSAGE" | jq '.proofs | length' 2>/dev/null)
print_status "Combined message contains $PROOF_COUNT proof(s)"

if [[ "$PROOF_COUNT" != "${#OWNER_MESSAGES[@]}" ]]; then
  print_warning "Expected ${#OWNER_MESSAGES[@]} proofs, got $PROOF_COUNT"
fi

# Save owner message locally
print_title "Saving Owner Message"

OWNER_MESSAGE_FILE="$DEPLOY_ROOT/owner-message.json"
echo "$COMBINED_MESSAGE" | jq '.' > "$OWNER_MESSAGE_FILE"

if [[ ! -f "$OWNER_MESSAGE_FILE" ]]; then
  print_error "Failed to save owner message"
  exit 1
fi

print_success "Owner message saved to: $OWNER_MESSAGE_FILE"
print_status ""
print_status "Owner message preview:"
echo "$COMBINED_MESSAGE" | jq '.' | head -20
print_status "..."

# Upload owner message to first node
print_title "Uploading Owner Message to Lead Node"

LEAD_NODE_IP="${NODE_IPS[0]}"

print_status "Uploading to Node 1 ($LEAD_NODE_IP)..."

if ! scp_to_node "$OWNER_MESSAGE_FILE" "$LEAD_NODE_IP" "$REMOTE_ML0_DIR/owner-message.json"; then
  print_error "Failed to upload owner message to lead node"
  exit 1
fi

# Verify upload
if ! ssh_exec "$LEAD_NODE_IP" "test -f $REMOTE_ML0_DIR/owner-message.json" >/dev/null 2>&1; then
  print_error "Owner message verification failed on lead node"
  exit 1
fi

print_success "Owner message uploaded to $REMOTE_ML0_DIR/owner-message.json"

# Summary
print_title "Owner Message Creation Complete"

print_success "Multi-signed owner message created successfully!"
print_status ""
print_status "Owner message details:"
print_status "  Owner address: $OWNER_ADDRESS"
print_status "  Metagraph ID: $METAGRAPH_ID"
print_status "  Parent ordinal: $PARENT_ORDINAL"
print_status "  Signers: ${#OWNER_MESSAGES[@]}"
print_status "  Proofs: $PROOF_COUNT"
print_status ""
print_status "Local file: $OWNER_MESSAGE_FILE"
print_status "Remote file: $REMOTE_ML0_DIR/owner-message.json (Node 1)"
print_status ""
print_status "Next steps:"
print_status "  Start nodes: ./deploy/scripts/06-start-nodes.sh"
