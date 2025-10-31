# Workchain Metagraph Deployment

✅ **STATUS: FUNCTIONAL - RESTART SUPPORT ADDED** ✅

This directory contains bash scripts for deploying the workchain metagraph to Digital Ocean droplets and connecting to Tessellation integrationnet.

## ✅ Current Capabilities

**What Works:**
- ✅ Building JARs (`02-build-jars.sh`)
- ✅ Deploying JARs to nodes (`03-deploy-jars.sh`)
- ✅ Creating genesis snapshot (`04-create-genesis.sh`)
- ✅ Creating multi-signed owner messages (`05-create-owner-message.sh`)
- ✅ **Starting nodes with genesis/rollback detection (`06-start-nodes.sh`)**
- ✅ **Restarting entire cluster (stop + start)**
- ✅ Stopping services (`stop-nodes.sh`)
- ✅ Checking status (`status.sh`)
- ✅ Viewing logs (`logs.sh`)

## 🆕 Recent Improvements

The deployment system now properly supports:

1. **Smart Genesis/Rollback Detection**: Automatically detects if data exists and chooses the appropriate startup mode
2. **Proper Genesis Sequence**: `run-genesis` → wait → kill → `run-rollback` for fresh starts
3. **Direct Rollback**: Skips genesis and starts directly in `run-rollback` when data exists
4. **Owner Message via CLI**: Passes owner message as CLI argument during genesis (not deprecated HTTP POST)
5. **ReadyToJoin State Handling**: Validators wait for correct state before joining clusters

## Overview

The deployment system uses pure bash scripts (no Terraform/Ansible) to:

- Build metagraph modules (metagraph-l0, currency-l1, data-l1)
- Deploy JARs to remote nodes
- Create genesis snapshot
- Generate multi-signed owner message
- Start and manage metagraph services

## Prerequisites

### Local Machine Requirements

- **bash** - Shell for running scripts
- **sbt** - Scala Build Tool for building JARs
- **ssh/scp** - SSH client for remote access
- **curl** - For downloading Tessellation tools and checking node health
- **jq** - JSON processor for working with owner messages
- **java** - JDK 11 or higher (for creating owner message locally)

### Remote Node Requirements

- **3 Digital Ocean droplets** (or any Linux servers)
- **SSH access** with key-based authentication
- **Java 11+** installed on all nodes
- **Sufficient disk space** (~10GB recommended per node)
- **Open ports**: 9000-9302 for metagraph services

### Network Configuration

The deployment connects to **integrationnet** Global L0:
- **GL0 Peer IP**: 52.9.216.57
- **GL0 Peer Port**: 9000
- **GL0 Peer ID**: (configured in deploy-config.sh)

## Quick Start

### 1. Configure Node IPs

Edit `deploy/config/deploy-config.sh` and update the `NODE_IPS` array:

```bash
NODE_IPS=(
  "your.node1.ip.address"
  "your.node2.ip.address"
  "your.node3.ip.address"
)
```

Also update SSH configuration if needed:

```bash
SSH_USER="root"  # or your user
SSH_KEY_PATH="$HOME/.ssh/id_rsa"  # path to your SSH key
```

### 2. Run Deployment Scripts in Order

```bash
# 1. Setup nodes (create directories, upload keystores, genesis.csv)
./deploy/scripts/01-setup-nodes.sh

# 2. Build JARs locally
./deploy/scripts/02-build-jars.sh

# 3. Deploy JARs to all nodes
./deploy/scripts/03-deploy-jars.sh

# 4. Create genesis snapshot on first node
./deploy/scripts/04-create-genesis.sh

# 5. Create multi-signed owner message
./deploy/scripts/05-create-owner-message.sh

# 6. Start all metagraph services
./deploy/scripts/06-start-nodes.sh
```

### 3. Verify Deployment

```bash
# Check status of all nodes
./deploy/scripts/status.sh

# View logs from a specific node/service
./deploy/scripts/logs.sh -n 1 -s metagraph-l0 -f
```

## Directory Structure

