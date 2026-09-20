#!/usr/bin/env bash
# checks/e2e/heads-e2e.sh — full-stack e2e over EVERY configured head (codex, grok, kimi, ...).
#
# Head-agnostic by design: heads are DISCOVERED from the live daemon (/api/heads), so adding a
# head to ~/.config/splice/splice.toml makes it run here with zero harness changes. Discovery is
# the ONLY roster: a hardcoded want-list of "interesting but absent" heads used to sit here and
# rotted into a false report — it named `kimi` while the configured key is `claude-kimi`
# (splice.toml:196), so every full run printed "kimi: no head configured" about a head that
# exists and works. A head that is genuinely missing is missing from splice.toml, which is the
# operator's own file; the harness has no business second-guessing it.
#
#   tier 1  wire probe   — real streaming turn straight at the head port; validates the Anthropic
#                          SSE contract + latency budgets client-side (stream_probe.ts), plus a
#                          count_tokens sanity call. Cheap, provider-billed, seconds per head.
#   tier 2  tmux drive   — launches the head's REAL Claude Code wrapper (claudex / claude-grok /
#                          claude-kimi …) inside an isolated tmux server, answers first-run
#                          prompts, plants an OPERATOR-SHAPED tool surface (a real stdio MCP
#                          server whose composed tool name is over 64 chars — see
#                          plant_overlong_mcp), sends live prompts, asserts the answers render
#                          and that the tool surface really formed, then runs the perf-JSONL
#                          oracle (perf_rows_ok) over the drive window.
#
# COST — tier 2 spends REAL provider quota on EVERY head, deliberately and without a gate,
# including a client-auth head. That is not an oversight of tier 1's credential gate: the two
# protect different things. Tier 1's gate exists because probing a client-auth head with $MGMT
# would ship the daemon's own management key to the vendor (see probe_bearer) — a LEAK. Tier 2
# cannot leak it (LaunchService withholds ANTHROPIC_AUTH_TOKEN from such a head, so the wrapper
# rides the operator's own `claude` login), it only spends. Every other head tier 2 drives spends
# an OAuth subscription too, so gating the client-auth one alone would single out a cost that is
# already universal. Instead the spend is announced per head at dispatch time — see tier2().
#
# Usage:
#   checks/e2e/heads-e2e.sh [--tier 1|2|all|perf-oracle|mcp-oracle|plant-oracle] [--head KEY] [--list]
#   (perf-oracle: selftest hook — tier 2's perf gate alone, over E2E_PERF_SINCE/E2E_PERF_WANT)
#   (mcp-oracle:  selftest hook — tier 2's tool-surface gate alone, over E2E_MCP_SCRATCH)
#   (plant-oracle: selftest hook — plant the MCP server + its enable settings into E2E_MCP_SCRATCH)
# Env:
#   E2E_TTFB_MS / E2E_FIRST_DELTA_MS / E2E_TOTAL_MS / E2E_GAP_MS   latency budgets (ms)
#   E2E_MODEL_<HEADKEY>   full discovery model id override (default: cheapest-looking row)
#   E2E_CHEAP_MODEL_RE    override the cheap-tier model regex (default below)
#   E2E_KEEP_TMUX=1       keep the tmux session + scratch dir on failure for post-mortem
#   SPLICE_E2E_CLIENT_TOKEN  a REAL caller credential for client-auth heads. Without it those
#                         heads SKIP tier 1 rather than be probed with the mgmt key — see
#                         probe_bearer() for why that would ship the key to the vendor. Setting
#                         it to the mgmt key is a FATAL preflight error, not a shortcut.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# V4-177: same state-root rule as StatePaths.kt and bin/splice-launch — SPLICE_STATE_DIR, then the
# pre-0.4 CLAUDEX_STATE_DIR, then ~/.splice/state, adopting ~/.claude-codex/state in place when that
# is the only root on the box.
resolve_state_dir() {
  # A variable holding only whitespace is NOT an answer. `-n` calls " " set; Kotlin's isNotBlank
  # does not, and StatePaths blank-checks PER VARIABLE. Without this, SPLICE_STATE_DIR=" " in a unit
  # file makes this read " /mgmt-key" relative to CWD and report "mgmt-key not found" on a perfectly
  # healthy install, while the daemon resolves the real root. The pattern IS isNotBlank: at least
  # one non-whitespace character. Per variable, so an empty SPLICE_STATE_DIR falls through to
  # CLAUDEX_STATE_DIR instead of skipping it.
  case "${SPLICE_STATE_DIR:-}" in *[![:space:]]*) printf '%s\n' "$SPLICE_STATE_DIR"; return 0 ;; esac
  case "${CLAUDEX_STATE_DIR:-}" in *[![:space:]]*) printf '%s\n' "$CLAUDEX_STATE_DIR"; return 0 ;; esac
  # Adoption needs POSITIVE evidence on both sides, the rule StatePaths' three-valued probe follows:
  # only proven absence may start a fresh root. `[ ! -d ]` is ALSO true for a path that cannot be
  # stat-ed, so an unreadable ~/.splice would adopt the pre-0.4 root here while the daemon declines
  # and warns. Believe "absent" only when the parent is traversable, or absent itself.
  # `-e`, not `-d`: a REGULAR FILE at the current root is not proven absence either, and `[ ! -d ]`
  # called it adoptable while StatePaths declines and reports it as a fault.
  if [ ! -e "$HOME/.splice/state" ] && { [ ! -e "$HOME/.splice" ] || [ -x "$HOME/.splice" ]; } &&
     [ -d "$HOME/.claude-codex/state" ]; then
    printf '%s\n' "$HOME/.claude-codex/state"
  else
    printf '%s\n' "$HOME/.splice/state"
  fi
}
STATE_DIR="$(resolve_state_dir)"
CONTROL_PORT="${SPLICE_CONTROL_PORT:-3096}"
CONTROL="http://127.0.0.1:${CONTROL_PORT}"
PROBE="$ROOT/checks/e2e/stream_probe.ts"
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
# ⊘ not ✓ or -: a skip used to look like a quiet pass in a long log, which is how
# muse was skipped all campaign. The word SKIP is in the live line, not only the summary.
skip()  { SKIP+=("$1: $2"); note "  ⊘ SKIP $1 — $2"; }

