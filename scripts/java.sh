#!/usr/bin/env -S -i SENTINEL_JAVA_SEALED_ENTRY=direct-v1 /usr/bin/bash --noprofile --norc
set -euo pipefail
umask 022

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
  /usr/bin/printf '%s\n' 'toolchain error: script must be executed directly' >&2
  exit 2
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
values="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked)"
mapfile -t fields <<<"$values"
java_home="$repository_root/.toolchain/${fields[5]}"
java_binary="$java_home/bin/java"

cd -- "$repository_root"
if [[ ! -d .toolchain || -L .toolchain ]]; then
  printf '%s\n' 'toolchain error: verified local toolchain root is missing' >&2
  exit 2
fi
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-private-directory "$repository_root/.toolchain"
if [[ ! -e .toolchain/home && ! -L .toolchain/home ]]; then
  /usr/bin/install -d -m 700 -- .toolchain/home
fi
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-private-directory "$repository_root/.toolchain/home"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked --verify-tree "$java_home"
if [[ ! -d "$java_home" || -L "$java_home" || ! -f "$java_binary"
      || -L "$java_binary" || ! -x "$java_binary" ]]; then
  printf '%s\n' 'toolchain error: verified Java tree is missing' >&2
  exit 2
fi
if [[ "$(sha256sum -- "$java_binary" | cut -d ' ' -f 1)" != "${fields[6]}" ]]; then
  printf '%s\n' 'toolchain error: Java binary checksum mismatch' >&2
  exit 2
fi
actual_version="$(env -i HOME="$repository_root/.toolchain/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:/usr/bin:/bin" JAVA_HOME="$java_home" \
  "$java_binary" -version 2>&1 | /usr/bin/sed -n '1p')"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-version-output "$actual_version"

exec env -i \
  HOME="$repository_root/.toolchain/home" \
  LANG=C.UTF-8 \
  LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:/usr/bin:/bin" \
  JAVA_HOME="$java_home" \
  "$java_binary" "$@"