```
deploy/
├── README.md                       # This file
├── config/
│   └── deploy-config.sh           # Configuration (node IPs, SSH, network, etc.)
├── genesis/
│   └── genesis.csv                # Genesis balances (from euclid)
├── keys/
│   ├── kd5ujc-01.p12              # Node 1 keystore (from euclid)
│   ├── kd5ujc-02.p12              # Node 2 keystore (from euclid)
│   └── kd5ujc-03.p12              # Node 3 keystore (from euclid)
├── jars/
│   ├── cl-keytool.jar             # Downloaded by scripts
│   └── cl-wallet.jar              # Downloaded by scripts
├── scripts/
│   ├── 01-setup-nodes.sh          # Setup directory structure and keystores
│   ├── 02-build-jars.sh           # Build metagraph JARs
│   ├── 03-deploy-jars.sh          # Deploy JARs to nodes
│   ├── 04-create-genesis.sh       # Create genesis snapshot
│   ├── 05-create-owner-message.sh # Create owner message
│   ├── 06-start-nodes.sh          # Start all services
│   ├── stop-nodes.sh              # Stop all services
│   ├── status.sh                  # Check service status
│   ├── logs.sh                    # View logs
│   └── utils.sh                   # Shared utility functions
├── owner-message.json             # Generated owner message
└── metagraph-id.txt               # Generated metagraph ID
```

## Detailed Script Documentation

### 01-setup-nodes.sh

**Purpose**: Prepare all nodes for deployment

**What it does**:
- Tests SSH connectivity to all nodes
- Creates directory structure: `~/code/{metagraph-l0,currency-l1,data-l1,keys}`
- Uploads keystores to each node (using existing euclid p12 files)
- Uploads genesis.csv to metagraph-l0 directory
- Downloads and uploads Tessellation tools (cl-keytool.jar, cl-wallet.jar)
- Verifies all files are in place

**When to run**: Once before initial deployment

**Usage**:
```bash
./deploy/scripts/01-setup-nodes.sh
```

### 02-build-jars.sh

**Purpose**: Build all metagraph modules locally

**What it does**:
- Builds currencyL0 module using `sbt currencyL0/assembly`
- Builds currencyL1 module using `sbt currencyL1/assembly`
- Builds dataL1 module using `sbt dataL1/assembly`
- Locates built JARs in target directories
- Calculates SHA256 hashes for verification
- Reports build summary

**When to run**: After code changes, before deploying

**Usage**:
```bash
./deploy/scripts/02-build-jars.sh
```

### 03-deploy-jars.sh

**Purpose**: Deploy built JARs to all nodes

**What it does**:
- Finds JAR files built by 02-build-jars.sh
- SCPs JARs to all nodes with standardized names:
  - `metagraph-l0.jar` → `~/code/metagraph-l0/`
  - `currency-l1.jar` → `~/code/currency-l1/`
  - `data-l1.jar` → `~/code/data-l1/`
- Verifies deployment using SHA256 hash comparison
- Reports deployment status

**When to run**: After building JARs, before creating genesis

**Usage**:
```bash
./deploy/scripts/03-deploy-jars.sh
```

### 04-create-genesis.sh

**Purpose**: Create genesis snapshot on the first node

**What it does**:
- Connects to first node via SSH
- Runs `java -jar metagraph-l0.jar create-genesis genesis.csv` remotely
- Sets appropriate environment variables (CL_APP_ENV=integrationnet, etc.)
- Verifies genesis.snapshot and genesis.address were created
- Copies genesis files to other nodes
- Saves metagraph ID locally

**When to run**: Once after deploying JARs, before creating owner message

**Usage**:
```bash
./deploy/scripts/04-create-genesis.sh
```

### 05-create-owner-message.sh

**Purpose**: Create multi-signed owner message for genesis

**What it does**:
- Downloads Tessellation tools if not present
- Gets owner address from first keystore
- Retrieves metagraph ID from node or local file
- Creates owner signing message with ALL 3 keystores (multi-sig)
- Combines all signatures into single JSON owner message
- Saves owner-message.json locally
- Uploads owner message to first node

**When to run**: Once after creating genesis, before starting nodes

**Usage**:
```bash
./deploy/scripts/05-create-owner-message.sh
```

### 06-start-nodes.sh

**Purpose**: Start all metagraph services in the correct order

**What it does**:
1. Starts node 1 metagraph-l0 in `run-genesis` mode with owner message
2. Waits for node 1 metagraph-l0 to be Ready
3. Starts nodes 2 & 3 metagraph-l0 as validators
4. Waits for all metagraph-l0 nodes to be Ready
5. Starts currency-l1 on all nodes
6. Waits for currency-l1 nodes to be Ready
7. Starts data-l1 on all nodes
8. Verifies all services are running

**When to run**: After creating owner message, to start the metagraph

**Usage**:
```bash
./deploy/scripts/06-start-nodes.sh
```

