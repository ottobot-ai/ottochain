#!/bin/bash

# restart-cluster.sh
# Simple restart of existing cluster using existing data (rollback mode only)
#
# This script:
# 1. Stops all services
# 2. Starts metagraph-l0 in rollback mode on all nodes
# 3. Starts currency-l1 and data-l1
# 4. NO GENESIS - uses existing data only

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "restart-cluster.sh" \
    "./deploy/scripts/restart-cluster.sh" \
    "Restart existing cluster using rollback mode (does NOT create new genesis)"
  exit 0
fi

print_title "Workchain Metagraph - Restart Cluster (Rollback Mode)"

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

# Read metagraph ID from lead node (like euclid does)
print_status "Fetching metagraph ID from lead node..."
LEAD_NODE_IP="${NODE_IPS[0]}"

METAGRAPH_ID=$(ssh_exec "$LEAD_NODE_IP" "cat $REMOTE_ML0_DIR/genesis.address 2>/dev/null" | tr -d '\r\n')

if [[ -z "$METAGRAPH_ID" ]]; then
  print_error "Failed to get metagraph ID from lead node $LEAD_NODE_IP"
  print_error "File not found: $REMOTE_ML0_DIR/genesis.address"
  exit 1
fi

print_status "Metagraph ID: $METAGRAPH_ID"
print_status ""

# Step 1: Stop all services
print_title "Step 1: Stopping All Services"

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i+1))

  print_status "Stopping Node $node_num ($node_ip)..."

  ssh_exec "$node_ip" "
    for pid in \$(jps -l | grep -E 'metagraph-l0|currency-l1|data-l1' | awk '{print \$1}'); do
      kill -9 \$pid 2>/dev/null
    done
  " || true

  sleep 1
done

print_success "All services stopped"
print_status "Waiting 5 seconds before restart..."
sleep 5

# Step 2: Start metagraph-l0 - Lead node FIRST, then validators
print_title "Step 2: Starting Metagraph L0 - Lead Node"

# Start node 1 in rollback mode
genesis_ip="${NODE_IPS[0]}"
keystore_alias="${KEYSTORE_ALIAS[0]}"
keystore_password="${KEYSTORE_PASSWORD[0]}"

print_status "Starting Node 1 metagraph-l0 (rollback mode)..."

rollback_cmd="cd $REMOTE_ML0_DIR && \
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
  java $JVM_OPTS -jar metagraph-l0.jar run-rollback --ip $genesis_ip \
    > metagraph-l0.log 2>&1 < /dev/null &' && sleep 1"

ssh_exec "$genesis_ip" "$rollback_cmd"
print_success "  Node 1 metagraph-l0 started"

# Wait for node 1 to be online
print_status ""
print_status "Waiting for Node 1 ML0 to come online..."
wait_for_node_online "$genesis_ip" "$ML0_PUBLIC_PORT" "Node 1 ML0" 120 1 || true

# Wait for node 1 to be Ready
print_status ""
print_status "Waiting for lead node (Node 1) to reach Ready state..."
if ! wait_for_node_ready "$genesis_ip" "$ML0_PUBLIC_PORT" "Node 1 ML0" 240 3; then
  print_error "Lead node did not reach Ready state"
  exit 1
fi

# NOW start validator nodes
if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  print_title "Step 3: Starting Metagraph L0 - Validators"

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    keystore_alias="${KEYSTORE_ALIAS[$i]}"
    keystore_password="${KEYSTORE_PASSWORD[$i]}"

    print_status "Starting Node $node_num metagraph-l0 (validator mode)..."

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

    ssh_exec "$node_ip" "$validator_cmd"
    print_success "  Node $node_num metagraph-l0 started"
    sleep 3
  done

  # Wait for validators to come online
  print_status ""
  print_status "Waiting for validator nodes to come online..."

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    wait_for_node_online "$node_ip" "$ML0_PUBLIC_PORT" "Node $node_num ML0" 120 1 || true
  done
fi

# Join validators to cluster
if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  print_status ""
  print_status "Getting lead node peer ID..."

  genesis_peer_id=$(ssh_exec "$genesis_ip" "curl -s http://localhost:$ML0_PUBLIC_PORT/node/info | jq -r '.id'" 2>/dev/null || echo "")

  if [[ -z "$genesis_peer_id" ]]; then
    print_error "Failed to get lead node peer ID"
    print_error "Debug: Try running: ssh $genesis_ip 'curl -s http://localhost:$ML0_PUBLIC_PORT/node/info'"
    exit 1
  fi

  print_status "Lead node peer ID: ${genesis_peer_id:0:16}..."

  print_status ""
  print_status "Joining validators to cluster..."

  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))

    print_status "  Waiting for Node $node_num to be ReadyToJoin..."
    if ! wait_for_node_state "$node_ip" "$ML0_PUBLIC_PORT" "ReadyToJoin" "Node $node_num ML0" 120 2; then
      print_error "Node $node_num did not reach ReadyToJoin"
      continue
    fi

    print_status "  Sending join request for Node $node_num..."
    if ssh_exec "$node_ip" "curl -s -X POST http://localhost:$ML0_CLI_PORT/cluster/join \
      -H 'Content-Type: application/json' \
      -d '{\"id\":\"$genesis_peer_id\",\"ip\":\"$genesis_ip\",\"p2pPort\":$ML0_P2P_PORT}'" >/dev/null 2>&1; then
      print_success "  Node $node_num joined cluster"
    else
      print_error "  Failed to join Node $node_num"
    fi

    sleep 2
  done
