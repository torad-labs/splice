// df-features: `splice desktop` names, for each mode, every Claude Desktop feature that depends on the backend, and
// whether it works on splice or is unavailable, with the reason. The words come from what splice knows of its heads
// and of Desktop's own rules in each mode.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { appOnYes, deskJson } from "./lib/full.ts";

setDefaultTimeout(180_000);

const NAMES = [
  "auto mode",
  "effort",
  "context window and compaction",
  "titles",
  "subagents",
  "web search and fetch",
  "images and files",
  "MCP",
  "scheduled tasks",
  "fast mode",
  "Remote Control and cloud environments",
  "claude.ai connectors",
  "SSH sessions",
  "usage display",
];
const STATES = ["works", "partly", "unavailable", "unknown"];

let astra: Upstream;
let s: Splice;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }));
  turnOn(s, "--model", "claude-astra--astra-large");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
});

const of = (name: string, mode: string) => (deskJson(s).features ?? []).find((f) => f.name === name && f.mode === mode);

describe("df-features", () => {
  test("every feature is named for both modes, with a state, and a reason wherever it is not plainly working", () => {
    const features = deskJson(s).features ?? [];
    for (const mode of ["standard", "app"]) {
      for (const name of NAMES) {
        const f = features.find((x) => x.name === name && x.mode === mode);
        expect([mode, name, f !== undefined]).toEqual([mode, name, true]);
        expect([mode, name, STATES.includes(String(f?.state))]).toEqual([mode, name, true]);
        if (f?.state !== "works") expect([mode, name, (f?.reason ?? "").length > 10]).toEqual([mode, name, true]);
      }
    }
  });

  test("in app mode, what Desktop itself turns off is unavailable, with Desktop named as the reason", () => {
    for (const name of ["fast mode", "Remote Control and cloud environments", "claude.ai connectors"]) {
      expect([name, of(name, "app")?.state]).toEqual([name, "unavailable"]);
      expect([name, of(name, "app")?.reason ?? ""]).toEqual([name, expect.stringMatching(/Desktop/)]);
    }
  });

  test("auto mode works in both modes: splice reviews its checks on every head", () => {
    expect(of("auto mode", "app")?.state).toBe("works");
    expect(of("auto mode", "standard")?.state).toBe("works");
  });

  test("the window, compaction and subagents work in both modes, and titles in app mode", () => {
    for (const mode of ["standard", "app"]) {
      for (const name of ["context window and compaction", "subagents"]) expect([mode, name, of(name, mode)?.state]).toEqual([mode, name, "works"]);
    }
    expect(of("titles", "app")?.state).toBe("works");
  });

  test("SSH sessions are unavailable in both modes, because the door listens on this computer only", () => {
    for (const mode of ["standard", "app"]) {
      expect([mode, of("SSH sessions", mode)?.state]).toEqual([mode, "unavailable"]);
      expect([mode, of("SSH sessions", mode)?.reason ?? ""]).toEqual([mode, expect.stringContaining("127.0.0.1")]);
    }
  });

  test("the usage display names the heads that report no plan windows", () => {
    for (const mode of ["standard", "app"]) {
      const f = of("usage display", mode);
      expect([mode, ["partly", "unavailable"].includes(String(f?.state))]).toEqual([mode, true]);
      expect([mode, f?.reason ?? ""]).toEqual([mode, expect.stringContaining("astra")]);
    }
  });

  test("splice desktop says the features of the mode Desktop is in", () => {
    let r = s.cli(["desktop"]);
    expect(r.out).toMatch(/^Features \(standard mode\):/m);
    appOnYes(s);
    r = s.cli(["desktop"]);
    expect(r.out).toMatch(/^Features \(app mode\):/m);
    for (const name of NAMES) expect([name, r.out.includes(name)]).toEqual([name, true]);
  });
});
