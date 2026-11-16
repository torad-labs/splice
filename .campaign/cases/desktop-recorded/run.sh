#!/bin/sh
# The cases of desktop-recorded. From the repo's folder: sh .campaign/cases/desktop-recorded/run.sh
# It prints one line for each case in the names file beside it, PASS <name> or FAIL <name>: <what happened instead>,
# and ends with 0. The cases are in cases.ts; what runs them is ../desktop-lib/run.sh.
dir=${CAMPAIGN_CASE:-$(dirname "$0")}
exec sh "$dir/../desktop-lib/run.sh" "$dir"
