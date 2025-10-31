#!/bin/bash

# genesis-start.sh
# INITIAL GENESIS DEPLOYMENT ONLY
#
# This script creates a NEW metagraph from scratch:
# 1. Archives any existing data
# 2. Starts node 1 in run-genesis mode
# 3. Waits for genesis snapshot creation
# 4. Starts validator nodes
# 5. Starts currency-l1 and data-l1
#
# WARNING: This will archive existing data and create a NEW metagraph!
# For restarting an existing metagraph, use: ./deploy/scripts/restart-cluster.sh

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "genesis-start.sh" \
    "./deploy/scripts/genesis-start.sh" \
    "Create NEW metagraph genesis (WARNING: archives existing data!)"
  exit 0
fi

print_title "Workchain Metagraph - Genesis Deployment"
print_status ""
print_warning "⚠️  This will create a NEW metagraph genesis!"
print_warning "⚠️  Any existing data will be archived!"
print_status ""
print_status "Press Ctrl+C to cancel, or wait 5 seconds to continue..."
sleep 5

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Check required commands
require_cmd ssh
require_cmd curl
require_cmd jq

# Validate SSH key
if ! check_ssh_key; then
  exit 1
fi

# Read metagraph ID
METAGRAPH_ID_FILE="$DEPLOY_ROOT/metagraph-id.txt"
if [[ ! -f "$METAGRAPH_ID_FILE" ]]; then
  print_error "Metagraph ID file not found: $METAGRAPH_ID_FILE"
  print_status "Please run create-genesis first: ./deploy/scripts/04-create-genesis.sh"
  exit 1
fi

METAGRAPH_ID=$(cat "$METAGRAPH_ID_FILE" | tr -d '\r\n')
if [[ -z "$METAGRAPH_ID" ]]; then
  print_error "Metagraph ID is empty in $METAGRAPH_ID_FILE"
  exit 1
fi

print_status "Metagraph ID: $METAGRAPH_ID"
print_status ""

# Archive existing data on genesis node
print_title "Step 1: Archiving Existing Data"

genesis_ip="${NODE_IPS[0]}"
timestamp=$(date +%Y%m%dT%H%M%S)

print_status "Archiving data on Node 1 ($genesis_ip)..."

ssh_exec "$genesis_ip" "
  cd $REMOTE_ML0_DIR
  mkdir -p archived-data
  if [ -d data ]; then
    echo 'Moving existing data to archived-data/data_$timestamp'
    mv data archived-data/data_$timestamp
  fi
" 2>/dev/null || true

print_success "Data archived"

# Start Node 1 in genesis mode
print_title "Step 2: Starting Genesis Node (Node 1)"

print_status "Starting metagraph-l0 in run-genesis mode..."

keystore_alias="${KEYSTORE_ALIAS[0]}"
keystore_password="${KEYSTORE_PASSWORD[0]}"

# Build genesis command with owner message if it exists
genesis_args="genesis.snapshot --ip $genesis_ip"
if [[ -f "$DEPLOY_ROOT/owner-message.json" ]]; then
  print_status "Using owner message from: $DEPLOY_ROOT/owner-message.json"
  genesis_args="$genesis_args --metagraph-owner-message owner-message.json"
fi

# NOTE: Genesis mode does NOT set CL_L0_TOKEN_IDENTIFIER (euclid pattern)
genesis_cmd="cd $REMOTE_ML0_DIR && \
  setsid bash -c 'CL_PUBLIC_HTTP_PORT=$ML0_PUBLIC_PORT \
  CL_P2P_HTTP_PORT=$ML0_P2P_PORT \
  CL_CLI_HTTP_PORT=$ML0_CLI_PORT \
  CL_GLOBAL_L0_PEER_HTTP_HOST=$GL0_PEER_IP \
  CL_GLOBAL_L0_PEER_HTTP_PORT=$GL0_PEER_PORT \
  CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  CL_KEYSTORE=metagraph.p12 \
  CL_KEYALIAS=$keystore_alias \
  CL_PASSWORD=\"$keystore_password\" \
  CL_APP_ENV=$CL_APP_ENV \
  CL_COLLATERAL=$CL_COLLATERAL \
  java $JVM_OPTS -jar metagraph-l0.jar run-genesis $genesis_args \
    > metagraph-l0.log 2>&1 < /dev/null &' && sleep 1"

if ! ssh_exec "$genesis_ip" "$genesis_cmd"; then
  print_error "Failed to start metagraph-l0 in genesis mode"
  exit 1
fi

print_success "Genesis node started"

# Wait for genesis node to be Ready
print_status ""
print_status "Waiting for genesis node to reach Ready state..."

if ! wait_for_node_ready "$genesis_ip" "$ML0_PUBLIC_PORT" "Node 1 metagraph-l0" 240 2; then
  print_error "Genesis node did not reach Ready state"
  print_status "Check logs: ssh $genesis_ip 'tail -100 $REMOTE_ML0_DIR/metagraph-l0.log'"
  exit 1
fi

print_success "Genesis node is Ready"

