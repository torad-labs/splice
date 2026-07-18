#!/usr/bin/env bash
# checks/e2e/heads-e2e.sh — full-stack e2e over EVERY configured head (codex, grok, kimi, ...).
#
# Head-agnostic by design: heads are DISCOVERED from the live daemon (/api/heads), so adding a
# kimi head to ~/.config/splice/splice.toml makes it run here with zero harness changes. A head
# that is known-interesting but absent (kimi today) is reported as SKIP with the reason.
#
#   tier 1  wire probe   — real streaming turn straight at the head port; validates the Anthropic
#                          SSE contract + latency budgets client-side (stream_probe.py), plus a
#                          count_tokens sanity call. Cheap, provider-billed, seconds per head.
#   tier 2  tmux drive   — launches the head's REAL Claude Code wrapper (claudex / claude-grok /
#                          kimi …) inside an isolated tmux server, answers first-run prompts,
#                          sends live prompts, asserts the answers render, then asserts fresh
#                          `outcome=ok` perf rows landed in the head's perf JSONL.
#
# Usage:
#   checks/e2e/heads-e2e.sh [--tier 1|2|all] [--head KEY] [--list]
# Env:
#   E2E_TTFB_MS / E2E_FIRST_DELTA_MS / E2E_TOTAL_MS / E2E_GAP_MS   latency budgets (ms)
#   E2E_MODEL_<HEADKEY>   full discovery model id override (default: cheapest-looking row)
#   E2E_KEEP_TMUX=1       keep the tmux session + scratch dir on failure for post-mortem
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STATE_DIR="${CLAUDEX_STATE_DIR:-$HOME/.claude-codex/state}"
CONTROL_PORT="${SPLICE_CONTROL_PORT:-3096}"
CONTROL="http://127.0.0.1:${CONTROL_PORT}"
PROBE="$ROOT/checks/e2e/stream_probe.py"
TMUX_SOCK="splice-e2e"
WANTED_HEADS="codex claudex grok claude-grok kimi claude-kimi"   # SKIP-report set; discovery rules

TIER="all"; ONLY_HEAD=""; LIST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tier) TIER="$2"; shift 2 ;;
    --head) ONLY_HEAD="$2"; shift 2 ;;
    --list) LIST=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

PASS=(); FAIL=(); SKIP=()
note()  { printf '%s\n' "$*" >&2; }
pass()  { PASS+=("$1"); note "  ✓ $1"; }
fail()  { FAIL+=("$1: $2"); note "  ✗ $1 — $2"; }
skip()  { SKIP+=("$1: $2"); note "  - $1 SKIP — $2"; }

# request-byte contract receipt (#924 Phase 1). On a tier-1 200, drop a receipt beside the goldens.
# The FULL binding — sha256 of the exact UPSTREAM request bytes the head sent, checked against
# sha256(builderOutput) so a blind golden-regenerate can't go green — needs a head-side
# upstream-request tap that does NOT exist yet (the head doesn't surface the bytes its
# RequestBuilder produced). Until that lands, this records what IS observable client-side and marks
# contract_bound=false. See gateway/CONTRACT.md for the tap + the enforcement it unlocks. This makes
# the receipt file + emission point real, not the binding — so wiring the tap is a localized change.
RECEIPT_DIR="$ROOT/checks/e2e/receipts"
emit_receipt() { # key model http_status
  mkdir -p "$RECEIPT_DIR"
  cat > "$RECEIPT_DIR/$1.json" <<JSON
{
  "head": "$1",
  "model": "$2",
  "http_status": $3,
  "observed_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "contract_bound": false,
  "note": "upstream-request-bytes tap not wired; sha256(builderOutput)==receipt.hash inactive — see gateway/CONTRACT.md"
}
JSON
  note "    receipt: checks/e2e/receipts/$1.json (contract_bound=false — see gateway/CONTRACT.md)"
}

# ── preflight ────────────────────────────────────────────────────────────────
if ! curl -sS -m 3 "$CONTROL/health" >/dev/null 2>&1; then
  note "daemon down — cold-starting (same recipe as the CLI)"
  sh -c 'nohup java ${SPLICE_JVM_OPTS:--Xmx1024m -XX:+UseStringDeduplication} -jar "$HOME/.local/share/splice/splice.jar" daemon >/dev/null 2>&1 &'
  for _ in $(seq 1 60); do curl -sS -m 2 "$CONTROL/health" >/dev/null 2>&1 && break; sleep 0.25; done
fi
curl -sS -m 3 "$CONTROL/health" >/dev/null || { echo "FATAL: control plane not answering on :$CONTROL_PORT" >&2; exit 1; }
MGMT="$(cat "$STATE_DIR/mgmt-key" 2>/dev/null || true)"
[ -n "$MGMT" ] || { echo "FATAL: mgmt-key missing at $STATE_DIR/mgmt-key" >&2; exit 1; }

