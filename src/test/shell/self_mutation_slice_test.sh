#!/usr/bin/bash
set -euo pipefail

output_file="$(/usr/bin/mktemp)"
error_file="$(/usr/bin/mktemp)"
trap '/usr/bin/rm -f -- "$output_file" "$error_file"' EXIT

./scripts/self-mutation-slice.sh >"$output_file" 2>"$error_file"
/usr/bin/grep -Fx \
  '{"schemaVersion":"sentinel-java-mutation-proof-v1","state":"KILLED","candidateCount":1,"controls":2,"replays":2,"strictKillRate":"100.000000"}' \
  "$output_file"
