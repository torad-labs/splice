#!/usr/bin/env bash
# Real-path cache A/B — capture ONE live claudex session, then replay the
# exact Anthropic request bodies through the proxy twice (replayReasoning ON
# vs OFF). Identical input; only the proxy toggle differs.
#
# Isolated on :3097 so production :3099 is never the experiment target.
# config.json is shared across ports — we pin :3099's runtime replayReasoning
# to false before mutating the file layer, and restore the snap at the end.
set -euo pipefail

ROOT="${CLAUDEX_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
export CLAUDEX_ROOT="$ROOT"
PORT="${CACHE_AB_PORT:-3097}"
PROD_PORT=3099
LOG="$HOME/.claude-codex/logs/codex-proxy-${PORT}.log"
KEY=$(cat "$HOME/.claude-codex/state/mgmt-key")
# Capture lives IN the repo (reproducible artifact). Session cwd for claudex
# stays under a scratch dir so we don't pollute the tree with .claude sessions.
EXP_SCRATCH="${CACHE_AB_SCRATCH:-$HOME/.claude/jobs/fb8f646c/tmp/cacheab}"
CAPTURE="${CACHE_AB_CAPTURE:-$ROOT/experiments/cache-replay/capture}"
CONFIG_SNAP="$EXP_SCRATCH/config-snap.json"
CLAUDEX="$ROOT/bin/claudex"
REPLAY=(node "$ROOT/experiments/cache-replay/replay-captured.mjs")
NOTOOLS=(--disallowedTools Bash Read Write Edit Glob Grep WebFetch WebSearch Task NotebookEdit TodoWrite)

PROMPTS=(
  "Sketch a token-bucket rate limiter in Node.js: one class, background refill timer, no deps. Write the code inline. Do not use any tools."
  "Make it burst-tolerant with a maxTokens cap, and explain the tradeoff between burst size and steady-state rate."
  "Add per-key buckets stored in a Map, plus a sweep that evicts keys idle for more than 5 minutes."
  "Write a Jest test that proves the refill math over 3 simulated seconds using fake timers, no real waiting."
  "Refactor to drop setInterval entirely and compute available tokens lazily on each take() call. Explain why lazy refill suits serverless."
  "Add a Redis-backed variant for multi-instance deployments and sketch the Lua script that makes take() atomic."
  "What failure modes does the Redis version have that the in-memory one does not? Be specific."
  "Write TypeScript type declarations for the entire public API surface of this limiter."
  "Give client-side guidance: jittered exponential backoff when a caller is rate-limited, with a code snippet."
  "Summarize the final design in one paragraph and list every edge case we handled across all turns."
)

mgmt() { # $1=port $2=METHOD $3=path [data]
  local port=$1 method=$2 path=$3; shift 3
  if [ "$#" -gt 0 ]; then
    curl -sS -X "$method" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d "$1" \
      "http://127.0.0.1:${port}${path}"
  else
    curl -sS -X "$method" -H "Authorization: Bearer $KEY" "http://127.0.0.1:${port}${path}"
  fi
}
patch_port() { mgmt "$1" PATCH /mgmt/config "$2" >/dev/null; }
get_config() { mgmt "$1" GET /mgmt/config; }

parse_arm() { # $1=start line
  tail -n +"$(($1 + 1))" "$LOG" | grep -aE 'cache: input=' | node -e '
    let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
      const rows=s.split("\n").filter(Boolean).map(l=>{
        const m=l.match(/input=(\d+) cached=(\d+) hit=(\d+)%/);
        return m?{in:+m[1],cached:+m[2],hit:+m[3]}:null;
      }).filter(Boolean);
      const seq=rows.map(r=>r.hit+"%").join("  ");
      const cold=rows.filter(r=>r.in>1024 && r.hit<20).length;
      const warm=rows.filter(r=>r.hit>=80).length;
      const avg=rows.length?Math.round(rows.reduce((a,r)=>a+r.hit,0)/rows.length):0;
      const unc=rows.reduce((a,r)=>a+(r.in-r.cached),0);
      console.log(`  turns=${rows.length}  hit%: ${seq}`);
      console.log(`  avg hit=${avg}%   COLD(>1024 & <20%)=${cold}   WARM(>=80%)=${warm}   total uncached=${unc}`);
    });'
}

# Kill anything on PORT, then spawn the current-tree proxy (inherits
# SPLICE_CAPTURE_DIR / CODEX_PROXY_PORT from this shell). Direct spawn — not the
# launcher — so we control env and avoid billing a probe turn.
restart_proxy() {
  local pids
  pids=$(ss -ltnp 2>/dev/null | grep -E ":${PORT}\\b" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)
  if [ -n "${pids:-}" ]; then
    echo "  killing :${PORT} pids: $pids"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 0.5
    # hard-kill if still bound
    pids=$(ss -ltnp 2>/dev/null | grep -E ":${PORT}\\b" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)
    if [ -n "${pids:-}" ]; then
      # shellcheck disable=SC2086
      kill -9 $pids 2>/dev/null || true
      sleep 0.3
    fi
  fi
  mkdir -p "$HOME/.claude-codex/logs"
  # Detached, log-append; env (incl. SPLICE_CAPTURE_DIR if set) is inherited.
  env CODEX_PROXY_PORT="$PORT" \
    nohup node "$ROOT/server/src/codex-proxy.mjs" --instance="$PORT" \
    >>"$LOG" 2>&1 &
  disown || true
  for i in $(seq 1 50); do
    if mgmt "$PORT" GET /mgmt/status >/tmp/cacheab-status.json 2>/dev/null; then
      local ver
      ver=$(node -e 'const j=JSON.parse(require("fs").readFileSync("/tmp/cacheab-status.json","utf8")); process.stdout.write(String(j.version??"?"))')
      echo "  :${PORT} up v${ver} (capture_env=${SPLICE_CAPTURE_DIR:-off})"
      return 0
    fi
    sleep 0.1
  done
  echo "  FATAL: :${PORT} did not come up — tail of $LOG:" >&2
  tail -30 "$LOG" >&2 || true
  exit 1
}

