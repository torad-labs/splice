// df-credential: `splice desktop credential` is the program Claude Desktop runs as its credential helper. It prints the
// desktop key, alone on one line, to the program that runs it, and to no terminal. It needs no running daemon, and
// after `splice desktop rotate-key` it prints the new key.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, doorTurn, rotateKey, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { credentialOnTerminal } from "./lib/full.ts";

setDefaultTimeout(120_000);

const ASTRA_BIG = "claude-astra--astra-large";

let astra: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }));
  d = turnOn(s, "--model", ASTRA_BIG);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
});

/** What `splice desktop credential` prints to a pipe, and how it exits. */
function credential(): { code: number; lines: string[]; err: string } {
  const r = s.cli(["desktop", "credential"]);
  return { code: r.code, lines: r.out.split("\n").filter((l) => l.trim() !== ""), err: r.err };
}

describe("df-credential", () => {
  test("to a pipe it prints one line, the desktop key, which opens the door", async () => {
    const c = credential();
    expect(c.code).toBe(0);
    expect(c.lines).toEqual([d.key]);
    const t = await doorTurn(d.url, { key: c.lines[0]!, model: ASTRA_BIG, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
  });

  test("on a terminal it prints no key, and says it is Claude Desktop's credential helper", () => {
    const t = credentialOnTerminal(s);
    expect(t.code).not.toBe(0);
    expect(t.out).not.toContain(d.key);
    expect(t.out).toMatch(/credential helper/i);
    expect(t.out).toContain("Claude Desktop");
  });

  test("after rotate-key it prints the new key", () => {
    const fresh = rotateKey(s);
    expect(fresh.key).not.toBe(d.key);
    expect(credential().lines).toEqual([fresh.key]);
    d = fresh;
  });

  test("with the daemon stopped it still prints the key", async () => {
    await s.stop();
    const c = credential();
    expect(c.code).toBe(0);
    expect(c.lines).toEqual([d.key]);
    await s.start();
  }, 240_000);
});
