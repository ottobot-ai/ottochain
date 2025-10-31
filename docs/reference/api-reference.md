# API Reference

This document describes the API endpoints available on the metagraph nodes.

## API Specifications

Official Swagger specs are available at:

- **Metagraph L0**: http://apidoc-integrationnet.constellationnetwork.io.s3-website-us-west-1.amazonaws.com/currency/v1/l0/public/
- **Currency L1**: http://apidoc-integrationnet.constellationnetwork.io.s3-website-us-west-1.amazonaws.com/currency/v1/l1/public/
- **Data L1**: http://apidoc-integrationnet.constellationnetwork.io.s3-website-us-west-1.amazonaws.com/currency/v1/l1-data/public/

## Port Configuration

Each node runs three layers on different ports:

| Layer        | Public Port | P2P Port | CLI Port |
|--------------|-------------|----------|----------|
| Metagraph L0 | 9100        | 9101     | 9102     |
| Currency L1  | 9200        | 9201     | 9202     |
| Data L1      | 9300        | 9301     | 9302     |

## Common Endpoints

### Node Information

Get basic node information:

```bash
GET /node/info
```

**Example**:
```bash
curl http://146.190.151.138:9100/node/info | jq
```

**Response**:
```json
{
  "state": "Ready",
  "session": 1761767594811,
  "clusterSession": 1761767594810,
  "version": "3.5.0-rc.8",
  "host": "146.190.151.138",
  "publicPort": 9100,
  "p2pPort": 9101,
  "id": "8fa57b7b42db6cbf2efc7d43e59a9ff62e28e076243482a3249747f11c0244af..."
}
```

**Fields**:
- `state` - Node state (ReadyToJoin, Observing, WaitingForReady, Ready)
- `session` - Node session timestamp
- `clusterSession` - Cluster session timestamp
- `version` - Tessellation version
- `host` - Node IP address
- `publicPort`, `p2pPort` - Port configuration
- `id` - Node peer ID (used for cluster join operations)

### Cluster Information

Get cluster membership:

```bash
GET /cluster/info
```

**Example**:
```bash
curl http://146.190.151.138:9100/cluster/info | jq
```

**Response**:
```json
[
  {
    "id": "8fa57b7b...",
    "ip": "146.190.151.138",
    "publicPort": 9100,
    "p2pPort": 9101,
    "clusterSession": "1761767594810",
    "session": "1761767594811",
    "state": "Ready",
    "jar": "0373cdd4..."
  },
  ...
]
```

## Metagraph L0 Specific Endpoints

### Get Latest Snapshot Ordinal

Get the ordinal number of the latest snapshot:

```bash
GET /snapshots/latest/ordinal
```

**Example**:
```bash
curl http://146.190.151.138:9100/snapshots/latest/ordinal | jq
```

**Response**:
```json
{
  "value": 1594
}
```

### Get Latest Snapshot

Get the full latest snapshot:

```bash
GET /snapshots/latest
```

### Get Snapshot by Ordinal

Get a specific snapshot by ordinal number:

```bash
GET /snapshots/{ordinal}
```

**Example**:
```bash
curl http://146.190.151.138:9100/snapshots/1500 | jq
```

### Get Latest Combined Snapshot

Get the latest snapshot with combined data:

```bash
GET /snapshots/latest/combined
```

## Currency L1 Endpoints

Currency L1 does not have snapshot-specific endpoints. It follows the Metagraph L0 snapshots.

**Key endpoints**:
- `/node/info` - Node information
- `/cluster/info` - Cluster membership
- `/transactions` - Transaction-related endpoints

## Data L1 Endpoints

Data L1 processes custom data updates and follows the Metagraph L0 snapshots.

**Key endpoints**:
- `/node/info` - Node information
- `/cluster/info` - Cluster membership
- `/data` - Custom data endpoints (application-specific)

## CLI Endpoints (localhost only)

CLI ports (9102, 9202, 9302) are only accessible from localhost on each node and provide cluster management functions.

### Join Cluster

Join a validator to the cluster (used during startup):

```bash
POST http://localhost:9102/cluster/join
Content-Type: application/json

{
  "id": "<leader_peer_id>",
  "ip": "<leader_ip>",
  "p2pPort": 9101
}
```

**Example** (from inside a node):
```bash
curl -X POST http://localhost:9102/cluster/join \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "8fa57b7b42db6cbf2efc7d43e59a9ff62e28e076243482a3249747f11c0244af...",
    "ip": "146.190.151.138",
    "p2pPort": 9101
  }'
```

## Node States

Understanding node states is crucial for cluster management:

| State           | Description                                      |
|-----------------|--------------------------------------------------|
| ReadyToJoin     | Node is ready to join a cluster                 |
| Observing       | Node is observing the network state             |
| WaitingForReady | Node is waiting to reach consensus              |
| Ready           | Node is fully operational and in consensus      |

## Useful Queries

### Check if all nodes are Ready

```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  echo -n "$ip: "
  curl -s http://$ip:9100/node/info | jq -r '.state'
done
```

### Get snapshot count from all nodes

```bash
for ip in 146.190.151.138 147.182.254.23 144.126.217.197; do
  echo -n "$ip: "
  curl -s http://$ip:9100/snapshots/latest/ordinal | jq -r '.value'
done
```

### Get cluster size

```bash
curl -s http://146.190.151.138:9100/cluster/info | jq 'length'
```

### Get all peer IDs in cluster

```bash
curl -s http://146.190.151.138:9100/cluster/info | jq -r '.[].id'
```

## Integration with Scripts

The deploy scripts use these endpoints internally:

- **status.sh**: Uses `/node/info` and `/snapshots/latest/ordinal`
- **restart-cluster.sh**: Uses `/node/info` for peer IDs and state checking
- **wait functions**: Poll `/node/info` for state changes

## Error Responses

Common HTTP status codes:

- **200 OK** - Request successful
- **404 Not Found** - Endpoint or resource doesn't exist
- **500 Internal Server Error** - Node encountered an error
- **503 Service Unavailable** - Node is not ready

When a node is not yet started or is in an early state, endpoints may return connection errors or empty responses.