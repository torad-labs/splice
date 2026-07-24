#!/usr/bin/env bash
# checks/e2e/reasoning-cache-probe.sh — RC-6: live proof of the gateway-held reasoning cache.
#
# The 2026-07-23 single-shot probe validated one tool call and missed the amnesia class entirely.
# This probe drives LIVE multi-tool agentic turns through a real daemon built from this tree and
# asserts at the level that failed us: the UPSTREAM REQUEST BYTES. A local recording mock stands in
# for chatgpt.com (the item's sanctioned "local echo probe" — the daemon has no request-bytes tap),
# so every body the head sends upstream is captured and assertable, deterministically, with no
# credentials and no provider bill.
#
# Passes (fresh isolated daemon each — ports/state/config never touch the operator's live daemon):
#   ON   reasoning_cache = true (default)
#     A  3-tool task, 2 fan-out rounds: round 1 answers with ONE reasoning envelope + TWO parallel
#        function_calls; the follow-up request must carry that envelope exactly once, immediately
#        before the FIRST function_call (INJECT-ONCE-PER-TURN), and round 3 must carry both turns'
#        envelopes each in-position, none duplicated.
#   B    staleness recovery: the follow-up carrying the envelope is answered 400
#        invalid_encrypted_content; the daemon must retry ONCE with reasoning stripped and the
#        client must still see a clean successful turn (NEVER-BELOW-STATUS-QUO).
#   OFF  reasoning_cache = false: the follow-up request must carry ZERO reasoning items — the
#        status-quo amnesia wire state, recorded as the "before" of the comparison.
#
# Usage:  checks/e2e/reasoning-cache-probe.sh
# Env:    RCP_JAR=<path>       reuse a built jar (default: gradle :app:shadowJar from this tree)
#         RCP_CONTROL_PORT / RCP_HEAD_PORT / RCP_MOCK_PORT   defaults 3496 / 3499 / 3497
#         RCP_KEEP=1           keep the scratch dir (configs, daemon logs, recorded requests)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONTROL_PORT="${RCP_CONTROL_PORT:-3496}"
HEAD_PORT="${RCP_HEAD_PORT:-3499}"
MOCK_PORT="${RCP_MOCK_PORT:-3497}"
SCRATCH="$(mktemp -d /tmp/splice-rcache-probe.XXXXXX)"
DAEMON_PID=""
MOCK_PID=""

note() { printf '%s\n' "$*" >&2; }
fatal() { note "FATAL: $*"; exit 1; }

cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null || true
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ -n "${RCP_KEEP:-}" ]; then
    note "(kept scratch dir: $SCRATCH)"
  else
    rm -rf "$SCRATCH"
  fi
}
trap cleanup EXIT

port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for p in "$CONTROL_PORT" "$HEAD_PORT" "$MOCK_PORT"; do
  port_free "$p" || fatal "port $p is in use — pick alternates via RCP_*_PORT (never the live daemon's 3096-3102)"
done

# ── jar ──────────────────────────────────────────────────────────────────────
JAR="${RCP_JAR:-$ROOT/gateway/app/build/libs/app-all.jar}"
if [ -z "${RCP_JAR:-}" ]; then
  note "building daemon jar from this tree (gradle :app:shadowJar)…"
  (cd "$ROOT/gateway" && ./gradlew -q :app:shadowJar) || fatal "jar build failed"
fi
[ -f "$JAR" ] || fatal "daemon jar missing at $JAR"

# ── scratch fixtures ─────────────────────────────────────────────────────────
mkdir -p "$SCRATCH/state-on" "$SCRATCH/state-off"

# Dummy ChatGPT auth: CodexAuthProvider reads the expiry from the access token's own `exp` JWT
# claim; a far-future claim means it never attempts a refresh, and the mock ignores the bearer.
python3 - "$SCRATCH/auth.json" <<'PY'
import base64, json, sys
def b64url(d): return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")
jwt = f'{b64url({"alg": "none"})}.{b64url({"exp": 4102444800})}.probe'
auth = {"tokens": {"access_token": jwt, "refresh_token": "rt_probe", "account_id": "acct_probe"},
        "last_refresh": "2026-01-01T00:00:00Z"}
