# Script Reference

Complete reference for all deployment scripts.

## Configuration

### deploy/config/deploy-config.sh

Main configuration file for all deployment scripts.

**Key Variables**:

```bash
# Node Configuration
NODE_IPS=(
  "146.190.151.138"  # Lead node
  "147.182.254.23"
  "144.126.217.197"
)

# Keystore Configuration
KEYSTORE_FILES=("kd5ujc-01.p12" "kd5ujc-02.p12" "kd5ujc-03.p12")
KEYSTORE_ALIAS=("kd5ujc-01" "kd5ujc-02" "kd5ujc-03")
KEYSTORE_PASSWORD=("password" "password" "password")

# Global L0 Configuration
GL0_PEER_IP="13.52.179.150"
GL0_PEER_PORT="9000"
GL0_PEER_ID="29eb8cf1da0bfaa8e02918507551f858796130a96f367a49635c80d44e849a9e..."

# Environment
CL_APP_ENV="integrationnet"
CL_COLLATERAL="0"

# Ports
ML0_PUBLIC_PORT="9100"
ML0_P2P_PORT="9101"
ML0_CLI_PORT="9102"

CL1_PUBLIC_PORT="9200"
CL1_P2P_PORT="9201"
CL1_CLI_PORT="9202"

DL1_PUBLIC_PORT="9300"
DL1_P2P_PORT="9301"
DL1_CLI_PORT="9302"

# Remote paths
REMOTE_ML0_DIR="/home/root/code/metagraph-l0"
REMOTE_CL1_DIR="/home/root/code/currency-l1"
REMOTE_DL1_DIR="/home/root/code/data-l1"
REMOTE_KEYS_DIR="/home/root/code/keys"
```

## Utility Functions

### deploy/scripts/utils.sh

Shared utility functions used by all scripts.

**Key Functions**:

- `print_status`, `print_success`, `print_error`, `print_warning` - Colored output
- `print_title` - Section headers
- `ssh_exec` - Execute command on remote node
- `scp_to_node` - Copy file to remote node
- `check_process_running` - Check if Java process is running
- `get_node_state` - Get node state from API
- `get_node_info` - Get full node info from API
- `wait_for_node_online` - Wait for node API to respond
- `wait_for_node_state` - Wait for specific node state
- `wait_for_node_ready` - Wait for Ready state
- `validate_config` - Validate configuration
- `test_ssh_connectivity` - Test SSH connection

## Setup Scripts

### 01-setup-nodes.sh

Initial node setup and configuration.

**Usage**:
```bash
./deploy/scripts/01-setup-nodes.sh
```

**What it does**:
1. Tests SSH connectivity to all nodes
2. Installs `jq` on each node
3. Creates directory structure
4. Uploads keystores to each node
5. Uploads genesis.csv
6. Downloads and uploads Tessellation tools (cl-keytool.jar, cl-wallet.jar)
7. Verifies setup completion

**Requirements**:
- SSH access to all nodes
- Keystores in `deploy/keys/`
- Genesis file at `deploy/genesis/genesis.csv`

**Output**:
- Creates `/home/root/code/{metagraph-l0,currency-l1,data-l1,keys}` on each node
- Copies keystores to all module directories
- Installs `jq` package

### 02-build-jars.sh

Builds metagraph JARs locally using SBT.

**Usage**:
```bash
./deploy/scripts/02-build-jars.sh
```

**What it does**:
1. Cleans previous builds
2. Compiles all modules
3. Assembles fat JARs:
   - `modules/l0/target/scala-2.13/metagraph-l0.jar`
   - `modules/data_l1/target/scala-2.13/currency-l1.jar`
   - `modules/data_l1/target/scala-2.13/data-l1.jar`

**Requirements**:
- SBT installed locally
- JDK 11+

**Build Time**: ~5-10 minutes depending on machine

### 03-deploy-jars.sh

Deploys compiled JARs to all nodes.

**Usage**:
```bash
./deploy/scripts/03-deploy-jars.sh
```

**What it does**:
1. Verifies JARs exist locally
2. Uploads each JAR to appropriate directory on each node:
   - `metagraph-l0.jar` → `/home/root/code/metagraph-l0/`
   - `currency-l1.jar` → `/home/root/code/currency-l1/`
   - `data-l1.jar` → `/home/root/code/data-l1/`
3. Verifies upload success

**Note**: Does NOT restart services. Run `restart-cluster.sh` after deploying.

## Cluster Management Scripts

### restart-cluster.sh

⭐ **Primary script for restarting existing clusters**

**Usage**:
```bash
./deploy/scripts/restart-cluster.sh
```

**What it does**:
1. Fetches metagraph ID from lead node's `genesis.address`
2. Stops all services (ML0, CL1, DL1) on all nodes
3. Starts Metagraph L0:
   - Node 1 in `run-rollback` mode
   - Waits for Node 1 to be Ready
   - Starts Nodes 2 & 3 in `run-validator` mode
   - Nodes 2 & 3 join cluster
   - Waits for consensus
