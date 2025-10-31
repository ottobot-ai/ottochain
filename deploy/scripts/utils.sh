#!/bin/bash
# Utility functions for workchain metagraph deployment

#######################
# Logging Functions
#######################

print_status() {
  echo "[INFO] $1"
}

print_success() {
  echo "[SUCCESS] $1"
}

print_error() {
  echo "[ERROR] $1" >&2
}

print_warning() {
  echo "[WARNING] $1"
}

print_title() {
  echo ""
  echo "=========================================="
  echo "$1"
  echo "=========================================="
  echo ""
}

#######################
# Validation Functions
#######################

require_cmd() {
  if ! command -v "$1" &> /dev/null; then
    print_error "Required command '$1' not found. Please install it and try again."
    exit 1
  fi
}

check_ssh_key() {
  if [[ ! -f "$SSH_KEY_PATH" ]]; then
    print_error "SSH key file not found: $SSH_KEY_PATH"
    return 1
  fi

  if [[ ! -r "$SSH_KEY_PATH" ]]; then
    print_error "SSH key file is not readable: $SSH_KEY_PATH"
    return 1
  fi

  local key_perms
  key_perms=$(stat -c "%a" "$SSH_KEY_PATH" 2>/dev/null)
  if [[ "$key_perms" != "600" ]] && [[ "$key_perms" != "400" ]]; then
    print_warning "SSH key permissions should be 600 or 400, current: $key_perms"
    print_status "You may need to run: chmod 600 $SSH_KEY_PATH"
  fi

  return 0
}

validate_node_ips() {
  for ip in "${NODE_IPS[@]}"; do
    if [[ "$ip" == "YOUR_NODE_"*"_IP" ]]; then
      print_error "Node IPs not configured. Please update NODE_IPS in deploy/config/deploy-config.sh"
      return 1
    fi
  done
  return 0
}

#######################
# SSH Functions
#######################

ssh_exec() {
  local node_ip="$1"
  local command="$2"

  ssh -i "$SSH_KEY_PATH" $SSH_OPTS "$SSH_USER@$node_ip" "$command"
}

scp_to_node() {
  local local_file="$1"
  local node_ip="$2"
  local remote_path="$3"

  scp -i "$SSH_KEY_PATH" $SSH_OPTS "$local_file" "$SSH_USER@$node_ip:$remote_path"
}

scp_from_node() {
  local node_ip="$1"
  local remote_file="$2"
  local local_path="$3"

  scp -i "$SSH_KEY_PATH" $SSH_OPTS "$SSH_USER@$node_ip:$remote_file" "$local_path"
}

test_ssh_connectivity() {
  local node_ip="$1"
  local node_num="$2"

  if ssh_exec "$node_ip" "echo 'SSH connection successful'" >/dev/null 2>&1; then
    print_success "Node $node_num ($node_ip) is reachable via SSH"
    return 0
  else
    print_error "Failed to connect to node $node_num ($node_ip)"
    return 1
  fi
}

#######################
# File Functions
#######################

calculate_hash() {
  local file="$1"

  if [[ ! -f "$file" ]]; then
    print_error "File not found for hash calculation: $file"
    return 1
  fi

  sha256sum "$file" | cut -d' ' -f1
}

verify_remote_hash() {
  local node_ip="$1"
  local remote_file="$2"
  local expected_hash="$3"

  local remote_hash
  remote_hash=$(ssh_exec "$node_ip" "sha256sum $remote_file 2>/dev/null | cut -d' ' -f1")

  if [[ -z "$remote_hash" ]]; then
    print_error "Failed to calculate remote hash for $remote_file"
    return 1
  fi

  if [[ "$expected_hash" == "$remote_hash" ]]; then
    return 0
  else
    print_error "Hash mismatch for $remote_file"
    print_error "  Expected: $expected_hash"
    print_error "  Got:      $remote_hash"
    return 1
  fi
}

#######################
# Node Health Functions
#######################

get_node_info() {
  local node_ip="$1"
  local port="$2"

  curl -s --connect-timeout 5 --max-time 10 "http://${node_ip}:${port}/node/info" 2>/dev/null
}

get_node_state() {
  local node_ip="$1"
  local port="$2"

  local node_info
  node_info=$(get_node_info "$node_ip" "$port")

  if [[ -z "$node_info" ]]; then
    echo "Unreachable"
    return 1
  fi

  echo "$node_info" | jq -r '.state // "Unknown"' 2>/dev/null || echo "Unknown"
}

wait_for_node_ready() {
  local node_ip="$1"
  local port="$2"
  local node_name="$3"
  local max_attempts="${4:-$HEALTH_CHECK_MAX_ATTEMPTS}"
  local delay="${5:-$HEALTH_CHECK_DELAY}"

  wait_for_node_state "$node_ip" "$port" "Ready" "$node_name" "$max_attempts" "$delay"
}

wait_for_node_state() {
  local node_ip="$1"
  local port="$2"
  local expected_state="$3"
  local node_name="$4"
  local max_attempts="${5:-$HEALTH_CHECK_MAX_ATTEMPTS}"
  local delay="${6:-$HEALTH_CHECK_DELAY}"

  print_status "Waiting for $node_name to reach $expected_state state..."

  local attempt=1
  while [ $attempt -le $max_attempts ]; do
    local state
    state=$(get_node_state "$node_ip" "$port")

    if [[ "$state" == "$expected_state" ]]; then
      print_success "$node_name is $expected_state!"
      return 0
    fi

    if [[ $((attempt % 10)) -eq 0 ]]; then
      print_status "  Attempt $attempt/$max_attempts: $node_name state is '$state'"
    fi

    sleep "$delay"
    ((attempt++))
  done

  print_error "$node_name did not reach $expected_state after $max_attempts attempts"
  return 1
}

