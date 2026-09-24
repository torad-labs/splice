#!/usr/bin/env bun
/** The structured half of tools/e2e/docker/lib.sh and upgrade.sh: every check that reads JSON or
 *  compares bytes, as one bun entry with a verb per check. The shell keeps the flow (steps, order,
 *  processes); this keeps the parsing, so the scenarios run no Python (the no-python wall).
 *
 *  Contract: a check prints its evidence to stdout and exits 0, or prints why and exits 1 — the
 *  calling step() captures both. Keys are read from their files and compared here, never passed on
 *  argv (a process listing would show them) and never printed.
 */
import { appendFileSync, existsSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

type Json = Record<string, unknown>;

class CheckFailed extends Error {}

function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new CheckFailed(message);
}

function arg(argv: readonly string[], index: number, name: string): string {
  const value = argv[index];
  check(value !== undefined, `missing argument <${name}>`);
  return value;
}

function readJson(path: string): Json {
  const text = path === "-" ? readFileSync(0, "utf8") : readFileSync(path, "utf8");
  return JSON.parse(text) as Json;
}

function obj(value: unknown): Json {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Json) : {};
}

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : [];
}

function mode(path: string): number {
  return statSync(path).mode & 0o777;
}

/** shlex.split for the one shape the status line takes: words, single- or double-quoted. */
function splitWords(command: string): string[] {
  const words: string[] = [];
  const pattern = /'([^']*)'|"((?:[^"\\]|\\.)*)"|(\S+)/g;
  for (const m of command.matchAll(pattern)) words.push(m[1] ?? m[2]?.replace(/\\(.)/g, "$1") ?? m[3] ?? "");
  return words;
}

