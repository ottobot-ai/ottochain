#!/bin/bash

# 02-build-jars.sh
# Build all metagraph modules using sbt assembly
#
# This script:
# 1. Validates environment and dependencies
# 2. Builds currencyL0, currencyL1, and dataL1 modules
# 3. Locates and verifies the built JAR files
# 4. Calculates SHA256 hashes for deployment verification
# 5. Reports build summary

set -euo pipefail

# Load configuration and utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../config/deploy-config.sh"
source "$SCRIPT_DIR/utils.sh"

# Help message
if [[ "${1:-}" == "--help" ]] || [[ "${1:-}" == "-h" ]]; then
  show_help "02-build-jars.sh" \
    "./deploy/scripts/02-build-jars.sh" \
    "Build all metagraph modules (currencyL0, currencyL1, dataL1) using sbt assembly"
  exit 0
fi

print_title "Workchain Metagraph - Build JARs"

# Check required commands
require_cmd sbt

# Change to project root
print_status "Project root: $PROJECT_ROOT"
cd "$PROJECT_ROOT"

# Build each module
print_title "Building Metagraph Modules"

build_module() {
  local sbt_module="$1"
  local module_name="$2"

  print_status "Building $module_name ($sbt_module)..."

  if ! sbt "$sbt_module/assembly"; then
    print_error "Failed to build $module_name"
    return 1
  fi

  print_success "$module_name built successfully"
  return 0
}

BUILD_SUCCESS=true

# Build all modules
for module_spec in "${MODULES[@]}"; do
  IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"

  if ! build_module "$sbt_module" "$target_dir"; then
    BUILD_SUCCESS=false
  fi
  echo ""
done

if [[ "$BUILD_SUCCESS" != "true" ]]; then
  print_error "One or more modules failed to build"
  exit 1
fi

# Locate and verify built JARs
print_title "Locating Built JARs"

declare -A JAR_PATHS
declare -A JAR_HASHES

for module_spec in "${MODULES[@]}"; do
  IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"

  print_status "Locating JAR for $target_dir..."

  # Find the JAR file
  jar_file=$(find "$PROJECT_ROOT/modules"/*/target/scala-2.13/ -name "${jar_pattern}-*.jar" -type f 2>/dev/null | head -1)

  if [[ -z "$jar_file" ]] || [[ ! -f "$jar_file" ]]; then
    print_error "JAR file not found for $target_dir (pattern: ${jar_pattern}-*.jar)"
    BUILD_SUCCESS=false
    continue
  fi

  # Calculate hash
  jar_hash=$(calculate_hash "$jar_file")

  if [[ $? -ne 0 ]] || [[ -z "$jar_hash" ]]; then
    print_error "Failed to calculate hash for $jar_file"
    BUILD_SUCCESS=false
    continue
  fi

  # Store paths and hashes
  JAR_PATHS["$target_dir"]="$jar_file"
  JAR_HASHES["$target_dir"]="$jar_hash"

  # Get file size
  jar_size=$(du -h "$jar_file" | cut -f1)

  print_success "  Found: $(basename "$jar_file")"
  print_status "  Path: $jar_file"
  print_status "  Size: $jar_size"
  print_status "  Hash: $jar_hash"
  echo ""
done

if [[ "$BUILD_SUCCESS" != "true" ]]; then
  print_error "Failed to locate or verify one or more JAR files"
  exit 1
fi

# Build summary
print_title "Build Summary"

print_success "All modules built successfully!"
print_status ""
print_status "Built JARs:"

for module_spec in "${MODULES[@]}"; do
  IFS=':' read -r sbt_module target_dir jar_pattern target_jar_name <<< "$module_spec"

  jar_file="${JAR_PATHS[$target_dir]}"
  jar_hash="${JAR_HASHES[$target_dir]}"

  print_status "  $target_dir:"
  print_status "    File: $(basename "$jar_file")"
  print_status "    Path: $jar_file"
  print_status "    Hash: $jar_hash"
  echo ""
done

print_status "Next steps:"
print_status "  Deploy JARs to nodes: ./deploy/scripts/03-deploy-jars.sh"
