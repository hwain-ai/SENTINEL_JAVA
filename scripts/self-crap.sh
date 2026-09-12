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
  /usr/bin/printf '%s\n' 'self-crap error: script must be executed directly' >&2
  exit 4
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
cd -- "$repository_root"

"$script_directory/bootstrap-backends.sh"
"$script_directory/bootstrap-pit-probe.sh"

run_root="$repository_root/.toolchain/self-crap-runs"
if [[ ! -e "$run_root" && ! -L "$run_root" ]]; then
  /usr/bin/install -d -m 700 -- "$run_root"
fi
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-private-directory "$run_root"
attempt="$(/usr/bin/mktemp -d "$run_root/attempt.XXXXXXXX")"
if [[ -e target || -L target ]]; then
  [[ -d target && ! -L target ]] || {
    /usr/bin/printf '%s\n' 'self-crap error: target must be a directory' >&2
    exit 4
  }
  /usr/bin/mv -- target "$attempt/prior-target"
fi

"$script_directory/mvn.sh" -q test >&2
[[ -s target/jacoco.exec ]] || {
  /usr/bin/printf '%s\n' 'self-crap error: fresh JaCoCo execution data is missing' >&2
  exit 4
}
/usr/bin/mkdir -p -- target/site/jacoco
"$script_directory/java.sh" \
  -jar .toolchain/backends/org.jacoco.cli-0.8.12-nodeps.jar \
  --quiet report target/jacoco.exec \
  --classfiles target/classes \
  --sourcefiles src/main/java \
  --encoding UTF-8 \
  --xml target/site/jacoco/jacoco.xml
"$script_directory/java.sh" \
  -cp target/classes \
  io.github.hwainhwang.sentinel.cli.SelfCrapMain \
  "$repository_root" src/main/java target/site/jacoco/jacoco.xml \
  .toolchain/m2/org/junit/platform/junit-platform-launcher/1.10.2/junit-platform-launcher-1.10.2.jar \
  .toolchain/m2/org/junit/platform/junit-platform-engine/1.10.2/junit-platform-engine-1.10.2.jar \
  .toolchain/m2/org/junit/platform/junit-platform-commons/1.10.2/junit-platform-commons-1.10.2.jar \
  .toolchain/m2/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar
