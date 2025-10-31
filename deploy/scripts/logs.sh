#!/bin/bash

# logs.sh
# View logs from metagraph services
#
# This script:
# 1. Allows viewing logs from any node/service
# 2. Supports tail -f for live log viewing
# 3. Supports viewing historical logs
# 4. Can filter logs by service type

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Default values
NODE_NUM=1
SERVICE="metagraph-l0"
FOLLOW=false
LINES=50

# Help message
show_logs_help() {
  cat << EOF
Workchain Metagraph - View Logs

USAGE:
    ./deploy/scripts/logs.sh [OPTIONS]

OPTIONS:
    -n, --node NODE_NUM       Node number (1-${#NODE_IPS[@]}, default: 1)
    -s, --service SERVICE     Service name: metagraph-l0, currency-l1, data-l1 (default: metagraph-l0)
    -f, --follow              Follow log output (like tail -f)
    -l, --lines LINES         Number of lines to show (default: 50)
    -h, --help                Show this help message

EXAMPLES:
    # View last 50 lines of metagraph-l0 on node 1
    ./deploy/scripts/logs.sh

    # Follow metagraph-l0 logs on node 1
    ./deploy/scripts/logs.sh -f

    # View currency-l1 logs on node 2
    ./deploy/scripts/logs.sh -n 2 -s currency-l1

    # View last 100 lines of data-l1 on node 3
    ./deploy/scripts/logs.sh -n 3 -s data-l1 -l 100

    # Follow data-l1 logs on node 1
    ./deploy/scripts/logs.sh -s data-l1 -f

SERVICES:
    metagraph-l0    Metagraph L0 layer
    currency-l1     Currency L1 layer
    data-l1         Data L1 layer

NOTES:
    - Logs are stored on remote nodes at:
        ~/code/metagraph-l0/metagraph-l0.log
        ~/code/currency-l1/currency-l1.log
        ~/code/data-l1/data-l1.log
    - Press Ctrl+C to stop following logs
EOF
}

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    -n|--node)
      NODE_NUM="$2"
      shift 2
      ;;
    -s|--service)
      SERVICE="$2"
      shift 2
      ;;
    -f|--follow)
      FOLLOW=true
      shift
      ;;
    -l|--lines)
      LINES="$2"
      shift 2
      ;;
    -h|--help)
      show_logs_help
      exit 0
      ;;
    *)
      print_error "Unknown option: $1"
      show_logs_help
      exit 1
      ;;
  esac
done

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Validate node number
if [[ ! "$NODE_NUM" =~ ^[0-9]+$ ]] || [[ "$NODE_NUM" -lt 1 ]] || [[ "$NODE_NUM" -gt "${#NODE_IPS[@]}" ]]; then
  print_error "Invalid node number: $NODE_NUM"
  print_status "Valid range: 1-${#NODE_IPS[@]}"
  exit 1
fi

# Validate service name
case "$SERVICE" in
  metagraph-l0|ml0|l0)
    SERVICE="metagraph-l0"
    LOG_DIR="$REMOTE_ML0_DIR"
    ;;
  currency-l1|cl1|currency)
    SERVICE="currency-l1"
    LOG_DIR="$REMOTE_CL1_DIR"
    ;;
  data-l1|dl1|data)
    SERVICE="data-l1"
    LOG_DIR="$REMOTE_DL1_DIR"
    ;;
  *)
    print_error "Invalid service: $SERVICE"
    print_status "Valid services: metagraph-l0, currency-l1, data-l1"
    exit 1
    ;;
esac

# Get node IP
NODE_IP="${NODE_IPS[$((NODE_NUM - 1))]}"
LOG_FILE="$LOG_DIR/$SERVICE.log"

# Display log info
print_title "Workchain Metagraph - Logs"
print_status "Node: Node $NODE_NUM ($NODE_IP)"
print_status "Service: $SERVICE"
print_status "Log file: $LOG_FILE"

if [[ "$FOLLOW" == "true" ]]; then
  print_status "Mode: Follow (Ctrl+C to stop)"
else
  print_status "Mode: Last $LINES lines"
fi

echo ""
print_status "=========================================="

# Check if log file exists
if ! ssh_exec "$NODE_IP" "test -f $LOG_FILE" >/dev/null 2>&1; then
  print_error "Log file not found on Node $NODE_NUM: $LOG_FILE"
  print_status "The service may not have been started yet, or the log path may be incorrect"
  exit 1
fi

# View logs
if [[ "$FOLLOW" == "true" ]]; then
  # Follow logs (tail -f)
  ssh_exec "$NODE_IP" "tail -f $LOG_FILE"
else
  # View last N lines
  ssh_exec "$NODE_IP" "tail -n $LINES $LOG_FILE"
  echo ""
  print_status "=========================================="
  print_status "Showing last $LINES lines"
  print_status "Use -f to follow logs in real-time"
  print_status "Use -l to change number of lines shown"
fi