# request-byte contract receipt (#924 Phase 1). On a tier-1 200, drop a receipt beside the goldens.
# The FULL binding — sha256 of the exact UPSTREAM request bytes the head sent, checked against
# sha256(builderOutput) so a blind golden-regenerate can't go green — needs a head-side
# upstream-request tap that does NOT exist yet (the head doesn't surface the bytes its
# RequestBuilder produced). Until that lands, this records what IS observable client-side and marks
# contract_bound=false. See gateway/CONTRACT.md for the tap + the enforcement it unlocks. This makes
# the receipt file + emission point real, not the binding — so wiring the tap is a localized change.
# DR-111: E2E_RECEIPT_DIR redirects emission for harness selftests — a loopback run against a
# real head KEY must never fabricate that head's in-repo receipt (the binding would grade fakes).
RECEIPT_DIR="${E2E_RECEIPT_DIR:-$ROOT/checks/e2e/receipts}"
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
  note "    receipt: $RECEIPT_DIR/$1.json (contract_bound=false — see gateway/CONTRACT.md)"
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

# The whole point of SPLICE_E2E_CLIENT_TOKEN is that it is NOT the mgmt key: it rides into the
# exact Authorization header a client-auth head forwards verbatim to api.anthropic.com
# (ClientAuth.forwardedClientHeaders). Reaching for "the token the harness already has" would re-create the
# leak this gate exists to prevent, so refuse before a single byte reaches a head.
if [ -n "${SPLICE_E2E_CLIENT_TOKEN:-}" ] && [ "$SPLICE_E2E_CLIENT_TOKEN" = "$MGMT" ]; then
  echo "FATAL: SPLICE_E2E_CLIENT_TOKEN is the daemon mgmt key. A client-auth head forwards that header VERBATIM to the vendor — supply a REAL caller credential or unset it." >&2
  exit 1
fi

# Credentials never ride on argv. Every argument of a running process is world-readable through
# /proc/<pid>/cmdline, so `ps -ef` during a probe exposed the daemon management key, and on a
# client-auth head the caller's own vendor token. curl reads the header from a config file on
# STDIN instead, which no other process can see, and which never touches disk. No call site here
# uses stdin for anything else. Review 2026-08-28 (PR 99, comment 31).
curl_auth() { # bearer curl-args... -> curl stdout
  local bearer="$1"; shift
  printf 'header = "Authorization: Bearer %s"\n' "$bearer" | curl -K - "$@"
}

