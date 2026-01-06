#!/bin/bash

# check-memory.sh
# Monitor memory, swap, and JVM health across all nodes

set -euo pipefail

# Load configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "check-memory.sh" \
    "./deploy/scripts/check-memory.sh" \
    "Monitor memory, swap, and JVM health across all nodes"
  exit 0
fi

print_title "Ottochain Memory Health Check"
print_status "Time: $(date)"
print_status ""

for i in "${!NODE_IPS[@]}"; do
  node_ip="${NODE_IPS[$i]}"
  node_num=$((i + 1))

  print_title "Node $node_num ($node_ip)"

  # Run memory diagnostics on remote node
  ssh $SSH_OPTS -o LogLevel=ERROR -i "$SSH_KEY_PATH" "$SSH_USER@$node_ip" bash << 'ENDSSH'
    echo "=== System Memory ==="
    free -h
    echo ""

    echo "=== Swap Usage ==="
    if [ -f /proc/swaps ]; then
      swap_total=$(free -h | grep Swap | awk '{print $2}')
      swap_used=$(free -h | grep Swap | awk '{print $3}')
      echo "Total: $swap_total | Used: $swap_used"

      # Check per-process swap
      if [ "$swap_used" != "0B" ] && [ -d /proc ]; then
        echo ""
        echo "Process swap usage:"
        for pid in $(pgrep java 2>/dev/null || true); do
          if [ -f /proc/$pid/status ]; then
            name=$(ps -p $pid -o comm= 2>/dev/null || echo "unknown")
            swap=$(grep VmSwap /proc/$pid/status 2>/dev/null | awk '{print $2}' || echo "0")
            if [ "$swap" != "0" ]; then
              echo "  $name (PID $pid): ${swap}KB"
            fi
          fi
        done
      fi
    else
      echo "No swap configured"
    fi
    echo ""

    echo "=== Java Process Memory (RSS) ==="
    ps aux | grep java | grep -v grep | awk '{printf "  %s: %dMB (%.1f%% CPU)\n", $11, $6/1024, $3}'
    echo ""

    echo "=== JVM Heap & GC Stats ==="
    for pid in $(jps -q 2>/dev/null || true); do
      name=$(jps -l 2>/dev/null | grep "^$pid " | awk '{print $2}' | awk -F'/' '{print $NF}' | sed 's/\.jar//' || echo "unknown")
      if [ -n "$name" ] && [ "$name" != "unknown" ]; then
        echo "--- $name (PID: $pid) ---"

        # Get GC stats
        jstat -gcutil $pid 1 1 2>/dev/null | tail -1 | awk '{
          printf "  Heap: Eden=%.1f%% Old=%.1f%% Meta=%.1f%%\n", $3, $4, $5
          printf "  GC: YGC=%s (%.1fs) FGC=%s (%.1fs) Total=%.1fs\n", $7, $8, $9, $10, $13
        }' || echo "  Unable to get stats"

        # Get actual heap usage
        jstat -gc $pid 1 1 2>/dev/null | tail -1 | awk '{
          heap_used = ($3 + $4 + $6 + $8) / 1024
          heap_total = ($1 + $2 + $5 + $7) / 1024
          meta_used = $10 / 1024
          printf "  Memory: Heap=%.0fMB/%.0fMB Meta=%.0fMB\n", heap_used, heap_total, meta_used
        }' || echo "  Unable to get memory"

        echo ""
      fi
    done
ENDSSH

  if [ $? -ne 0 ]; then
    print_error "Failed to connect to Node $node_num"
  fi

  echo ""
done

print_title "Summary"
print_status "To monitor over time, run: watch -n 30 ./deploy/scripts/check-memory.sh"
print_status "To check GC pressure: ./deploy/scripts/check-memory.sh | grep -A 2 'FGC='"