cleanup() {
  echo
  echo "### restore ###"
  if [ -f "$CONFIG_SNAP" ]; then
    # Restore file layer from snap, and pin both ports' runtime to the snap values.
    local snap_replay snap_effort
    snap_replay=$(node -e "const j=require('$CONFIG_SNAP'); process.stdout.write(String(j.replayReasoning??false))")
    snap_effort=$(node -e "const j=require('$CONFIG_SNAP'); process.stdout.write(JSON.stringify(j.effort??null))")
    cp "$CONFIG_SNAP" "$HOME/.claude-codex/state/config.json"
    patch_port "$PORT" "{\"replayReasoning\":${snap_replay},\"effort\":${snap_effort}}" || true
    patch_port "$PROD_PORT" "{\"replayReasoning\":false}" || true
    echo "  restored config.json + pinned :${PROD_PORT} replayReasoning=false"
  fi
}
trap cleanup EXIT

mkdir -p "$EXP_SCRATCH/armA" "$EXP_SCRATCH/armB"
# Fresh capture dir in-repo (caller can set CACHE_AB_CAPTURE to keep a prior one).
if [ "${CACHE_AB_KEEP_CAPTURE:-0}" != "1" ]; then
  rm -rf "$CAPTURE"
fi
mkdir -p "$CAPTURE"
cp "$HOME/.claude-codex/state/config.json" "$CONFIG_SNAP"

# Pin production so shared config.json mutations cannot flip its live path.
patch_port "$PROD_PORT" '{"replayReasoning":false}'
echo "pinned :${PROD_PORT} runtime replayReasoning=false"

echo "### 1/3 CAPTURE (live claudex → :${PORT}, replayReasoning=ON) ###"
export SPLICE_CAPTURE_DIR="$CAPTURE"
export CODEX_PROXY_PORT="$PORT"
restart_proxy
patch_port "$PORT" '{"replayReasoning":true,"effort":"medium"}'
echo "  capture dir: $CAPTURE"
echo "  config: $(get_config "$PORT" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log(JSON.stringify({replay:j.effective.replayReasoning,effort:j.effective.effort,port:j.effective.port}))})')"

cd "$EXP_SCRATCH/armA"
for i in "${!PROMPTS[@]}"; do
  p="${PROMPTS[$i]}"
  cont=()
  if [ "$i" -eq 0 ]; then
    p="[Capture] $p"
  else
    cont=(--continue)
  fi
  echo "  capture turn $((i+1))/${#PROMPTS[@]} …"
  # claudex reuses the already-running proxy (version match); SPLICE_CAPTURE_DIR
  # is already in the proxy process env from restart_proxy above.
  timeout 300 env CLAUDEX_ROOT="$ROOT" CODEX_PROXY_PORT="$PORT" "$CLAUDEX" -p "$p" "${cont[@]}" "${NOTOOLS[@]}" \
    >/tmp/cacheab-capture-turn-$((i+1)).out 2>/tmp/cacheab-capture-turn-$((i+1)).err \
    || echo "  WARN: turn $((i+1)) exit $?"
done

n_cap=$(find "$CAPTURE" -name 'turn-*.json' | wc -l)
echo "  captured $n_cap request bodies"
if [ "$n_cap" -lt 2 ]; then
  echo "FATAL: need >=2 captured turns (got $n_cap). Check /tmp/cacheab-capture-turn-*.err" >&2
  ls -la /tmp/cacheab-capture-turn-*.err 2>/dev/null || true
  tail -20 /tmp/cacheab-capture-turn-1.err 2>/dev/null || true
  exit 1
fi
# Capture must include redacted_thinking somewhere or the ON arm has nothing to replay.
if ! grep -ql redacted_thinking "$CAPTURE"/turn-*.json; then
  echo "FATAL: no redacted_thinking in capture — proxy was not replaying during capture" >&2
  exit 1
fi
echo "  redacted_thinking present in capture ✓"

echo
echo "### 2/3 REPLAY arm A (replayReasoning=ON) ###"
unset SPLICE_CAPTURE_DIR
# Restart without capture env so replay POSTs are not re-dumped.
restart_proxy
patch_port "$PORT" '{"replayReasoning":true,"effort":"medium"}'
startA=$(wc -l < "$LOG")
"${REPLAY[@]}" --dir "$CAPTURE" --port "$PORT" --tag '[ArmA]'
echo "Arm A (ON):"
parse_arm "$startA"

echo
echo "### 3/3 REPLAY arm B (replayReasoning=OFF) ###"
patch_port "$PORT" '{"replayReasoning":false,"effort":"medium"}'
startB=$(wc -l < "$LOG")
"${REPLAY[@]}" --dir "$CAPTURE" --port "$PORT" --tag '[ArmB]'
echo "Arm B (OFF):"
parse_arm "$startB"

echo
echo ">> Verdict: same Anthropic bodies both arms; only replayReasoning differed."
echo ">> If Arm A (ON) shows more COLD / lower avg hit% than Arm B (OFF), replay busts the cache."
echo ">> Capture (in-repo): $CAPTURE"