### stop-nodes.sh

**Purpose**: Stop all metagraph services

**What it does**:
- Kills all java processes running metagraph JARs on each node
- Verifies processes have stopped
- Reports status

**When to run**: To stop the metagraph for maintenance or updates

**Usage**:
```bash
./deploy/scripts/stop-nodes.sh
```

### status.sh

**Purpose**: Check health status of all services

**What it does**:
- Checks process status on each node
- Queries HTTP health endpoints (/node/info)
- Displays node state (Ready, Observing, etc.)
- Shows version and snapshot ordinal information
- Reports cluster-wide health summary

**When to run**: Anytime to check if metagraph is healthy

**Usage**:
```bash
./deploy/scripts/status.sh
```

### logs.sh

**Purpose**: View logs from any service on any node

**What it does**:
- Supports viewing logs from metagraph-l0, currency-l1, or data-l1
- Can tail logs in real-time (-f flag)
- Can view last N lines (default 50)
- Allows selecting specific node and service

**When to run**: For debugging or monitoring

**Usage**:
```bash
# View last 50 lines of metagraph-l0 on node 1
./deploy/scripts/logs.sh

# Follow metagraph-l0 logs on node 1
./deploy/scripts/logs.sh -f

# View currency-l1 logs on node 2
./deploy/scripts/logs.sh -n 2 -s currency-l1

# View last 100 lines of data-l1 on node 3
./deploy/scripts/logs.sh -n 3 -s data-l1 -l 100
```

## Configuration Details

### Port Configuration

The default port configuration (defined in `deploy-config.sh`):

| Service | Public Port | P2P Port | CLI Port |
|---------|-------------|----------|----------|
| Metagraph L0 | 9100 | 9101 | 9102 |
| Currency L1 | 9200 | 9201 | 9202 |
| Data L1 | 9300 | 9301 | 9302 |

### Keystore Configuration

Uses existing keystores from euclid configuration:

| Node | Keystore File | Alias | Password |
|------|---------------|-------|----------|
| Node 1 | kd5ujc-01.p12 | kd5ujc-01 | Refusing-Avenge8-Repose-Garnish |
| Node 2 | kd5ujc-02.p12 | kd5ujc-02 | Italicize6-Drippy-Stool-Trash |
| Node 3 | kd5ujc-03.p12 | kd5ujc-03 | Huntress-Gossip6-Deflected-Jitters |

**Owner wallet**: Uses first keystore (kd5ujc-01.p12)

### Genesis Configuration

Genesis CSV (`genesis.csv`) contains initial token distribution:

```csv
DAG3oEyfPqnQeARHox8Bfh4hxC7n6ZaFyf5BMUXe,1000000000000
DAG8mwjvqDzxqDTpcDuAs8f9B9M9jADo3R9Lm6ip,1000000000000
DAG04s6k9heWUGFLqsGF4i7sKYUGNBbpaiBiTP6m,1000000000000
DAG2pw1tWFSsjgjciVaaXVsfgGxmS6yB7rPjzxS6,1000000000000
```

## Deployment Workflow

### Initial Deployment

```
01-setup-nodes.sh
       ↓
02-build-jars.sh
       ↓
03-deploy-jars.sh
       ↓
04-create-genesis.sh
       ↓
05-create-owner-message.sh
       ↓
06-start-nodes.sh
       ↓
    status.sh (verify)
```

### Updating Code

```
stop-nodes.sh
       ↓
02-build-jars.sh
       ↓
03-deploy-jars.sh
       ↓
06-start-nodes.sh
       ↓
    status.sh (verify)
```

### Restarting Metagraph

The system now supports proper restart functionality:

```bash
# Simple restart (preserves data)
./deploy/scripts/stop-nodes.sh
./deploy/scripts/06-start-nodes.sh
./deploy/scripts/status.sh

# Force fresh genesis (archives old data)
./deploy/scripts/stop-nodes.sh
FORCE_GENESIS=true ./deploy/scripts/06-start-nodes.sh
```

**How it works:**
- Script checks for existing `data/incremental_snapshot` on node 1
- **If data exists** → Starts directly in `run-rollback` mode (fast restart)
- **If no data** → Runs full genesis sequence (genesis → kill → rollback)
- **If FORCE_GENESIS=true** → Archives old data and runs fresh genesis

## Troubleshooting

### Issue: SSH Connection Failed

**Symptoms**: Cannot connect to nodes