# ── discovery ────────────────────────────────────────────────────────────────
# lines: key<TAB>label<TAB>port<TAB>healthy
discover() {
  curl -sS -m 5 "$CONTROL/api/heads" -H "Authorization: Bearer $MGMT" | python3 -c '
import json, sys
for h in json.load(sys.stdin)["heads"]:
    print("\t".join(str(h[k]) for k in ("key", "label", "port", "healthy")))'
}
HEADS="$(discover)"
[ -n "$HEADS" ] || { echo "FATAL: /api/heads returned no heads" >&2; exit 1; }

if [ "$LIST" = 1 ]; then printf '%s\n' "$HEADS"; exit 0; fi

# report interesting-but-unconfigured heads (kimi until a [heads.*] lands in splice.toml)
for want in kimi; do
  if ! printf '%s\n' "$HEADS" | cut -f1 | grep -qx "$want" && [ -z "$ONLY_HEAD" ]; then
    skip "$want" "no head configured — add a [heads.$want] (anthropic-passthrough provider) to ~/.config/splice/splice.toml"
  fi
done

pick_model() { # port -> full discovery id (prefer a cheap-looking row)
  local port="$1"
  curl -sS -m 5 "http://127.0.0.1:$port/v1/models" | python3 -c '
import json, re, sys
rows = [d["id"] for d in json.load(sys.stdin)["data"]]
cheap = [r for r in rows if re.search(r"mini|spark|flash|lite", r)]
print((cheap or rows)[0])'
}

# ── tier 1: wire probe ───────────────────────────────────────────────────────
tier1() {
  local key="$1" port="$2" model model_var summary
  model_var="E2E_MODEL_$(printf '%s' "$key" | tr '[:lower:]-' '[:upper:]_')"
  model="${!model_var:-}"
  [ -n "$model" ] || model="$(pick_model "$port")" || { fail "$key/wire" "model discovery failed"; return; }
  note "[$key] tier1 wire probe on :$port model=$model"
  if summary="$(python3 "$PROBE" --head "$key" --port "$port" --model "$model" \
      --ttfb-ms "${E2E_TTFB_MS:-20000}" --first-delta-ms "${E2E_FIRST_DELTA_MS:-45000}" \
      --total-ms "${E2E_TOTAL_MS:-120000}" --gap-ms "${E2E_GAP_MS:-30000}")"; then
    note "    $summary"
    pass "$key/wire"
    emit_receipt "$key" "$model" 200
  else
    note "    ${summary:-<no output>}"
    fail "$key/wire" "$(printf '%s' "$summary" | python3 -c 'import json,sys
try: print("; ".join(json.load(sys.stdin)["violations"])[:300])
except Exception: print("probe crashed")')"
  fi
  local ct
  ct="$(curl -sS -m 10 "http://127.0.0.1:$port/v1/messages/count_tokens" \
        -H 'Content-Type: application/json' \
        -d "{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}")"
  if printf '%s' "$ct" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d["input_tokens"], int)' 2>/dev/null; then
    pass "$key/count_tokens"
  else
    fail "$key/count_tokens" "bad payload: ${ct:0:120}"
  fi
}

# ── tier 2: tmux TUI drive ───────────────────────────────────────────────────
pane() { tmux -L "$TMUX_SOCK" capture-pane -pt "$1" -S -160 2>/dev/null || true; }

# Wait until the pane matches $want. Auto-answers first-run dialogs along the way. Returns
# 0=matched, 1=timeout, 2=auth-needed. NB: the first-run TRUST dialog draws its selection cursor
# with the SAME `❯` glyph the input prompt uses — so readiness MUST key on the main-screen status
# bar ("bypass permissions" / "for shortcuts"), never on `❯` (that false-matched the trust screen
# and the harness typed prompts into a dialog that swallowed them).
wait_pane() { # session deadline_s want_regex -> 0|1|2
  local sess="$1" want="$3" p end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    p="$(pane "$sess")"
    # dialogs first — they can sit UNDER a spurious readiness match otherwise
    if printf '%s' "$p" | grep -qiE "trust this folder|do you trust"; then
      tmux -L "$TMUX_SOCK" send-keys -t "$sess" Enter; sleep 1; continue
    fi
    if printf '%s' "$p" | grep -qiE "text style|theme to use|choose the text"; then
      tmux -L "$TMUX_SOCK" send-keys -t "$sess" Enter; sleep 1; continue
    fi
    if printf '%s' "$p" | grep -qiE "sign in|/login to authenticate|run: .* login|not logged in"; then
      return 2  # auth needed — skip, do not fail
    fi
    if printf '%s' "$p" | grep -qE "$want"; then return 0; fi
    sleep 1
  done
  return 1
}

send_prompt() { # session text
  tmux -L "$TMUX_SOCK" send-keys -t "$1" -l "$2"
  sleep 0.3
  tmux -L "$TMUX_SOCK" send-keys -t "$1" Enter
}

