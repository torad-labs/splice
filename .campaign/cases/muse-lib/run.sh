#!/bin/sh
# Runs the cases of one muse-tool-cache item: sh run.sh <the item's folder>, from the repo's folder.
# The item's cases.ts prints one line for each case: PASS <name>, or FAIL <name>: <what happened instead>. Should it
# end before a case has reported (bun is missing, the file does not load, it was killed), that case fails here, so
# every name in the item's names file is printed once, and the script ends with 0.
dir=$1
out=$(mktemp)
bun "$dir/cases.ts" > "$out"
code=$?
grep -E '^(PASS|FAIL) ' "$out"
said=$(mktemp)
sed -n -E 's/^(PASS|FAIL) //p' "$out" | sed -E -e 's/: .*$//' -e 's/[[:space:]]+$//' > "$said"
while IFS= read -r name || [ -n "$name" ]; do
	[ -n "$name" ] || continue
	grep -F -x -q -e "$name" "$said" || echo "FAIL $name: its cases ended (exit $code) before this one reported"
done < "$dir/names"
rm -f "$out" "$said"
exit 0
