#!/usr/bin/env bun
// NEW: the three structured checks of inside.sh that used to be inline python3 (the no-python wall):
// the count_tokens answer, a launch recipe's contract, and the shipped example topology on a fresh machine.
// The shell keeps the flow; this reads JSON and TOML and asserts.
//
//   topology_checks.ts count-tokens                      (JSON answer on stdin)
//   topology_checks.ts launch-recipe <head-port>         (launch reply on stdin)
//   MGMT_KEY=<key> topology_checks.ts example-heads <example.toml> <heads.json> <control-port> <home>
//
// Contract, as lib.ts: evidence on stdout and exit 0, or why and exit 1. The management key rides in the
// environment, never argv, where a process listing would show it.
import { accessSync, constants, lstatSync, readFileSync, realpathSync } from "node:fs";
import { join } from "node:path";

type Json = Record<string, any>;

class CheckFailed extends Error {}

function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new CheckFailed(message);
}

const obj = (v: unknown): Json => (v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Json) : {});
const stdinJson = (): Json => JSON.parse(readFileSync(0, "utf8")) as Json;

function countTokens(): void {
  const d = stdinJson();
  check(Number.isInteger(d["input_tokens"]), `input_tokens is not an integer: ${JSON.stringify(d)}`);
  console.log(JSON.stringify(d));
}

const RECIPE_KEYS = [
  "ANTHROPIC_BASE_URL",
  "API_FORCE_IDLE_TIMEOUT",
  "API_TIMEOUT_MS",
  "CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES",
  "CLAUDE_CODE_RETRY_WATCHDOG",
  "CLAUDE_STREAM_IDLE_TIMEOUT_MS",
  "CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS",
] as const;

function launchRecipe(port: string): void {
  const r = stdinJson();
  const env = obj(r["env"]);
  console.log(JSON.stringify(Object.fromEntries(RECIPE_KEYS.map((k) => [k, env[k] ?? null]))), "argv:", JSON.stringify(r["argv"]));
  check(env["ANTHROPIC_BASE_URL"] === `http://127.0.0.1:${port}`, `ANTHROPIC_BASE_URL is ${env["ANTHROPIC_BASE_URL"]}`);
  const timeout = String(env["API_TIMEOUT_MS"] ?? "") || "0";
  check(/^[0-9]+$/.test(timeout), `API_TIMEOUT_MS must be a plain decimal integer: ${env["API_TIMEOUT_MS"]}`);
  check(BigInt(timeout) > 900_000n, `API_TIMEOUT_MS must exceed the daemon 900s wall: ${env["API_TIMEOUT_MS"]}`);
  // Nov 8-9: without this the client cut every non-streaming request at Bun's built-in 360 s fetch timeout and re-sent
  // it 228 times, so the head's own deadline never got to answer. "0" is the falsy value that turns it off. Every head.
  check(
    env["API_FORCE_IDLE_TIMEOUT"] === "0",
    `the client's built-in fetch timeout must be off: ${env["API_FORCE_IDLE_TIMEOUT"]}`,
  );
  // V4-72: without persistent retry the client stops after 10 attempts (~2-3 min) and a longer
  // rate-limit hold ends the session instead of resuming when the window reopens. Every head.
  check(env["CLAUDE_CODE_RETRY_WATCHDOG"] === "1", `persistent retry must be planted: ${env["CLAUDE_CODE_RETRY_WATCHDOG"]}`);
  // Nov 9: with this unset the client re-sends a timed-out non-streaming request without limit (one subagent
  // re-sent one request 228 times over 25 hours). Every head, like the watchdog.
  check(
    env["CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES"] === "2",
    `non-streaming timeout retries must be bounded at 2: ${env["CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES"]}`,
  );
  // Nov 6: a streaming turn held at the head for a usage limit got "response stalled" and a re-send, because the
  // client's stream watchdog gives up about 300 s plus this value after the last model event. It takes the session's
  // client timeout, the wall a hold ends before. Every head.
  check(
    env["CLAUDE_STREAM_IDLE_TIMEOUT_MS"] === env["API_TIMEOUT_MS"],
    `the stream idle timeout must be the client timeout: ${env["CLAUDE_STREAM_IDLE_TIMEOUT_MS"]}`,
  );
  // The value above would also stretch the client's no-byte watchdog to 30 minutes; it keeps 300 s. Every head.
  check(
    env["CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS"] === "300000",
    `the byte idle timeout must stay 300 s: ${env["CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS"]}`,
  );
  check(Array.isArray(r["argv"]) && r["argv"].length > 0, "empty argv");
}