# ── discovery ────────────────────────────────────────────────────────────────
# lines: key<TAB>label<TAB>port<TAB>healthy<TAB>authKind
#
# authKind is load-bearing, not decoration: it is the ONLY thing that tells tier 1 whether a head
# holds a splice credential or forwards the caller's own upstream (see probe_bearer). A daemon too
# old to report the field is a HARD failure rather than a default — guessing "probably not
# client-auth" is exactly the assumption that leaks the mgmt key.
discover() {
  curl_auth "$MGMT" -sS -m 5 "$CONTROL/api/heads" | python3 -c '
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
  curl_auth "$bearer" -sS -m 5 "http://127.0.0.1:$port/v1/models" \
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
# SAFETY (HD-15): a client-auth head holds NO splice credential. ClientAuth.authorize()
# short-circuits to true for it (`if (deps.forwardClientAuth) return true`) and
# ClientAuth.forwardedClientHeaders copies the inbound Authorization header VERBATIM to the
# vendor, via TurnPreparation. Both live in splice/gateway/head/ClientAuth.kt. Presenting
# $MGMT there would ship the daemon's own 32-byte management key to api.anthropic.com — and
# ClientAuthProvider.allowRefreshAfterFailure is false (ClientAuthProvider.kt:38), so it surfaces as
# a bare 401 that reads like a product bug. Such a head is probed ONLY with a real caller
# credential, or not at all.
# ALLOWLIST, not a blocklist. `[ "$1" != client ]` recognized exactly one dangerous value and
# treated every other string as safe, including strings nobody has verified — a gate that fails
# OPEN, eight lines below discovery's own law that guessing is what leaks the key. authKind is
# ctx.providerCfg.auth.kind, the operator's raw TOML string (ManagedHeadFactory.kt:58), and
# AuthKindRegistry.from() deliberately tolerates a custom kind by returning null, so an unrecognized
# value here is reachable by config alone. Whether such a head forwards the caller's Authorization
# upstream is exactly what we do not know, so it is refused rather than probed with $MGMT.
# Review 2026-08-28 (PR 99, comment 3).
probe_bearer() { # auth_kind -> bearer on stdout; rc=1 "skip this head", rc=2 harness-FATAL
  case "$1" in
    client)
      [ -n "${SPLICE_E2E_CLIENT_TOKEN:-}" ] || return 1
      printf '%s' "$SPLICE_E2E_CLIENT_TOKEN" ;;
    chatgpt-oauth|grok-oauth|kimi-oauth|muse-oauth|api-key)
      printf '%s' "$MGMT" ;;
    *)
      echo "FATAL: unrecognized authKind '$1' — refusing to probe. A head whose auth kind this" >&2
      echo "       harness does not know may forward the Authorization header upstream, so" >&2
      echo "       presenting \$MGMT could leak the daemon management key to a vendor. Add the" >&2
      echo "       kind to probe_bearer() once you have confirmed which side holds the credential." >&2
      # return, NOT exit (DR-49a): tier1 calls this inside a command substitution, so an exit
      # only killed the SUBSHELL — the parent read rc=1, the same code as the legit client-auth
      # skip, and the FATAL above scrolled past as decoration on a run that exited 0. The caller
      # turns rc>=2 into the real harness exit.
      return 2 ;;
  esac
}