fi

# Wait for all nodes to reach Ready (consensus)
print_status ""
print_status "Waiting for all nodes to reach Ready (consensus)..."

for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  wait_for_node_ready "$node_ip" "$ML0_PUBLIC_PORT" "Node $node_num ML0" 120 3 || true
done

print_success "Metagraph-l0 cluster ready"

# Step 3: Start currency-l1
print_title "Step 3: Starting Currency L1"

# Get ML0 peer IDs for each node
ml0_peer_ids=()
for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  peer_id=$(ssh_exec "$node_ip" "curl -s http://localhost:$ML0_PUBLIC_PORT/node/info | jq -r '.id'" 2>/dev/null || echo "")
  ml0_peer_ids+=("$peer_id")
done

# Start node 1 as initial validator
node_ip="${NODE_IPS[0]}"
keystore_alias="${KEYSTORE_ALIAS[0]}"
keystore_password="${KEYSTORE_PASSWORD[0]}"
ml0_peer_id="${ml0_peer_ids[0]}"

print_status "Starting Node 1 currency-l1 (initial validator)..."

cl1_cmd="cd $REMOTE_CL1_DIR && \
  setsid bash -c 'CL_PUBLIC_HTTP_PORT=$CL1_PUBLIC_PORT \
  CL_P2P_HTTP_PORT=$CL1_P2P_PORT \
  CL_CLI_HTTP_PORT=$CL1_CLI_PORT \
  CL_L0_PEER_ID=$ml0_peer_id \
  CL_L0_PEER_HTTP_HOST=$node_ip \
  CL_L0_PEER_HTTP_PORT=$ML0_PUBLIC_PORT \
  CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  CL_GLOBAL_L0_PEER_HTTP_HOST=$GL0_PEER_IP \
  CL_GLOBAL_L0_PEER_HTTP_PORT=$GL0_PEER_PORT \
  CL_L0_TOKEN_IDENTIFIER=$METAGRAPH_ID \
  CL_KEYSTORE=metagraph.p12 \
  CL_KEYALIAS=$keystore_alias \
  CL_PASSWORD=\"$keystore_password\" \
  CL_APP_ENV=$CL_APP_ENV \
  CL_COLLATERAL=$CL_COLLATERAL \
  java $JVM_OPTS -jar currency-l1.jar run-initial-validator --ip $node_ip \
    > currency-l1.log 2>&1 < /dev/null &' && sleep 1"

ssh_exec "$node_ip" "$cl1_cmd"
print_success "  Node 1 currency-l1 started"

# Start validator nodes
if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    keystore_alias="${KEYSTORE_ALIAS[$i]}"
    keystore_password="${KEYSTORE_PASSWORD[$i]}"
    ml0_peer_id="${ml0_peer_ids[$i]}"

    print_status "Starting Node $node_num currency-l1 (validator)..."

    cl1_cmd="cd $REMOTE_CL1_DIR && \
      setsid bash -c 'CL_PUBLIC_HTTP_PORT=$CL1_PUBLIC_PORT \
      CL_P2P_HTTP_PORT=$CL1_P2P_PORT \
      CL_CLI_HTTP_PORT=$CL1_CLI_PORT \
      CL_L0_PEER_ID=$ml0_peer_id \
      CL_L0_PEER_HTTP_HOST=$node_ip \
      CL_L0_PEER_HTTP_PORT=$ML0_PUBLIC_PORT \
      CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
      CL_GLOBAL_L0_PEER_HTTP_HOST=$GL0_PEER_IP \
      CL_GLOBAL_L0_PEER_HTTP_PORT=$GL0_PEER_PORT \
      CL_L0_TOKEN_IDENTIFIER=$METAGRAPH_ID \
      CL_KEYSTORE=metagraph.p12 \
      CL_KEYALIAS=$keystore_alias \
      CL_PASSWORD=\"$keystore_password\" \
      CL_APP_ENV=$CL_APP_ENV \
      CL_COLLATERAL=$CL_COLLATERAL \
      java $JVM_OPTS -jar currency-l1.jar run-validator --ip $node_ip \
        > currency-l1.log 2>&1 < /dev/null &' && sleep 1"

    ssh_exec "$node_ip" "$cl1_cmd"
    print_success "  Node $node_num currency-l1 started"
    sleep 3
  done
fi

