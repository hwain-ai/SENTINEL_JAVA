#!/bin/sh
# Standard tools first so a hostile PATH cannot hide dirname or python3; the launcher itself gives
# every child a clean environment.
PATH="/usr/bin:/bin:${PATH:-}"
export PATH
# Thin wrapper: the cross-platform launcher lives in scripts/toolchain.py (Linux and macOS).
# `mvn.sh deps PROJECT` and offline Maven with a closed argument set are both forwarded.
exec "${SENTINEL_PYTHON:-python3}" -I -B "$(dirname "$0")/toolchain.py" mvn-or-deps "$@"