# Start validator nodes (nodes 2 & 3)
if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  print_title "Step 3: Starting Validator Nodes"

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    keystore_alias="${KEYSTORE_ALIAS[$i]}"
    keystore_password="${KEYSTORE_PASSWORD[$i]}"

    print_status "Starting Node $node_num metagraph-l0 (validator)..."

    # Validators use run-validator mode with CL_L0_TOKEN_IDENTIFIER set
    validator_cmd="cd $REMOTE_ML0_DIR && \
      setsid bash -c 'CL_PUBLIC_HTTP_PORT=$ML0_PUBLIC_PORT \
      CL_P2P_HTTP_PORT=$ML0_P2P_PORT \
      CL_CLI_HTTP_PORT=$ML0_CLI_PORT \
      CL_GLOBAL_L0_PEER_HTTP_HOST=$GL0_PEER_IP \
      CL_GLOBAL_L0_PEER_HTTP_PORT=$GL0_PEER_PORT \
      CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
      CL_L0_TOKEN_IDENTIFIER=$METAGRAPH_ID \
      CL_KEYSTORE=metagraph.p12 \
      CL_KEYALIAS=$keystore_alias \
      CL_PASSWORD=\"$keystore_password\" \
      CL_APP_ENV=$CL_APP_ENV \
      CL_COLLATERAL=$CL_COLLATERAL \
      java $JVM_OPTS -jar metagraph-l0.jar run-validator --ip $node_ip \
        > metagraph-l0.log 2>&1 < /dev/null &' && sleep 1"

    if ! ssh_exec "$node_ip" "$validator_cmd"; then
      print_error "Failed to start validator on Node $node_num"
      exit 1
    fi

    print_success "  Node $node_num started"
    sleep 3
  done
fi

# Wait for validators to come online
if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  print_status ""
  print_status "Waiting for validators to come online..."

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    wait_for_node_online "$node_ip" "$ML0_PUBLIC_PORT" "Node $node_num ML0" 120 1 || true
  done

  # Wait for ReadyToJoin state
  print_status ""
  print_status "Waiting for validators to be ReadyToJoin..."

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    wait_for_node_state "$node_ip" "$ML0_PUBLIC_PORT" "ReadyToJoin" "Node $node_num ML0" 120 2 || true
  done

  # Join validators to cluster
  print_status ""
  print_status "Joining validators to genesis cluster..."

  genesis_peer_id=$(ssh_exec "$genesis_ip" "curl -s http://localhost:$ML0_CLI_PORT/node/info | jq -r '.id'" 2>/dev/null || echo "")

  if [[ -z "$genesis_peer_id" ]]; then
    print_error "Failed to get genesis peer ID"
    exit 1
  fi

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))

    ssh_exec "$node_ip" "curl -s -X POST http://localhost:$ML0_CLI_PORT/cluster/join \
      -H 'Content-Type: application/json' \
      -d '{\"id\":\"$genesis_peer_id\",\"ip\":\"$genesis_ip\",\"p2pPort\":$ML0_P2P_PORT}'" >/dev/null 2>&1

    print_success "  Node $node_num joined"
    sleep 2
  done

  # Wait for all nodes to reach Ready (consensus)
  print_status ""
  print_status "Waiting for consensus (all nodes Ready)..."

  for i in "${!NODE_IPS[@]}"; do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    wait_for_node_ready "$node_ip" "$ML0_PUBLIC_PORT" "Node $node_num ML0" 120 3 || true
  done
fi

print_success "Metagraph-l0 cluster ready"

# Start currency-l1 on all nodes
print_title "Step 4: Starting Currency L1"

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  keystore_alias="${KEYSTORE_ALIAS[$i]}"
  keystore_password="${KEYSTORE_PASSWORD[$i]}"

  print_status "Starting Node $node_num currency-l1..."

  cl1_cmd="cd $REMOTE_CL1_DIR && \
    setsid bash -c 'CL_PUBLIC_HTTP_PORT=$CL1_PUBLIC_PORT \
    CL_P2P_HTTP_PORT=$CL1_P2P_PORT \
    CL_CLI_HTTP_PORT=$CL1_CLI_PORT \
    CL_L0_PEER_HTTP_HOST=$node_ip \
    CL_L0_PEER_HTTP_PORT=$ML0_PUBLIC_PORT \
    CL_KEYSTORE=metagraph.p12 \
    CL_KEYALIAS=$keystore_alias \
    CL_PASSWORD=\"$keystore_password\" \
    CL_APP_ENV=$CL_APP_ENV \
    CL_COLLATERAL=$CL_COLLATERAL \
    java $JVM_OPTS -jar currency-l1.jar run-validator --ip $node_ip \
      > currency-l1.log 2>&1 < /dev/null &' && sleep 1"

  ssh_exec "$node_ip" "$cl1_cmd" || true
  print_success "  Node $node_num currency-l1 started"
  sleep 3
done

# Wait and join CL1
print_status ""
print_status "Waiting for currency-l1 cluster to form..."

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  wait_for_node_online "$node_ip" "$CL1_PUBLIC_PORT" "Node $node_num CL1" 120 1 || true
done