/** `login` of each auth kind, offline: what its output must contain, and whether ALL words or ANY one. */
const LOGIN_FLOWS: Record<string, { words: string[]; all: boolean }> = {
  "chatgpt-oauth": { words: ["open this URL to sign in", "https://auth.openai.com"], all: true },
  "grok-oauth": { words: ["open this URL to sign in", "https://"], all: true },
  "kimi-oauth": { words: ["login error", "could not start device login", "enter this code"], all: false },
  "muse-oauth": { words: ["login error", "could not start device login", "enter this code"], all: false },
  "api-key": { words: ["pipe it instead", "splice key set"], all: true },
  // A Claude head signs in with Claude Code's own /login, and its verb says so (V4-276): the Claude command of
  // the one login in ~/.claude, a second Claude command declared under another name of its own folder's.
  client: {
    words: ["no browser login for that kind", "signs in with Claude Code's own /login", "runs on Claude Code's own login"],
    all: false,
  },
};

// A window the client would compact too early on is presented as a `[1m]` selector (V4-358, ClientSpelling:
// grok-4.7 launches as grok-4.7[1m]); the row is the same, so the model is compared without that hint and the
// window, which the hint never moves, exactly.
const selectorFree = (model: unknown) => String(model ?? "").replace(/\[1m\]$/i, "");

// The Claude command runs Claude Code over its own home, ~/.claude: one Claude login, not one per command. Until
// Nov 12, 2026 it ran over a second home, ~/.claude-claude-splice, with a sign-in of its own. Since Nov 13 the head is
// keyed `claude`, and its one command is Claude Code's own `claude`, which the wrap takes over (POST
// /api/claude-head/wrap): splice lays no command of its own for it, and its sign-in is Claude Code's /login.
const CLAUDE_HEAD = "claude";

/** The settings a launch hands Claude Code on its command line, which is where an own-home launch carries the
 *  head's: nothing is written into ~/.claude for it. Null when the launch carries none. */
const settingsOverlay = (argv: unknown): Json | null => {
  const args = Array.isArray(argv) ? argv.map(String) : [];
  const at = args.indexOf("--settings");
  return at >= 0 && at + 1 < args.length ? obj(JSON.parse(args[at + 1]!)) : null;
};

/** A client-login head leaves Claude Code its own model list: these settings name none. */
const pickerFree = (settings: Json) =>
  !["availableModels", "enforceAvailableModels", "modelOverrides"].some((k) => k in settings);

const isLink = (path: string) => {
  try {
    return lstatSync(path).isSymbolicLink();
  } catch {
    return false;
  }
};
const executable = (path: string) => {
  try {
    accessSync(path, constants.X_OK);
    return true;
  } catch {
    return false;
  }
};

async function launch(port: string, key: string, head: string): Promise<Json> {
  const resp = await fetch(`http://127.0.0.1:${port}/launch/${head}`, {
    method: "POST",
    body: '{"dangerouslySkipPermissions":"","args":[]}',
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    signal: AbortSignal.timeout(30_000),
  });
  check(resp.ok, `HTTP ${resp.status}`);
  return (await resp.json()) as Json;
}

