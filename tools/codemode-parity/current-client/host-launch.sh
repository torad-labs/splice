#!/usr/bin/env bash
set -eu
printf '%s\n' "$$" > "$SPLICE_HOST_PID_FILE"
bun "$SPLICE_HOST_RECORD_SCRIPT"
exec "$SPLICE_HOST_PROGRAM" "$@"