open(sys.argv[1], "w").write(json.dumps(auth))
PY

write_config() { # path reasoning_cache_bool
  cat > "$1" <<TOML
[daemon]
control_port = $CONTROL_PORT
show_reasoning = "text"
summary = "detailed"
replay_reasoning = false

[providers.codex]
dialect = "openai-responses"
base_url = "http://127.0.0.1:$MOCK_PORT"
auth = { kind = "chatgpt-oauth", file = "$SCRATCH/auth.json" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true, reasoning_cache = $2 }
[[providers.codex.models]]
id = "gpt-5.6-sol"
label = "Codex 5.6 Sol"
context_window = 400000

[heads.claudex]
provider = "codex"
port = $HEAD_PORT
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5.6-sol"
[heads.claudex.claude]
command = "claudex"
TOML
}
write_config "$SCRATCH/splice-on.toml" true
write_config "$SCRATCH/splice-off.toml" false

# ── mock upstream: scripted Responses SSE + request recorder ─────────────────
cat > "$SCRATCH/mock_upstream.py" <<'PY'
"""Recording mock of the OpenAI Responses upstream. Scenario comes from the user text; the round
comes from how many function_call_output items the transcript carries. Every request body is
appended to requests.jsonl for the wire assertions."""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, RECORD = int(sys.argv[1]), sys.argv[2]
LOCK, SEQ = threading.Lock(), [0]

COMPLETED = {"type": "response.completed", "response": {"usage": {
    "input_tokens": 10, "output_tokens": 5, "output_tokens_details": {"reasoning_tokens": 3}}}}

def reasoning(rid, env):
    return [{"type": "response.reasoning_summary_text.delta", "output_index": 0, "delta": f"plan {rid} "},
            {"type": "response.output_item.done", "output_index": 0,
             "item": {"type": "reasoning", "id": rid, "encrypted_content": env}}]

def fn_call(idx, call_id, name):
    return [{"type": "response.output_item.added", "output_index": idx,
             "item": {"type": "function_call", "call_id": call_id, "name": name}},
            {"type": "response.function_call_arguments.delta", "output_index": idx, "delta": "{}"},
            {"type": "response.function_call_arguments.done", "output_index": idx}]

def text(idx, s):
    return [{"type": "response.output_item.added", "output_index": idx, "item": {"type": "message"}},
            {"type": "response.output_text.delta", "output_index": idx, "delta": s}]

def plan(scenario, outputs, has_reasoning):
    if scenario == "A":
        if outputs == 0:
            return 200, reasoning("rs_a1", "env_a1") + fn_call(1, "call_a1a", "lookup_alpha") \
                + fn_call(2, "call_a1b", "lookup_beta") + [COMPLETED]
        if outputs == 2:
            return 200, reasoning("rs_a2", "env_a2") + fn_call(1, "call_a2", "lookup_gamma") + [COMPLETED]
        return 200, text(0, "FINAL") + [COMPLETED]
    if scenario == "B":
        if outputs == 0:
            return 200, reasoning("rs_b1", "env_b1") + fn_call(1, "call_b1", "lookup_alpha") + [COMPLETED]
        if has_reasoning:
            return 400, {"error": {"type": "invalid_request_error",
                                   "message": "invalid_encrypted_content: could not decrypt reasoning item"}}
        return 200, text(0, "RECOVERED") + [COMPLETED]
    if scenario == "OFF":
        if outputs == 0:
            return 200, reasoning("rs_o1", "env_o1") + fn_call(1, "call_o1", "lookup_alpha") + [COMPLETED]
        return 200, text(0, "FINAL") + [COMPLETED]
    return 500, {"error": {"message": f"mock: no scenario tag in request ({scenario!r})"}}

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        body = json.loads(raw)
        scenario = next((s for s in ("OFF", "A", "B") if f"SCENARIO-{s}" in raw), "?")
        items = body.get("input", [])
        outputs = sum(1 for it in items if isinstance(it, dict) and it.get("type") == "function_call_output")
        has_reasoning = any(isinstance(it, dict) and it.get("type") == "reasoning" for it in items)
        status, payload = plan(scenario, outputs, has_reasoning)
        with LOCK:
            SEQ[0] += 1
            row = {"seq": SEQ[0], "scenario": scenario, "outputs": outputs, "status": status,
                   "include": body.get("include"), "input": items}
            with open(RECORD, "a") as f:
                f.write(json.dumps(row) + "\n")
        if status == 200:
            data = "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in payload).encode()
            ctype = "text/event-stream"
        else:
            data = json.dumps(payload).encode()
            ctype = "application/json"
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
PY