# Wait and join CL1
print_status ""
print_status "Waiting for currency-l1 nodes..."

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  wait_for_node_online "$node_ip" "$CL1_PUBLIC_PORT" "Node $node_num CL1" 120 1 || true
done

if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  lead_ip="${NODE_IPS[0]}"
  lead_peer_id=$(ssh_exec "$lead_ip" "curl -s http://localhost:$CL1_PUBLIC_PORT/node/info | jq -r '.id'" 2>/dev/null || echo "")

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

# Step 4: Start data-l1
print_title "Step 4: Starting Data L1"

# Start node 1 as initial validator
node_ip="${NODE_IPS[0]}"
keystore_alias="${KEYSTORE_ALIAS[0]}"
keystore_password="${KEYSTORE_PASSWORD[0]}"
ml0_peer_id="${ml0_peer_ids[0]}"

print_status "Starting Node 1 data-l1 (initial validator)..."

dl1_cmd="cd $REMOTE_DL1_DIR && \
  setsid bash -c 'CL_PUBLIC_HTTP_PORT=$DL1_PUBLIC_PORT \
  CL_P2P_HTTP_PORT=$DL1_P2P_PORT \
  CL_CLI_HTTP_PORT=$DL1_CLI_PORT \
  CL_L0_PEER_ID=$ml0_peer_id \
  CL_L0_PEER_HTTP_HOST=$node_ip \
  CL_L0_PEER_HTTP_PORT=$ML0_PUBLIC_PORT \
  CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
  CL_GLOBAL_L0_PEER_HTTP_HOST=$GL0_PEER_IP \
  CL_GLOBAL_L0_PEER_HTTP_PORT=$GL0_PEER_PORT \
  CL_L0_TOKEN_IDENTIFIER=$METAGRAPH_ID \
  CL_KEYSTORE=metagraph.p12 \
  CL_KEYALIAS=$keystore_alias \
  CL_PASSWORD=\"$keystore_password\" \
  CL_APP_ENV=$CL_APP_ENV \
  CL_COLLATERAL=$CL_COLLATERAL \
  java $JVM_OPTS -jar data-l1.jar run-initial-validator --ip $node_ip \
    > data-l1.log 2>&1 < /dev/null &' && sleep 1"

ssh_exec "$node_ip" "$dl1_cmd"
print_success "  Node 1 data-l1 started"

# Start validator nodes
if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  for i in $(seq 1 $((${#NODE_IPS[@]} - 1))); do
    node_ip="${NODE_IPS[$i]}"
    node_num=$((i + 1))
    keystore_alias="${KEYSTORE_ALIAS[$i]}"
    keystore_password="${KEYSTORE_PASSWORD[$i]}"
    ml0_peer_id="${ml0_peer_ids[$i]}"

    print_status "Starting Node $node_num data-l1 (validator)..."

    dl1_cmd="cd $REMOTE_DL1_DIR && \
      setsid bash -c 'CL_PUBLIC_HTTP_PORT=$DL1_PUBLIC_PORT \
      CL_P2P_HTTP_PORT=$DL1_P2P_PORT \
      CL_CLI_HTTP_PORT=$DL1_CLI_PORT \
      CL_L0_PEER_ID=$ml0_peer_id \
      CL_L0_PEER_HTTP_HOST=$node_ip \
      CL_L0_PEER_HTTP_PORT=$ML0_PUBLIC_PORT \
      CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID \
      CL_GLOBAL_L0_PEER_HTTP_HOST=$GL0_PEER_IP \
      CL_GLOBAL_L0_PEER_HTTP_PORT=$GL0_PEER_PORT \
      CL_L0_TOKEN_IDENTIFIER=$METAGRAPH_ID \
      CL_KEYSTORE=metagraph.p12 \
      CL_KEYALIAS=$keystore_alias \
      CL_PASSWORD=\"$keystore_password\" \
      CL_APP_ENV=$CL_APP_ENV \
      CL_COLLATERAL=$CL_COLLATERAL \
      java $JVM_OPTS -jar data-l1.jar run-validator --ip $node_ip \
        > data-l1.log 2>&1 < /dev/null &' && sleep 1"

    ssh_exec "$node_ip" "$dl1_cmd"
    print_success "  Node $node_num data-l1 started"
    sleep 3
  done
fi

# Wait and join DL1
print_status ""
print_status "Waiting for data-l1 nodes..."

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))
  wait_for_node_online "$node_ip" "$DL1_PUBLIC_PORT" "Node $node_num DL1" 120 1 || true
done

if [[ ${#NODE_IPS[@]} -gt 1 ]]; then
  lead_ip="${NODE_IPS[0]}"
  lead_peer_id=$(ssh_exec "$lead_ip" "curl -s http://localhost:$DL1_PUBLIC_PORT/node/info | jq -r '.id'" 2>/dev/null || echo "")

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
print_title "Restart Complete"

print_success "All services restarted successfully!"
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
print_status "Check status: ./deploy/scripts/status.sh"