# ── tier 1: wire probe ───────────────────────────────────────────────────────
tier1() {
  local key="$1" port="$2" auth_kind="$3" model model_var summary bearer picked why rc=0
  # The credential decision comes FIRST — before /v1/models, before the turn, before count_tokens.
  # Every one of those presents a bearer to the head, so there is no safe "probe a little" state.
  # rc discrimination (DR-49a): 1 = the legit client-auth skip; >=2 = probe_bearer's FATAL, which
  # its own exit could never deliver from inside the substitution's subshell. tier1 runs in the
  # parent shell, so THIS exit is the harness-fatal the FATAL text promises.
  bearer="$(probe_bearer "$auth_kind")" || rc=$?
  if [ "$rc" -ge 2 ]; then
    exit 1
  elif [ "$rc" -eq 1 ]; then
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
  if summary="$(SPLICE_PROBE_BEARER="$bearer" bun "$PROBE" --head "$key" --port "$port" --model "$model" \
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
  # DR-113: a curl transport failure (refused/reset/timeout) errexited the WHOLE harness with
  # curl's exit code instead of recording a per-head fail — the parse below only ever saw
  # payloads that arrived rc=0. Same set -e family as DR-110/DR-49a.
  ct="$(curl_auth "$bearer" -sS -m 10 "http://127.0.0.1:$port/v1/messages/count_tokens" \
        -H 'Content-Type: application/json' \
        -d "{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}")" \
    || ct="TRANSPORT FAILURE: count_tokens curl rc=$?"
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
    # The trust dialog's DEFAULT SELECTION IS "No, exit" (verified against Claude Code 2.1.257 in
    # a fresh mktemp dir, which is the only kind of dir tier 2 ever launches in — so it is always
    # untrusted and this dialog is always drawn). A bare Enter therefore ANSWERED NO and killed
    # the wrapper before a single turn; the drive then died in wait_pane's 90s timeout as "TUI
    # never became ready", which reads like a head problem and is not one. `--dangerously-skip-
    # permissions` does not skip it either. So: move the cursor, then CONFIRM the trusting option
    # is the selected one before pressing Enter — a reordered or re-worded dialog stalls to the
    # timeout instead of silently answering No again.
    if printf '%s' "$p" | grep -qiE "trust this folder|do you trust"; then
      if printf '%s' "$p" | grep -qE '❯[[:space:]]*Yes'; then
        tmux -L "$TMUX_SOCK" send-keys -t "$sess" Enter
      else
        tmux -L "$TMUX_SOCK" send-keys -t "$sess" Down
      fi
      sleep 1; continue
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
#   · no UNRECOVERED non-ok row landed                     — …and nothing stayed broken alongside it.
#     Filtering to outcome=="ok" (as this once did) made a failed turn's row structurally
#     unreadable, so a head that was alive but WRONG could not be failed by anything here.
#   · the retry counters on every ok row are clean         — …without fighting to get there
#
# WHY "unrecovered" and not "any non-ok". The window is per-head WALL-CLOCK and a perf row carries
# no session/PID discriminator, so it cannot be narrowed to the harness's own turns — a plain
# "any non-ok row fails" reds on traffic the harness never sent. Two classes, both real here:
#   · client_abort is recorded when the CLIENT went away — TurnDriver.kt:227 and :247, and
#     TurnPipeline.kt:52 (TurnOutcome.ClientAbandoned). Never a head defect; an operator pressing
#     Esc in another TUI during tier 2's multi-minute window would red the head. Live census:
#     136 on claude-kimi, 71 on claudex. It is EXCLUDED from the fail set and reported as info.
#   · a transient upstream 5xx that Claude Code retried successfully writes one non-ok row AND a
#     following ok row. User-visible outcome is success, so failing it is a false red.
# A non-ok row therefore counts as RECOVERED iff the next row OF THE SAME MODEL in the window is
# outcome=ok — precisely "the retry worked". Measured over the live JSONLs, that adjacency is the
# dominant shape of a blip (claudex: 63% of failure runs are a single row, p50 1735ms from the
# failure to the next ok) while a genuinely sick head produces RUNS (claude-kimi's bad period:
# runs of 12, 44, 83, 149 consecutive failures).
# SAME MODEL is load-bearing (DR-49b): whole-file adjacency paired unrelated turns, and a healthy
# model's interleaved oks pardoned EVERY failure of a broken one (fail-A, ok-B, fail-A, ok-B read
# as zero unrecovered — proven red by the selftest fixture). `model` is the only relatedness key a
# perf row carries (PerfStats.kt: no session/PID), so same-model concurrent traffic still
# adjacency-pairs — the residual is stated, not solved. Lane-scoping only ever moves rows toward
# unrecovered (live claudex window: 93 -> 89 recovered, 256 -> 260 unrecovered), so it cannot
# newly pardon anything. Teeth check against the very window that first proved this assertion
# (claudex ts>=1786930524162; the snapshot measured here was 107 rows, 91 ok / 16 non-ok across
# THREE models, run lengths 1,1,1,1,5,7): whole-file pairing scored it 11 unrecovered / 5
# pardoned, and lane-scoping reds HARDER on the same rows — 16 unrecovered / 0 pardoned, because
# every one of those 5 pardons was itself a cross-model adjacency, the exact lie this fix closes.
# A trailing failure with nothing after it in its lane is unrecovered by construction, so a head
# that dies at the end of the window still reds.
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
rows = []
try:
    with open(path) as f:
        for line in f:
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if r.get("ts", 0) >= since:
                rows.append(r)
