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
  /usr/bin/printf '%s\n' 'typed test error: script must be executed directly' >&2
  exit 2
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

if [[ "$#" -ne 3 && "$#" -ne 4 ]]; then
  /usr/bin/printf '%s\n' 'typed test error: usage' >&2
  exit 2
fi
selector="$1"
source_relative="$2"
evidence_directory="$3"
retain_key=false
if [[ "$#" -eq 4 ]]; then
  [[ "$4" == "--retain-proof-key" ]] || {
    /usr/bin/printf '%s\n' 'typed test error: usage' >&2
    exit 2
  }
  retain_key=true
fi
if [[ ! "$selector" =~ ^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$ ]]; then
  /usr/bin/printf '%s\n' 'typed test error: selector invalid' >&2
  exit 2
fi

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
tool_root="$(cd -- "$script_directory/.." && pwd -P)"
project_root="$(pwd -P)"
if [[ ! -f "$project_root/pom.xml" || -L "$project_root/pom.xml"
      || -e "$project_root/.mvn" || -L "$project_root/.mvn" ]]; then
  /usr/bin/printf '%s\n' 'typed test error: project root invalid' >&2
  exit 2
fi
request="$project_root/target/sentinel-junit-request-v1"
request_arguments=(write "$project_root" "$source_relative" "$evidence_directory")
if [[ "$retain_key" == true ]]; then
  request_arguments+=(--retain-key)
fi
fields="$(/usr/bin/python3 -I "$script_directory/junit_request.py" \
  "${request_arguments[@]}")"
mapfile -t values <<<"$fields"
nonce="${values[0]}"
source_sha256="${values[1]}"
event_file="${values[2]}"
hmac_key="${values[3]}"
cleanup_request() {
  /usr/bin/rm -f -- "$request"
}
trap cleanup_request EXIT

java_fields="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$tool_root/toolchain.lock.json" java --require-locked)"
maven_fields="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$tool_root/toolchain.lock.json" maven --require-locked)"
mapfile -t java_values <<<"$java_fields"
mapfile -t maven_values <<<"$maven_fields"
java_home="$tool_root/.toolchain/${java_values[5]}"
maven_home="$tool_root/.toolchain/${maven_values[5]}"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$tool_root/toolchain.lock.json" java --require-locked --verify-tree "$java_home"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$tool_root/toolchain.lock.json" maven --require-locked --verify-tree "$maven_home"
agent_fields="$(/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  "$project_root/backend.lock.json" jacoco-agent)"
mapfile -t agent_values <<<"$agent_fields"
/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  "$project_root/backend.lock.json" jacoco-agent \
  --verify "$project_root/.toolchain/backends/${agent_values[4]}"

set +e
(cd -- "$project_root" && exec /usr/bin/env -i \
  HOME="$tool_root/.toolchain/home" \
  LANG=C.UTF-8 \
  LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:$maven_home/bin:/usr/bin:/bin" \
  JAVA_HOME="$java_home" \
  MAVEN_HOME="$maven_home" \
  MAVEN_SKIP_RC=true \
  "$maven_home/bin/mvn" -o -B -ntp \
  -Dmaven.repo.local="$tool_root/.toolchain/m2" -Dtest="$selector" test)
test_exit=$?
set -e
if ! "$script_directory/java.sh" -cp "$project_root/target/classes" \
  io.github.hwainhwang.sentinel.cli.JUnitEventValidateMain \
  "$event_file" "$nonce" "$source_sha256" "$hmac_key"; then
  /usr/bin/printf '%s\n' 'typed test error: typed JUnit evidence missing or invalid' >&2
  exit 4
fi
exit "$test_exit"
