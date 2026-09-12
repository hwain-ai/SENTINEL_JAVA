#!/usr/bin/bash
set -euo pipefail

output_file="$(/usr/bin/mktemp)"
error_file="$(/usr/bin/mktemp)"
trap '/usr/bin/rm -f -- "$output_file" "$error_file"' EXIT

./scripts/self-crap.sh >"$output_file" 2>"$error_file"
/usr/bin/grep -Eq \
  '^\{"schemaVersion":"sentinel-java-self-crap-v1","passed":true,"total":[1-9][0-9]*,"known":[1-9][0-9]*,"unknown":0,"aboveLimit":0\}$' \
  "$output_file"
