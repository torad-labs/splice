#!/usr/bin/env bun
/** Wire-level SSE probe for one splice head — the tier-1 e2e check.
 *
 *  Drives a REAL streaming turn against a live head port and validates the Anthropic SSE
 *  contract byte-for-byte as a client would experience it:
 *
 *    ordering   message_start first, ping anywhere, block start/delta/stop pairing by index,
 *               message_delta (with stop_reason) then message_stop LAST, nothing after,
 *               no `error` event, SSE comments (`: ping` keepalives) tolerated
 *    streaming  deltas actually arrive incrementally (a proxy that buffers the whole reply
 *               into one flush is a streaming regression even when the bytes are correct)
 *    latency    TTFB / first-delta / total / max inter-event gap against env-tunable budgets
 *
 *  Prints a one-line JSON summary (machine-readable for the orchestrator) and exits 0/1.
 *  Stdlib only — no dependencies.
 *
 *  V4-145: converted to TypeScript (bun). THIS ONE IS EXERCISABLE HERE, which for this file is the
 *  whole point: tools/e2e/fixtures/loopback_control.ts serves exactly the Anthropic SSE this probe
 *  validates, so the port was driven against a real stream and the two summaries differenced, not
 *  merely compared on fixtures.
 *
 *  Three things in the original are semantics rather than syntax, and each is carried exactly:
 *
 *    THE PARSED PAYLOAD IS A PYTHON OBJECT. The original stores `json.loads(payload)` and later
 *    re-emits one of them with `json.dumps(data)[:300]` in the error-event message. JSON.parse
 *    would give `{"a":1}` where Python gives `{"a": 1}`, so the payload goes through python-json's
 *    ordered tree and comes back out with Python's bytes.
 *
 *    THE VIOLATION MESSAGES CONTAIN Python repr() OF CONTAINERS. `f"... {names[i:]}"` and
 *    `f"... {sorted(open_blocks)}"` render as `['a', 'b']`, not `["a","b"]`. This campaign has
 *    found that exact divergence nine times across six walls, every one of them invisible on a
 *    green path, so it is fixed at the source here rather than left for the differential to catch.
 *
 *    `(data or {}).get(...)` IS NOT OPTIONAL CHAINING. Python raises AttributeError when the
 *    parsed payload is a truthy non-mapping (a bare JSON string or number), which for an uncaught
 *    exception means the probe DIES rather than reporting a violation. pyGet reproduces both the
 *    falsy-coalescing and the raise, because "reports a violation" and "crashes" are different
 *    observable outcomes even when both exit non-zero.
 */
import { dumps, loads, obj, isPyObj, isPyNum, floatRepr, type PyValue } from "../src/compat/python-json.ts";

interface Args {
  head: string;
  port: number;
  model: string;
  prompt: string;
  max_tokens: number;
  ttfb_ms: number;
  first_delta_ms: number;
  total_ms: number;
  gap_ms: number;
  min_deltas: number;
}

const DEFAULTS: Args = {
  head: "",
  port: 0,
  model: "",
  prompt: "Count from 1 to 30, comma separated, then say END.",
  max_tokens: 256,
  ttfb_ms: 20_000,
  first_delta_ms: 45_000,
  total_ms: 120_000,
  gap_ms: 30_000,
  min_deltas: 1,
};

const FLAGS: Record<string, keyof Args> = {
  "--head": "head",
  "--port": "port",
  "--model": "model",
  "--prompt": "prompt",
  "--max-tokens": "max_tokens",
  "--ttfb-ms": "ttfb_ms",
  "--first-delta-ms": "first_delta_ms",
  "--total-ms": "total_ms",
  "--gap-ms": "gap_ms",
  "--min-deltas": "min_deltas",
};

/** argparse semantics for the flags this script declares: all-or-nothing pairs, required trio. */
function parseArgs(argv: string[]): Args | null {
  const out: Args = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i += 2) {
    const key = FLAGS[argv[i]];
    if (key === undefined || argv[i + 1] === undefined) return null;
    const raw = argv[i + 1];
    if (typeof DEFAULTS[key] === "number") {
      (out[key] as number) = Number(raw);
    } else {
      (out[key] as string) = raw;
    }
  }
  if (out.head === "" || out.port === 0 || out.model === "") return null;
  return out;
}

// ---------------------------------------------------------------------------------------------
// Python value semantics, for the three places the original leans on them.
// ---------------------------------------------------------------------------------------------

