#!/bin/bash
set -e

# OttoChain Full Stack Entrypoint
# All 5 layers in one image for guaranteed compatibility.
# LAYER env var selects which to run: gl0, gl1, ml0, cl1, dl1

case "${LAYER,,}" in
  gl0)
    JAR="/ottochain/jars/gl0.jar"
    PORT="${GL0_PORT:-9000}"
    P2P_PORT="${GL0_P2P_PORT:-9001}"
    CLI_PORT="${GL0_CLI_PORT:-9002}"
    echo "Starting GL0 (Global L0) on port ${PORT}..."
    ;;
  gl1)
    JAR="/ottochain/jars/gl1.jar"
    PORT="${GL1_PORT:-9100}"
    P2P_PORT="${GL1_P2P_PORT:-9101}"
    CLI_PORT="${GL1_CLI_PORT:-9102}"
    echo "Starting GL1 (Global L1 / DAG L1) on port ${PORT}..."
    ;;
  ml0)
    JAR="/ottochain/jars/ml0.jar"
    PORT="${ML0_PORT:-9200}"
    P2P_PORT="${ML0_P2P_PORT:-9201}"
    CLI_PORT="${ML0_CLI_PORT:-9202}"
    echo "Starting ML0 (Metagraph L0) on port ${PORT}..."
    ;;
  cl1)
    JAR="/ottochain/jars/cl1.jar"
    PORT="${CL1_PORT:-9300}"
    P2P_PORT="${CL1_P2P_PORT:-9301}"
    CLI_PORT="${CL1_CLI_PORT:-9302}"
    echo "Starting CL1 (Currency L1) on port ${PORT}..."
    ;;
  dl1)
    JAR="/ottochain/jars/dl1.jar"
    PORT="${DL1_PORT:-9400}"
    P2P_PORT="${DL1_P2P_PORT:-9401}"
    CLI_PORT="${DL1_CLI_PORT:-9402}"
    echo "Starting DL1 (Data L1) on port ${PORT}..."
    ;;
  *)
    echo "Error: LAYER must be one of: gl0, gl1, ml0, cl1, dl1"
    echo "Got: ${LAYER:-<not set>}"
    exit 1
    ;;
esac

if [ ! -f "$JAR" ]; then
  echo "Error: JAR not found at $JAR"
  ls -la /ottochain/jars/
  exit 1
fi

# Determine run mode
RUN_MODE="${RUN_MODE:-run-validator}"

# Build command line args
ARGS=""

# Add keystore if exists
if [ -f "${CL_KEYSTORE}" ]; then
  ARGS="${ARGS} --keystore ${CL_KEYSTORE}"
  ARGS="${ARGS} --alias ${CL_KEYALIAS:-alias}"
  ARGS="${ARGS} --password ${CL_PASSWORD:-password}"
fi

# Add environment
ARGS="${ARGS} --env ${CL_APP_ENV:-testnet}"

# Add ports
ARGS="${ARGS} --http-port ${PORT}"
ARGS="${ARGS} --p2p-port ${P2P_PORT}"
ARGS="${ARGS} --cli-port ${CLI_PORT}"

# Add external IP if provided
if [ -n "${CL_EXTERNAL_IP}" ]; then
  ARGS="${ARGS} --ip ${CL_EXTERNAL_IP}"
fi

# Add collateral (default 0 for metagraph)
ARGS="${ARGS} --collateral ${CL_COLLATERAL:-0}"

# Layer-specific peer configuration
case "${LAYER,,}" in
  gl1)
    # GL1 needs GL0 peer info
    if [ -n "${CL_L0_PEER_ID}" ]; then
      ARGS="${ARGS} --l0-peer-id ${CL_L0_PEER_ID}"
      ARGS="${ARGS} --l0-peer-host ${CL_L0_PEER_HOST:-localhost}"
      ARGS="${ARGS} --l0-peer-port ${CL_L0_PEER_PORT:-9000}"
    fi
    ;;
  ml0|cl1|dl1)
    # Metagraph layers need GL0 peer info
    if [ -n "${CL_GLOBAL_L0_PEER_ID}" ]; then
      ARGS="${ARGS} --global-l0-peer-id ${CL_GLOBAL_L0_PEER_ID}"
      ARGS="${ARGS} --global-l0-peer-host ${CL_GLOBAL_L0_PEER_HOST:-localhost}"
      ARGS="${ARGS} --global-l0-peer-port ${CL_GLOBAL_L0_PEER_PORT:-9000}"
    fi
    
    # CL1 and DL1 also need ML0 peer info
    if [ "${LAYER,,}" = "cl1" ] || [ "${LAYER,,}" = "dl1" ]; then
      if [ -n "${CL_L0_PEER_ID}" ]; then
        ARGS="${ARGS} --l0-peer-id ${CL_L0_PEER_ID}"
        ARGS="${ARGS} --l0-peer-host ${CL_L0_PEER_HOST:-localhost}"
        ARGS="${ARGS} --l0-peer-port ${CL_L0_PEER_PORT:-9200}"
      fi
    fi
    
    # Token ID for metagraph layers
    if [ -n "${CL_TOKEN_ID}" ]; then
      ARGS="${ARGS} --token-id ${CL_TOKEN_ID}"
    fi
    ;;
esac

# Genesis file path for run-genesis mode
if [ "${RUN_MODE}" = "run-genesis" ] && [ -f "/ottochain/data/genesis.snapshot" ]; then
  ARGS="${ARGS} --genesis /ottochain/data/genesis.snapshot"
fi

# Add any extra args passed to container
ARGS="${ARGS} $@"

echo "Running: java ${JAVA_OPTS} -jar ${JAR} ${RUN_MODE} ${ARGS}"
exec java ${JAVA_OPTS} -jar "${JAR}" ${RUN_MODE} ${ARGS}