if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  lead_ip="${NODE_IPS[0]}"
  lead_peer_id=$(ssh_exec "$lead_ip" "curl -s http://localhost:$CL1_CLI_PORT/node/info | jq -r '.id'" 2>/dev/null || echo "")

  if [[ -n "$lead_peer_id" ]]; then
    for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
      node_ip="${NODE_IPS[$i]}"
      node_num=$((i + 1))

      wait_for_node_state "$node_ip" "$CL1_PUBLIC_PORT" "ReadyToJoin" "Node $node_num CL1" 120 2 || true

      ssh_exec "$node_ip" "curl -s -X POST http://localhost:$CL1_CLI_PORT/cluster/join \
        -H 'Content-Type: application/json' \
        -d '{\"id\":\"$lead_peer_id\",\"ip\":\"$lead_ip\",\"p2pPort\":$CL1_P2P_PORT}'" >/dev/null 2>&1 || true

      print_status "  Node $node_num joined"
      sleep 2
    done
  fi
fi

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  wait_for_node_ready "$node_ip" "$CL1_PUBLIC_PORT" "Node $node_num CL1" 120 3 || true
done

print_success "Currency-l1 cluster ready"

# Start data-l1 on all nodes
print_title "Step 5: Starting Data L1"

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  keystore_alias="${KEYSTORE_ALIAS[$i]}"
  keystore_password="${KEYSTORE_PASSWORD[$i]}"

  print_status "Starting Node $node_num data-l1..."

  dl1_cmd="cd $REMOTE_DL1_DIR && \
    setsid bash -c 'CL_PUBLIC_HTTP_PORT=$DL1_PUBLIC_PORT \
    CL_P2P_HTTP_PORT=$DL1_P2P_PORT \
    CL_CLI_HTTP_PORT=$DL1_CLI_PORT \
    CL_L0_PEER_HTTP_HOST=$node_ip \
    CL_L0_PEER_HTTP_PORT=$ML0_PUBLIC_PORT \
    CL_KEYSTORE=metagraph.p12 \
    CL_KEYALIAS=$keystore_alias \
    CL_PASSWORD=\"$keystore_password\" \
    CL_APP_ENV=$CL_APP_ENV \
    CL_COLLATERAL=$CL_COLLATERAL \
    java $JVM_OPTS -jar data-l1.jar run-validator --ip $node_ip \
      > data-l1.log 2>&1 < /dev/null &' && sleep 1"

  ssh_exec "$node_ip" "$dl1_cmd" || true
  print_success "  Node $node_num data-l1 started"
  sleep 3
done

# Wait and join DL1
print_status ""
print_status "Waiting for data-l1 cluster to form..."

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num $((i + 1))
  wait_for_node_online "$node_ip" "$DL1_PUBLIC_PORT" "Node $node_num DL1" 120 1 || true
done

if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  lead_ip="${NODE_IPS[0]}"
  lead_peer_id=$(ssh_exec "$lead_ip" "curl -s http://localhost:$DL1_CLI_PORT/node/info | jq -r '.id'" 2>/dev/null || echo "")

  if [[ -n "$lead_peer_id" ]]; then
    for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
      node_ip="${NODE_IPS[$i]}"
      node_num=$((i + 1))

      wait_for_node_state "$node_ip" "$DL1_PUBLIC_PORT" "ReadyToJoin" "Node $node_num DL1" 120 2 || true

      ssh_exec "$node_ip" "curl -s -X POST http://localhost:$DL1_CLI_PORT/cluster/join \
        -H 'Content-Type: application/json' \
        -d '{\"id\":\"$lead_peer_id\",\"ip\":\"$lead_ip\",\"p2pPort\":$DL1_P2P_PORT}'" >/dev/null 2>&1 || true

      print_status "  Node $node_num joined"
      sleep 2
    done
  fi
fi

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  wait_for_node_ready "$node_ip" "$DL1_PUBLIC_PORT" "Node $node_num DL1" 120 3 || true
done

print_success "Data-l1 cluster ready"

# Summary
print_title "Genesis Deployment Complete"

print_success "New metagraph genesis created successfully!"
print_status ""
print_status "Service endpoints:"

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))

  print_status "  Node $node_num ($node_ip):"
  print_status "    Metagraph L0: http://$node_ip:$ML0_PUBLIC_PORT/node/info"
  print_status "    Currency L1:  http://$node_ip:$CL1_PUBLIC_PORT/node/info"
  print_status "    Data L1:      http://$node_ip:$DL1_PUBLIC_PORT/node/info"
done

print_status ""
print_status "Useful commands:"
print_status "  Check status:     ./deploy/scripts/status.sh"
print_status "  View logs:        ./deploy/scripts/logs.sh"
print_status "  Restart cluster:  ./deploy/scripts/restart-cluster.sh"
print_status "  Stop all nodes:   ./deploy/scripts/stop-nodes.sh"
print_status ""
print_warning "⚠️  For restarts, use restart-cluster.sh (NOT this script!)"