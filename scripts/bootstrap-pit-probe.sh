#!/usr/bin/env -S -i SENTINEL_JAVA_SEALED_ENTRY=direct-v1 /usr/bin/bash --noprofile --norc
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
  /usr/bin/printf '%s\n' 'PIT bootstrap error: script must be executed directly' >&2
  exit 2
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
manifest="$repository_root/src/main/resources/pit-probe-artifacts.tsv"
destination="$repository_root/.toolchain/pit-probe"
pit_download=""
cleanup_download() {
  if [[ -n "$pit_download" && "$pit_download" == "$destination"/.download.* ]]; then
    /usr/bin/rm -f -- "$pit_download"
  fi
}
trap cleanup_download EXIT
[[ -d "$repository_root/.toolchain" && ! -L "$repository_root/.toolchain" ]]
[[ -f "$manifest" && ! -L "$manifest" ]]
if [[ ! -e "$destination" && ! -L "$destination" ]]; then
  /usr/bin/install -d -m 700 -- "$destination"
fi
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  "$repository_root/toolchain.lock.json" java --require-locked \
  --verify-private-directory "$destination"

verify_artifact() {
  local artifact="$1" expected_size="$2" expected_hash="$3"
  [[ -f "$artifact" && ! -L "$artifact" ]]
  [[ "$(/usr/bin/stat -c %h -- "$artifact")" == 1 ]]
  [[ "$(/usr/bin/stat -c %s -- "$artifact")" == "$expected_size" ]]
  [[ "$(/usr/bin/sha256sum -- "$artifact" | /usr/bin/cut -d ' ' -f 1)" == "$expected_hash" ]]
}

install_artifact() {
  local size="$1" hash="$2" file="$3" url="$4"
  if [[ -e "$destination/$file" || -L "$destination/$file" ]]; then
    verify_artifact "$destination/$file" "$size" "$hash"
    return
  fi
  pit_download="$(/usr/bin/mktemp "$destination/.download.XXXXXXXX")"
  /usr/bin/curl --fail --silent --show-error --location --proto '=https' \
    --proto-redir '=https' --tlsv1.2 --max-time 60 --max-filesize "$size" \
    --output "$pit_download" "$url"
  verify_artifact "$pit_download" "$size" "$hash"
  # RISK(side-effect): publish only the validated private download; never replace an installed jar.
  /usr/bin/ln -- "$pit_download" "$destination/$file"
  /usr/bin/rm -- "$pit_download"
  pit_download=""
  verify_artifact "$destination/$file" "$size" "$hash"
}

{
  IFS= read -r header
  [[ "$header" == sentinel-java-pit-artifacts-v1 ]]
  while IFS=$'\t' read -r role version size hash file url license license_url; do
    [[ "$role" =~ ^[a-z0-9-]+$ && "$version" =~ ^[0-9.]+$ ]]
    [[ "$size" =~ ^[1-9][0-9]*$ && "$hash" =~ ^[0-9a-f]{64}$ ]]
    [[ "$file" =~ ^[a-z0-9.-]+\.jar$ && "$url" == https://repo.maven.apache.org/maven2/*/"$file" ]]
    [[ -n "$license" && "$license_url" == https://* ]]
    install_artifact "$size" "$hash" "$file" "$url"
  done
} < "$manifest"