except FileNotFoundError:
    pass

rows.sort(key=lambda r: r.get("ts", 0))
ok = [r for r in rows if r.get("outcome") == "ok"]
aborts = sum(1 for r in rows if r.get("outcome") == "client_abort")

def tally(outcomes):
    seen = {}
    for o in outcomes:
        seen[o] = seen.get(o, 0) + 1
    return ", ".join(f"{k}x{v}" for k, v in sorted(seen.items()))

# DR-49b: pair within the MODEL lane, never across the whole file — see the SAME MODEL
# paragraph above for why raw adjacency lied green under interleaved concurrent traffic.
lanes = {}
for r in rows:
    lanes.setdefault(r.get("model"), []).append(r)
unrecovered, recovered = [], []
for lane in lanes.values():
    for i, r in enumerate(lane):
        o = r.get("outcome")
        if o in ("ok", "client_abort"):
            continue
        nxt = lane[i + 1].get("outcome") if i + 1 < len(lane) else None
        (recovered if nxt == "ok" else unrecovered).append(o)

problems = []
if len(ok) < want:
    problems.append(f"only {len(ok)} ok perf rows since window start (want >= {want})")
if unrecovered:
    problems.append("unrecovered non-ok rows in window: " + tally(unrecovered))
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
print(f"{len(ok)} ok rows / 0 unrecovered non-ok, slowest total={worst}ms, "
      f"attempts==1 on {carried}/{len(ok)} rows carrying it, retries=0, refreshes=0"
      + (f", search_rounds={rounds} (informational)" if rounds else "")
      + (f", retried-then-ok: {tally(recovered)} (informational)" if recovered else "")
      + (f", client_abort x{aborts} (informational — client went away)" if aborts else ""))
PY
}

# Operator 2026-09-15: claude-muse 400d with "name must be at most 64 characters, got 68".
# The live offender was this composed MCP tool name. Tier 2 used to launch in an empty
# mktemp dir, so the session never carried an operator-shaped tool surface. Planting this
# name into the scratch dir is the cheapest honest stand-in that does not depend on which
# plugins happen to be installed. Length is load-bearing: keep it over 64.
#
# THE NAME IS COMPOSED, NOT DECLARED. Claude Code spells an MCP tool mcp__<server key>__<tool>,
# so the 68 characters come from the .mcp.json KEY plus the name the server advertises in its
# tools/list — never from a string anybody writes out in full. Both halves live here; the server
# owns only its short half (checks/e2e/mcp_overlong_tool_server.ts).
#
# REDO 2026-09-17 — the first version of this arm was inert. It planted
# `python3 -c "raise SystemExit(0)"`, which exits before the first byte of the stdio handshake,
# and it wrote no settings, so the project MCP server was never even enabled. Claude Code
# registered zero tools, the 68-char name never reached the wire, and the arm could not have
# caught the 400 it exists to catch. Both halves are fixed below: a REAL server, and the
# settings line that enables it non-interactively.
OVERLONG_TOOL_NAME="mcp__plugin_desktop-commander_desktop-commander__read_process_output"
OVERLONG_MCP_SERVER="plugin_desktop-commander_desktop-commander"
OVERLONG_MCP_TOOL="read_process_output"
OVERLONG_MCP_SERVER_SCRIPT="$ROOT/checks/e2e/mcp_overlong_tool_server.ts"
OVERLONG_MCP_LOG_NAME="mcp-handshake.jsonl"

