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
  /usr/bin/printf '%s\n' 'self mutation error: script must be executed directly' >&2
  exit 2
}
unset -f sealed_entry_is_direct
unset SENTINEL_JAVA_SEALED_ENTRY
export PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd -- "$script_directory/.." && pwd -P)"
cd -- "$repository_root"
if [[ ! "$repository_root" =~ ^/[A-Za-z0-9._/-]+$ ]]; then
  /usr/bin/printf '%s\n' 'self mutation error: repository path is unsafe for upstream shell bridge' >&2
  exit 2
fi

"$script_directory/bootstrap-backends.sh" >/dev/null
"$script_directory/doctor.sh" >/dev/null
"$script_directory/mvn.sh" -o -B -ntp compile >/dev/null

target_source="src/main/java/io/github/hwainhwang/sentinel/crap/ExactCrap.java"
selector="io.github.hwainhwang.sentinel.crap.ExactCrapTest"
original_sha256="$(/usr/bin/sha256sum -- "$target_source" | /usr/bin/cut -d ' ' -f 1)"
runs_root="$repository_root/.toolchain/self-mutation-runs"
if [[ ! -e "$runs_root" && ! -L "$runs_root" ]]; then
  /usr/bin/install -d -m 700 -- "$runs_root"
fi
/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  toolchain.lock.json java --require-locked --verify-private-directory "$runs_root"
attempt="$(/usr/bin/mktemp -d "$runs_root/attempt.XXXXXXXX")"
evidence="$attempt/evidence"
/usr/bin/install -d -m 700 -- "$evidence"

java_fields="$(/usr/bin/python3 -I "$script_directory/toolchain_lock.py" \
  toolchain.lock.json java --require-locked)"
mutation_fields="$(/usr/bin/python3 -I "$script_directory/backend_lock.py" \
  backend.lock.json mutate4java)"
mapfile -t java_values <<<"$java_fields"
mapfile -t mutation_values <<<"$mutation_fields"
java_home="$repository_root/.toolchain/${java_values[5]}"
mutation_jar="$repository_root/.toolchain/backends/${mutation_values[9]}"
agent_file="org.jacoco.agent-0.8.12-runtime.jar"

prepare_snapshot() {
  local snapshot="$1"
  /usr/bin/install -d -m 700 -- "$snapshot"
  /usr/bin/cp -a -- pom.xml backend.lock.json src "$snapshot/"
  /usr/bin/install -d -m 700 -- "$snapshot/.toolchain/backends"
  /usr/bin/install -m 600 -- \
    ".toolchain/backends/$agent_file" "$snapshot/.toolchain/backends/$agent_file"
  local copied_sha256
  copied_sha256="$(/usr/bin/sha256sum -- "$snapshot/$target_source" | /usr/bin/cut -d ' ' -f 1)"
  [[ "$copied_sha256" == "$original_sha256" ]] || {
    /usr/bin/printf '%s\n' 'self mutation error: snapshot source mismatch' >&2
    return 2
  }
}

run_replay() {
  local label="$1"
  local snapshot="$attempt/$label/snapshot"
  /usr/bin/install -d -m 700 -- "$attempt/$label"
  prepare_snapshot "$snapshot"
  local typed_command="$repository_root/scripts/typed-mvn-test.sh $selector $target_source $evidence --retain-proof-key"
  set +e
  (cd -- "$snapshot" && /usr/bin/env -i \
    HOME="$repository_root/.toolchain/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    PATH="$java_home/bin:/usr/bin:/bin" JAVA_HOME="$java_home" \
    "$java_home/bin/java" -jar "$mutation_jar" "$target_source" \
      --lines 64 --max-workers 1 --test-command "$typed_command") \
      >"$attempt/$label/raw.txt" 2>"$attempt/$label/error.txt"
  local exit_code=$?
  set -e
  if [[ "$exit_code" -ne 0 ]]; then
    /usr/bin/printf 'self mutation error: upstream replay %s failed with %s\n' \
      "$label" "$exit_code" >&2
    return 2
  fi
  /usr/bin/grep -Fq \
    'KILLED src/main/java/io/github/hwainhwang/sentinel/crap/ExactCrap.java:64 replace decimal with null' \
    "$attempt/$label/raw.txt"
  /usr/bin/grep -Fq 'Summary: 1 killed, 0 survived, 1 total.' \
    "$attempt/$label/raw.txt"
}

run_replay run-a
run_replay run-b

after_sha256="$(/usr/bin/sha256sum -- "$target_source" | /usr/bin/cut -d ' ' -f 1)"
if [[ "$after_sha256" != "$original_sha256" ]]; then
  /usr/bin/printf '%s\n' 'self mutation error: protected source changed' >&2
  exit 2
fi

events=()
while IFS= read -r -d '' event; do
  events+=("$event")
done < <(/usr/bin/find "$evidence" -maxdepth 1 -type f -name '*.json' -print0 \
  | /usr/bin/sort -z)
if [[ "${#events[@]}" -ne 4 ]]; then
  /usr/bin/printf 'self mutation error: expected 4 typed events, found %s\n' \
    "${#events[@]}" >&2
  exit 2
fi

proof_arguments=("$original_sha256")
key_files=()
for event in "${events[@]}"; do
  key_file="${event%.json}.key"
  if [[ ! -f "$key_file" || -L "$key_file" ]]; then
    /usr/bin/printf '%s\n' 'self mutation error: authenticated event key missing' >&2
    exit 2
  fi
  hmac_key="$(<"$key_file")"
  if [[ ! "$hmac_key" =~ ^[0-9a-f]{64}$ ]]; then
    /usr/bin/printf '%s\n' 'self mutation error: authenticated event key invalid' >&2
    exit 2
  fi
  proof_arguments+=("$event" "$hmac_key")
  key_files+=("$key_file")
done
cleanup_keys() {
  /usr/bin/rm -f -- "${key_files[@]}"
}
trap cleanup_keys EXIT

/usr/bin/env -i HOME="$repository_root/.toolchain/home" LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  PATH="$java_home/bin:/usr/bin:/bin" JAVA_HOME="$java_home" \
  "$java_home/bin/java" -cp "$repository_root/target/classes" \
  io.github.hwainhwang.sentinel.cli.MutationProofMain \
  "${proof_arguments[@]}"
