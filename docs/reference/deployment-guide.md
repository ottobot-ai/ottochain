# Deployment Guide

This guide covers deploying and managing the Workchain metagraph.

## Prerequisites

- SSH access to 3 Digital Ocean nodes
- SSH key: `~/.ssh/do-workchain-intnet`
- JDK 11+ installed locally
- SBT installed locally
- `jq`, `curl`, and `ssh` commands available

## Initial Deployment (Genesis)

⚠️ **WARNING**: Only use for creating a NEW metagraph. For existing deployments, use the restart guide below.

### Step 1: Setup Nodes

Install dependencies and create directory structure on all nodes:

```bash
./deploy/scripts/01-setup-nodes.sh
```

This script:
- Tests SSH connectivity
- Installs `jq` on all nodes
- Creates directory structure (`/home/root/code/{metagraph-l0,currency-l1,data-l1,keys}`)
- Uploads keystores to nodes
- Downloads and uploads Tessellation tools

### Step 2: Build JARs

Build the metagraph modules locally:

```bash
./deploy/scripts/02-build-jars.sh
```

This compiles:
- `metagraph-l0.jar`
- `currency-l1.jar`
- `data-l1.jar`

### Step 3: Deploy JARs

Upload compiled JARs to all nodes:

```bash
./deploy/scripts/03-deploy-jars.sh
```

### Step 4-5: Genesis Creation

⚠️ **Not documented** - The current genesis process was done manually via euclid. Use the existing genesis data.

## Restarting Existing Cluster

For an **existing metagraph**, use the restart script:

```bash
./deploy/scripts/restart-cluster.sh
```

### What the Restart Does

1. **Fetches metagraph ID** from lead node's `genesis.address` file
2. **Stops all services** on all nodes (ML0, CL1, DL1)
3. **Starts Metagraph L0**:
   - Node 1 (lead): Starts in `run-rollback` mode
   - Waits for Node 1 to reach `Ready` state
   - Nodes 2 & 3: Start in `run-validator` mode
   - Validators join the cluster
   - Wait for consensus (all nodes `Ready`)
4. **Starts Currency L1**:
   - Node 1: `run-initial-validator` mode
   - Nodes 2 & 3: `run-validator` mode
   - Validators join the cluster
5. **Starts Data L1**:
   - Node 1: `run-initial-validator` mode
   - Nodes 2 & 3: `run-validator` mode
   - Validators join the cluster

### Key Differences from Genesis

- Uses existing snapshot data (no data loss)
- Fetches metagraph ID dynamically from nodes
- Lead node uses `run-rollback` instead of `run-genesis`
- All nodes have `CL_L0_TOKEN_IDENTIFIER` set from the start

## Data Synchronization

If nodes have different snapshot data, sync from the source node:

```bash
./deploy/scripts/download-and-sync-data.sh
```

This script:
1. **Backs up** existing data on target nodes to `archived-data/data-backup-TIMESTAMP`
2. **Downloads** data from source node (147.182.254.23) to local machine
3. **Uploads** synced data to target nodes (146.190.151.138 and 144.126.217.197)
4. **Verifies** sync with file counts

⚠️ **Note**: Only ML0 data is synced. Currency-L1 and Data-L1 don't have persistent data directories.

## Checking Status

View cluster health:

```bash
./deploy/scripts/status.sh
```

Outputs:
- Process status (running/not running)
- Node state (Ready, Observing, etc.)
- Latest snapshot ordinal
- Cluster consensus status

## Viewing Logs

View logs from any node:

```bash
./deploy/scripts/logs.sh <node_number> <layer>
```

Examples:
```bash
./deploy/scripts/logs.sh 1 ml0      # Node 1 metagraph-l0 logs
./deploy/scripts/logs.sh 2 cl1      # Node 2 currency-l1 logs
./deploy/scripts/logs.sh 3 dl1      # Node 3 data-l1 logs
```

## Stopping the Cluster

Stop all services without deleting data:

```bash
./deploy/scripts/stop-nodes.sh
```

## Important Concepts

### Metagraph ID

- Stored on each node: `/home/root/code/metagraph-l0/genesis.address`
- Example: `DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG`
- Used as `CL_L0_TOKEN_IDENTIFIER` for all L1 layers
- Dynamically fetched by restart script (no local file needed)

### Node Roles

**Lead Node (Node 1 - 146.190.151.138)**:
- Starts first in `run-rollback` mode
- Must reach `Ready` before validators start
- L1 layers use `run-initial-validator`

**Validator Nodes (Nodes 2 & 3)**:
- Start after lead node is Ready
- Use `run-validator` mode
- Join cluster via API call to lead node
- Each needs its own ML0 peer ID for L1 layers

### Environment Variables

All layers require:
- `CL_KEYSTORE`, `CL_KEYALIAS`, `CL_PASSWORD` - Keystore authentication
- `CL_APP_ENV` - Environment (integrationnet/testnet/mainnet)
- `CL_COLLATERAL` - Staking amount

**Metagraph L0** specific:
- `CL_GLOBAL_L0_PEER_*` - Global L0 network connection
- `CL_L0_TOKEN_IDENTIFIER` - Metagraph ID

**L1 layers** (Currency & Data) specific:
- `CL_L0_PEER_ID` - Their own node's ML0 peer ID
- `CL_L0_PEER_HTTP_HOST` - Their own node's IP
- `CL_L0_PEER_HTTP_PORT` - ML0 port (9100)
- `CL_GLOBAL_L0_PEER_*` - Global L0 network connection
- `CL_L0_TOKEN_IDENTIFIER` - Metagraph ID

## Best Practices

1. **Always use restart-cluster.sh** for existing deployments
2. **Check status** before and after operations
3. **Back up data** before major changes (sync script does this automatically)
4. **Monitor logs** during startup for errors
5. **Wait for consensus** - don't interrupt the startup process
6. **Keep local files minimal** - fetch metagraph ID from nodes, not local files

## Common Workflows

### Update Code and Redeploy

```bash
# Build new JARs
./deploy/scripts/02-build-jars.sh

# Stop cluster
./deploy/scripts/stop-nodes.sh

# Deploy new JARs
./deploy/scripts/03-deploy-jars.sh

# Restart with new code
./deploy/scripts/restart-cluster.sh

# Verify
./deploy/scripts/status.sh
```

### Sync Data from Source Node

```bash
# Stop cluster
./deploy/scripts/stop-nodes.sh

# Sync data (backs up old data automatically)
./deploy/scripts/download-and-sync-data.sh

# Restart with synced data
./deploy/scripts/restart-cluster.sh

# Verify
./deploy/scripts/status.sh
```

### Troubleshoot Node Issues

```bash
# Check status
./deploy/scripts/status.sh

# View logs
./deploy/scripts/logs.sh 1 ml0
./deploy/scripts/logs.sh 2 cl1

# SSH to node directly
ssh -i ~/.ssh/do-workchain-intnet root@146.190.151.138

# Check processes
jps -l

# View logs directly
tail -100 /home/root/code/metagraph-l0/metagraph-l0.log
```