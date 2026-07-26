#!/usr/bin/env bash
# Red-green fixture test for the 'Update live-probe issue' step.
#
# Extracts the run block of the step named 'Update live-probe issue' from
# .github/workflows/live-probe.yml (or $LIVE_PROBE_YAML) via python3+yaml,
# serves synthetic issue data through a stub `gh` on PATH, and runs the
# extracted block under bash -eo pipefail. All scenarios use zero network calls.
set -euo pipefail

dir="$(cd "$(dirname "$0")" && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

yaml="${LIVE_PROBE_YAML:-$dir/../../workflows/live-probe.yml}"
step_name="Update live-probe issue"

python3 - <<PY > "$tmpdir/step.sh"
import sys
import yaml

with open("$yaml") as f:
    data = yaml.safe_load(f)

for job_name, job in data.get("jobs", {}).items():
    for step in job.get("steps", []):
        if step.get("name") == "$step_name":
            run = step.get("run")
            if run is None:
                print("FAIL: step has no run block", file=sys.stderr)
                sys.exit(1)
            sys.stdout.write(run)
            sys.exit(0)

print("FAIL: step not found", file=sys.stderr)
sys.exit(1)
PY
chmod +x "$tmpdir/step.sh"

cat > "$tmpdir/gh" <<'GH'
#!/usr/bin/env bash
# Synthetic gh for offline testing of the live-probe issue step.
status=0
case "$1" in
  label)
    if [ "${GH_LABEL_CREATE_EXIT:-0}" != "0" ]; then
      echo "could not create label: permission denied" >&2
      status=1
    fi
    ;;
  issue)
    case "$2" in
      list)
        if [ "${GH_ISSUE_LIST_EXIT:-0}" != "0" ]; then
          echo "HTTP 500: Server Error (synthetic)" >&2
          status=1
        elif [[ "$*" == *"--jq .[0].number"* ]]; then
          if [ -n "${GH_ISSUE_LIST_LOOKUP:-}" ]; then
            echo "$GH_ISSUE_LIST_LOOKUP"
          fi
        elif [[ "$*" == *"--jq .[].number"* ]]; then
          if [ -n "${GH_ISSUE_LIST_SUCCESS:-}" ]; then
            echo "$GH_ISSUE_LIST_SUCCESS"
          fi
        fi
        ;;
      create)
        if [[ "$*" == *"--label"* ]]; then
          if [ "${GH_ISSUE_CREATE_LABEL_EXIT:-0}" != "0" ]; then
            echo "could not add label: 'ci-live-probe' not found" >&2
            status=1
          fi
        fi
        ;;
      comment|close)
        if [ "$2" = "close" ] && [ -n "${GH_ISSUE_CLOSE_FAIL:-}" ] && [ "$3" = "${GH_ISSUE_CLOSE_FAIL}" ]; then
          echo "HTTP 502: Bad Gateway (synthetic)" >&2
          status=1
        fi
        ;;
    esac
    ;;
esac
echo "gh $* [$status]" >> "$GH_STUB_LOG"
exit $status
GH
chmod +x "$tmpdir/gh"

export GH_STUB_LOG="$tmpdir/gh.log"
export PATH="$tmpdir:$PATH"

run_scenario() {
  local name="$1"
  local setup_fn="$2"
  : > "$GH_STUB_LOG"
  (
    export TIER1_OUTCOME=failure
    export RUN_URL=http://example.invalid/run/1
    export ISSUE_LABEL=ci-live-probe
    export GH_LABEL_CREATE_EXIT=0
    export GH_ISSUE_CREATE_LABEL_EXIT=0
    export GH_ISSUE_LIST_LOOKUP=""
    export GH_ISSUE_LIST_SUCCESS=""
    export GH_ISSUE_LIST_EXIT=0
    export GH_ISSUE_CLOSE_FAIL=""
    "$setup_fn"
    bash -eo pipefail "$tmpdir/step.sh"
  ) > "$tmpdir/out.$name" 2> "$tmpdir/err.$name"
  echo "$?"
}

scen1_label_absent_failure_creates_unlabeled_fallback() {
  export GH_ISSUE_CREATE_LABEL_EXIT=1
}

scen2_label_present_failure_creates_labeled() {
  :
}

scen3_open_issue_exists_comments_not_creates() {
  export GH_ISSUE_LIST_LOOKUP=42
}

