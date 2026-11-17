// capability-config: a capability is a table in splice.toml, and boot fails closed on one that is wrong. Every finding
// is listed at once, each naming its capability and the key or value at fault, and the daemon does not start. A
// well-formed file boots, `splice status` lists what is declared, and a capability switched on for no head is
// declared and off. (docs/specs/harness.md, decision 1.)

import { afterAll, describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { Splice, freePort } from "../server/lib/harness.ts";
import { launch } from "../speed/lib/session.ts";
import { KEYS, Provider, Worker, blocks, capability, first, head } from "./lib/capability.ts";

const live: Splice[] = [];
const up = new Provider("the provider").start();
const brief = new Worker().start();
const idle = new Worker().start();

afterAll(async () => {
  for (const s of live) await s.dispose();
  for (const each of [up, brief, idle]) each.stop();
});

/** A capability's table with every key well-formed, and `change` written over it. A key set to null is left out. */
function table(name: string, change: Record<string, string | null> = {}): string {
  const keys: Record<string, string | null> = {
    heads: '["astra"]',
    call: '"rule"',
    when: '{ turn = "first", thread = "main", mode = "interactive" }',
    place: '"first-user-message"',
    may: '"add"',
    message: '"Checking the request"',
    color: '"cyan"',
    timeout_s: "30",
    on_failure: '"open"',
    send: '["first_user_message"]',
    worker: '{ kind = "http", url = "http://127.0.0.1:9/work" }',
    ...change,
  };
  const lines = Object.entries(keys)
    .filter(([, v]) => v !== null)
    .map(([k, v]) => `${k} = ${v}`);
  return `\n[capabilities.${name}]\n${lines.join("\n")}\n`;
}

/** Declarations splice refuses, and the word its finding has to carry beside the capability's name. */
const WRONG: { name: string; change: Record<string, string | null>; says: string }[] = [
  { name: "extra-key", change: { colour: '"cyan"' }, says: "colour" },
  { name: "no-such-head", change: { heads: '["nowhere"]' }, says: "nowhere" },
  { name: "agent-called", change: { call: '"agent"' }, says: "call" },
  { name: "odd-turn", change: { when: '{ turn = "second" }' }, says: "turn" },
  { name: "odd-rule-key", change: { when: '{ weekday = "monday" }' }, says: "weekday" },
  { name: "odd-place", change: { place: '"system-prompt"' }, says: "place" },
  { name: "odd-may", change: { may: '"rewrite"' }, says: "may" },
  { name: "odd-color", change: { color: '"ultraviolet"' }, says: "color" },
  { name: "no-time", change: { timeout_s: "0" }, says: "timeout_s" },
  { name: "all-day", change: { timeout_s: "86400" }, says: "timeout_s" },
  { name: "odd-failure", change: { on_failure: '"maybe"' }, says: "on_failure" },
  { name: "odd-field", change: { send: '["first_user_message", "api_key"]' }, says: "api_key" },
  { name: "text-nowhere", change: { place: '"none"', may: '"add"' }, says: "place" },
  { name: "every-turn-text", change: { when: '{ turn = "any" }' }, says: "turn" },
  { name: "odd-worker", change: { worker: '{ kind = "mcp", server = "files" }' }, says: "kind" },
  { name: "no-such-builtin", change: { worker: '{ kind = "builtin", name = "nothing-like-it" }' }, says: "nothing-like-it" },
  { name: "no-url", change: { worker: '{ kind = "http" }' }, says: "url" },
  { name: "Odd_Name", change: {}, says: "Odd_Name" },
];

describe("capability-config", () => {
  test("a wrong declaration stops the daemon from starting, and every finding is listed at once", async () => {
    const s = await Splice.create(
      head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) + table("well-formed") + WRONG.map((w) => table(w.name, w.change)).join(""),
      KEYS,
    );
    live.push(s);
    await expect(s.start()).rejects.toThrow();
    const lines = readFileSync(s.log, "utf8").split("\n");
    for (const w of WRONG) {
      expect(lines.some((line) => line.includes(w.name) && line.includes(w.says)), `a finding that names ${w.name} and ${w.says}`).toBe(true);
    }
    expect(lines.some((line) => line.includes("well-formed"))).toBe(false);
  }, 120_000);

  test("a well-formed file boots, and splice status lists each capability, the heads it is on for, and its worker", async () => {
    const s = await Splice.boot(
      head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) +
        head({ key: "borealis", command: "claude-borealis", port: await freePort(), url: up.url }) +
        capability("brief", { heads: ["astra"], url: brief.url }) +
        capability("idle", { heads: [], url: idle.url }),
      KEYS,
    );
    live.push(s);
    // biome-ignore lint/suspicious/noControlCharactersInRegex: the escape that starts a terminal colour
    const status = s.cli(["status"]).out.replace(/\u001b\[[0-9;]*m/g, "").split("\n");
    const of = (name: string) => status.find((line) => new RegExp(`(^|\\s)${name}(\\s|$)`).test(line)) ?? "";
    expect(of("brief")).toContain("astra");
    expect(of("brief")).not.toContain("borealis");
    expect(of("brief")).toContain(brief.url);
    expect(of("idle")).toMatch(/\boff\b/);
    expect(of("idle")).toContain(idle.url);

    // declared and off: nobody is asked, on either head, and the request is today's
    const on = await launch(s, "claude-astra");
    const other = await launch(s, "claude-borealis");
    const called = await first(on, "Add a --json flag to the status command.");
    expect(brief.of(called.session).length).toBe(1);
    const plain = await first(other, "Add a --json flag to the status command.");
    expect(plain.sent.status).toBe(200);
    expect(blocks(up.of(plain.session)[0]!.body.messages[0])).toEqual(plain.mine);
    expect(brief.of(plain.session).length).toBe(0);
    expect(idle.asked.length).toBe(0);
  }, 300_000);

  test("with no capability declared, every request is the bytes it is today", async () => {
    const s = await Splice.boot(head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }), KEYS);
    live.push(s);
    const on = await launch(s, "claude-astra");
    const { session, mine, sent } = await first(on, "Add a --json flag to the status command.");
    expect(sent.status).toBe(200);
    expect(blocks(up.of(session)[0]!.body.messages[0])).toEqual(mine);
    const lines = s
      .cli(["status"])
      // biome-ignore lint/suspicious/noControlCharactersInRegex: the escape that starts a terminal colour
      .out.replace(/\u001b\[[0-9;]*m/g, "")
      .split("\n");
    expect(lines.some((line) => /capabilit/i.test(line))).toBe(false);
  }, 300_000);
});
