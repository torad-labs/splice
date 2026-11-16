#!/bin/sh
# Runs the cases in a folder as they were frozen there. The folder's names file lists the cases, one name on a line.
# It fails when a case fails, when run.sh does not end with 0, and when the cases that reported are not exactly the
# ones listed, each once: a run.sh that stops early, or was cut down, is no pass.
# The folder is named from where this script is, unless it is given whole: a folder of the product named like an item
# is never the item's cases.
case $1 in /*) dir=$1 ;; *) dir=$(dirname "$0")/$1 ;; esac
out=$(CAMPAIGN_CASE="$dir" sh "$dir/run.sh" 2>&1)
code=$?
lines=$(printf '%s\n' "$out" | grep -c -E '^(PASS|FAIL) ')
failing=$(printf '%s\n' "$out" | grep -c -E '^FAIL ')
printf '%s\n' "$out" | grep -E '^FAIL ' | head -n 30
if [ -f "$dir/names" ]; then
	if [ "$code" != 0 ]; then
		echo "run.sh exited $code: it ends with 0 when every case has reported, so the cases did not finish"
		printf '%s\n' "$out" | grep -v -E '^(PASS|FAIL) ' | tail -n 5
		exit 1
	fi
	got=$(mktemp)
	printf '%s\n' "$out" | sed -n -E 's/^(PASS|FAIL) //p' | sed -E -e 's/: .*$//' -e 's/[[:space:]]+$//' > "$got"
	odd=$( { grep -F -x -v -f "$got" "$dir/names" | sed 's/^/did not run: /'; sort "$got" | uniq -d | sed 's/^/ran more than once: /'; grep -F -x -v -f "$dir/names" "$got" | sort -u | sed 's/^/was not frozen: /'; } | head -n 30)
	rm -f "$got"
	[ -z "$odd" ] || { printf '%s\n' "$odd"; exit 1; }
else
	# Cases frozen before their names were kept are held to how many there were.
	want=$(cat "$dir/count" 2>/dev/null)
	if [ "$lines" != "$want" ]; then
		echo "$lines of the $want frozen cases ran"
		printf '%s\n' "$out" | grep -v -E '^(PASS|FAIL) ' | tail -n 5
		exit 1
	fi
fi
[ "$failing" = 0 ] || { echo "$failing of $lines cases fail"; exit 1; }
echo "all $lines cases pass"
