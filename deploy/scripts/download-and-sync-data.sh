#!/bin/bash
# download-and-sync-data.sh
# Download data from node .23 and upload to other nodes

set -e

SOURCE_NODE="147.182.254.23"
TARGET_NODES=("146.190.151.138" "144.126.217.197")
SSH_KEY="$HOME/.ssh/do-workchain-intnet"
SSH_USER="root"

TEMP_DIR="./temp-data-backup"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$TEMP_DIR"

echo "Step 0: Backing up and removing existing data on target nodes..."

for target_ip in "${TARGET_NODES[@]}"; do
  echo ""
  echo "  Processing $target_ip..."
  ssh -i $SSH_KEY ${SSH_USER}@${target_ip} "
    # Create archived-data directory if it doesn't exist
    mkdir -p /home/root/code/metagraph-l0/archived-data

    # Move existing data to archived-data with timestamp
    if [ -d /home/root/code/metagraph-l0/data ]; then
      echo '    Moving existing data to archived-data...'
      mv /home/root/code/metagraph-l0/data /home/root/code/metagraph-l0/archived-data/data-backup-${TIMESTAMP}
    fi

    # Create fresh data directory
    mkdir -p /home/root/code/metagraph-l0/data
  "
done

echo ""
echo "Step 1: Downloading data from source node $SOURCE_NODE..."

# Download metagraph-l0 data (currency-l1 and data-l1 don't have data directories)
echo "  Downloading metagraph-l0 data..."
rsync -avz --progress \
  -e "ssh -i $SSH_KEY" \
  ${SSH_USER}@${SOURCE_NODE}:/home/root/code/metagraph-l0/data/ \
  "$TEMP_DIR/metagraph-l0-data/"

echo ""
echo "Step 2: Uploading data to target nodes..."

for target_ip in "${TARGET_NODES[@]}"; do
  echo ""
  echo "Uploading to $target_ip..."

  # Upload metagraph-l0 data
  echo "  Uploading metagraph-l0 data..."
  rsync -avz --progress \
    -e "ssh -i $SSH_KEY" \
    "$TEMP_DIR/metagraph-l0-data/" \
    ${SSH_USER}@${target_ip}:/home/root/code/metagraph-l0/data/
done

echo ""
echo "Step 3: Verifying data sync..."

for node_ip in "${TARGET_NODES[@]}"; do
  echo ""
  echo "Node $node_ip:"
  ssh -i $SSH_KEY ${SSH_USER}@${node_ip} "
    echo '  ML0 incremental snapshots:' \$(find /home/root/code/metagraph-l0/data/incremental_snapshot/hash -type f 2>/dev/null | wc -l)
    echo '  ML0 global snapshots:' \$(find /home/root/code/metagraph-l0/data/global_snapshots -type f 2>/dev/null | wc -l)
    echo '  ML0 calculated states:' \$(find /home/root/code/metagraph-l0/data/calculated_state -type f 2>/dev/null | wc -l)
  "
done

echo ""
echo "✓ Data sync complete!"
echo ""
echo "Old data backed up on each node at:"
echo "  /home/root/code/metagraph-l0/archived-data/data-backup-${TIMESTAMP}"
echo ""
echo "You can now restart the cluster:"
echo "  ./deploy/scripts/06-start-nodes.sh"