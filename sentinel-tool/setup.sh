#!/usr/bin/bash
# First-run preparation for the Java checker: pinned JDK and Maven, locked
# JaCoCo and mutate4java backends, the compiled checker classes and a doctor
# pass. Safe to rerun; each step verifies before it downloads anything.
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
"$root/scripts/bootstrap-toolchain.sh"
"$root/scripts/bootstrap-backends.sh"
"$root/scripts/bootstrap-m2.sh"
"$root/scripts/mvn.sh" -o -B -ntp -q compile
"$root/scripts/doctor.sh" >/dev/null
printf 'sentinel-tool: java checker ready\n' >&2