**Solutions**:
- Verify node IPs are correct in `deploy-config.sh`
- Check SSH key path is correct
- Verify SSH key permissions: `chmod 600 ~/.ssh/your-key`
- Test manual SSH: `ssh -i ~/.ssh/your-key user@node-ip`

### Issue: Genesis Creation Failed

**Symptoms**: genesis.snapshot or genesis.address not created

**Solutions**:
- Check logs: `./deploy/scripts/logs.sh -n 1 -s metagraph-l0`
- Verify JAR was deployed: `ssh node1 'ls -la ~/code/metagraph-l0/'`
- Check GL0 peer connectivity: `curl http://52.9.216.57:9000/cluster/info`

### Issue: Nodes Not Becoming Ready

**Symptoms**: Nodes stuck in Observing or other states

**Solutions**:
- Check logs for errors: `./deploy/scripts/logs.sh -f`
- Verify owner message was uploaded: `ssh node1 'cat ~/code/metagraph-l0/owner-message.json'`
- Ensure genesis files exist on all nodes
- Check network connectivity between nodes

### Issue: Process Crashes on Startup

**Symptoms**: Process starts but immediately stops

**Solutions**:
- Check logs: `./deploy/scripts/logs.sh -n <node> -s <service>`
- Verify JVM memory settings in `deploy-config.sh` (JVM_OPTS)
- Check disk space on nodes: `ssh node 'df -h'`
- Verify Java version: `ssh node 'java -version'`

### Common Error Messages

**"Hash mismatch for <jar>"**
- JAR file corrupted during transfer
- Solution: Re-run `03-deploy-jars.sh`

**"Failed to connect to node"**
- SSH connectivity issue
- Solution: Check SSH configuration and network

**"genesis.address not found"**
- Genesis not created yet
- Solution: Run `04-create-genesis.sh`

**"Node did not become Ready"**
- Node startup issue
- Solution: Check logs and verify configuration

## Service Endpoints

Once deployed, services are accessible at:

### Node 1
- Metagraph L0: `http://<node1-ip>:9100/node/info`
- Currency L1: `http://<node1-ip>:9200/node/info`
- Data L1: `http://<node1-ip>:9300/node/info`

### Node 2
- Metagraph L0: `http://<node2-ip>:9100/node/info`
- Currency L1: `http://<node2-ip>:9200/node/info`
- Data L1: `http://<node2-ip>:9300/node/info`

### Node 3
- Metagraph L0: `http://<node3-ip>:9100/node/info`
- Currency L1: `http://<node3-ip>:9200/node/info`
- Data L1: `http://<node3-ip>:9300/node/info`

## Advanced Usage

### Viewing Cluster Info

```bash
# Check metagraph-l0 cluster
curl http://<node-ip>:9100/cluster/info | jq

# Check currency-l1 cluster
curl http://<node-ip>:9200/cluster/info | jq

# Check data-l1 cluster
curl http://<node-ip>:9300/cluster/info | jq
```

### Manually Restarting a Single Service

```bash
# SSH into the node
ssh -i ~/.ssh/your-key user@node-ip

# Stop the service
pkill -f metagraph-l0.jar

# Restart the service manually (copy command from 06-start-nodes.sh)
cd ~/code/metagraph-l0
CL_PUBLIC_HTTP_PORT=9100 ... java -jar metagraph-l0.jar run-validator ...
```

### Changing Configuration

1. Edit `deploy/config/deploy-config.sh`
2. Stop nodes: `./deploy/scripts/stop-nodes.sh`
3. Re-run affected scripts
4. Start nodes: `./deploy/scripts/06-start-nodes.sh`

## Security Considerations

- **SSH Keys**: Keep private keys secure, use appropriate permissions (600)
- **Keystore Passwords**: Currently hardcoded in config, consider using secrets management
- **Network Security**: Ensure only necessary ports are open on Digital Ocean firewall
- **Owner Key**: The owner keystore (kd5ujc-01.p12) has special privileges

## Support

For issues or questions:
- Check logs: `./deploy/scripts/logs.sh -f`
- Check status: `./deploy/scripts/status.sh`
- Review configuration: `cat deploy/config/deploy-config.sh`
- Consult Tessellation documentation

## Next Steps

After successful deployment:
1. Verify all nodes are in Ready state
2. Test token transfers
3. Monitor snapshot creation
4. Set up monitoring/alerting (optional)
5. Configure automatic restarts (optional)
6. Backup keystores and configuration
