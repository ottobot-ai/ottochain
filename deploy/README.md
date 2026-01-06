# Ottochain Metagraph Deployment

Bash scripts for deploying and managing the Ottochain metagraph on Digital Ocean droplets, connected to Tessellation integrationnet.

## Quick Start

### For Existing Deployments (Restart)

```bash
# Restart the entire cluster (preserves data)
./deploy/scripts/restart-cluster.sh

# Check cluster status
./deploy/scripts/status.sh

# View logs
./deploy/scripts/logs.sh -n 1 -s metagraph-l0 -f
```

### For Fresh Deployments

```bash
# 1. Configure nodes in deploy/config/deploy-config.sh
# 2. Setup nodes
./deploy/scripts/01-setup-nodes.sh

# 3. Build JARs
./deploy/scripts/02-build-jars.sh

# 4. Deploy JARs
./deploy/scripts/03-deploy-jars.sh

# 5. Create genesis
./deploy/scripts/04-create-genesis.sh

# 6. Create owner message
./deploy/scripts/05-create-owner-message.sh

# 7. Start cluster (genesis mode)
./deploy/scripts/genesis-start.sh

# 8. Verify
./deploy/scripts/status.sh
```

## Available Scripts

### Core Deployment Scripts

| Script | Purpose | When to Use |
|--------|---------|-------------|
| `01-setup-nodes.sh` | Setup nodes (directories, keystores, genesis.csv) | Once, initial setup |
| `02-build-jars.sh` | Build metagraph JARs locally | After code changes |
| `03-deploy-jars.sh` | Deploy JARs to all nodes | After building |
| `04-create-genesis.sh` | Create genesis snapshot | Once, initial deployment |
| `05-create-owner-message.sh` | Generate multi-signed owner message | Once, after genesis |

### Cluster Management Scripts

| Script | Purpose | When to Use |
|--------|---------|-------------|
| `genesis-start.sh` | Start cluster in genesis mode | First-time deployment |
| `restart-cluster.sh` | Stop and restart entire cluster | Regular restarts, after updates |
| `stop-nodes.sh` | Stop all services | Manual shutdown |
| `status.sh` | Check cluster health | Anytime |
| `logs.sh` | View node logs | Debugging, monitoring |

### Utility Scripts

| Script | Purpose |
|--------|---------|
| `check-memory.sh` | Monitor memory, swap, and JVM health across all nodes |
| `download-and-sync-data.sh` | Download and sync data between nodes |
| `utils.sh` | Shared utility functions |

## Directory Structure

```
deploy/
├── README.md                       # This file
├── config/
│   └── deploy-config.sh           # Configuration (IPs, SSH, ports, etc.)
├── keys/
│   ├── kd5ujc-01.p12              # Node 1 keystore
│   ├── kd5ujc-02.p12              # Node 2 keystore
│   └── kd5ujc-03.p12              # Node 3 keystore
├── jars/
│   ├── cl-keytool.jar             # Tessellation keytool
│   └── cl-wallet.jar              # Tessellation wallet
├── scripts/
│   ├── 01-setup-nodes.sh          # Initial node setup
│   ├── 02-build-jars.sh           # Build metagraph modules
│   ├── 03-deploy-jars.sh          # Deploy JARs to nodes
│   ├── 04-create-genesis.sh       # Create genesis snapshot
│   ├── 05-create-owner-message.sh # Generate owner message
│   ├── genesis-start.sh           # First-time cluster start
│   ├── restart-cluster.sh         # Restart existing cluster
│   ├── stop-nodes.sh              # Stop all services
│   ├── status.sh                  # Check health
│   ├── logs.sh                    # View logs
│   ├── check-memory.sh            # Monitor memory and JVM health
│   ├── download-and-sync-data.sh  # Data sync utility
│   └── utils.sh                   # Shared functions
├── owner-message.json             # Generated owner message
└── metagraph-id.txt               # Metagraph identifier
```

## Configuration

Edit `deploy/config/deploy-config.sh` to configure:

### Node IPs
```bash
NODE_IPS=(
  "146.190.151.138"  # Node 1
  "147.182.254.23"   # Node 2
  "144.126.217.197"  # Node 3
)
```

### SSH Configuration
```bash
SSH_USER="root"
SSH_KEY_PATH="$HOME/.ssh/id_rsa"
```

### Port Configuration

| Service | Public Port | P2P Port | CLI Port |
|---------|-------------|----------|----------|
| Metagraph L0 | 9100 | 9101 | 9102 |
| Currency L1 | 9200 | 9201 | 9202 |
| Data L1 | 9300 | 9301 | 9302 |

### Global L0 Configuration (integrationnet)
```bash
GL0_PEER_IP="52.9.216.57"
GL0_PEER_PORT="9000"
GL0_PEER_ID="<peer-id-in-config>"
```

## Deployment Workflows

### Initial Deployment (First Time)