4. Starts Currency L1:
   - Node 1 in `run-initial-validator` mode
   - Nodes 2 & 3 in `run-validator` mode
   - Validators join cluster
5. Starts Data L1:
   - Node 1 in `run-initial-validator` mode
   - Nodes 2 & 3 in `run-validator` mode
   - Validators join cluster
6. Verifies all nodes reach Ready state

**Total Time**: ~5-10 minutes

**Safe to use**: Does NOT modify data directories

### stop-nodes.sh

Stops all services without removing data.

**Usage**:
```bash
./deploy/scripts/stop-nodes.sh
```

**What it does**:
1. Finds all Java processes matching `metagraph-l0`, `currency-l1`, `data-l1`
2. Kills processes with `kill -9`
3. Verifies processes stopped

**Safe to use**: Does NOT modify data

### genesis-start.sh

⚠️ **DANGER: Creates NEW metagraph genesis**

**Usage**:
```bash
./deploy/scripts/genesis-start.sh
```

**What it does**:
1. Archives existing data to `archived-data/data_TIMESTAMP/`
2. Starts Node 1 in `run-genesis` mode
3. Waits for genesis creation
4. Starts validators and L1 layers

**WARNING**:
- Creates a NEW metagraph with NEW metagraph ID
- Archives ALL existing data
- Should ONLY be used for initial deployment
- NOT for restarting existing metagraphs

**When to use**: Never (unless creating a completely new metagraph from scratch)

## Monitoring Scripts

### status.sh

Check health and status of all nodes.

**Usage**:
```bash
./deploy/scripts/status.sh
```

**Output**:
```
==========================================
Node 1 (146.190.151.138)
==========================================

[INFO] Metagraph L0:
[SUCCESS]   Process: Running
[INFO]   State: Ready
[INFO]   Version: 3.5.0-rc.8
[INFO]   Last Snapshot: 1594
[INFO]   Endpoint: http://146.190.151.138:9100
...

==========================================
Cluster Summary
==========================================

[INFO] Metagraph L0 Cluster:
[SUCCESS]   Node 1: Ready
[SUCCESS]   Node 2: Ready
[SUCCESS]   Node 3: Ready
[INFO]   Ready nodes: 3/3
...
```

**What it checks**:
- Process status (running/not running)
- Node state via API
- Latest snapshot ordinal
- Node version
- Cluster consensus

### logs.sh

View logs from any node.

**Usage**:
```bash
./deploy/scripts/logs.sh [node_number] [layer] [lines]
```

**Parameters**:
- `node_number`: 1, 2, or 3
- `layer`: ml0, cl1, or dl1
- `lines`: Number of lines to show (default: 100)

**Examples**:
```bash
./deploy/scripts/logs.sh 1 ml0          # Last 100 lines from Node 1 ML0
./deploy/scripts/logs.sh 2 cl1 50       # Last 50 lines from Node 2 CL1
./deploy/scripts/logs.sh 3 dl1 200      # Last 200 lines from Node 3 DL1
```

**Log Locations**:
- Metagraph L0: `/home/root/code/metagraph-l0/metagraph-l0.log`
- Currency L1: `/home/root/code/currency-l1/currency-l1.log`
- Data L1: `/home/root/code/data-l1/data-l1.log`

### check-memory.sh

Monitor memory usage, swap status, and JVM health across all nodes.

**Usage**:
```bash
./deploy/scripts/check-memory.sh
```

**What it does**:
1. Connects to each node via SSH
2. Reports system memory (total, used, free, available)
3. Reports swap usage (total and per-process breakdown)
4. Shows Java process resident memory (RSS) and CPU usage
5. Displays JVM heap statistics:
   - Eden, Old Gen, Metaspace percentages
   - Young GC and Full GC counts and cumulative times
   - Actual heap usage in MB

**Examples**:
```bash
# Check all nodes once
./deploy/scripts/check-memory.sh

# Monitor continuously (refresh every 30 seconds)
watch -n 30 ./deploy/scripts/check-memory.sh

# Quick GC pressure check
./deploy/scripts/check-memory.sh | grep -E "(Node|Old=|FGC=)"

# Check swap usage only
./deploy/scripts/check-memory.sh | grep -A 2 "Swap Usage"
```

**Example output**:
```
==========================================
Node 1 (146.190.151.138)
==========================================

=== System Memory ===
               total        used        free      shared  buff/cache   available
Mem:           3.8Gi       3.2Gi       128Mi       3.0Mi       471Mi       354Mi
Swap:          2.0Gi       0.0Ki       2.0Gi

=== Swap Usage ===
Total: 2.0Gi | Used: 0.0Ki

Process swap usage:

=== Java Process Memory (RSS) ===
  java: 1103MB (10.1% CPU)
  java: 1018MB (16.1% CPU)
  java: 1029MB (15.5% CPU)

=== JVM Heap & GC Stats ===
--- metagraph-l0 (PID: 2678) ---
  Heap: Eden=25.1% Old=44.1% Meta=89.6%
  GC: YGC=21525 (135.7s) FGC=9 (2.3s) Total=138.0s
  Memory: Heap=189MB/512MB Meta=151MB

--- currency-l1 (PID: 2893) ---
  Heap: Eden=10.7% Old=70.6% Meta=90.5%
  GC: YGC=46596 (762.5s) FGC=556 (154.1s) Total=916.6s
  Memory: Heap=259MB/512MB Meta=134MB

--- data-l1 (PID: 3006) ---
  Heap: Eden=36.8% Old=47.1% Meta=90.4%
  GC: YGC=46150 (750.8s) FGC=556 (154.6s) Total=905.4s
  Memory: Heap=217MB/512MB Meta=136MB
```