/** Python truthiness. `0`, `0.0`, `{}` and `[]` are all falsy, which is what `data or {}` means. */
function pyTruthy(v: PyValue): boolean {
  if (v === null || v === false) return false;
  if (typeof v === "string") return v.length > 0;
  if (Array.isArray(v)) return v.length > 0;
  if (isPyNum(v)) return Number(v.__pyNum) !== 0;
  if (isPyObj(v)) return v.__pyObj.length > 0;
  return true;
}

function pyTypeName(v: PyValue): string {
  if (v === null) return "NoneType";
  if (typeof v === "boolean") return "bool";
  if (typeof v === "string") return "str";
  if (Array.isArray(v)) return "list";
  if (isPyNum(v)) return v.isFloat ? "float" : "int";
  return "dict";
}

/** `(v or {}).get(key, dflt)` — coalesce falsy to {}, then either read the mapping or raise
 *  AttributeError exactly where Python would. */
function pyGet(v: PyValue, key: string, dflt: PyValue = null): PyValue {
  const o: PyValue = pyTruthy(v) ? v : obj([]);
  if (!isPyObj(o)) {
    throw new TypeError(`'${pyTypeName(o)}' object has no attribute 'get'`);
  }
  const hit = o.__pyObj.find(([k]) => k === key);
  return hit === undefined ? dflt : hit[1];
}

/** Python hash/equality for the members of `open_blocks`: bools alias 1/0, ints and equal-valued
 *  floats collapse, and a dict or list is unhashable. The pairing check is set membership, so
 *  getting this wrong changes verdicts rather than wording. */
function hashKey(v: PyValue): string {
  if (typeof v === "boolean") return "n:" + (v ? "1" : "0");
  if (isPyNum(v)) return "n:" + String(Number(v.__pyNum));
  if (typeof v === "string") return "s:" + v;
  if (v === null) return "z:";
  throw new TypeError(`unhashable type: '${pyTypeName(v)}'`);
}

/** Python repr() of a scalar. Scoped deliberately: the only values reaching it are SSE event names
 *  and the `index` field of a content_block event, so the exotic escapes repr() also emits
 *  (non-printable code points, the `"`-vs-`'` delimiter switch) are handled and the rest are not. */
function pyRepr(v: PyValue): string {
  if (v === null) return "None";
  if (v === true) return "True";
  if (v === false) return "False";
  if (isPyNum(v)) return v.isFloat ? floatRepr(v.__pyNum) : v.__pyNum;
  if (typeof v === "string") {
    const q = v.includes("'") && !v.includes('"') ? '"' : "'";
    let body = "";
    for (const ch of v) {
      if (ch === "\\") body += "\\\\";
      else if (ch === q) body += "\\" + q;
      else if (ch === "\n") body += "\\n";
      else if (ch === "\r") body += "\\r";
      else if (ch === "\t") body += "\\t";
      else body += ch;
    }
    return q + body + q;
  }
  throw new TypeError(`repr() of an unsupported type reached a message this script builds`);
}

/** Python `str([...])` — repr() of a list, which is what an f-string of a list renders. */
function pyReprList(values: PyValue[]): string {
  return "[" + values.map(pyRepr).join(", ") + "]";
}

/** Python sorted() over a homogeneous sequence; a mixed int/None set raises, and the original would
 *  die on it rather than report. */
function pySorted(values: PyValue[]): PyValue[] {
  const out = [...values];
  const numOf = (v: PyValue): number => (typeof v === "boolean" ? (v ? 1 : 0) : Number((v as { __pyNum: string }).__pyNum));
  if (out.every((v) => isPyNum(v) || typeof v === "boolean")) {
    return out.sort((a, b) => numOf(a) - numOf(b));
  }
  if (out.every((v) => typeof v === "string")) {
    return out.sort((a, b) => ((a as string) < (b as string) ? -1 : (a as string) > (b as string) ? 1 : 0));
  }
  const kinds = [...new Set(out.map(pyTypeName))];
  throw new TypeError(`'<' not supported between instances of '${kinds[0]}' and '${kinds[kinds.length - 1]}'`);
}

// ---------------------------------------------------------------------------------------------

/** Incremental SSE parser: records (t_ms, event, data_json|None) + comment/chunk stats. */
class SseCollector {
  buf = "";
  curEvent: string | null = null;
  curData: string[] = [];
  events: [number, string, PyValue][] = [];
  comments = 0;
  chunksWithEvents = 0;

  feed(tMs: number, text: string): void {
    const before = this.events.length;
    this.buf += text;
    let nl = this.buf.indexOf("\n");
    while (nl >= 0) {
      const line = this.buf.slice(0, nl).replace(/\r+$/, "");
      this.buf = this.buf.slice(nl + 1);
      this.line(tMs, line);
      nl = this.buf.indexOf("\n");
    }
    if (this.events.length > before) this.chunksWithEvents += 1;
  }