```
┌─────────────────────────┐
│ 01-setup-nodes.sh       │  Setup directories, upload keystores
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ 02-build-jars.sh        │  Build metagraph modules
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ 03-deploy-jars.sh       │  Upload JARs to nodes
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ 04-create-genesis.sh    │  Create genesis snapshot
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ 05-create-owner-msg.sh  │  Generate multi-sig owner message
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ genesis-start.sh        │  Start cluster in genesis mode
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ status.sh               │  Verify all nodes Ready
└─────────────────────────┘
```

### Code Updates

```
┌─────────────────────────┐
│ stop-nodes.sh           │  Stop all services
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ 02-build-jars.sh        │  Build updated code
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ 03-deploy-jars.sh       │  Deploy new JARs
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ restart-cluster.sh      │  Restart with rollback mode
└────────────┬────────────┘
             │
┌────────────▼────────────┐
│ status.sh               │  Verify health
└─────────────────────────┘
```

### Regular Restart

```bash
# Simple restart (preserves all data)
./deploy/scripts/restart-cluster.sh

# Verify
./deploy/scripts/status.sh
```

## Script Details

### restart-cluster.sh

**Most commonly used script** for managing existing deployments.

**What it does:**
- Stops all services gracefully
- Detects if data exists (smart genesis/rollback selection)
- Starts metagraph-l0, currency-l1, data-l1 in correct order
- Waits for each layer to be Ready before starting next
- Verifies cluster health

**Restart Modes:**
```bash
# Normal restart (preserves data, uses rollback)
./scripts/restart-cluster.sh

# Force fresh genesis (archives old data)
FORCE_GENESIS=true ./scripts/restart-cluster.sh
```

**Smart startup logic:**
- Checks for `data/incremental_snapshot` on node 1
- **Data exists** → Direct rollback mode (fast)
- **No data** → Full genesis sequence (genesis → kill → rollback)
- **FORCE_GENESIS=true** → Archive old data, run fresh genesis

### genesis-start.sh

**First-time cluster startup** after creating genesis.

**What it does:**
- Starts node 1 metagraph-l0 in `run-genesis` mode with owner message
- Kills genesis process after Ready state
- Restarts node 1 in `run-rollback` mode
- Starts nodes 2 & 3 as validators
- Starts currency-l1 on all nodes
- Starts data-l1 on all nodes

**When to use:** Only for initial deployment after running `05-create-owner-message.sh`

### status.sh

Check cluster health and status.

**Example output:**
```
=== Node 1 (146.190.151.138) ===
Metagraph-L0: ✓ Running (Ready, ordinal: 1234)
Currency-L1:  ✓ Running (Ready, ordinal: 5678)
Data-L1:      ✓ Running (Ready, ordinal: 5678)
```

**Options:**
```bash
./scripts/status.sh           # Check all nodes
./scripts/status.sh -n 1      # Check only node 1
```

### logs.sh

View logs from any service on any node.

**Usage:**
```bash
# View last 50 lines of metagraph-l0 on node 1
./scripts/logs.sh

# Follow metagraph-l0 logs in real-time
./scripts/logs.sh -f

# View currency-l1 logs on node 2
./scripts/logs.sh -n 2 -s currency-l1

# View last 200 lines of data-l1 on node 3
./scripts/logs.sh -n 3 -s data-l1 -l 200

# Follow data-l1 logs on node 1
./scripts/logs.sh -s data-l1 -f
```

**Options:**
- `-n <1|2|3>` - Node number (default: 1)
- `-s <service>` - Service: `metagraph-l0`, `currency-l1`, `data-l1` (default: metagraph-l0)
- `-l <N>` - Number of lines (default: 50)
- `-f` - Follow mode (tail -f)

### stop-nodes.sh

Stop all metagraph services on all nodes.

**What it does:**
- Kills all java processes running metagraph JARs
- Verifies processes stopped
- Reports status

**Usage:**
```bash
./scripts/stop-nodes.sh
```

### check-memory.sh

Monitor memory usage, swap status, and JVM health across all nodes.

**What it does:**
- Checks system memory (total, used, available)
- Reports swap usage (total and per-process)
- Shows Java process RSS (resident memory)
- Displays JVM heap statistics (Eden, Old Gen, Metaspace)
- Reports GC activity (Young GC and Full GC counts/times)

**Usage:**
```bash
# Check all nodes once
./scripts/check-memory.sh

# Monitor continuously (refresh every 30 seconds)
watch -n 30 ./scripts/check-memory.sh

# Quick check for GC pressure
./scripts/check-memory.sh | grep -E "(Node|Old=|FGC=)"
```

**Example output:**
```
=== Node 1 (146.190.151.138) ===

=== System Memory ===
               total        used        free      shared  buff/cache   available
Mem:           3.8Gi       3.2Gi       128Mi       3.0Mi       471Mi       354Mi
Swap:          2.0Gi       0.0Ki       2.0Gi

=== Java Process Memory (RSS) ===
  java: 1103MB (10.1% CPU)
  java: 1018MB (16.1% CPU)
  java: 1029MB (15.5% CPU)

=== JVM Heap & GC Stats ===
--- metagraph-l0 (PID: 2678) ---
  Heap: Eden=25.1% Old=44.1% Meta=89.6%
  GC: YGC=21525 (135.7s) FGC=9 (2.3s) Total=138.0s
  Memory: Heap=189MB/512MB Meta=151MB
```