# Plants the server AND enables it. A project-scoped .mcp.json is INERT on its own: Claude Code
# asks the operator to approve it on first sight, and tier 2 drives a TUI with nobody to answer,
# so an unapproved server silently contributes no tools. `enabledMcpjsonServers` names this one
# server; `enableAllProjectMcpServers` is the blanket form of the same permission — both are read
# from project settings (scratch/.claude/settings.local.json, the same file Claude Code writes
# itself when a human clicks approve). VERIFIED SEPARATELY against Claude Code 2.1.257 on
# 2026-09-17: each key ALONE makes the client spawn the planted server and pull its tools (the
# server's handshake receipt lands either way), so neither is decoration and either one is a
# sufficient enable — which is why the selftest accepts either. Both are written because the
# scratch dir is thrown away at the end of the head, so there is nothing to keep tidy.
#
# `claude mcp list` is NOT the oracle here and says "Pending approval" for this server no matter
# what these settings say: it reports the per-project approval recorded in ~/.claude.json, which
# the harness deliberately does not touch (it is the operator's own global file). The oracle is
# whether the server is actually spawned — which is what mcp_surface_ok reads.
plant_overlong_mcp() { # scratch_dir — real stdio MCP server in .mcp.json, enabled in project settings
  local scratch="$1"
  # JSON is emitted by python, not a heredoc: $ROOT rides into the args array, and a path that
  # needs escaping must not be able to produce a .mcp.json Claude Code silently fails to parse.
  SCRATCH="$scratch" SERVER="$OVERLONG_MCP_SERVER" SCRIPT="$OVERLONG_MCP_SERVER_SCRIPT" \
  LOG_NAME="$OVERLONG_MCP_LOG_NAME" python3 - <<'PY'
import json, os, pathlib
scratch = pathlib.Path(os.environ["SCRATCH"])
server = os.environ["SERVER"]
(scratch / ".mcp.json").write_text(json.dumps({
    "mcpServers": {
        server: {
            "command": "bun",
            "args": [os.environ["SCRIPT"]],
            # Absolute: the server is spawned with the scratch as cwd today, but the receipt the
            # gate reads must not depend on that staying true.
            "env": {"SPLICE_E2E_MCP_LOG": str(scratch / os.environ["LOG_NAME"])},
        },
    },
}, indent=2) + "\n", encoding="utf-8")
settings = scratch / ".claude"
settings.mkdir(exist_ok=True)
(settings / "settings.local.json").write_text(json.dumps({
    "enabledMcpjsonServers": [server],
    "enableAllProjectMcpServers": True,
}, indent=2) + "\n", encoding="utf-8")
PY
}

# The receipt that proves the tool surface was REAL, read from the JSONL the planted server
# appends per JSON-RPC method it serves. Two facts are asserted, and only these two are
# observable: Claude Code completed the `initialize` handshake with the server, and it pulled
# `tools/list` and was answered with OVERLONG_MCP_TOOL. Since Claude Code composes
# mcp__<server key>__<advertised tool>, a served tools/list IS the 68-char name entering this
# session's tool surface — the surface the operator's muse turn carried when it 400d.
#
# WHY NOT THE WIRE BYTES. The stronger receipt — the exact request the head sent upstream — needs
# the head-side tap gateway/CONTRACT.md describes and that does not exist yet (the same gap
# emit_receipt marks contract_bound=false for), and a perf row carries no tool names at all
# (PerfStats.kt writes ts/model/outcome/marks/counters). So this gate asserts the name entered
# the session and the turn assertions assert the head answered anyway: an unshortened over-cap
# name comes back 400 and turn 1 never renders ANSWER=42. Together that is the catch.
mcp_surface_ok() { # scratch_dir -> verdict on stdout; rc 1 when the surface never formed
  python3 - "$1/$OVERLONG_MCP_LOG_NAME" "$OVERLONG_MCP_TOOL" "$OVERLONG_TOOL_NAME" <<'PY'
import json, pathlib, sys
path, want_tool, composed = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
if not path.exists():
    print("no MCP handshake receipt at %s — Claude Code never spawned the planted server "
          "(is it enabled in the scratch settings?)" % path)
    sys.exit(1)
rows = []
for line in path.read_text(encoding="utf-8").splitlines():
    try:
        rows.append(json.loads(line))
    except json.JSONDecodeError:
        continue
methods = [r.get("method") for r in rows]
if "initialize" not in methods:
    print("MCP receipt has no initialize row (methods: %s) — the stdio handshake never completed"
          % (methods or "<none>"))
    sys.exit(1)
listed = [t for r in rows if r.get("method") == "tools/list" for t in (r.get("tools") or [])]
if not listed:
    print("MCP receipt has no tools/list row (methods: %s) — the server initialized but its tools "
          "never entered the session" % methods)
    sys.exit(1)
if want_tool not in listed:
    print("MCP server advertised %s, not %r — the composed name is no longer %r"
          % (listed, want_tool, composed))
    sys.exit(1)
print("initialize + tools/list served; %r advertised, so %r (%d chars) entered the session tool "
      "surface" % (want_tool, composed, len(composed)))
PY
}

