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
#   E2E_CHEAP_MODEL_RE    override the cheap-tier model regex (default below)
#   E2E_KEEP_TMUX=1       keep the tmux session + scratch dir on failure for post-mortem
#   SPLICE_E2E_CLIENT_TOKEN  a REAL caller credential for client-auth heads. Without it those
#                         heads SKIP tier 1 rather than be probed with the mgmt key — see
#                         probe_bearer() for why that would ship the key to the vendor.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STATE_DIR="${CLAUDEX_STATE_DIR:-$HOME/.claude-codex/state}"
CONTROL_PORT="${SPLICE_CONTROL_PORT:-3096}"
CONTROL="http://127.0.0.1:${CONTROL_PORT}"
PROBE="$ROOT/checks/e2e/stream_probe.py"
TMUX_SOCK="splice-e2e"

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
# lines: key<TAB>label<TAB>port<TAB>healthy<TAB>authKind
#
# authKind is load-bearing, not decoration: it is the ONLY thing that tells tier 1 whether a head
# holds a splice credential or forwards the caller's own upstream (see probe_bearer). A daemon too
# old to report the field is a HARD failure rather than a default — guessing "probably not
# client-auth" is exactly the assumption that leaks the mgmt key.
discover() {
  curl -sS -m 5 "$CONTROL/api/heads" -H "Authorization: Bearer $MGMT" | python3 -c '
import json, sys
FIELDS = ("key", "label", "port", "healthy", "authKind")
for h in json.load(sys.stdin)["heads"]:
    missing = [k for k in FIELDS if k not in h]
    if missing:
        sys.exit("/api/heads row %r lacks %s — daemon predates this harness" % (h.get("key"), missing))
    print("\t".join(str(h[k]) for k in FIELDS))'
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

# The cheap tier of every dialect this harness can meet. `haiku` was the missing one and it was a
# COST TRAP, not a cosmetic gap: the Anthropic catalog is fable/opus/sonnet/haiku
# (config/splice.example.toml:275-289), none of which matched `mini|spark|flash|lite`, so an
# anthropic-passthrough head fell through to rows[0] — claude-fable-5, simultaneously the most
# expensive row and the head's pinned_model. Verified live: grok (grok-4.6/4.5/4.3) and kimi
# (k3-256k/kimi-for-coding/k3[1m]) match nothing either and take that same fallback today.
CHEAP_MODEL_RE="${E2E_CHEAP_MODEL_RE:-haiku|mini|spark|flash|lite|nano}"

pick_model() { # port bearer -> "<full discovery id><TAB><why it was chosen>"
  local port="$1" bearer="$2"
  # /v1/models sits behind the head's authorize() like every other head route, so discovery must
  # present a credential — the SAME one the probe itself will send, never unconditionally $MGMT.
  # Without it the head correctly answers authentication_error and discovery died on a KeyError.
  curl -sS -m 5 -H "Authorization: Bearer $bearer" "http://127.0.0.1:$port/v1/models" \
    | CHEAP_RE="$CHEAP_MODEL_RE" python3 -c '
import json, os, re, sys
cheap_re = os.environ["CHEAP_RE"]
rows = [d["id"] for d in json.load(sys.stdin)["data"]]
if not rows:
    sys.exit("/v1/models returned an empty catalog")
cheap = [r for r in rows if re.search(cheap_re, r)]
# No silent fallback. Tier 1 spends real provider quota, so a run that cannot find a cheap row must
# SAY it is about to bill the catalog head — the failure mode this replaces was invisible.
why = ("cheap tier, matched /%s/" % cheap_re) if cheap else (
    "NO row matched /%s/ — falling back to the catalog head, the MOST EXPENSIVE row of %s"
    % (cheap_re, rows))
print("%s\t%s" % ((cheap or rows)[0], why))'
}

# The bearer a tier-1 probe presents to a head, or a nonzero exit when it must not be probed.
#
# SAFETY (HD-15): a client-auth head holds NO splice credential. HeadServer.authorize()
# short-circuits to true for it (HeadServer.kt:472-478) and forwardedClientHeaders copies the
# inbound Authorization header VERBATIM to the vendor (HeadServer.kt:398-402, :413-416). Presenting
# $MGMT there would ship the daemon's own 32-byte management key to api.anthropic.com — and
# ClientAuthProvider.allowRefreshAfterFailure is false (ClientAuthProvider.kt:38), so it surfaces as
# a bare 401 that reads like a product bug. Such a head is probed ONLY with a real caller
# credential, or not at all.
probe_bearer() { # auth_kind -> bearer on stdout; rc=1 means "do not probe this head"
  if [ "$1" != client ]; then printf '%s' "$MGMT"; return 0; fi
  [ -n "${SPLICE_E2E_CLIENT_TOKEN:-}" ] || return 1
  printf '%s' "$SPLICE_E2E_CLIENT_TOKEN"
}

# ── tier 1: wire probe ───────────────────────────────────────────────────────
tier1() {
  local key="$1" port="$2" auth_kind="$3" model model_var summary bearer picked why
  # The credential decision comes FIRST — before /v1/models, before the turn, before count_tokens.
  # Every one of those presents a bearer to the head, so there is no safe "probe a little" state.
  if ! bearer="$(probe_bearer "$auth_kind")"; then
    skip "$key/wire" "client-auth head, no caller credential supplied (set SPLICE_E2E_CLIENT_TOKEN to probe it)"
    skip "$key/count_tokens" "client-auth head, no caller credential supplied"
    return
  fi
  model_var="E2E_MODEL_$(printf '%s' "$key" | tr '[:lower:]-' '[:upper:]_')"
  model="${!model_var:-}"
  if [ -n "$model" ]; then
    why="$model_var override"
  else
    picked="$(pick_model "$port" "$bearer")" || { fail "$key/wire" "model discovery failed"; return; }
    model="${picked%%$'\t'*}"; why="${picked#*$'\t'}"
  fi
  note "[$key] tier1 wire probe on :$port model=$model"
  note "    model choice: $why"
  if summary="$(SPLICE_PROBE_BEARER="$bearer" python3 "$PROBE" --head "$key" --port "$port" --model "$model" \
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
        -H 'Content-Type: application/json' -H "Authorization: Bearer $bearer" \
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

# The tier-2 oracle over the head's perf JSONL. Three assertions on the drive window:
#   · at least $3 rows with outcome=ok landed              — a turn happened
#   · NO row with any other outcome landed                 — …and nothing failed alongside it.
#     Filtering to outcome=="ok" (as this did) made a failed turn's row structurally unreadable, so
#     a head that was alive but WRONG could not be failed by anything in the harness.
#   · the retry counters on every ok row are clean         — …without fighting to get there
#
# Counter semantics are verified against TurnPerf and ~200k live rows, because the obvious
# assertions are wrong in two different ways:
#   · TurnPerf.add() DROPS a zero delta (core/perf/TurnPerf.kt, pinned by TurnPerfTest's
#     `RETRIES !in snap.counters`), so retries/refreshes are ABSENT on a clean turn, never 0.
#   · `attempts` is written by UpstreamClient.kt:229, which the WebSocket runner bypasses entirely
#     — live census: present on 99% of claude-kimi/claude-grok rows but only 4% of claudex's.
#     So assert the VALUE where the field exists; requiring its PRESENCE would red every ws head.
#   Hence `r.get(name, want) != want`: absent reads as compliant, a written value must be right.
#   · `search_rounds` is legitimately 1-3 on a healthy responses head (tool_search deferral, 493
#     live claudex rows) — it is REPORTED, never asserted.
perf_rows_ok() { # head_key since_epoch_ms min_rows -> prints the row + counter verdict
  python3 - "$STATE_DIR/$1-perf.jsonl" "$2" "$3" <<'PY'
import json, sys
path, since, want = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
ok, bad = [], []
try:
    with open(path) as f:
        for line in f:
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if r.get("ts", 0) < since:
                continue
            (ok if r.get("outcome") == "ok" else bad).append(r)
except FileNotFoundError:
    pass

problems = []
if len(ok) < want:
    problems.append(f"only {len(ok)} ok perf rows since window start (want >= {want})")
if bad:
    seen = {}
    for r in bad:
        seen[r.get("outcome")] = seen.get(r.get("outcome"), 0) + 1
    problems.append("non-ok rows in window: " + ", ".join(f"{k}x{v}" for k, v in sorted(seen.items())))
for name, clean in (("attempts", 1), ("retries", 0), ("refreshes", 0)):
    off = [r[name] for r in ok if r.get(name, clean) != clean]
    if off:
        problems.append(f"{name}={sorted(set(off))} on {len(off)}/{len(ok)} ok rows (want {clean})")
if problems:
    print("; ".join(problems))
    sys.exit(1)
worst = max((r.get("total", 0) for r in ok), default=0)
carried = sum(1 for r in ok if "attempts" in r)
rounds = sorted({r["search_rounds"] for r in ok if "search_rounds" in r})
print(f"{len(ok)} ok rows / 0 non-ok, slowest total={worst}ms, "
      f"attempts==1 on {carried}/{len(ok)} rows carrying it, retries=0, refreshes=0"
      + (f", search_rounds={rounds} (informational)" if rounds else ""))
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
while IFS=$'\t' read -r key label port healthy auth_kind; do
  [ -n "$ONLY_HEAD" ] && [ "$key" != "$ONLY_HEAD" ] && continue
  if [ "$healthy" != "True" ] && [ "$healthy" != "true" ]; then
    fail "$key" "head reported unhealthy by /api/heads"
    continue
  fi
  note "== head: $key (label=$label port=$port auth=$auth_kind)"
  case "$TIER" in
    1)   tier1 "$key" "$port" "$auth_kind" ;;
    2)   tier2 "$key" "$label" ;;
    all) tier1 "$key" "$port" "$auth_kind"; tier2 "$key" "$label" ;;
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