  private line(tMs: number, line: string): void {
    if (line.startsWith(":")) {
      this.comments += 1;
      return;
    }
    if (line.startsWith("event: ")) {
      this.curEvent = line.slice("event: ".length);
      return;
    }
    if (line.startsWith("data: ")) {
      this.curData.push(line.slice("data: ".length));
      return;
    }
    if (line === "" && this.curEvent !== null) {
      const payload = this.curData.join("\n");
      let data: PyValue = null;
      if (payload) {
        try {
          data = loads(payload);
        } catch {
          data = obj([["_unparseable", payload.slice(0, 200)]]);
        }
      }
      this.events.push([tMs, this.curEvent, data]);
      this.curEvent = null;
      this.curData = [];
    }
  }
}

function validate(
  events: [number, string, PyValue][],
  chunksWithEvents: number,
  args: Args,
  timings: Record<string, number | null>,
): string[] {
  const v: string[] = [];
  const names = events.map((e) => e[1]);
  if (events.length === 0) return ["no SSE events received at all"];

  for (const [, name, data] of events) {
    if (name === "error") {
      v.push(`error event on the wire: ${dumps(data).slice(0, 300)}`);
    }
  }

  const nonPing = names.filter((n) => n !== "ping");
  if (nonPing.length === 0 || nonPing[0] !== "message_start") {
    v.push(`first substantive event is ${nonPing.length > 0 ? nonPing[0] : "absent"}, not message_start`);
  }
  const startCount = names.filter((n) => n === "message_start").length;
  if (startCount !== 1) v.push(`message_start count = ${startCount} (want exactly 1)`);
  const stopCount = names.filter((n) => n === "message_stop").length;
  if (stopCount === 0) {
    v.push("no message_stop — stream did not end cleanly");
  } else if (stopCount !== 1) {
    v.push(`message_stop count = ${stopCount} (want exactly 1)`);
  }
  if (stopCount > 0 && names[names.length - 1] !== "message_stop") {
    v.push(`events AFTER message_stop: ${pyReprList(names.slice(names.indexOf("message_stop") + 1))}`);
  }

  // Insertion-ordered set of open block indices, keyed by Python hash/equality, holding the
  // original value for the report.
  const openBlocks = new Map<string, PyValue>();
  let pairingOk = true;
  for (const [, name, data] of events) {
    const idx = pyGet(data, "index", null);
    if (name === "content_block_start") {
      const key = hashKey(idx);
      if (openBlocks.has(key)) {
        v.push(`content_block_start for already-open index ${pyRepr(idx)}`);
        pairingOk = false;
      }
      openBlocks.set(key, idx);
    } else if (name === "content_block_delta" && !openBlocks.has(hashKey(idx))) {
      v.push(`delta for non-open block index ${pyRepr(idx)}`);
      pairingOk = false;
    } else if (name === "content_block_stop") {
      if (!openBlocks.has(hashKey(idx))) {
        v.push(`content_block_stop for non-open index ${pyRepr(idx)}`);
        pairingOk = false;
      }
      openBlocks.delete(hashKey(idx));
    }
  }
  if (pairingOk && openBlocks.size > 0 && names.includes("message_stop")) {
    v.push(`blocks still open at message_stop: ${pyReprList(pySorted([...openBlocks.values()]))}`);
  }

  const stops = events.filter(([, n]) => n === "message_delta").map(([, , d]) => d);
  const lastStop = stops.length > 0 ? stops[stops.length - 1] : null;
  const stopReason = pyGet(pyGet(lastStop, "delta", obj([])), "stop_reason", null);
  if (stops.length === 0 || !pyTruthy(stopReason)) {
    v.push("message_delta with a stop_reason missing before message_stop");
  }

  const deltas = names.filter((n) => n === "content_block_delta").length;
  if (deltas < args.min_deltas) {
    v.push(`only ${deltas} content_block_delta events (want >= ${args.min_deltas})`);
  }
  if (deltas >= 4 && chunksWithEvents < 2) {
    v.push("whole response arrived in ONE read chunk — proxy is buffering, not streaming");
  }

  const budgets: [string, number][] = [
    ["ttfb_ms", args.ttfb_ms],
    ["first_delta_ms", args.first_delta_ms],
    ["total_ms", args.total_ms],
    ["max_gap_ms", args.gap_ms],
  ];
  for (const [label, cap] of budgets) {
    const val = timings[label];
    if (val !== null && val !== undefined && val > cap) {
      v.push(`${label}=${val} exceeds budget ${cap}`);
    }
  }
  return v;
}

