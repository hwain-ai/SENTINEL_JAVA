#!/usr/bin/env -S -i SENTINEL_JAVA_SEALED_ENTRY=direct-v1 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 077

sealed_entry_is_direct() {
  local -a process_arguments=()
  local -a process_environment=()
  [[ -r "/proc/$$/cmdline" && -r "/proc/$$/environ" ]] || return 1
  mapfile -d '' -t process_arguments <"/proc/$$/cmdline"
  mapfile -d '' -t process_environment <"/proc/$$/environ"
  [[ "${#process_arguments[@]}" -ge 4 ]] || return 1
  [[ "${process_arguments[0]}" == "/usr/bin/bash" ]] || return 1
  [[ "${process_arguments[1]}" == "--noprofile" ]] || return 1
  [[ "${process_arguments[2]}" == "--norc" ]] || return 1
  [[ "${process_arguments[3]}" == "${BASH_SOURCE[0]}" ]] || return 1
  [[ "${#process_environment[@]}" -eq 1 ]] || return 1
  [[ "${process_environment[0]}" == "SENTINEL_JAVA_SEALED_ENTRY=direct-v1" ]]
}

sealed_entry_is_direct || {
  /usr/bin/printf '%s\n' 'doctor error: script must be executed directly' >&2
  exit 2
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
cd -- "$repository_root"

java_fields="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  toolchain.lock.json java --require-locked)"
maven_fields="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  toolchain.lock.json maven --require-locked)"
mapfile -t java_values <<<"$java_fields"
mapfile -t maven_values <<<"$maven_fields"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  toolchain.lock.json java --require-locked \
  --verify-tree ".toolchain/${java_values[5]}"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  toolchain.lock.json maven --require-locked \
  --verify-tree ".toolchain/${maven_values[5]}"

agent_fields="$(/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json jacoco-agent)"
cli_fields="$(/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json jacoco-cli)"
mutation_fields="$(/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json mutate4java)"
mapfile -t agent_values <<<"$agent_fields"
mapfile -t cli_values <<<"$cli_fields"
mapfile -t mutation_values <<<"$mutation_fields"
/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json jacoco-agent \
  --verify ".toolchain/backends/${agent_values[4]}"
/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json jacoco-cli \
  --verify ".toolchain/backends/${cli_values[4]}"
/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json mutate4java \
  --verify-source ".toolchain/backends/${mutation_values[8]}"
/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json mutate4java \
  --verify-jar ".toolchain/backends/${mutation_values[9]}"

/usr/bin/printf '%s\n' \
  "{\"schemaVersion\":\"sentinel-java-doctor-v1\",\"passed\":true,\"java\":\"${java_values[0]}\",\"maven\":\"${maven_values[0]}\",\"jacoco\":\"${agent_values[0]}\",\"jacocoAgentSha256\":\"${agent_values[3]}\",\"jacocoCliSha256\":\"${cli_values[3]}\",\"mutate4javaCommit\":\"${mutation_values[2]}\",\"mutate4javaArchiveSha256\":\"${mutation_values[3]}\",\"mutate4javaSourceArchiveSha256\":\"${mutation_values[7]}\",\"mutate4javaJarSha256\":\"${mutation_values[11]}\",\"mutationBackendStatus\":\"${mutation_values[4]}\"}"