wait_for_node_online() {
  local node_ip="$1"
  local port="$2"
  local node_name="$3"
  local max_attempts="${4:-120}"
  local delay="${5:-1}"

  print_status "Waiting for $node_name to be online..."

  local attempt=1
  while [ $attempt -le $max_attempts ]; do
    if curl -s --connect-timeout 2 --max-time 5 "http://${node_ip}:${port}/node/info" >/dev/null 2>&1; then
      print_success "$node_name is online!"
      return 0
    fi

    if [[ $((attempt % 10)) -eq 0 ]]; then
      print_status "  Attempt $attempt/$max_attempts: waiting for HTTP response..."
    fi

    sleep "$delay"
    ((attempt++))
  done

  print_error "$node_name did not come online after $max_attempts attempts"
  return 1
}

check_process_running() {
  local node_ip="$1"
  local jar_name="$2"

  # Use jps to check if the specific JAR is running
  local count=$(ssh_exec "$node_ip" "jps -l | grep '$jar_name' | wc -l" 2>/dev/null || echo "0")
  [[ "$count" -gt 0 ]]
}

kill_remote_process() {
  local node_ip="$1"
  local jar_name="$2"

  print_status "Killing $jar_name process on $node_ip..."

  # Use jps to find and kill the specific process
  local kill_cmd='
    for pid in $(jps -l | grep "'$jar_name'" | awk "{print \$1}"); do
      kill -9 $pid 2>/dev/null
    done
  '
  ssh_exec "$node_ip" "$kill_cmd" || true
  sleep 2
}

check_remote_data_exists() {
  local node_ip="$1"
  local data_dir="$2"

  ssh_exec "$node_ip" "test -d $data_dir && echo 'yes' || echo 'no'" 2>/dev/null
}

#######################
# Tessellation Functions
#######################

download_tessellation_jar() {
  local jar_name="$1"
  local output_dir="$2"

  if [[ -f "$output_dir/$jar_name" ]]; then
    print_status "$jar_name already exists, skipping download"
    return 0
  fi

  print_status "Downloading $jar_name..."

  local release_info
  release_info=$(curl -s "${TESSELLATION_RELEASES_URL}/latest")

  if [[ $? -ne 0 ]]; then
    print_error "Failed to fetch Tessellation release information"
    return 1
  fi

  local download_url
  download_url=$(echo "$release_info" | jq -r ".assets[] | select(.name == \"$jar_name\") | .browser_download_url")

  if [[ -z "$download_url" ]] || [[ "$download_url" == "null" ]]; then
    print_error "$jar_name not found in latest release"
    return 1
  fi

  if ! curl -L -o "$output_dir/$jar_name" "$download_url" 2>/dev/null; then
    print_error "Failed to download $jar_name"
    return 1
  fi

  print_success "$jar_name downloaded successfully"
  return 0
}

download_tessellation_jars() {
  local output_dir="$1"

  mkdir -p "$output_dir"

  for jar in "${TESSELLATION_JARS[@]}"; do
    if ! download_tessellation_jar "$jar" "$output_dir"; then
      return 1
    fi
  done

  print_success "All Tessellation JARs downloaded"
  return 0
}

#######################
# Wallet Functions
#######################

get_wallet_address() {
  local keystore_file="$1"
  local keystore_alias="$2"
  local keystore_password="$3"

  CL_KEYSTORE="$keystore_file" \
  CL_KEYALIAS="$keystore_alias" \
  CL_PASSWORD="$keystore_password" \
  java -jar "$JARS_DIR/cl-wallet.jar" show-address 2>/dev/null
}

get_wallet_id() {
  local keystore_file="$1"
  local keystore_alias="$2"
  local keystore_password="$3"

  CL_KEYSTORE="$keystore_file" \
  CL_KEYALIAS="$keystore_alias" \
  CL_PASSWORD="$keystore_password" \
  java -jar "$JARS_DIR/cl-wallet.jar" show-id 2>/dev/null
}

show_wallet_info() {
  local keystore_file="$1"
  local keystore_alias="$2"
  local keystore_password="$3"
  local label="$4"

  if [[ ! -f "$keystore_file" ]]; then
    print_error "$label keystore not found: $keystore_file"
    return 1
  fi

  local address
  address=$(get_wallet_address "$keystore_file" "$keystore_alias" "$keystore_password")

  if [[ $? -ne 0 ]] || [[ -z "$address" ]]; then
    print_error "Failed to retrieve $label address"
    return 1
  fi

  local wallet_id
  wallet_id=$(get_wallet_id "$keystore_file" "$keystore_alias" "$keystore_password")

  if [[ $? -ne 0 ]] || [[ -z "$wallet_id" ]]; then
    print_error "Failed to retrieve $label ID"
    return 1
  fi

  print_success "$label:"
  print_status "  Address: $address"
  print_status "  ID: $wallet_id"

  return 0
}

#######################
# Cleanup Functions
#######################

cleanup_temp_files() {
  local temp_dir="$1"

  if [[ -d "$temp_dir" ]]; then
    rm -rf "$temp_dir"
  fi
}

#######################
# Help Functions
#######################

show_help() {
  local script_name="$1"
  local usage="$2"
  local description="$3"

  cat << EOF
Workchain Metagraph Deployment - $script_name

USAGE:
    $usage

DESCRIPTION:
    $description

REQUIREMENTS:
    - SSH access to all nodes
    - Node IPs configured in deploy/config/deploy-config.sh
    - Keystore files in deploy/keys/
    - Genesis CSV in deploy/genesis/

For more information, see deploy/README.md
EOF
}