# ── client: drives the agentic loop over the Anthropic dialect ───────────────
cat > "$SCRATCH/probe_client.py" <<'PY'
"""Anthropic-dialect client for one scripted agentic turn chain: POST /v1/messages, execute every
tool_use with a scripted result, loop until end_turn. Asserts the CLIENT-side contract (no error
events, expected final text, expected tool count); the wire truth is asserted from the recorder."""
import http.client
import json
import sys

PORT, SCENARIO, EXPECT_FINAL, EXPECT_TOOLS = int(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4])
MGMT_KEY = sys.argv[5]

TOOLS = [{"name": n, "description": "probe tool", "input_schema": {"type": "object", "properties": {}}}
         for n in ("lookup_alpha", "lookup_beta", "lookup_gamma")]
messages = [{"role": "user", "content": f"SCENARIO-{SCENARIO} run the scripted task"}]
tools_seen, final_text = [], None

def parse_sse(data):
    events = []
    for frame in data.split("\n\n"):
        for line in frame.splitlines():
            if line.startswith("data:"):
                events.append(json.loads(line[5:].strip()))
    return events

for _ in range(6):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=90)
    conn.request("POST", "/v1/messages",
                 json.dumps({"model": "gpt-5.6-sol", "max_tokens": 512, "stream": True,
                             "messages": messages, "tools": TOOLS}),
                 {"Content-Type": "application/json", "anthropic-version": "2023-06-01",
                  "x-api-key": MGMT_KEY})
    resp = conn.getresponse()
    payload = resp.read().decode()
    assert resp.status == 200, f"head returned {resp.status}: {payload[:400]}"
    blocks, stop_reason = {}, None
    for ev in parse_sse(payload):
        t = ev.get("type")
        if t == "error":
            sys.exit(f"CLIENT-FAIL[{SCENARIO}]: error event on the wire: {json.dumps(ev)[:400]}")
        elif t == "content_block_start":
            blocks[ev["index"]] = dict(ev["content_block"], _text="", _json="")
        elif t == "content_block_delta":
            d = ev["delta"]
            if d.get("type") == "text_delta":
                blocks[ev["index"]]["_text"] += d["text"]
            elif d.get("type") == "input_json_delta":
                blocks[ev["index"]]["_json"] += d["partial_json"]
        elif t == "message_delta":
            stop_reason = ev["delta"].get("stop_reason") or stop_reason
    content, results = [], []
    for _, b in sorted(blocks.items()):
        if b["type"] == "text" and b["_text"]:
            content.append({"type": "text", "text": b["_text"]})
        elif b["type"] == "tool_use":
            tools_seen.append(b["name"])
            content.append({"type": "tool_use", "id": b["id"], "name": b["name"],
                            "input": json.loads(b["_json"] or "{}")})
            results.append({"type": "tool_result", "tool_use_id": b["id"], "content": "ok"})
    if stop_reason == "tool_use":
        messages.append({"role": "assistant", "content": content})
        messages.append({"role": "user", "content": results})
        continue
    final_text = "".join(c["text"] for c in content if c["type"] == "text")
    break

assert final_text is not None, f"CLIENT-FAIL[{SCENARIO}]: turn chain never reached end_turn"
assert EXPECT_FINAL in final_text, \
    f"CLIENT-FAIL[{SCENARIO}]: final text {final_text!r} missing {EXPECT_FINAL!r}"
assert len(tools_seen) == EXPECT_TOOLS, \
    f"CLIENT-FAIL[{SCENARIO}]: {len(tools_seen)} tool calls, wanted {EXPECT_TOOLS}: {tools_seen}"
print(f"  client[{SCENARIO}]: {EXPECT_TOOLS} tool(s) executed, final {EXPECT_FINAL!r} — ok")
PY