scen4_success_closes_all_open_labeled_issues() {
  export TIER1_OUTCOME=success
  export GH_ISSUE_LIST_SUCCESS=$'7\n8'
}

scen5_label_create_fails_still_green_and_signals() {
  export GH_LABEL_CREATE_EXIT=1
  export GH_ISSUE_CREATE_LABEL_EXIT=1
}

# The re-review counterexamples (CX-A/CX-B), kept as required cases: a GitHub-API hiccup in
# this notification step must never turn a provider-healthy run red, and never lose the
# provider-outage signal — the tier-1 gate owns the run's redness, not gh.
scen6_success_close_fails_step_stays_green() {
  export TIER1_OUTCOME=success
  export GH_ISSUE_LIST_SUCCESS=$'7\n8'
  export GH_ISSUE_CLOSE_FAIL=8
}

scen7_success_list_fails_step_stays_green() {
  export TIER1_OUTCOME=success
  export GH_ISSUE_LIST_EXIT=1
}

scen8_failure_lookup_fails_falls_through_to_create() {
  export GH_ISSUE_LIST_EXIT=1
}

count_log() {
  grep -c "$1" "$GH_STUB_LOG" || true
}

count_log_success() {
  grep "$1" "$GH_STUB_LOG" | grep -c '\[0\]$' || true
}

fail() {
  local scenario="$1"
  local message="$2"
  echo "FAIL [$scenario]: $message" >&2
  echo "--- stdout ---" >&2
  cat "$tmpdir/out.$scenario" >&2 || true
  echo "--- stderr ---" >&2
  cat "$tmpdir/err.$scenario" >&2 || true
  echo "--- stub log ---" >&2
  cat "$GH_STUB_LOG" >&2 || true
  exit 1
}

# Scenario 1: label absent, failure path falls back to unlabeled create.
exit1="$(run_scenario scen1 scen1_label_absent_failure_creates_unlabeled_fallback)"
if [ "$exit1" != "0" ]; then
  fail scen1 "expected exit 0, got $exit1"
fi
if [ "$(count_log '^gh label create ')" -ne 1 ]; then
  fail scen1 "expected exactly one 'gh label create' attempt"
fi
if [ "$(count_log_success '^gh label create ')" -ne 1 ]; then
  fail scen1 "expected successful label create for ci-live-probe"
fi
if [ "$(count_log_success '^gh issue create ')" -ne 1 ]; then
  fail scen1 "expected exactly one successful 'gh issue create'"
fi
if grep '^gh issue create .*\[0\]$' "$GH_STUB_LOG" | grep -q -- '--label'; then
  fail scen1 "expected successful unlabeled fallback create"
fi

# Scenario 2: label present, failure path uses labeled create.
exit2="$(run_scenario scen2 scen2_label_present_failure_creates_labeled)"
if [ "$exit2" != "0" ]; then
  fail scen2 "expected exit 0, got $exit2"
fi
if [ "$(count_log_success '^gh issue create ')" -ne 1 ]; then
  fail scen2 "expected exactly one successful 'gh issue create'"
fi
if [ "$(count_log '^gh issue create ')" -ne 1 ]; then
  fail scen2 "expected only one 'gh issue create' call"
fi
if ! grep '^gh issue create .*\[0\]$' "$GH_STUB_LOG" | grep -q -- '--label ci-live-probe'; then
  fail scen2 "expected successful labeled create"
fi
if [ "$(count_log '^gh label create ')" -ne 1 ]; then
  fail scen2 "expected exactly one 'gh label create' attempt"
fi

# Scenario 3: open issue exists, comment instead of create.
exit3="$(run_scenario scen3 scen3_open_issue_exists_comments_not_creates)"
if [ "$exit3" != "0" ]; then
  fail scen3 "expected exit 0, got $exit3"
fi
if [ "$(count_log_success '^gh issue comment 42')" -ne 1 ]; then
  fail scen3 "expected exactly one successful 'gh issue comment 42'"
fi
if [ "$(count_log_success '^gh issue create ')" -ne 0 ]; then
  fail scen3 "expected zero successful 'gh issue create' calls"
fi

# Scenario 4: success closes all open labeled issues.
exit4="$(run_scenario scen4 scen4_success_closes_all_open_labeled_issues)"
if [ "$exit4" != "0" ]; then
  fail scen4 "expected exit 0, got $exit4"
fi
if [ "$(count_log_success '^gh issue close 7')" -ne 1 ]; then
  fail scen4 "expected successful close of issue 7"
