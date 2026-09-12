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
cd -- "$repository_root"

valid_test_selector() {
  local value="$1"
  [[ "$value" =~ ^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(,[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*)*$ ]]
}

for argument in "$@"; do
  case "$argument" in
    -o|--offline|-B|--batch-mode|-ntp|--no-transfer-progress|-q|--quiet|-e|--errors|-V|--show-version|-v|--version|clean|compile|test|package|verify)
      ;;
    -Dtest=*|-Dit.test=*)
      valid_test_selector "${argument#*=}" || {
        printf 'toolchain error: Maven argument is not allowed: %s\n' "$argument" >&2
        exit 2
      }
      ;;
    *)
      printf 'toolchain error: Maven argument is not allowed: %s\n' "$argument" >&2
      exit 2
      ;;
  esac
done
unset -f valid_test_selector

if [[ -e .mvn || -L .mvn ]]; then
  printf '%s\n' 'toolchain error: repository .mvn overrides are not allowed' >&2
  exit 2
fi
java_values="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked)"
maven_values="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" maven --require-locked)"
mapfile -t java_fields <<<"$java_values"
mapfile -t maven_fields <<<"$maven_values"
java_home="$repository_root/.toolchain/${java_fields[5]}"
maven_home="$repository_root/.toolchain/${maven_fields[5]}"
java_binary="$java_home/bin/java"
maven_binary="$maven_home/bin/mvn"

if [[ ! -d .toolchain || -L .toolchain ]]; then
  printf '%s\n' 'toolchain error: verified local toolchain root is missing' >&2
  exit 2
fi
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-private-directory "$repository_root/.toolchain"
for private_directory in .toolchain/home .toolchain/m2; do
  if [[ ! -e "$private_directory" && ! -L "$private_directory" ]]; then
    /usr/bin/install -d -m 700 -- "$private_directory"
  fi
  /usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
    "$repository_root/toolchain.lock.json" java --require-locked \
    --verify-private-directory "$repository_root/$private_directory"
done
for user_configuration in \
  .toolchain/home/.m2/settings.xml \
  .toolchain/home/.m2/settings-security.xml \
  .toolchain/home/.m2/toolchains.xml \
  .toolchain/home/.mavenrc; do
  if [[ -e "$user_configuration" || -L "$user_configuration" ]]; then
    printf 'toolchain error: Maven user configuration is not allowed: %s\n' \
      "$user_configuration" >&2
    exit 2
  fi
done
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked --verify-tree "$java_home"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" maven --require-locked --verify-tree "$maven_home"
if [[ ! -d "$java_home" || -L "$java_home" || ! -f "$java_binary"
      || -L "$java_binary" || ! -x "$java_binary" ]]; then
  printf '%s\n' 'toolchain error: verified Java tree is missing' >&2
  exit 2
fi
if [[ ! -d "$maven_home" || -L "$maven_home" || ! -f "$maven_binary"
      || -L "$maven_binary" || ! -x "$maven_binary" ]]; then
  printf '%s\n' 'toolchain error: verified Maven tree is missing' >&2
  exit 2
fi
if [[ "$(sha256sum -- "$java_binary" | cut -d ' ' -f 1)" != "${java_fields[6]}" ]]; then
  printf '%s\n' 'toolchain error: Java binary checksum mismatch' >&2
  exit 2
fi
if [[ "$(sha256sum -- "$maven_binary" | cut -d ' ' -f 1)" != "${maven_fields[6]}" ]]; then
  printf '%s\n' 'toolchain error: Maven binary checksum mismatch' >&2
  exit 2
fi

java_version="$(env -i HOME="$repository_root/.toolchain/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:/usr/bin:/bin" JAVA_HOME="$java_home" \
  "$java_binary" -version 2>&1 | /usr/bin/sed -n '1p')"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-version-output "$java_version"
maven_version="$(env -i HOME="$repository_root/.toolchain/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:$maven_home/bin:/usr/bin:/bin" JAVA_HOME="$java_home" \
  MAVEN_HOME="$maven_home" MAVEN_SKIP_RC=true \
  "$maven_binary" --version 2>&1 | /usr/bin/sed -n '1p')"
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" maven --require-locked \
  --verify-version-output "$maven_version"
exec env -i \
  HOME="$repository_root/.toolchain/home" \
  LANG=C.UTF-8 \
  LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:$maven_home/bin:/usr/bin:/bin" \
  JAVA_HOME="$java_home" \
  MAVEN_HOME="$maven_home" \
  MAVEN_SKIP_RC=true \
  "$maven_binary" -o -B -ntp -Dmaven.repo.local="$repository_root/.toolchain/m2" "$@"