async function main(): Promise<number> {
  const args = parseArgs(process.argv.slice(2));
  if (args === null) {
    process.stderr.write("stream_probe.ts: --head, --port and --model are required\n");
    return 2;
  }
  const body = dumps(
    obj([
      ["model", args.model],
      ["stream", true],
      ["max_tokens", { __pyNum: String(args.max_tokens), isFloat: false }],
      ["messages", [obj([["role", "user"], ["content", args.prompt]])]],
    ]),
  );
  // Every head route sits behind authorize(), so the probe must present a credential exactly as a
  // real client does; without one the head answers 401 and the probe reports "no SSE events" for
  // what is really a missing header. WHICH credential is the caller's decision, not this script's,
  // and the distinction is a safety boundary rather than a preference: on a splice-credentialed
  // head this is the daemon's mgmt key, but on a CLIENT-auth head the gateway forwards this exact
  // header verbatim to the vendor, so the mgmt key must never be what lands here. tools/e2e/src/commands/heads.ts
  // (probe_bearer) owns that choice and hands the result down in SPLICE_PROBE_BEARER.
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const bearer = process.env["SPLICE_PROBE_BEARER"] ?? "";
  if (bearer) headers["Authorization"] = `Bearer ${bearer}`;

  // The original reads under `deadline = now + total_ms/1000 + 30`, so a head that accepts the
  // connection and then goes silent ends the probe rather than hanging the gate forever.
  const controller = new AbortController();
  const deadlineMs = args.total_ms + 30_000;
  const timer = setTimeout(() => controller.abort(), deadlineMs);

  const t0 = performance.now();
  const resp = await fetch(`http://127.0.0.1:${args.port}/v1/messages`, {
    method: "POST",
    body,
    headers,
    signal: controller.signal,
  });
  const ttfbMs = Math.round(performance.now() - t0);

  const col = new SseCollector();
  const eventTimes: number[] = [];
  const statusOk = resp.status === 200;
  const ctype = resp.headers.get("Content-Type") ?? "";
  const decoder = new TextDecoder("utf-8", { fatal: false });
  try {
    if (resp.body !== null) {
      const reader = resp.body.getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        const tMs = Math.round(performance.now() - t0);
        const before = col.events.length;
        col.feed(tMs, decoder.decode(value));
        for (const e of col.events.slice(before)) eventTimes.push(e[0]);
      }
    }
  } finally {
    clearTimeout(timer);
  }

  const totalMs = eventTimes.length > 0 ? eventTimes[eventTimes.length - 1] : Math.round(performance.now() - t0);
  const firstDelta = col.events.find(([, n]) => n === "content_block_delta");
  const firstDeltaMs = firstDelta === undefined ? null : firstDelta[0];
  let maxGapMs = 0;
  for (let i = 1; i < eventTimes.length; i++) {
    maxGapMs = Math.max(maxGapMs, eventTimes[i] - eventTimes[i - 1]);
  }
  const timings: Record<string, number | null> = {
    ttfb_ms: ttfbMs,
    first_delta_ms: firstDeltaMs,
    total_ms: totalMs,
    max_gap_ms: maxGapMs,
  };
  const violations = validate(col.events, col.chunksWithEvents, args, timings);
  // Python inserts HTTP at 0, then Content-Type at 0, so Content-Type ends up first.
  if (!statusOk) violations.unshift(`HTTP ${resp.status}`);
  if (!ctype.includes("text/event-stream")) {
    violations.unshift(`Content-Type '${ctype}' is not text/event-stream`);
  }

  const ok = violations.length === 0;
  const summary = obj([
    ["head", args.head],
    ["model", args.model],
    ["ok", ok],
    ["ttfb_ms", { __pyNum: String(ttfbMs), isFloat: false }],
    ["first_delta_ms", firstDeltaMs === null ? null : { __pyNum: String(firstDeltaMs), isFloat: false }],
    ["total_ms", { __pyNum: String(totalMs), isFloat: false }],
    ["max_gap_ms", { __pyNum: String(maxGapMs), isFloat: false }],
    ["events", { __pyNum: String(col.events.length), isFloat: false }],
    ["deltas", { __pyNum: String(col.events.filter(([, n]) => n === "content_block_delta").length), isFloat: false }],
    ["keepalive_comments", { __pyNum: String(col.comments), isFloat: false }],
    ["chunks_with_events", { __pyNum: String(col.chunksWithEvents), isFloat: false }],
    ["violations", violations],
  ]);
  process.stdout.write(dumps(summary) + "\n");
  return ok ? 0 : 1;
}

if (import.meta.main) {
  process.exit(await main());
}
