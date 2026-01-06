#!/bin/bash

# stop-nodes.sh
# Stop all metagraph services on all nodes
#
# This script:
# 1. Finds and kills all running metagraph processes
# 2. Verifies processes have been stopped
# 3. Reports status for each node

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "stop-nodes.sh" \
    "./deploy/scripts/stop-nodes.sh" \
    "Stop all metagraph services (metagraph-l0, currency-l1, data-l1) on all nodes"
  exit 0
fi

print_title "Ottochain Metagraph - Stop Nodes"

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Check required commands
require_cmd ssh

# Validate SSH key
if ! check_ssh_key; then
  exit 1
fi

# Function to stop services on a node
stop_node_services() {
  local node_ip="$1"
  local node_num="$2"

  print_status "Stopping services on Node $node_num ($node_ip)..."

  # Find and kill Java processes using jps
  local kill_script='
    for pid in $(jps -l | grep -E "metagraph-l0|currency-l1|data-l1" | awk "{print \$1}"); do
      kill -9 $pid 2>/dev/null
    done
  '

  ssh_exec "$node_ip" "$kill_script" || true

  # Wait for processes to terminate
  sleep 2

  # Verify processes are stopped using jps
  local still_running=$(ssh_exec "$node_ip" "jps -l | grep -E 'metagraph-l0|currency-l1|data-l1' | wc -l" 2>/dev/null || echo "0")

  if [[ "$still_running" == "0" ]]; then
    print_success "  All services stopped on Node $node_num"
    return 0
  else
    print_warning "  $still_running process(es) still running on Node $node_num"
    ssh_exec "$node_ip" "jps -l | grep -E 'metagraph-l0|currency-l1|data-l1'" 2>/dev/null || true
    return 1
  fi
}

# Stop services on all nodes
print_title "Stopping All Metagraph Services"

STOP_SUCCESS=true

for i in "${!NODE_IPS[@]}"; do
  if ! stop_node_services "${NODE_IPS[$i]}" "$((i+1))"; then
    STOP_SUCCESS=false
  fi
done

echo ""

if [[ "$STOP_SUCCESS" == "true" ]]; then
  print_title "All Services Stopped"
  print_success "All metagraph services have been stopped successfully!"
else
  print_title "Stop Complete with Warnings"
  print_warning "Some processes may still be running on one or more nodes"
  print_status "You can manually kill processes with:"
  print_status "  ssh <node_ip> 'jps -l | grep metagraph-l0 | awk \"{print \\\$1}\" | xargs kill -9'"
  print_status "  ssh <node_ip> 'jps -l | grep currency-l1 | awk \"{print \\\$1}\" | xargs kill -9'"
  print_status "  ssh <node_ip> 'jps -l | grep data-l1 | awk \"{print \\\$1}\" | xargs kill -9'"
fi

print_status ""
print_status "To restart the metagraph:"
print_status "  ./deploy/scripts/06-start-nodes.sh"
print_status ""
print_status "To check status:"
print_status "  ./deploy/scripts/status.sh"
