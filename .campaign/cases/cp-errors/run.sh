#!/bin/sh
# cp-errors: a 529 from the provider carrying Retry-After reaches the session at once as a 529 overloaded_error
# with that retry-after, sent upstream once, as a 503 with Retry-After does.
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/setup.sh"
bun run "$HERE/case.ts"
exit 0