fi
if [ "$(count_log_success '^gh issue close 8')" -ne 1 ]; then
  fail scen4 "expected successful close of issue 8"
fi
if ! grep '^gh issue close 7 .*\[0\]$' "$GH_STUB_LOG" | grep -q -- '--comment'; then
  fail scen4 "expected close 7 with --comment"
fi
if ! grep '^gh issue close 8 .*\[0\]$' "$GH_STUB_LOG" | grep -q -- '--comment'; then
  fail scen4 "expected close 8 with --comment"
fi
if [ "$(count_log_success '^gh issue create ')" -ne 0 ]; then
  fail scen4 "expected zero successful 'gh issue create' calls"
fi
if [ "$(count_log_success '^gh issue comment ')" -ne 0 ]; then
  fail scen4 "expected zero successful 'gh issue comment' calls"
fi

# Scenario 5: label create fails, still green and signals via unlabeled create.
exit5="$(run_scenario scen5 scen5_label_create_fails_still_green_and_signals)"
if [ "$exit5" != "0" ]; then
  fail scen5 "expected exit 0, got $exit5"
fi
if ! grep -q '::warning::could not provision label ci-live-probe' "$tmpdir/out.scen5"; then
  fail scen5 "expected warning about label provisioning"
fi
if [ "$(count_log '^gh label create ')" -ne 1 ]; then
  fail scen5 "expected exactly one 'gh label create' attempt"
fi
if [ "$(count_log_success '^gh label create ')" -ne 0 ]; then
  fail scen5 "expected label create to fail"
fi
if [ "$(count_log_success '^gh issue create ')" -ne 1 ]; then
  fail scen5 "expected exactly one successful 'gh issue create'"
fi
if grep '^gh issue create .*\[0\]$' "$GH_STUB_LOG" | grep -q -- '--label'; then
  fail scen5 "expected successful unlabeled fallback create"
fi

echo "PASS: all 5 live-probe issue scenarios (using $yaml)"

# Scenario 6 (CX-A): success path, one close hits a transient API error — the step stays
# green, BOTH closes are attempted, and the failed close warns instead of aborting.
exit6="$(run_scenario scen6 scen6_success_close_fails_step_stays_green)"
if [ "$exit6" != "0" ]; then
  fail scen6 "expected exit 0 despite a failing close, got $exit6"
fi
if [ "$(count_log_success '^gh issue close 7')" -ne 1 ]; then
  fail scen6 "expected successful close of issue 7"
fi
if [ "$(count_log '^gh issue close 8')" -ne 1 ]; then
  fail scen6 "expected close of issue 8 to be attempted"
fi
if [ "$(count_log_success '^gh issue close 8')" -ne 0 ]; then
  fail scen6 "expected close of issue 8 to fail (synthetic 502)"
fi
if ! grep -q '::warning::could not close issue 8' "$tmpdir/out.scen6"; then
  fail scen6 "expected ::warning:: for the failed close"
fi

# Scenario 7 (CX-A2): success path, the issue LIST itself 500s — step stays green with a
# warning, no closes attempted (next run retries), zero create/comment calls.
exit7="$(run_scenario scen7 scen7_success_list_fails_step_stays_green)"
if [ "$exit7" != "0" ]; then
  fail scen7 "expected exit 0 despite a failing issue list, got $exit7"
fi
if [ "$(count_log '^gh issue close ')" -ne 0 ]; then
  fail scen7 "expected zero close attempts when the list fails"
fi
if ! grep -q '::warning::no open ci-live-probe issues to close (or GitHub API hiccup)' "$tmpdir/out.scen7"; then
  fail scen7 "expected ::warning:: about the list hiccup"
fi

# Scenario 8 (CX-B): failure path, the recurring-issue LOOKUP 502s — the step falls through
# to the create path (the outage signal is never lost) and still exits 0.
exit8="$(run_scenario scen8 scen8_failure_lookup_fails_falls_through_to_create)"
if [ "$exit8" != "0" ]; then
  fail scen8 "expected exit 0 despite a failing lookup, got $exit8"
fi
if [ "$(count_log_success '^gh issue create ')" -ne 1 ]; then
  fail scen8 "expected exactly one successful 'gh issue create' after the lookup hiccup"
fi
if [ "$(count_log_success '^gh issue comment ')" -ne 0 ]; then
  fail scen8 "expected zero successful 'gh issue comment' calls"
fi

echo "PASS: all 8 live-probe issue scenarios (using $yaml)"
