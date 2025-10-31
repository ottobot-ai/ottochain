# Troubleshooting

Common issues and solutions when working with the Workchain metagraph.

## Quick Diagnostics

### Check Overall Health

```bash
./deploy/scripts/status.sh
```

Look for:
- ✅ All processes running
- ✅ All nodes in "Ready" state
- ✅ Snapshot ordinals incrementing

### View Recent Logs

```bash
./deploy/scripts/logs.sh 1 ml0
./deploy/scripts/logs.sh 2 cl1
./deploy/scripts/logs.sh 3 dl1
```

Look for:
- ❌ ERROR or Exception messages
- ⚠️ WARN messages (may be normal)
- ✅ INFO showing progress

## Common Issues

### Issue: Nodes Stuck in "ReadyToJoin"

**Symptoms**:
- Validator nodes show state "ReadyToJoin"
- Don't progress to "Ready"
- Status script shows nodes waiting

**Causes**:
- Join request failed
- Lead node peer ID is incorrect
- Network connectivity issues

**Solution 1**: Check if join request was sent
```bash
./deploy/scripts/logs.sh 2 ml0 | grep -i join
```

**Solution 2**: Manually verify lead node peer ID
```bash
ssh root@146.190.151.138 \
  curl -s http://localhost:9100/node/info | jq -r '.id'
```

**Solution 3**: Restart the cluster
```bash
./deploy/scripts/restart-cluster.sh
```

### Issue: "Failed to get lead node peer ID"

**Symptoms**:
```
[ERROR] Failed to get lead node peer ID
[ERROR] Debug: Try running: ssh 146.190.151.138 'curl -s http://localhost:9100/node/info'
```

**Causes**:
- Lead node not fully started
- `jq` not installed on nodes
- Node API not responding

**Solution 1**: Install jq on nodes
```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  ssh root@$ip "apt-get update -qq && apt-get install -y jq"
done
```

**Solution 2**: Wait longer for lead node
The restart script may be moving too fast. Lead node needs more time to reach Ready state.

**Solution 3**: Check lead node manually
```bash
ssh root@146.190.151.138 curl -s http://localhost:9100/node/info
```

### Issue: Currency L1 or Data L1 Won't Start

**Symptoms**:
- ML0 is Ready on all nodes
- CL1 or DL1 shows "Not running" in status
- Log shows missing environment variables

**Log Example**:
```
Missing expected flag --global-l0-peer-id
Missing expected flag --global-l0-peer-host
Missing expected flag --l0-token-identifier
```

**Causes**:
- Missing environment variables in start command
- Wrong run mode (should be `run-initial-validator` for node 1, `run-validator` for others)

**Solution**: Check the log file
```bash
ssh root@146.190.151.138 cat /home/root/code/currency-l1/currency-l1.log
```

If you see the help message, the start command is incorrect.

**Fix**: Re-run restart script
```bash
./deploy/scripts/restart-cluster.sh
```

### Issue: Last Snapshot Shows "N/A"

**Symptoms**:
- Node state is "Ready"
- Status shows "Last Snapshot: N/A"

**Causes**:
- Incorrect API endpoint
- Node hasn't created first snapshot yet

**Solution 1**: Check if snapshots exist
```bash
ssh root@146.190.151.138 \
  curl -s http://localhost:9100/snapshots/latest/ordinal
```

Should return: `{"value": 1234}`

**Solution 2**: Verify snapshot files exist
```bash
ssh root@146.190.151.138 \
  "find /home/root/code/metagraph-l0/data/incremental_snapshot -type f | wc -l"
```

### Issue: Nodes Have Different Snapshot Counts

**Symptoms**:
```
Node 1: Last Snapshot: 1500
Node 2: Last Snapshot: 1200
Node 3: Last Snapshot: 1490
```

**Causes**:
- Nodes were started with different data
- Node 2 fell behind or had stale data

**Solution**: Sync data from the node with most snapshots
```bash
# Edit download-and-sync-data.sh to set:
# SOURCE_NODE="147.182.254.23"  (node with highest count)
# TARGET_NODES=(other nodes)

./deploy/scripts/stop-nodes.sh
./deploy/scripts/download-and-sync-data.sh
./deploy/scripts/restart-cluster.sh
```

### Issue: SSH Connection Refused

**Symptoms**:
```
ssh: connect to host 146.190.151.138 port 22: Connection refused
```

**Causes**:
- Node is down
- Firewall blocking SSH
- Wrong IP address
- Wrong SSH key

**Solution 1**: Verify node is running
- Check Digital Ocean dashboard
- Ensure droplet is powered on

**Solution 2**: Verify SSH key
```bash
ls -la ~/.ssh/do-workchain-intnet
# Should show the private key file
```

**Solution 3**: Test SSH manually
```bash
ssh -i ~/.ssh/do-workchain-intnet root@146.190.151.138
```

### Issue: Port Already in Use

**Symptoms**:
```
java.net.BindException: Address already in use
```

**Causes**:
- Process from previous run still running
- Another service using the port

