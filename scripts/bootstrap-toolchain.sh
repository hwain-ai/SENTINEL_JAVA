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
lock_file="$repository_root/toolchain.lock.json"

read_lock() {
  /usr/bin/python3 -I "$script_directory/toolchain_lock.py" "$lock_file" "$1" --require-locked
}

verify_archive() {
  local archive="$1"
  local expected_size="$2"
  local expected_sha256="$3"
  test -f "$archive" && test ! -L "$archive"
  test "$(stat -c '%s' -- "$archive")" = "$expected_size"
  test "$(sha256sum -- "$archive" | cut -d ' ' -f 1)" = "$expected_sha256"
}

verify_tree() {
  /usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
    "$lock_file" "$1" --require-locked --verify-tree "$2"
}

verify_private_directory() {
  /usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
    "$lock_file" java --require-locked --verify-private-directory "$1"
}

ensure_private_directory() {
  if [[ ! -e "$1" && ! -L "$1" ]]; then
    /usr/bin/install -d -m 700 -- "$1"
  fi
  verify_private_directory "$1"
}

binary_relative_path() {
  if [[ "$1" == "java" ]]; then
    printf '%s\n' 'bin/java'
  else
    printf '%s\n' 'bin/mvn'
  fi
}

verify_binary() {
  local binary="$2/$(binary_relative_path "$1")"
  test -f "$binary" && test ! -L "$binary" && test -x "$binary"
  test "$(sha256sum -- "$binary" | cut -d ' ' -f 1)" = "$3"
}

verify_version() {
  local tool="$1"
  local root="$2"
  local actual
  if [[ "$tool" == "java" ]]; then
    actual="$(env -i HOME="$repository_root/.toolchain/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
      PATH="$root/bin:/usr/bin:/bin" JAVA_HOME="$root" \
      "$root/bin/java" -version 2>&1 | /usr/bin/sed -n '1p')"
  else
    local java_values
    java_values="$(read_lock java)"
    mapfile -t java_fields <<<"$java_values"
    local java_home="$repository_root/.toolchain/${java_fields[5]}"
    actual="$(env -i HOME="$repository_root/.toolchain/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
      PATH="$java_home/bin:$root/bin:/usr/bin:/bin" JAVA_HOME="$java_home" \
      MAVEN_HOME="$root" MAVEN_SKIP_RC=true \
      "$root/bin/mvn" --version 2>&1 | /usr/bin/sed -n '1p')"
  fi
  /usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
    "$lock_file" "$tool" --require-locked --verify-version-output "$actual"
}

bootstrap() {
  local tool="$1"
  local values
  values="$(read_lock "$tool")"
  mapfile -t fields <<<"$values"
  local version="${fields[0]}"
  local url="${fields[1]}"
  local size="${fields[2]}"
  local archive_sha256="${fields[3]}"
  local archive_root="${fields[4]}"
  local install_directory="${fields[5]}"
  local binary_sha256="${fields[6]}"
  local version_output="${fields[7]}"
  local tree_sha256="${fields[8]}"
  local cache_directory="$repository_root/.toolchain/downloads"
  local archive="$cache_directory/$archive_sha256.tar.gz"
  local destination="$repository_root/.toolchain/$install_directory"
  local staging="$repository_root/.toolchain/.staging-$install_directory"

  if [[ -d "$destination" && ! -L "$destination" ]]; then
    verify_tree "$tool" "$destination"
    verify_binary "$tool" "$destination" "$binary_sha256"
    verify_version "$tool" "$destination"
    return
  fi
  mkdir -p -- "$cache_directory"
  if ! verify_archive "$archive" "$size" "$archive_sha256"; then
    rm -f -- "$archive"
    rm -f -- "$archive.part"
    curl --fail --location --proto '=https' --proto-redir '=https' \
      --tlsv1.2 --output "$archive.part" "$url"
    verify_archive "$archive.part" "$size" "$archive_sha256"
    mv -- "$archive.part" "$archive"
  fi
  rm -rf -- "$staging"
  mkdir -- "$staging"
  tar --extract --gzip --file "$archive" --directory "$staging" --no-same-owner --no-same-permissions
  test -d "$staging/$archive_root" && test ! -L "$staging/$archive_root"
  verify_tree "$tool" "$staging/$archive_root"
  verify_binary "$tool" "$staging/$archive_root" "$binary_sha256"
  verify_version "$tool" "$staging/$archive_root"
  test ! -e "$destination"
  mv -- "$staging/$archive_root" "$destination"
  rmdir -- "$staging"
  test -n "$version" && test -n "$binary_sha256" && test -n "$version_output" && test -n "$tree_sha256"
}

# Parse both locks before writing anything. A partially approved lock must fail closed.
read_lock java >/dev/null
read_lock maven >/dev/null
cd -- "$repository_root"
if [[ -e .toolchain || -L .toolchain ]]; then
  [[ -d .toolchain && ! -L .toolchain ]] || {
    printf '%s\n' 'toolchain error: .toolchain must be a local directory' >&2
    exit 2
  }
fi
ensure_private_directory "$repository_root/.toolchain"
ensure_private_directory "$repository_root/.toolchain/home"
ensure_private_directory "$repository_root/.toolchain/m2"
ensure_private_directory "$repository_root/.toolchain/downloads"
bootstrap java
bootstrap maven
