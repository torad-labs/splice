#!/usr/bin/env bash
# Real-session cache A/B — the honest version.
#
# Two isolated claudex-next sessions on the side proxy (:3097), same 10 turns,
# the ONLY variable is replayReasoning (ON vs OFF). Real Claude Code -> proxy
# path, so the request shapes are exactly production (no reconstruction artifact
# like the synthetic harness had). Per-turn cache hit% is read straight off the
# proxy's own log. Distinct turn-1 tag per arm => distinct prompt_cache_key =>
# independent cache shards (no cross-contamination). Isolated on :3097 so it
# never mixes with the operator's live :3099 traffic.
set -uo pipefail

LOG="$HOME/.claude-codex/logs/codex-proxy-3097.log"
KEY=$(cat "$HOME/.claude-codex/state/mgmt-key")
EXP="$HOME/.claude/jobs/fb8f646c/tmp/cacheab"
MGMT="http://127.0.0.1:3097/mgmt/config"
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

patch() { curl -s -X PATCH -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d "$1" "$MGMT" >/dev/null; }

parse_arm() { # $1=start line, $2=label
  tail -n +$(($1+1)) "$LOG" | grep -aE 'cache: input=' | node -e '
    let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
      const rows=s.split("\n").filter(Boolean).map(l=>{const m=l.match(/input=(\d+) cached=(\d+) hit=(\d+)%/);return m?{in:+m[1],cached:+m[2],hit:+m[3]}:null}).filter(Boolean);
      const seq=rows.map(r=>r.hit+"%").join("  ");
      const cold=rows.filter(r=>r.in>1024 && r.hit<20).length;    // real miss: over the floor yet stone cold
      const warm=rows.filter(r=>r.hit>=80).length;
      const avg=rows.length?Math.round(rows.reduce((a,r)=>a+r.hit,0)/rows.length):0;
      const unc=rows.reduce((a,r)=>a+(r.in-r.cached),0);
      console.log(`  turns=${rows.length}  hit%: ${seq}`);
      console.log(`  avg hit=${avg}%   COLD(>1024 & <20%)=${cold}   WARM(>=80%)=${warm}   total uncached=${unc}`);
    });'
}

run_arm() { # $1=label $2=replay $3=dir $4=tag
  echo "### Arm $1 : replayReasoning=$2 ###"
  patch "{\"replayReasoning\":$2}"
  cd "$3" || exit 1
  local start; start=$(wc -l < "$LOG")
  for i in "${!PROMPTS[@]}"; do
    local p="${PROMPTS[$i]}"; local cont=()
    [ "$i" -eq 0 ] && p="$4 $p" || cont=(--continue)
    timeout 300 claudex-next -p "$p" "${cont[@]}" "${NOTOOLS[@]}" >/dev/null 2>&1
    echo "  ...arm $1 turn $((i+1))/10 done"
  done
  parse_arm "$start" "$1"
}

# Same effort in both arms (control): medium keeps 45k-context turns fast; the
# cache mechanism is prefix-stability, not effort. Only replay differs.
patch '{"effort":"medium"}'
echo "cache-replay REAL A/B  proxy=:3097  model=gpt-5.6-sol  effort=medium  turns=10/arm"
run_arm A true  "$EXP/armA" "[RunA]"
run_arm B false "$EXP/armB" "[RunB]"
echo
echo ">> Verdict shape: if replay ON (Arm A) shows scattered COLD turns while replay OFF (Arm B) stays WARM, your production observation reproduces."
