#!/bin/sh
# cp-schema: a ca_file that holds no certificate must be a boot finding (providers.acme.ca_file), not a crash.
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/setup.sh"
bun run "$HERE/case.ts"
exit 0