**Solution 1**: Kill existing processes
```bash
./deploy/scripts/stop-nodes.sh
```

**Solution 2**: Manually kill processes
```bash
ssh root@146.190.151.138 "killall -9 java"
```

**Solution 3**: Check what's using the port
```bash
ssh root@146.190.151.138 "lsof -i :9100"
```

### Issue: Out of Memory (OOM)

**Symptoms**:
- Process crashes
- Log shows `java.lang.OutOfMemoryError`
- Node becomes unresponsive

**Causes**:
- JVM heap too small
- Memory leak
- Too many snapshots in memory

**Solution 1**: Increase JVM heap in config
Edit `deploy-config.sh`:
```bash
JVM_OPTS="-Xms2048M -Xmx4096M"  # Increase from default
```

**Solution 2**: Restart node
```bash
./deploy/scripts/restart-cluster.sh
```

**Solution 3**: Check system memory
```bash
ssh root@146.190.151.138 free -h
```

### Issue: Metagraph ID Mismatch

**Symptoms**:
- Validators can't join
- Different nodes report different metagraph IDs
- Log shows "Invalid metagraph ID"

**Causes**:
- Nodes have different genesis data
- One node was from a different metagraph

**Solution**: Verify genesis.address on all nodes
```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  echo -n "$ip: "
  ssh root@$ip cat /home/root/code/metagraph-l0/genesis.address
done
```

All should show the same ID: `DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG`

If different, sync data from the correct node.

## Diagnostic Commands

### Check if Java processes are running

```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  echo "=== $ip ==="
  ssh root@$ip jps -l
done
```

### Check node states

```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  echo -n "$ip ML0: "
  curl -s http://$ip:9100/node/info | jq -r '.state'
done
```

### Check cluster membership

```bash
curl -s http://146.190.151.138:9100/cluster/info | jq -r '.[].ip'
```

Should show all 3 IPs.

### Check disk space

```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  echo "=== $ip ==="
  ssh root@$ip df -h /home/root/code
done
```

### Check snapshot counts

```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  echo -n "$ip: "
  ssh root@$ip \
    "find /home/root/code/metagraph-l0/data/incremental_snapshot/hash -type f | wc -l"
done
```

### Monitor log in real-time

```bash
ssh root@146.190.151.138 tail -f /home/root/code/metagraph-l0/metagraph-l0.log
```

Press `Ctrl+C` to stop.

## Emergency Procedures

### Complete Cluster Restart

```bash
# Stop everything
./deploy/scripts/stop-nodes.sh

# Wait 10 seconds
sleep 10

# Verify all stopped
./deploy/scripts/status.sh

# Restart
./deploy/scripts/restart-cluster.sh
```

### Rollback to Previous Snapshot Data

```bash
# On a node, move current data and restore backup
ssh root@146.190.151.138 "
  cd /home/root/code/metagraph-l0
  mv data data-broken
  cp -r archived-data/data-backup-20250129-120000 data
"

# Restart
./deploy/scripts/restart-cluster.sh
```

### Clean Start (keeps archived data)

```bash
# Stop cluster
./deploy/scripts/stop-nodes.sh

# Archive and clear data on all nodes
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  ssh root@$ip "
    cd /home/root/code/metagraph-l0
    mkdir -p archived-data
    mv data archived-data/data-\$(date +%Y%m%d-%H%M%S)
    mkdir data
  "
done

# Sync from source
./deploy/scripts/download-and-sync-data.sh

# Restart
./deploy/scripts/restart-cluster.sh
```

## Performance Issues

### Slow Snapshot Creation

**Symptoms**:
- Snapshots taking too long
- State showing "Observing" for extended periods

**Check**:
```bash
# Monitor snapshot ordinal
watch -n 5 'curl -s http://146.190.151.138:9100/snapshots/latest/ordinal | jq'
```

**Solutions**:
- Check network latency between nodes
- Verify CPU isn't maxed out
- Check if disk I/O is bottlenecked

### High CPU Usage

```bash
ssh root@146.190.151.138 top -b -n 1 | head -20
```

Normal for CPU to spike during:
- Snapshot creation
- State transitions
- Initial sync

## Getting Help

When reporting issues, include:

1. **Status output**:
   ```bash
   ./deploy/scripts/status.sh > status-output.txt
   ```

2. **Recent logs** from all nodes:
   ```bash
   ./deploy/scripts/logs.sh 1 ml0 200 > node1-ml0.log
   ./deploy/scripts/logs.sh 2 ml0 200 > node2-ml0.log
   ./deploy/scripts/logs.sh 3 ml0 200 > node3-ml0.log
   ```

3. **Configuration** (remove passwords):
   ```bash
   grep -v PASSWORD deploy/config/deploy-config.sh > config-sanitized.txt
   ```

4. **Node info**:
   ```bash
   curl http://146.190.151.138:9100/node/info | jq > node-info.json
   curl http://146.190.151.138:9100/cluster/info | jq > cluster-info.json
   ```

5. **Description** of what you were trying to do and what happened