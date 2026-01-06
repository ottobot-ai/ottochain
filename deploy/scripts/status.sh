#!/bin/bash

# status.sh
# Check status of all metagraph services
#
# This script:
# 1. Checks process status on each node
# 2. Queries HTTP health endpoints
# 3. Displays node state and cluster information
# 4. Reports overall health status

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "status.sh" \
    "./deploy/scripts/status.sh" \
    "Check status of all metagraph services on all nodes"
  exit 0
fi

print_title "Ottochain Metagraph - Node Status"

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Check required commands
require_cmd ssh
require_cmd curl
require_cmd jq

# Function to check service status on a node
check_node_status() {
  local node_ip="$1"
  local node_num="$2"

  echo ""
  print_title "Node $node_num ($node_ip)"

  # Check SSH connectivity
  if ! ssh_exec "$node_ip" "echo 'OK'" >/dev/null 2>&1; then
    print_error "  Cannot connect via SSH"
    return 1
  fi

  # Check metagraph-l0
  print_status "Metagraph L0:"
  local ml0_proc_running=false
  if check_process_running "$node_ip" "metagraph-l0.jar" 2>/dev/null; then
    ml0_proc_running=true
    print_success "  Process: Running"

    local ml0_state
    ml0_state=$(get_node_state "$node_ip" "$ML0_PUBLIC_PORT")
    print_status "  State: $ml0_state"

    # Get node info if available
    local ml0_info
    ml0_info=$(get_node_info "$node_ip" "$ML0_PUBLIC_PORT" 2>/dev/null)
    if [[ -n "$ml0_info" ]]; then
      local version
      version=$(echo "$ml0_info" | jq -r '.version // "unknown"' 2>/dev/null || echo "unknown")
      print_status "  Version: $version"

      # Get latest snapshot ordinal
      local ordinal
      ordinal=$(ssh_exec "$node_ip" "curl -s http://localhost:$ML0_PUBLIC_PORT/snapshots/latest/ordinal | jq -r '.value // \"N/A\"'" 2>/dev/null || echo "N/A")
      print_status "  Last Snapshot: $ordinal"
    fi

    print_status "  Endpoint: http://$node_ip:$ML0_PUBLIC_PORT"
  else
    print_error "  Process: Not running"
  fi

  # Check currency-l1
  print_status "Currency L1:"
  local cl1_proc_running=false
  if check_process_running "$node_ip" "currency-l1.jar" 2>/dev/null; then
    cl1_proc_running=true
    print_success "  Process: Running"

    local cl1_state
    cl1_state=$(get_node_state "$node_ip" "$CL1_PUBLIC_PORT")
    print_status "  State: $cl1_state"

    print_status "  Endpoint: http://$node_ip:$CL1_PUBLIC_PORT"
  else
    print_error "  Process: Not running"
  fi

  # Check data-l1
  print_status "Data L1:"
  local dl1_proc_running=false
  if check_process_running "$node_ip" "data-l1.jar" 2>/dev/null; then
    dl1_proc_running=true
    print_success "  Process: Running"

    local dl1_state
    dl1_state=$(get_node_state "$node_ip" "$DL1_PUBLIC_PORT")
    print_status "  State: $dl1_state"

    print_status "  Endpoint: http://$node_ip:$DL1_PUBLIC_PORT"
  else
    print_error "  Process: Not running"
  fi

  # Return success if all processes are running
  if [[ "$ml0_proc_running" == "true" ]] && \
     [[ "$cl1_proc_running" == "true" ]] && \
     [[ "$dl1_proc_running" == "true" ]]; then
    return 0
  else
    return 1
  fi
}

# Check status on all nodes
ALL_HEALTHY=true

for i in "${!NODE_IPS[@]}"; do
  if ! check_node_status "${NODE_IPS[$i]}" "$((i+1))"; then
    ALL_HEALTHY=false
  fi
done

# Cluster summary
echo ""
print_title "Cluster Summary"

# Check metagraph-l0 cluster
print_status "Metagraph L0 Cluster:"
ml0_ready_count=0
for i in "${!NODE_IPS[@]}"; do
  state=$(get_node_state "${NODE_IPS[$i]}" "$ML0_PUBLIC_PORT" 2>/dev/null || echo "Unreachable")
  node_num=$((i+1))

  if [[ "$state" == "Ready" ]]; then
    print_success "  Node $node_num: $state"
    ((ml0_ready_count++))
  else
    print_warning "  Node $node_num: $state"
  fi
done
print_status "  Ready nodes: $ml0_ready_count/${#NODE_IPS[@]}"

# Check currency-l1 cluster
print_status "Currency L1 Cluster:"
cl1_ready_count=0
for i in "${!NODE_IPS[@]}"; do
  state=$(get_node_state "${NODE_IPS[$i]}" "$CL1_PUBLIC_PORT" 2>/dev/null || echo "Unreachable")
  node_num=$((i+1))

  if [[ "$state" == "Ready" ]]; then
    print_success "  Node $node_num: $state"
    ((cl1_ready_count++))
  else
    print_warning "  Node $node_num: $state"
  fi
done
print_status "  Ready nodes: $cl1_ready_count/${#NODE_IPS[@]}"

# Check data-l1 cluster
print_status "Data L1 Cluster:"
dl1_ready_count=0
for i in "${!NODE_IPS[@]}"; do
  state=$(get_node_state "${NODE_IPS[$i]}" "$DL1_PUBLIC_PORT" 2>/dev/null || echo "Unreachable")
  node_num=$((i+1))

  if [[ "$state" == "Ready" ]]; then
    print_success "  Node $node_num: $state"
    ((dl1_ready_count++))
  else
    print_warning "  Node $node_num: $state"
  fi
done
print_status "  Ready nodes: $dl1_ready_count/${#NODE_IPS[@]}"

# Overall status
echo ""
if [[ "$ALL_HEALTHY" == "true" ]] && \
   [[ "$ml0_ready_count" -eq "${#NODE_IPS[@]}" ]] && \
   [[ "$cl1_ready_count" -eq "${#NODE_IPS[@]}" ]] && \
   [[ "$dl1_ready_count" -eq "${#NODE_IPS[@]}" ]]; then
  print_success "All services are running and healthy!"
else
  print_warning "Some services are not running or not Ready"
  print_status ""
  print_status "Troubleshooting:"
  print_status "  View logs:  ./deploy/scripts/logs.sh"
  print_status "  Stop nodes: ./deploy/scripts/stop-nodes.sh"
  print_status "  Start nodes: ./deploy/scripts/06-start-nodes.sh"
fi