# pass/fail wrapper, shared by tier 2 and the mcp-oracle tier so the selftest exercises the exact
# gate tier 2 runs (same shape as perf_gate below, for the same reason).
mcp_surface_gate() { # head_key scratch_dir
  local verdict
  if verdict="$(mcp_surface_ok "$2")"; then
    note "    tool surface: $verdict"
    pass "$1/mcp-tool-surface"
  else
    fail "$1/mcp-tool-surface" "$verdict"
  fi
}

tier2() {
  local key="$1" label="$2" auth_kind="${3:-}" sess="e2e-$1" scratch start_ms rc
  if ! command -v "$label" >/dev/null 2>&1; then
    skip "$key/tui" "wrapper '$label' not on PATH (run: splice install)"
    return
  fi
  # Deliberate, announced spend — not a gap in tier 1's credential gate. See the COST note in the
  # file header: tier 2 cannot leak the mgmt key on a client-auth head (LaunchService withholds
  # ANTHROPIC_AUTH_TOKEN, so the wrapper rides the operator's own login), it can only bill it.
  if [ "$auth_kind" = client ]; then
    note "    NOTE: client-auth head — these 2 turns bill YOUR personal Anthropic subscription, not a splice credential"
  fi
  scratch="$(mktemp -d "/tmp/splice-e2e-$key.XXXXXX")"
  plant_overlong_mcp "$scratch"
  # MILLISECONDS, not seconds. `$(date +%s) * 1000` truncates to the second, so any row written
  # earlier in that same second falls inside the window — and under `--tier all` the gap between
  # tier 1's own perf row and this line is one count_tokens curl plus a mktemp, tens of ms. That
  # bled tier 1 into tier 2 nearly always: a tier-1 failure was re-reported as a tier-2 perf-rows
  # failure for one event, and a passing tier-1 ok row counted toward tier 2's ">= 2 ok rows", so
  # tier 2 could go green having seen only one of its own two turns.
  start_ms=$(python3 -c 'import time; print(int(time.time() * 1000))')
  note "[$key] tier2 tmux drive: launching '$label' in $scratch"
  tmux -L "$TMUX_SOCK" kill-session -t "$sess" 2>/dev/null || true
  # keep the pane alive after exit so a crash is post-mortem-able
  tmux -L "$TMUX_SOCK" new-session -d -s "$sess" -x 200 -y 50 -c "$scratch" \
    "sh -c '$label; echo E2E_WRAPPER_EXITED=\$?; sleep 600'"

  # DR-110: the rc capture must survive set -e — the bare call's nonzero return errexited the
  # harness before rc was read, so the README-promised SKIP path never ran: the first
  # not-logged-in head killed the run mid-roster (no summary, no tier2_cleanup, tmux session and
  # scratch leaked). Same family as probe_bearer's DR-49a capture below the FATAL comment.
  rc=0; wait_pane "$sess" 90 'bypass permissions|for shortcuts' || rc=$?
  if [ $rc = 2 ]; then skip "$key/tui" "head not logged in"; tier2_cleanup "$key" "$sess" "$scratch" keep; return; fi
  if [ $rc != 0 ]; then fail "$key/tui" "TUI never became ready (90s)"; tier2_cleanup "$key" "$sess" "$scratch"; return; fi

  # The expected answers (ANSWER=42 / SECOND=DONE) deliberately do NOT appear in the prompt text,
  # so a match is the model's RESPONSE, never the echoed input line.
  send_prompt "$sess" "Compute six times seven and reply with exactly ANSWER= followed by the number."
  rc=0; wait_pane "$sess" 150 'ANSWER=42' || rc=$?
  # BEFORE the early return, deliberately. When the over-long name is what broke the turn, the
  # handshake receipt is the diagnosis — a return that discards it leaves "no ANSWER=42" as the
  # only evidence, which is exactly the shape the operator's unexplained 400 already had.
  mcp_surface_gate "$key" "$scratch"
  if [ $rc != 0 ]; then
    fail "$key/tui" "no ANSWER=42 within 150s"; tier2_cleanup "$key" "$sess" "$scratch"; return
  fi
  pass "$key/tui-turn1"

  send_prompt "$sess" "Reply with exactly the word SECOND followed by an equals sign and the word DONE."
  if ! wait_pane "$sess" 150 'SECOND=DONE'; then
    fail "$key/tui" "no SECOND=DONE within 150s (multi-turn)"; tier2_cleanup "$key" "$sess" "$scratch"; return
  fi
  pass "$key/tui-turn2"

  perf_gate "$key" "$start_ms" 2
  tier2_cleanup "$key" "$sess" "$scratch"
}

