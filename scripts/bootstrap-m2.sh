#!/usr/bin/env -S -i SENTINEL_JAVA_SEALED_ENTRY=direct-v1 /usr/bin/bash --noprofile --norc
# Populates the checker's private offline Maven repository (.toolchain/m2) once.
# The sealed mvn.sh only runs offline, so a fresh clone needs this single online
# resolution of the checker's own build, test and package plugins and dependencies.
set -euo pipefail
umask 077

sealed_entry_is_direct() {
  local -a process_arguments=() process_environment=()
  [[ -r "/proc/$$/cmdline" && -r "/proc/$$/environ" ]] || return 1
  mapfile -d '' -t process_arguments <"/proc/$$/cmdline"
  mapfile -d '' -t process_environment <"/proc/$$/environ"
  [[ "${#process_arguments[@]}" -ge 4 ]] || return 1
  [[ "${process_arguments[0]}" == "/usr/bin/bash" ]] || return 1
  [[ "${process_arguments[1]}" == "--noprofile" && "${process_arguments[2]}" == "--norc" ]] || return 1
  [[ "${process_arguments[3]}" == "${BASH_SOURCE[0]}" ]] || return 1
  [[ "${#process_environment[@]}" -eq 1 ]] || return 1
  [[ "${process_environment[0]}" == "SENTINEL_JAVA_SEALED_ENTRY=direct-v1" ]]
}

sealed_entry_is_direct || {
  /usr/bin/printf '%s\n' 'm2 bootstrap error: script must be executed directly' >&2
  exit 2
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
cd -- "$repository_root"

java_values="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked)"
maven_values="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" maven --require-locked)"
mapfile -t java_fields <<<"$java_values"
mapfile -t maven_fields <<<"$maven_values"
java_home="$repository_root/.toolchain/${java_fields[5]}"
maven_home="$repository_root/.toolchain/${maven_fields[5]}"
repository="$repository_root/.toolchain/m2"
marker="$repository/.sentinel-populated-v1"

[[ -x "$java_home/bin/java" && -x "$maven_home/bin/mvn" ]] || {
  /usr/bin/printf '%s\n' 'm2 bootstrap error: run scripts/bootstrap-toolchain.sh first' >&2
  exit 2
}
/usr/bin/install -d -m 700 -- "$repository" "$repository_root/.toolchain/home"
if [[ -f "$marker" ]]; then
  /usr/bin/printf '%s\n' 'm2 bootstrap: offline repository already populated' >&2
  exit 0
fi

# One online run through compile, one real test (resolves the Surefire JUnit provider) and package.
env -i \
  HOME="$repository_root/.toolchain/home" \
  LANG=C.UTF-8 \
  LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:$maven_home/bin:/usr/bin:/bin" \
  JAVA_HOME="$java_home" \
  MAVEN_HOME="$maven_home" \
  MAVEN_SKIP_RC=true \
  "$maven_home/bin/mvn" -B -ntp -q -Dmaven.repo.local="$repository" \
  -Dtest=CanonicalDecimalTest -Dsurefire.failIfNoSpecifiedTests=false package
/usr/bin/printf 'populated by scripts/bootstrap-m2.sh\n' > "$marker"
/usr/bin/chmod 600 -- "$marker"
/usr/bin/printf '%s\n' 'm2 bootstrap: offline repository populated' >&2