async function exampleHeads(example: string, headsFile: string, port: string, home: string): Promise<void> {
  const key = process.env["MGMT_KEY"] ?? "";
  check(key !== "", "MGMT_KEY is not set");
  const t = Bun.TOML.parse(readFileSync(example, "utf8")) as Json;
  const d = JSON.parse(readFileSync(headsFile, "utf8")) as Json;
  const heads = d["heads"] ?? d;
  const rows: Json[] = Array.isArray(heads) ? heads : Object.values(heads);
  const listed = rows.map((h) => String(h["key"])).sort();
  const declared = Object.keys(t["heads"]).sort();
  console.log("declared:", JSON.stringify(declared));
  console.log("listed:  ", JSON.stringify(rows.map((h) => [h["key"], h["running"], h["healthy"]])));
  check(JSON.stringify(listed) === JSON.stringify(declared), `listed ${JSON.stringify(listed)} != declared ${JSON.stringify(declared)}`);

  const registry = realpathSync(join(home, ".claude", "sessions"));
  const bad: string[] = [];
  for (const [head, h] of Object.entries<Json>(t["heads"])) {
    const prov = obj(t["providers"][h["provider"]]);
    const windows = new Map<string, unknown>((prov["models"] ?? []).map((m: Json) => [m["id"], m["context_window"]]));
    const pinned: string = h["pinned_model"] ?? "";
    const want = h["context_window"] || windows.get(pinned) || prov["context_window"];
    const command: string = obj(h["claude"])["command"] ?? head;
    // the Claude head's command is Claude Code's own, wrapped or not; every other head's is the link splice laid
    const wrapper = head === CLAUDE_HEAD || executable(join(home, ".local", "bin", command));
    let env: Json;
    let argv: unknown;
    try {
      const reply = await launch(port, key, head);
      env = obj(reply["env"]);
      argv = reply["argv"];
    } catch (e) {
      // the receipt wants the reason, whatever it is
      console.log(`${head.padEnd(14)} ${command.padEnd(18)} launch FAILED: ${e}`);
      bad.push(head);
      continue;
    }
    // The Claude command's sessions land in the registry itself: it names no folder of its own, so there is nothing
    // to link. Every other head keeps a folder whose sessions link there.
    const ownHome = head === CLAUDE_HEAD;
    const sessions = join(env["CLAUDE_CONFIG_DIR"] ?? "", "sessions");
    let linked = false;
    try {
      linked = ownHome ? !("CLAUDE_CONFIG_DIR" in env) : isLink(sessions) && realpathSync(sessions) === registry;
    } catch {
      linked = false;
    }
    // Foreign heads own the declared window. Client-login heads leave window and tier selection to Claude Code.
    let windowOk: boolean;
    let tierOk = true;
    let pickerOk = true;
    if (String(obj(prov["auth"])["kind"] ?? "").toLowerCase() === "client") {
      windowOk = !("CLAUDE_CODE_MAX_CONTEXT_TOKENS" in env || "CLAUDE_CODE_AUTO_COMPACT_WINDOW" in env);
      tierOk = !Object.keys(env).some((k) => k.startsWith("ANTHROPIC_DEFAULT_"));
      if (ownHome) {
        const overlay = settingsOverlay(argv);
        check(overlay, `${head}: the launch carries no --settings, where it hands Claude Code the head's settings`);
        pickerOk = pickerFree(overlay);
      } else {
        const config = env["CLAUDE_CONFIG_DIR"];
        check(config, `${head}: the launch recipe names no CLAUDE_CONFIG_DIR`);
        const settings = JSON.parse(readFileSync(join(config, "settings.json"), "utf8")) as Json;
        const state = JSON.parse(readFileSync(join(config, ".claude.json"), "utf8")) as Json;
        pickerOk = pickerFree(settings) && !("additionalModelOptionsCache" in state);
      }
    } else {
      windowOk = env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"] === String(want);
    }
    const ok =
      selectorFree(env["ANTHROPIC_MODEL"]) === selectorFree(pinned) && windowOk && tierOk && pickerOk && linked && wrapper;
    console.log(
      `${head.padEnd(14)} ${command.padEnd(18)} model=${env["ANTHROPIC_MODEL"]} window=${env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"]}` +
        ` example=${pinned}@${want} sessions_link=${linked} wrapper=${wrapper} ${ok ? "OK" : "MISMATCH"}`,
    );
    if (!ok) bad.push(head);
  }
  check(bad.length === 0, `heads off the example contract: ${JSON.stringify(bad)}`);

  // `<wrapper> login` for every head of the shipped example, offline: each auth kind must reach ITS
  // flow — OAuth prints the authorize URL and waits (bounded by timeout), the Kimi device flow fails
  // on the unreachable network AFTER trying, api-key names the pipe path, client auth says so.
  const failed: string[] = [];
  for (const [head, h] of Object.entries<Json>(t["heads"])) {
    if (head === CLAUDE_HEAD) continue; // `claude login` is Claude Code's own, not a verb splice answers
    const kind: string = t["providers"][h["provider"]]["auth"]["kind"];
    const command: string = obj(h["claude"])["command"] ?? head;
    const proc = Bun.spawnSync(["timeout", "10", join(home, ".local", "bin", command), "login"], {
      stdin: "ignore",
      stdout: "pipe",
      stderr: "pipe",
    });
    const out = proc.stdout.toString() + proc.stderr.toString();
    const flow = LOGIN_FLOWS[kind];
    check(flow, `${command}: no login expectation for auth kind '${kind}'`);
    const hit = flow.all ? flow.words.every((w) => out.includes(w)) : flow.words.some((w) => out.includes(w));
    const first = out.split("\n").find((l) => l.trim() !== "") ?? "";
    console.log(`${command.padEnd(18)} ${kind.padEnd(13)} ${hit ? "OK" : "MISSING"}  ${first.slice(0, 110)}`);
    if (!hit) {
      console.log(out.slice(-600));
      failed.push(command);
    }
  }
  check(failed.length === 0, `login verb did not reach its provider flow for: ${JSON.stringify(failed)}`);
}

if (import.meta.main) {
  const [verb = "", ...a] = process.argv.slice(2);
  try {
    if (verb === "count-tokens") countTokens();
    else if (verb === "launch-recipe") launchRecipe(a[0] ?? "");
    else if (verb === "example-heads") await exampleHeads(a[0] ?? "", a[1] ?? "", a[2] ?? "", a[3] ?? "");
    else {
      console.log(`topology_checks.ts: unknown verb '${verb}' (count-tokens, launch-recipe, example-heads)`);
      process.exit(2);
    }
  } catch (e) {
    console.log(e instanceof CheckFailed ? e.message : `topology_checks.ts ${verb}: ${String(e)}`);
    process.exit(1);
  }
}