**What to look for:**
- **Available memory <500MB** - System under memory pressure
- **Swap usage >0** - Memory spilling to disk (performance impact)
- **Old Gen >80%** - Heap nearing capacity
- **High FGC count** - Excessive Full GCs indicate memory pressure
- **GC time %** - If total GC time is >5% of uptime, investigate

**When to use:**
- After deploying new JARs with different JVM settings
- When investigating performance issues
- To verify swap configuration
- To monitor memory trends over time

## Service Endpoints

Once deployed, services are accessible at:

### Node 1 (146.190.151.138)
- Metagraph L0: http://146.190.151.138:9100/node/info
- Currency L1: http://146.190.151.138:9200/node/info
- Data L1: http://146.190.151.138:9300/node/info

### Node 2 (147.182.254.23)
- Metagraph L0: http://147.182.254.23:9100/node/info
- Currency L1: http://147.182.254.23:9200/node/info
- Data L1: http://147.182.254.23:9300/node/info

### Node 3 (144.126.217.197)
- Metagraph L0: http://144.126.217.197:9100/node/info
- Currency L1: http://144.126.217.197:9200/node/info
- Data L1: http://144.126.217.197:9300/node/info

## Prerequisites

### Local Machine
- bash
- sbt (Scala Build Tool)
- ssh/scp
- curl
- jq
- java 11+

### Remote Nodes
- 3 Linux servers (Digital Ocean droplets)
- SSH access with key-based authentication
- Java 11+ installed
- Disk space: ~10GB per node
- Open ports: 9100-9302

## Troubleshooting

### Cluster Won't Start

**Check logs:**
```bash
./scripts/logs.sh -f
```

**Common issues:**
- Owner message not uploaded → Re-run `05-create-owner-message.sh`
- Genesis files missing → Re-run `04-create-genesis.sh`
- Network connectivity → Check firewall, node IPs

### Nodes Stuck in Observing State

**Solutions:**
- Check logs for errors
- Verify genesis files exist on all nodes
- Ensure owner message is valid
- Check GL0 peer connectivity

### JAR Deployment Failed

**Hash mismatch error:**
```bash
# Re-deploy JARs
./scripts/03-deploy-jars.sh
```

**Build failed:**
```bash
# Clean and rebuild
cd /path/to/ottochain
sbt clean
./deploy/scripts/02-build-jars.sh
```

### SSH Connection Failed

**Verify SSH:**
```bash
# Test manual connection
ssh -i ~/.ssh/id_rsa root@146.190.151.138

# Check key permissions
chmod 600 ~/.ssh/id_rsa
```

## Advanced Usage

### View Cluster Info

```bash
# Check metagraph-l0 cluster
curl http://146.190.151.138:9100/cluster/info | jq

# Check currency-l1 cluster
curl http://146.190.151.138:9200/cluster/info | jq

# Check data-l1 cluster
curl http://146.190.151.138:9300/cluster/info | jq
```

### Manual Service Restart

```bash
# SSH to node
ssh root@146.190.151.138

# Stop service
pkill -f metagraph-l0.jar

# Restart (copy command from restart-cluster.sh)
cd ~/code/metagraph-l0
CL_PUBLIC_HTTP_PORT=9100 ... java -jar metagraph-l0.jar run-rollback ...
```

### Data Sync Between Nodes

```bash
# Download and sync data
./scripts/download-and-sync-data.sh
```

## Security Notes

- **SSH Keys:** Keep private keys secure (chmod 600)
- **Keystore Passwords:** Stored in config, consider secrets management
- **Network Security:** Configure Digital Ocean firewall for ports 9100-9302
- **Owner Keystore:** First keystore (kd5ujc-01.p12) has special privileges

**Owner wallet:** Uses first keystore (kd5ujc-01.p12)

## Genesis Configuration

Genesis CSV contains initial token distribution:

```csv
DAG3oEyfPqnQeARHox8Bfh4hxC7n6ZaFyf5BMUXe,1000000000000
DAG8mwjvqDzxqDTpcDuAs8f9B9M9jADo3R9Lm6ip,1000000000000
DAG04s6k9heWUGFLqsGF4i7sKYUGNBbpaiBiTP6m,1000000000000
DAG2pw1tWFSsjgjciVaaXVsfgGxmS6yB7rPjzxS6,1000000000000
```

## Support

**Check status:**
```bash
./scripts/status.sh
```

**Check logs:**
```bash
./scripts/logs.sh -f
```

**Review configuration:**
```bash
cat config/deploy-config.sh
```

**Documentation:**
- [Complete Deployment Guide](../docs/guides/deployment.md)
- [Ottochain Documentation](../docs/README.md)

## Next Steps After Deployment

1. ✅ Verify all nodes Ready: `./scripts/status.sh`
2. ✅ Monitor logs: `./scripts/logs.sh -f`
3. ✅ Test state machine creation: See `../e2e-test/`
4. ✅ Test oracle creation: See `../docs/guides/terminal-usage.md`
5. 🔧 Setup monitoring/alerting (optional)
6. 🔧 Backup keystores and configuration