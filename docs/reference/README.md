# Ottochain Metagraph Documentation

This directory contains documentation for deploying and managing the Ottochain metagraph on Digital Ocean infrastructure.

## Documentation Index

1. [Deployment Guide](deployment-guide.md) - Complete guide to deploying the metagraph
2. [API Reference](api-reference.md) - Metagraph API endpoints and their usage
3. [Script Reference](script-reference.md) - Detailed reference for all deployment scripts
4. [Architecture](architecture.md) - Understanding the metagraph architecture
5. [Troubleshooting](troubleshooting.md) - Common issues and solutions

## Quick Start

For existing deployments, use the restart script:

```bash
./deploy/scripts/restart-cluster.sh
```

For checking status:

```bash
./deploy/scripts/status.sh
```

## Directory Structure

```
ottochain/
├── deploy/
│   ├── config/
│   │   └── deploy-config.sh          # Configuration file
│   ├── keys/                          # P12 keystores
│   ├── scripts/
│   │   ├── 01-setup-nodes.sh          # Initial node setup
│   │   ├── 02-build-jars.sh           # Build metagraph JARs
│   │   ├── 03-deploy-jars.sh          # Deploy JARs to nodes
│   │   ├── restart-cluster.sh         # Restart existing cluster
│   │   ├── stop-nodes.sh              # Stop all services
│   │   ├── status.sh                  # Check cluster status
│   │   ├── logs.sh                    # View node logs
│   │   └── download-and-sync-data.sh  # Sync data between nodes
│   └── genesis/                       # Genesis configuration
└── modules/
    ├── l0/                            # Metagraph L0 module
    ├── data_l1/                       # Data L1 module
    └── models/                        # Shared models
```

## Node Information

- **Node 1 (Lead)**: 146.190.151.138
- **Node 2**: 147.182.254.23
- **Node 3**: 144.126.217.197

All nodes run:
- Metagraph L0 on port 9100
- Currency L1 on port 9200
- Data L1 on port 9300

## Important Files

- `deploy/config/deploy-config.sh` - All deployment configuration
- `deploy/metagraph-id.txt` - Metagraph identifier (fetched from nodes)
- Node-specific: `/home/root/code/metagraph-l0/genesis.address` - Metagraph ID on each node