perf_rows_ok() { # head_key since_epoch_ms min_rows -> prints "n rows, slowest total=..ms"
  python3 - "$STATE_DIR/$1-perf.jsonl" "$2" "$3" <<'PY'
import json, sys
path, since, want = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
rows = []
try:
    with open(path) as f:
        for line in f:
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if r.get("ts", 0) >= since and r.get("outcome") == "ok":
                rows.append(r)
except FileNotFoundError:
    pass
if len(rows) < want:
    print(f"only {len(rows)} ok perf rows since window start (want >= {want})")
    sys.exit(1)
worst = max(r.get("total", 0) for r in rows)
print(f"{len(rows)} ok rows, slowest total={worst}ms")
PY
}

tier2() {
  local key="$1" label="$2" sess="e2e-$1" scratch start_ms rc
  if ! command -v "$label" >/dev/null 2>&1; then
    skip "$key/tui" "wrapper '$label' not on PATH (run: splice install)"
    return
  fi
  scratch="$(mktemp -d "/tmp/splice-e2e-$key.XXXXXX")"
  start_ms=$(($(date +%s) * 1000))
  note "[$key] tier2 tmux drive: launching '$label' in $scratch"
  tmux -L "$TMUX_SOCK" kill-session -t "$sess" 2>/dev/null || true
  # keep the pane alive after exit so a crash is post-mortem-able
  tmux -L "$TMUX_SOCK" new-session -d -s "$sess" -x 200 -y 50 -c "$scratch" \
    "sh -c '$label; echo E2E_WRAPPER_EXITED=\$?; sleep 600'"

  wait_pane "$sess" 90 'bypass permissions|for shortcuts'; rc=$?
  if [ $rc = 2 ]; then skip "$key/tui" "head not logged in"; tier2_cleanup "$key" "$sess" "$scratch" keep; return; fi
  if [ $rc != 0 ]; then fail "$key/tui" "TUI never became ready (90s)"; tier2_cleanup "$key" "$sess" "$scratch"; return; fi

  # The expected answers (ANSWER=42 / SECOND=DONE) deliberately do NOT appear in the prompt text,
  # so a match is the model's RESPONSE, never the echoed input line.
  send_prompt "$sess" "Compute six times seven and reply with exactly ANSWER= followed by the number."
  if ! wait_pane "$sess" 150 'ANSWER=42'; then
    fail "$key/tui" "no ANSWER=42 within 150s"; tier2_cleanup "$key" "$sess" "$scratch"; return
  fi
  pass "$key/tui-turn1"

  send_prompt "$sess" "Reply with exactly the word SECOND followed by an equals sign and the word DONE."
  if ! wait_pane "$sess" 150 'SECOND=DONE'; then
    fail "$key/tui" "no SECOND=DONE within 150s (multi-turn)"; tier2_cleanup "$key" "$sess" "$scratch"; return
  fi
  pass "$key/tui-turn2"

  local perf
  if perf="$(perf_rows_ok "$key" "$start_ms" 2)"; then
    note "    perf: $perf"
    pass "$key/perf-rows"
  else
    fail "$key/perf-rows" "$perf"
  fi
  tier2_cleanup "$key" "$sess" "$scratch"
}

tier2_cleanup() {
  local key="$1" sess="$2" scratch="$3" keep="${4:-}"
  if [ -n "${E2E_KEEP_TMUX:-}" ] || [ "$keep" = keep ]; then
    note "    (kept tmux session '$sess' on socket -L $TMUX_SOCK and $scratch)"
    return
  fi
  pane "$sess" > "/tmp/splice-e2e-$key-pane.txt" 2>/dev/null || true
  tmux -L "$TMUX_SOCK" kill-session -t "$sess" 2>/dev/null || true
  rm -rf "$scratch"
}

# ── run ──────────────────────────────────────────────────────────────────────
while IFS=$'\t' read -r key label port healthy; do
  [ -n "$ONLY_HEAD" ] && [ "$key" != "$ONLY_HEAD" ] && continue
  if [ "$healthy" != "True" ] && [ "$healthy" != "true" ]; then
    fail "$key" "head reported unhealthy by /api/heads"
    continue
  fi
  note "== head: $key (label=$label port=$port)"
  case "$TIER" in
    1)   tier1 "$key" "$port" ;;
    2)   tier2 "$key" "$label" ;;
    all) tier1 "$key" "$port"; tier2 "$key" "$label" ;;
    *)   echo "bad --tier $TIER" >&2; exit 2 ;;
  esac
done <<< "$HEADS"

# leave no stray tmux server when every session was cleaned
tmux -L "$TMUX_SOCK" list-sessions >/dev/null 2>&1 || tmux -L "$TMUX_SOCK" kill-server 2>/dev/null || true

note ""
note "── e2e summary ──"
note "  pass: ${#PASS[@]}  fail: ${#FAIL[@]}  skip: ${#SKIP[@]}"
for s in "${SKIP[@]:-}"; do [ -n "$s" ] && note "  SKIP $s"; done
for f in "${FAIL[@]:-}"; do [ -n "$f" ] && note "  FAIL $f"; done
[ ${#FAIL[@]} -eq 0 ]
