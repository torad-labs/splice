#!/bin/sh
# cp-login: DELETE /api/auth/<head>/login/<id> cancels a running sign-in, killing the login command.
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/setup.sh"
bun run "$HERE/case.ts"
exit 0