**Interpreting the output**:

- **Available Memory**:
  - <500MB: System under memory pressure
  - <200MB: Critical, may OOM

- **Swap Usage**:
  - 0: Ideal, no swapping
  - <100MB: Minor swapping, acceptable
  - >500MB: Heavy swapping, performance degraded

- **Old Gen %**:
  - <70%: Healthy
  - 70-85%: Under pressure
  - >85%: Critical, frequent Full GCs likely

- **Full GC (FGC) Count**:
  - Compare across time to see rate of increase
  - Rapid increase (>100/hour) indicates memory pressure
  - ML0 should have <50 FGCs per day
  - L1s may have more but should stabilize

- **GC Time %**:
  - Calculate: (Total GC Time / Uptime) × 100
  - <1%: Excellent
  - 1-5%: Acceptable
  - >5%: Investigate memory issues

**When to use**:
- After applying JVM configuration changes
- When investigating performance issues
- To verify swap configuration
- To establish baseline metrics
- To monitor memory trends over time
- Before and after code deployments

**Requirements**:
- SSH access to all nodes
- `jps` and `jstat` available on remote nodes (included with JDK)

## Data Management Scripts

### download-and-sync-data.sh

Synchronize snapshot data from source node to target nodes.

**Usage**:
```bash
./deploy/scripts/download-and-sync-data.sh
```

**Configuration** (in the script):
```bash
SOURCE_NODE="147.182.254.23"           # Node with good data
TARGET_NODES=("146.190.151.138" "144.126.217.197")
```

**What it does**:
1. **Backup**: On each target node, moves existing data to `archived-data/data-backup-TIMESTAMP/`
2. **Download**: Downloads ML0 data from source node to local `./temp-data-backup/`
3. **Upload**: Uploads data to target nodes
4. **Verify**: Counts files in `incremental_snapshot/hash/`, `global_snapshots/`, and `calculated_state/`

**Example output**:
```
Node 146.190.151.138:
  ML0 incremental snapshots: 1234
  ML0 global snapshots: 567
  ML0 calculated states: 890
```

**Safe to use**: Backs up data before overwriting

**Note**: Only syncs ML0 data. CL1 and DL1 don't have persistent data directories.

## Helper Commands

### Check which scripts exist

```bash
ls -1 deploy/scripts/*.sh
```

### View script help

Most scripts support `--help`:

```bash
./deploy/scripts/restart-cluster.sh --help
./deploy/scripts/status.sh --help
```

### Run script from anywhere

Scripts work from any directory:

```bash
cd /tmp
/home/scas/git/ottochain/deploy/scripts/status.sh
```

## Script Dependencies

```
01-setup-nodes.sh
    ├── Requires: SSH access, keystores, genesis.csv
    └── Creates: Directory structure, installs jq

02-build-jars.sh
    ├── Requires: SBT, JDK
    └── Creates: JAR files in modules/*/target/

03-deploy-jars.sh
    ├── Requires: Built JARs from step 2
    └── Uploads: JARs to nodes

restart-cluster.sh
    ├── Requires: JARs deployed, genesis.address on nodes
    └── Starts: All services in correct order

status.sh
    ├── Requires: Cluster running
    └── Reports: Health and status

download-and-sync-data.sh
    ├── Requires: SSH access
    └── Syncs: ML0 snapshot data between nodes
```

## Common Script Patterns

### Sequential operations (must succeed)

```bash
./deploy/scripts/02-build-jars.sh && \
./deploy/scripts/03-deploy-jars.sh && \
./deploy/scripts/restart-cluster.sh
```

### Check status after operation

```bash
./deploy/scripts/restart-cluster.sh && \
./deploy/scripts/status.sh
```

### View logs after restart

```bash
./deploy/scripts/restart-cluster.sh
./deploy/scripts/logs.sh 1 ml0
```

## Troubleshooting Scripts

If a script fails:

1. **Check SSH connectivity**:
   ```bash
   ssh -i ~/.ssh/do-ottochain-intnet root@146.190.151.138 echo "OK"
   ```

2. **Check node processes**:
   ```bash
   ssh -i ~/.ssh/do-ottochain-intnet root@146.190.151.138 jps -l
   ```

3. **Check logs directly**:
   ```bash
   ssh -i ~/.ssh/do-ottochain-intnet root@146.190.151.138 \
     tail -100 /home/root/code/metagraph-l0/metagraph-l0.log
   ```

4. **Verify configuration**:
   ```bash
   cat deploy/config/deploy-config.sh
   ```