const verbs: Record<string, (argv: readonly string[]) => void> = {
  /** record <steps-file> <name> <verdict> <seconds> <detail> — one receipt step as a JSON line. */
  record(argv) {
    const step = {
      step: arg(argv, 1, "name"),
      verdict: arg(argv, 2, "verdict"),
      seconds: Number(arg(argv, 3, "seconds")),
      detail: arg(argv, 4, "detail"),
    };
    appendFileSync(arg(argv, 0, "steps-file"), JSON.stringify(step) + "\n");
  },

  /** finish <steps-file> <receipt> <failed> <actual> <tested> <kind> <label> — the receipt file. */
  finish(argv) {
    const lines = readFileSync(arg(argv, 0, "steps-file"), "utf8").split("\n").filter((l) => l.trim() !== "");
    const steps = lines.map((l) => JSON.parse(l) as Json);
    const receipt = {
      kind: arg(argv, 5, "kind"),
      at: new Date().toISOString().replace(/\.\d{3}Z$/, "+00:00"),
      verdict: arg(argv, 2, "failed") === "1" ? "FAIL" : "PASS",
      claudeCodeVersion: arg(argv, 3, "actual"),
      testedClaudeCodeVersion: arg(argv, 4, "tested"),
      steps,
    };
    writeFileSync(arg(argv, 1, "receipt"), JSON.stringify(receipt, null, 1));
    const passed = steps.filter((s) => s["verdict"] === "PASS").length;
    console.log(`\n${arg(argv, 6, "label")}: ${receipt.verdict} (${passed}/${steps.length} steps)`);
  },

  /** field <file|-> <key> — one top-level field of a JSON document (a mock's first line, /health). */
  field(argv) {
    const path = arg(argv, 0, "file");
    const text = path === "-" ? readFileSync(0, "utf8") : readFileSync(path, "utf8").split("\n")[0] ?? "";
    const value = (JSON.parse(text) as Json)[arg(argv, 1, "key")];
    check(value !== undefined, `no field ${argv[1]}`);
    console.log(String(value));
  },

  /** health-ready < /health — exit 0 only when ok and every head is ready. */
  "health-ready"() {
    const h = readJson("-");
    check(h["ok"] === true && h["readyHeads"] === h["heads"] && h["failedHeads"] === 0, "not every head is ready");
  },

  /** api-heads < /api/heads — exactly the three e2e heads, each running. */
  "api-heads"() {
    const d = readJson("-");
    const heads = d["heads"] ?? d;
    const rows = (Array.isArray(heads) ? heads : Object.values(obj(heads))).map(obj);
    console.log("heads:", JSON.stringify(rows.map((h) => [h["key"], h["running"], h["healthy"]])));
    const keys = rows.map((h) => str(h["key"])).sort();
    check(JSON.stringify(keys) === JSON.stringify(["claudex", "mockchat", "mockchat2"]), `heads are ${keys.join(",")}`);
    check(rows.every((h) => h["running"]), "a head is not running");
  },

  /** head-contract <head> <model> <window> <rows id:window,…> <home> <recipe> <control-port> <mgmt-key-file>
   *
   *  Each head materializes its OWN picker (settings.json availableModels + model, enforced, and a
   *  .claude.json additionalModelOptionsCache row per model carrying its context_window) and hands
   *  Claude Code ONE client window (the pinned row's); the four tier slots never share a BARE id;
   *  the launch also packages the head (status line, /login command, its hook); and since v0.4.0 the
   *  session holds the TURN key, never the management key, read by the status line from a 0600
   *  header file, so neither settings.json nor curl's argv carries a key. */
  "head-contract"(argv) {
    const head = arg(argv, 0, "head");
    const model = arg(argv, 1, "model");
    const window = Number(arg(argv, 2, "window"));
    const expectedRows = new Map(
      arg(argv, 3, "rows").split(",").map((kv) => {
        const [id = "", w = "0"] = kv.split(":");
        return [id, Number(w)] as const;
      }),
    );
    const home = arg(argv, 4, "home");
    const env = obj(readJson(arg(argv, 5, "recipe"))["env"]);
    const control = arg(argv, 6, "control-port");
    const mgmtKey = readFileSync(arg(argv, 7, "mgmt-key-file"), "utf8").trim();
    const cfg = str(env["CLAUDE_CONFIG_DIR"]) || join(home, `.claude-${head}`);
    const settings = readJson(join(cfg, "settings.json"));
    const cache = (obj(readJson(join(cfg, ".claude.json")))["additionalModelOptionsCache"] ?? []) as unknown[];
    const rows = new Map(cache.map(obj).map((row) => [str(row["value"]), row["context_window"]] as const));
    const statusline = str(obj(settings["statusLine"])["command"]);
    const hooks = (obj(settings["hooks"])["UserPromptSubmit"] ?? []) as unknown[];
    const hookCmds = hooks.flatMap((entry) => ((obj(entry)["hooks"] ?? []) as unknown[]).map((h) => str(obj(h)["command"])));
    const loginMd = join(cfg, "commands", "login.md");
    const pick = (keys: string[]) => Object.fromEntries(keys.map((k) => [k, env[k]]));
    console.log("recipe:", JSON.stringify(pick(["ANTHROPIC_MODEL", "CLAUDE_CODE_MAX_CONTEXT_TOKENS", "CLAUDE_CODE_AUTO_COMPACT_WINDOW", "CLAUDE_CONFIG_DIR"])));
    console.log("picker:", JSON.stringify({ model: settings["model"], availableModels: settings["availableModels"],
      enforceAvailableModels: settings["enforceAvailableModels"], rows: Object.fromEntries(rows) }));
    console.log("packaging:", JSON.stringify({ statusLine: statusline.replaceAll(mgmtKey, "[redacted]"),
      "login.md": existsSync(loginMd), UserPromptSubmit: hookCmds }));
    check(env["ANTHROPIC_MODEL"] === model, `ANTHROPIC_MODEL is ${String(env["ANTHROPIC_MODEL"])}`);
    check(env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"] === String(window), `CLAUDE_CODE_MAX_CONTEXT_TOKENS is ${String(env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])}`);
    check(env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"] === String(window), `CLAUDE_CODE_AUTO_COMPACT_WINDOW is ${String(env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])}`);
    check(rows.get(model) === window, `pinned row window in the picker cache: ${String(rows.get(model))}`);
    check(settings["model"] === model, `settings model is ${String(settings["model"])}`);
    check(JSON.stringify(settings["availableModels"]) === JSON.stringify([...expectedRows.keys()]),
      `picker off: ${JSON.stringify(settings["availableModels"])}`);
    check(settings["enforceAvailableModels"] === true, "picker is not enforced");
    check(JSON.stringify([...rows].sort()) === JSON.stringify([...expectedRows].sort()), `rows: ${JSON.stringify([...rows])}`);
    const slots = Object.keys(env).sort().filter((k) => /^ANTHROPIC_DEFAULT_(OPUS|SONNET|HAIKU|FABLE)_MODEL$/.test(k)).map((k) => str(env[k]));
    console.log("slots:", JSON.stringify(slots));
    const bare = slots.filter((v) => expectedRows.has(v));
    const wrapped = slots.filter((v) => !expectedRows.has(v));
    check(new Set(bare).size === bare.length, `two tiers carry one bare id, so /model draws that model twice: ${JSON.stringify(slots)}`);
    check(wrapped.every((v) => [...expectedRows.keys()].some((m) => v.endsWith(`--${m}`))), `a tier points outside the roster: ${JSON.stringify(slots)}`);
    const turnKey = str(env["ANTHROPIC_AUTH_TOKEN"]);
    check(turnKey !== "" && turnKey !== mgmtKey, "a gateway session must be planted the turn key, not the management key");
    check(!statusline.includes(mgmtKey) && !statusline.includes(turnKey), "no key inline in the status line command");
    const words = splitWords(statusline);
    const headerArg = words[3] ?? "";
    check(JSON.stringify(words.slice(0, 3)) === JSON.stringify(["curl", "-sS", "-H"]) && headerArg.startsWith("@") &&
      JSON.stringify(words.slice(4)) === JSON.stringify(["--data-binary", "@-", `http://127.0.0.1:${control}/statusline/${head}`]),
      `status line must post to this head with the turn-key header file: ${JSON.stringify(words)}`);
    const headerFile = headerArg.slice(1);
    check(readFileSync(headerFile, "utf8") === `Authorization: Bearer ${turnKey}\n`, "header file carries the planted turn key");
    check(mode(headerFile) === 0o600, "header file is owner-only");
    check(existsSync(loginMd), "no in-session /login command materialized");
    check(hookCmds.some((c) => c.includes("splice-login-hook")), `no /login hook on UserPromptSubmit: ${JSON.stringify(hookCmds)}`);
  },

  /** recipe-plants-mgmt <recipe> <mgmt-key-file> — the old release plants the management key. */
  "recipe-plants-mgmt"(argv) {
    const r = readJson(arg(argv, 0, "recipe"));
    const env = obj(r["env"]);
    const key = readFileSync(arg(argv, 1, "mgmt-key-file"), "utf8").trim();
    const argv0 = strings(r["argv"])[0] ?? "?";
    console.log("recipe env keys:", JSON.stringify(Object.keys(env).sort()), "unset:", JSON.stringify(strings(r["unset"])), "argv[0]:", argv0);
    check(strings(r["argv"]).length > 0, "empty argv");
    check(env["ANTHROPIC_AUTH_TOKEN"] === key, "the old release is expected to plant the management key");
    console.log("ANTHROPIC_AUTH_TOKEN is the management key (compared, not printed)");
  },

  /** replay <recipe> <expected> — run a kept launch recipe exactly as the shim would exec it: the
   *  env of a Claude Code process that was started before the upgrade, asking for its next turn. */
  replay(argv) {
    const r = readJson(arg(argv, 0, "recipe"));
    const expected = arg(argv, 1, "expected");
    const unset = new Set(strings(r["unset"]));
    const env: Record<string, string> = {};
    for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !unset.has(k)) env[k] = v;
    for (const [k, v] of Object.entries(obj(r["env"]))) env[k] = str(v);
    Object.assign(env, { DISABLE_AUTOUPDATER: "1", DISABLE_TELEMETRY: "1", DISABLE_ERROR_REPORTING: "1",
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1" });
    const proc = Bun.spawnSync(["timeout", "120", ...strings(r["argv"])], { env, stdin: "ignore", stdout: "pipe", stderr: "pipe" });
    const out = proc.stdout.toString() + proc.stderr.toString();
    console.log(out.slice(-1500));
    check(proc.exitCode === 0, `the replayed turn exited ${proc.exitCode}`);
    check(out.includes(expected), `the replayed turn did not complete (no '${expected}')`);
  },

  /** turn-key-placement <recipe> <settings.json> <state-dir> — after a relaunch the session env holds
   *  a turn key, the management key is nowhere in it, neither key is in settings.json or the status
   *  line, and the status line reads <state>/turn-auth-header, which is 0600. */
  "turn-key-placement"(argv) {
    const env = obj(readJson(arg(argv, 0, "recipe"))["env"]);
    const settingsText = readFileSync(arg(argv, 1, "settings"), "utf8");
    const state = arg(argv, 2, "state-dir");
    const mgmt = readFileSync(join(state, "mgmt-key"), "utf8").trim();
    const turn = str(env["ANTHROPIC_AUTH_TOKEN"]);
    const statusline = str(obj((JSON.parse(settingsText) as Json)["statusLine"])["command"]);
    const header = join(state, "turn-auth-header");
    console.log("session env holds:", turn === "" ? "no key" : turn === mgmt ? "THE MANAGEMENT KEY" : "a turn key");
    console.log("header file:", header, existsSync(header) ? `0${mode(header).toString(8)}` : "missing");
    check(turn !== "" && turn !== mgmt, "the relaunched session is not planted a turn key");
    check(!JSON.stringify(env).includes(mgmt), "the management key is somewhere in the session env");
    check(!settingsText.includes(mgmt) && !settingsText.includes(turn), "a key is written into settings.json");
    check(!statusline.includes(mgmt) && !statusline.includes(turn), "a key is in the status line argv");
    check(splitWords(statusline)[3] === `@${header}`, `the status line does not read ${header}: ${statusline}`);
    check(mode(header) === 0o600, "turn-auth-header is not 0600");
  },

  /** appended <before> <after> — the resumed transcript begins with the pre-upgrade bytes and grew. */
  appended(argv) {
    const pre = readFileSync(arg(argv, 0, "before"));
    const now = readFileSync(arg(argv, 1, "after"));
    console.log(`transcript ${pre.length} -> ${now.length} bytes`);
    check(now.subarray(0, pre.length).equals(pre), "the resumed transcript does not begin with the pre-upgrade bytes");
    check(now.length > pre.length, "the resume appended nothing to the pre-upgrade session");
  },
};

if (import.meta.main) {
  const [verb = "", ...rest] = process.argv.slice(2);
  const run = verbs[verb];
  if (run === undefined) {
    console.log(`lib.ts: unknown verb '${verb}' (${Object.keys(verbs).join(", ")})`);
    process.exit(2);
  }
  try {
    run(rest);
  } catch (e) {
    console.log(e instanceof CheckFailed ? e.message : `lib.ts ${verb}: ${String(e)}`);
    process.exit(1);
  }
}