# The pass/fail wrapper around perf_rows_ok — shared by tier 2 and the perf-oracle tier so the
# selftest exercises the exact gate tier 2 runs, not a lookalike.
perf_gate() { # head_key since_epoch_ms min_rows
  local perf
  if perf="$(perf_rows_ok "$1" "$2" "$3")"; then
    note "    perf: $perf"
    pass "$1/perf-rows"
  else
    fail "$1/perf-rows" "$perf"
  fi
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
MATCHED=0
while IFS=$'\t' read -r key label port healthy auth_kind; do
  [ -n "$ONLY_HEAD" ] && [ "$key" != "$ONLY_HEAD" ] && continue
  MATCHED=1
  if [ "$healthy" != "True" ] && [ "$healthy" != "true" ]; then
    fail "$key" "head reported unhealthy by /api/heads"
    continue
  fi
  note "== head: $key (label=$label port=$port auth=$auth_kind)"
  case "$TIER" in
    1)   tier1 "$key" "$port" "$auth_kind" ;;
    2)   tier2 "$key" "$label" "$auth_kind" ;;
    all) tier1 "$key" "$port" "$auth_kind"; tier2 "$key" "$label" "$auth_kind" ;;
    # Selftest hook (DR-49b): run tier 2's perf gate alone over an E2E_PERF_SINCE/WANT window,
    # so the oracle's pairing rules are red/green provable without a tmux drive or provider spend.
    perf-oracle) perf_gate "$key" "${E2E_PERF_SINCE:?set E2E_PERF_SINCE (epoch ms)}" \
                   "${E2E_PERF_WANT:?set E2E_PERF_WANT (min ok rows)}" ;;
    # Selftest hook (V4-33), same shape and same reason as perf-oracle: run tier 2's tool-surface
    # gate alone over an E2E_MCP_SCRATCH dir, so the gate is red/green provable against a receipt
    # written by the REAL server without a tmux drive or provider spend.
    mcp-oracle) mcp_surface_gate "$key" "${E2E_MCP_SCRATCH:?set E2E_MCP_SCRATCH (scratch dir holding the handshake receipt)}" ;;
    # Selftest hook (V4-33): run the PLANT alone into E2E_MCP_SCRATCH. The selftest then reads the
    # config this produced and spawns the server from it — command, args and env exactly as
    # planted, nothing retyped. A check that greps this file for the word "enabledMcpjsonServers"
    # passes on the COMMENT that explains it; only running the plant can tell the two apart.
    plant-oracle) plant_overlong_mcp "${E2E_MCP_SCRATCH:?set E2E_MCP_SCRATCH (dir to plant into)}"
                  pass "$key/mcp-plant" ;;
    *)   echo "bad --tier $TIER" >&2; exit 2 ;;
  esac
done <<< "$HEADS"

if [ -n "$ONLY_HEAD" ] && [ "$MATCHED" -eq 0 ]; then
  fail "$ONLY_HEAD" "requested head '$ONLY_HEAD' was not returned by /api/heads"
fi

# leave no stray tmux server when every session was cleaned
tmux -L "$TMUX_SOCK" list-sessions >/dev/null 2>&1 || tmux -L "$TMUX_SOCK" kill-server 2>/dev/null || true

note ""
note "── e2e summary ──"
note "  pass: ${#PASS[@]}  fail: ${#FAIL[@]}  skip: ${#SKIP[@]}"
if [ ${#SKIP[@]} -ne 0 ]; then
  note "  ⚠ ${#SKIP[@]} skipped — a skip is not a pass"
fi
for s in "${SKIP[@]:-}"; do [ -n "$s" ] && note "  SKIP $s"; done
for f in "${FAIL[@]:-}"; do [ -n "$f" ] && note "  FAIL $f"; done
[ ${#FAIL[@]} -eq 0 ]
