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
  /usr/bin/printf '%s\n' 'backend bootstrap error: script must be executed directly' >&2
  exit 2
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
lock="$repository_root/backend.lock.json"
destination_root="$repository_root/.toolchain/backends"

if [[ ! -d "$repository_root/.toolchain" || -L "$repository_root/.toolchain" ]]; then
  /usr/bin/printf '%s\n' 'backend bootstrap error: bootstrap the toolchain first' >&2
  exit 2
fi
if [[ ! -e "$destination_root" && ! -L "$destination_root" ]]; then
  /usr/bin/install -d -m 700 -- "$destination_root"
fi
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-private-directory "$destination_root"
java_fields="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked)"
mapfile -t java_values <<<"$java_fields"
java_home="$repository_root/.toolchain/${java_values[5]}"

verify_artifact() {
  /usr/bin/python3 -I "$script_directory/backend_lock.py" \
    "$lock" "$1" --verify "$2"
}

install_artifact() {
  local backend="$1"
  local fields
  local -a values=()
  fields="$(/usr/bin/python3 -I "$script_directory/backend_lock.py" "$lock" "$backend")"
  mapfile -t values <<<"$fields"
  local url="${values[1]}"
  local destination="$destination_root/${values[4]}"
  if [[ -e "$destination" || -L "$destination" ]]; then
    verify_artifact "$backend" "$destination"
    return
  fi
  local temporary
  temporary="$(/usr/bin/mktemp "$destination_root/.download.XXXXXXXX")"
  trap '/usr/bin/rm -f -- "$temporary"' RETURN
  /usr/bin/curl --fail --location --proto '=https' --proto-redir '=https' \
    --tlsv1.2 --output "$temporary" "$url"
  /usr/bin/chmod 600 -- "$temporary"
  verify_artifact "$backend" "$temporary"
  if ! /usr/bin/ln -- "$temporary" "$destination"; then
    verify_artifact "$backend" "$destination"
  fi
  /usr/bin/rm -f -- "$temporary"
  trap - RETURN
  verify_artifact "$backend" "$destination"
}

install_artifact jacoco-agent
install_artifact jacoco-cli

mutation_fields="$(/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  "$lock" mutate4java)"
mapfile -t mutation_values <<<"$mutation_fields"
mutation_source="$destination_root/${mutation_values[8]}"
mutation_jar="$destination_root/${mutation_values[9]}"

verify_mutation_source() {
  /usr/bin/python3 -I "$script_directory/backend_lock.py" \
    "$lock" mutate4java --verify-source "$1"
}

verify_mutation_jar() {
  /usr/bin/python3 -I "$script_directory/backend_lock.py" \
    "$lock" mutate4java --verify-jar "$1"
}

install_mutation_source() {
  if [[ -e "$mutation_source" || -L "$mutation_source" ]]; then
    verify_mutation_source "$mutation_source"
    return
  fi
  local temporary
  temporary="$(/usr/bin/mktemp "$destination_root/.source.XXXXXXXX")"
  trap '/usr/bin/rm -f -- "$temporary"' RETURN
  /usr/bin/curl --fail --location --proto '=https' --proto-redir '=https' \
    --tlsv1.2 --output "$temporary" "${mutation_values[5]}"
  /usr/bin/chmod 600 -- "$temporary"
  verify_mutation_source "$temporary"
  if ! /usr/bin/ln -- "$temporary" "$mutation_source"; then
    verify_mutation_source "$mutation_source"
  fi
  /usr/bin/rm -f -- "$temporary"
  trap - RETURN
  verify_mutation_source "$mutation_source"
}

safe_remove_build_root() {
  local build_root="$1"
  case "$build_root" in
    "$destination_root"/.mutation-build.*)
      /usr/bin/rm -rf -- "$build_root"
      ;;
    *)
      /usr/bin/printf '%s\n' 'backend bootstrap error: unsafe build cleanup path' >&2
      return 2
      ;;
  esac
}

build_mutation_jar() {
  if [[ -e "$mutation_jar" || -L "$mutation_jar" ]]; then
    verify_mutation_jar "$mutation_jar"
    return
  fi
  local build_root
  build_root="$(/usr/bin/mktemp -d "$destination_root/.mutation-build.XXXXXXXX")"
  trap 'safe_remove_build_root "$build_root"' RETURN
  local source_root="$build_root/source"
  local classes_root="$build_root/classes"
  /usr/bin/install -d -m 700 -- "$source_root" "$classes_root"
  /usr/bin/tar --extract --gzip --file "$mutation_source" \
    --directory "$source_root" --strip-components=1 \
    --no-same-owner --no-same-permissions
  if [[ -n "$(/usr/bin/find "$source_root" -type l -print -quit)" ]]; then
    /usr/bin/printf '%s\n' 'backend bootstrap error: mutation source contains a symlink' >&2
    return 2
  fi
  local source_count
  source_count="$(/usr/bin/find "$source_root/src/mutate4java" \
    -type f -name '*.java' -print | /usr/bin/wc -l)"
  if [[ "$source_count" != "${mutation_values[13]}" ]]; then
    /usr/bin/printf '%s\n' 'backend bootstrap error: mutation source count mismatch' >&2
    return 2
  fi
  /usr/bin/find "$source_root/src/mutate4java" -type f -name '*.java' -print0 \
    | /usr/bin/sort -z \
    | /usr/bin/xargs -0 "$java_home/bin/javac" \
      -encoding UTF-8 --release 17 -proc:none -classpath '' -sourcepath '' \
      -d "$classes_root"
  local class_count
  class_count="$(/usr/bin/find "$classes_root" -type f -name '*.class' -print \
    | /usr/bin/wc -l)"
  if [[ "$class_count" != "${mutation_values[14]}" ]]; then
    /usr/bin/printf '%s\n' 'backend bootstrap error: mutation class count mismatch' >&2
    return 2
  fi
  local temporary_jar="$build_root/${mutation_values[9]}"
  "$java_home/bin/jar" --create --file "$temporary_jar" \
    --date=2000-01-01T00:00:00Z --main-class "${mutation_values[12]}" \
    -C "$classes_root" .
  /usr/bin/chmod 600 -- "$temporary_jar"
  verify_mutation_jar "$temporary_jar"
  if ! /usr/bin/ln -- "$temporary_jar" "$mutation_jar"; then
    verify_mutation_jar "$mutation_jar"
  fi
  verify_mutation_jar "$mutation_jar"
  safe_remove_build_root "$build_root"
  trap - RETURN
}

install_mutation_source
build_mutation_jar