# ── wire assertions over the recorded upstream bodies ────────────────────────
cat > "$SCRATCH/assert_wire.py" <<'PY'
"""The acceptance: assert the reasoning items on the UPSTREAM WIRE — presence, identity, position
(immediately before the turn's FIRST function_call), inject-once, strip-on-400, and the cache-off
'before' state."""
import json
import sys

rows = [json.loads(line) for line in open(sys.argv[1])]
failures = []

def ok(cond, msg):
    if not cond:
        failures.append(msg)

def pick(scenario, outputs, **extra):
    got = [r for r in rows if r["scenario"] == scenario and r["outputs"] == outputs
           and all(r.get(k) == v for k, v in extra.items())]
    ok(got, f"no recorded request for scenario {scenario} outputs={outputs} {extra}")
    return got

def reasoning_ids(row):
    return [it["id"] for it in row["input"] if it.get("type") == "reasoning"]

def idx_of(row, type_, **fields):
    for i, it in enumerate(row["input"]):
        if it.get("type") == type_ and all(it.get(k) == v for k, v in fields.items()):
            return i
    return None

def assert_in_position(row, rid, env, call_id, label):
    ri, ci = idx_of(row, "reasoning", id=rid), idx_of(row, "function_call", call_id=call_id)
    ok(ri is not None, f"{label}: reasoning {rid} missing")
    ok(ci is not None, f"{label}: function_call {call_id} missing")
    if ri is not None and ci is not None:
        ok(ci == ri + 1, f"{label}: {rid} at {ri} not immediately before {call_id} at {ci}")
        ok(row["input"][ri].get("encrypted_content") == env, f"{label}: {rid} envelope mismatch")

# ON / A round 1: fresh turn — nothing to inject; include must request the envelopes back.
for r in pick("A", 0):
    ok(reasoning_ids(r) == [], f"A r1 (seq {r['seq']}): fresh turn carries reasoning {reasoning_ids(r)}")
    ok("reasoning.encrypted_content" in (r["include"] or []),
       f"A r1 (seq {r['seq']}): include lacks reasoning.encrypted_content — cache can never fill")

# ON / A round 2 (both parallel results in): ONE envelope, before the FIRST of the two calls.
for r in pick("A", 2):
    ok(reasoning_ids(r) == ["rs_a1"],
       f"A r2 (seq {r['seq']}): reasoning ids {reasoning_ids(r)}, wanted exactly [rs_a1]")
    assert_in_position(r, "rs_a1", "env_a1", "call_a1a", f"A r2 (seq {r['seq']})")
    a, b = idx_of(r, "function_call", call_id="call_a1a"), idx_of(r, "function_call", call_id="call_a1b")
    if a is not None and b is not None:
        ok(b == a + 1, f"A r2 (seq {r['seq']}): second parallel call not adjacent — item between? "
                       "(inject-once violated)")

# ON / A round 3: both turns' envelopes, each in-position, none duplicated.
for r in pick("A", 3):
    ok(sorted(reasoning_ids(r)) == ["rs_a1", "rs_a2"],
       f"A r3 (seq {r['seq']}): reasoning ids {reasoning_ids(r)}, wanted [rs_a1, rs_a2]")
    assert_in_position(r, "rs_a1", "env_a1", "call_a1a", f"A r3 (seq {r['seq']})")
    assert_in_position(r, "rs_a2", "env_a2", "call_a2", f"A r3 (seq {r['seq']})")

# ON / B: the poisoned follow-up 400s, the retry is immediate, stripped, and otherwise intact.
bad = pick("B", 1, status=400)
good = [r for r in rows if r["scenario"] == "B" and r["outputs"] == 1 and r["status"] == 200]
ok(len(bad) == 1, f"B: wanted exactly one 400'd request, got {len(bad)}")
ok(len(good) == 1, f"B: wanted exactly one stripped retry, got {len(good)}")
if len(bad) == 1 and len(good) == 1:
    ok(reasoning_ids(bad[0]) == ["rs_b1"], f"B (seq {bad[0]['seq']}): 400'd body should carry rs_b1")
    ok(good[0]["seq"] == bad[0]["seq"] + 1, "B: stripped retry was not the immediate next request")
    ok(reasoning_ids(good[0]) == [], f"B (seq {good[0]['seq']}): retry still carries reasoning")
    ok(idx_of(good[0], "function_call", call_id="call_b1") is not None,
       f"B (seq {good[0]['seq']}): retry lost the function_call")
    kept = [it for it in bad[0]["input"] if it.get("type") != "reasoning"]
    ok(kept == good[0]["input"], f"B (seq {good[0]['seq']}): retry differs beyond the stripped reasoning")

