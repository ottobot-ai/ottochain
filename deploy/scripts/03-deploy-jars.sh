#!/bin/bash

# 03-deploy-jars.sh
# Deploy built JARs to all metagraph nodes
#
# This script:
# 1. Locates built JAR files from sbt assembly
# 2. Deploys JARs to all nodes via SCP
# 3. Verifies deployment using SHA256 hash comparison
# 4. Reports deployment status for each node

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "03-deploy-jars.sh" \
    "./deploy/scripts/03-deploy-jars.sh" \
    "Deploy built metagraph JARs to all nodes"
  exit 0
fi

print_title "Workchain Metagraph - Deploy JARs"

# Validate configuration
if ! validate_config; then
  print_error "Configuration validation failed"
  exit 1
fi

# Check required commands
require_cmd scp
require_cmd ssh

# Validate SSH key
if ! check_ssh_key; then
  exit 1
fi

# Change to project root
cd "$PROJECT_ROOT"

# Locate built JARs
print_title "Locating Built JARs"

declare -A JAR_PATHS
declare -A JAR_HASHES
declare -A TARGET_NAMES

LOCATE_SUCCESS=true

for module_spec in "${MODULES[@]}"; do
  IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"

  print_status "Locating $target_dir JAR..."

  # Find the JAR file
  jar_file=$(find "$PROJECT_ROOT/modules"/*/target/scala-2.13/ -name "${jar_pattern}-*.jar" -type f 2>/dev/null | head -1)

  if [[ -z "$jar_file" ]] || [[ ! -f "$jar_file" ]]; then
    print_error "JAR file not found for $target_dir (pattern: ${jar_pattern}-*.jar)"
    print_status "Run ./deploy/scripts/02-build-jars.sh first to build the JARs"
    LOCATE_SUCCESS=false
    continue
  fi

  # Calculate hash
  jar_hash=$(calculate_hash "$jar_file")

  if [[ $? -ne 0 ]] || [[ -z "$jar_hash" ]]; then
    print_error "Failed to calculate hash for $jar_file"
    LOCATE_SUCCESS=false
    continue
  fi

  # Store information
  JAR_PATHS["$target_dir"]="$jar_file"
  JAR_HASHES["$target_dir"]="$jar_hash"
  TARGET_NAMES["$target_dir"]="$target_jar_name"

  print_success "  Found: $(basename "$jar_file")"
  print_status "  Hash: $jar_hash"
done

if [[ "$LOCATE_SUCCESS" != "true" ]]; then
  print_error "Failed to locate all required JAR files"
  exit 1
fi

# Deploy to each node
print_title "Deploying JARs to Nodes"

deploy_to_node() {
  local node_ip="$1"
  local node_num="$2"

  print_status "Deploying to Node $node_num ($node_ip)..."

  # Test SSH connectivity
  if ! test_ssh_connectivity "$node_ip" "$node_num" >/dev/null 2>&1; then
    print_error "Cannot connect to Node $node_num"
    return 1
  fi

  # Deploy each module's JAR
  for module_spec in "${MODULES[@]}"; do
    IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"

    local jar_file="${JAR_PATHS[$target_dir]}"
    local jar_hash="${JAR_HASHES[$target_dir]}"
    local remote_dir="$REMOTE_CODE_DIR/$target_dir"
    local remote_jar="$remote_dir/$target_jar_name"

    print_status "  Deploying $target_jar_name to $target_dir..."

    # Ensure remote directory exists
    if ! ssh_exec "$node_ip" "mkdir -p $remote_dir" >/dev/null 2>&1; then
      print_error "Failed to create directory $remote_dir on Node $node_num"
      return 1
    fi

    # Deploy JAR
    if ! scp_to_node "$jar_file" "$node_ip" "$remote_jar"; then
      print_error "Failed to copy $target_jar_name to Node $node_num"
      return 1
    fi

    # Verify deployment
    print_status "  Verifying $target_jar_name..."
    if ! verify_remote_hash "$node_ip" "$remote_jar" "$jar_hash"; then
      print_error "Hash verification failed for $target_jar_name on Node $node_num"
      return 1
    fi

    print_success "  ✓ $target_jar_name deployed and verified"
  done

  print_success "Node $node_num deployment completed successfully"
  return 0
}

# Deploy to all nodes
DEPLOYMENT_SUCCESS=true

for i in "${!NODE_IPS[@]}"; do
  if ! deploy_to_node "${NODE_IPS[$i]}" "$((i+1))"; then
    DEPLOYMENT_SUCCESS=false
    print_error "Deployment failed on Node $((i+1))"
  fi
  echo ""
done

if [[ "$DEPLOYMENT_SUCCESS" != "true" ]]; then
  print_error "Deployment failed for one or more nodes"
  exit 1
fi

# Deployment summary
print_title "Deployment Summary"

print_success "All JARs deployed successfully to all nodes!"
print_status ""
print_status "Deployed modules:"

for module_spec in "${MODULES[@]}"; do
  IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"
  print_status "  $sbt_module → ~/$REMOTE_CODE_DIR/$target_dir/$target_jar_name"
done

print_status ""
print_status "Deployment locations:"
for i in "${!NODE_IPS[@]}"; do
  print_status "  Node $((i+1)) (${NODE_IPS[$i]}): ~/$REMOTE_CODE_DIR/"
done

print_status ""
print_status "Next steps:"
print_status "  Create genesis: ./deploy/scripts/04-create-genesis.sh"