# OFF: the status-quo 'before' — the follow-up is amnesiac on the wire.
off = pick("OFF", 1)
for r in off:
    ok(reasoning_ids(r) == [],
       f"OFF r2 (seq {r['seq']}): cache off must carry zero reasoning, got {reasoning_ids(r)}")

if failures:
    print("WIRE ASSERTIONS FAILED:")
    for f in failures:
        print(f"  ✗ {f}")
    sys.exit(1)

on_r2 = pick("A", 2)[0]
print("  wire: before (cache off) round-2 carries 0 reasoning items — the model re-plans blind")
print(f"  wire: after (cache on) round-2 carries {reasoning_ids(on_r2)} in-position before its "
      "function_call; 400 staleness strips-and-retries once; fresh turns untouched")
PY

# ── daemon lifecycle ─────────────────────────────────────────────────────────
start_daemon() { # config state_dir log
  SPLICE_CONFIG="$1" CLAUDEX_STATE_DIR="$2" \
    java ${SPLICE_JVM_OPTS:--Xmx512m} -jar "$JAR" daemon > "$3" 2>&1 &
  DAEMON_PID=$!
  for _ in $(seq 1 120); do
    curl -sS -m 2 "http://127.0.0.1:$HEAD_PORT/v1/models" >/dev/null 2>&1 && return 0
    kill -0 "$DAEMON_PID" 2>/dev/null || { sed -n '1,40p' "$3" >&2; fatal "daemon died on boot (log above)"; }
    sleep 0.25
  done
  sed -n '1,40p' "$3" >&2
  fatal "head :$HEAD_PORT never became ready (log above)"
}

stop_daemon() {
  kill "$DAEMON_PID" 2>/dev/null || true
  wait "$DAEMON_PID" 2>/dev/null || true
  DAEMON_PID=""
  for _ in $(seq 1 40); do
    port_free "$CONTROL_PORT" && port_free "$HEAD_PORT" && return 0
    sleep 0.25
  done
  fatal "daemon ports never freed after kill"
}

python3 "$SCRATCH/mock_upstream.py" "$MOCK_PORT" "$SCRATCH/requests.jsonl" &
MOCK_PID=$!
sleep 0.3
kill -0 "$MOCK_PID" 2>/dev/null || fatal "mock upstream failed to start"

note "== pass ON (reasoning_cache = true) — scenarios A (3-tool, parallel round) + B (400 strip-retry)"
start_daemon "$SCRATCH/splice-on.toml" "$SCRATCH/state-on" "$SCRATCH/daemon-on.log"
MGMT="$(cat "$SCRATCH/state-on/mgmt-key")"
python3 "$SCRATCH/probe_client.py" "$HEAD_PORT" A FINAL 3 "$MGMT" >&2
python3 "$SCRATCH/probe_client.py" "$HEAD_PORT" B RECOVERED 1 "$MGMT" >&2
stop_daemon

note "== pass OFF (reasoning_cache = false) — status-quo wire state"
start_daemon "$SCRATCH/splice-off.toml" "$SCRATCH/state-off" "$SCRATCH/daemon-off.log"
MGMT="$(cat "$SCRATCH/state-off/mgmt-key")"
python3 "$SCRATCH/probe_client.py" "$HEAD_PORT" OFF FINAL 1 "$MGMT" >&2
stop_daemon

note "== wire assertions over $(wc -l < "$SCRATCH/requests.jsonl") recorded upstream requests"
python3 "$SCRATCH/assert_wire.py" "$SCRATCH/requests.jsonl" >&2

note "reasoning-cache-probe: PASS"
