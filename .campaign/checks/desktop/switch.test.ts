// desktop-switch: `splice desktop on` opens the door for Claude Desktop's Code tab and says the five variables to set
// once in Desktop's own Code environment, which only the Code tab reads. It writes nothing into ~/.claude/settings.json,
// which every terminal session reloads. `off` closes the door, so a Code tab turn is refused with the variables to
// remove, and `splice desktop` says what is so. What an older splice's `on` wrote into the settings file is taken out
// when the daemon starts, and a file it left readable by its owner alone gets back the mode a new file would have.
// (Rewritten for 0.6.1: the old checks of what `on` wrote into the settings file pinned the behavior this release
// removes.)

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, readFileSync, rmSync, statSync } from "node:fs";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { clientHead, desktop, doorTurn, printedEnv, readSettings, settingsFile, slottedHead, turnOn, writeSettings } from "./lib/desktop.ts";

const ASTRA_BIG = "claude-astra--astra-large";
const BOREALIS_SMALL = "claude-borealis--borealis-small";
const NAMES = [
  "CLAUDE_CODE_USE_MANTLE",
  "ANTHROPIC_BEDROCK_MANTLE_BASE_URL",
  "AWS_BEARER_TOKEN_BEDROCK",
  "CLAUDE_CODE_MAX_CONTEXT_TOKENS",
  "CLAUDE_CODE_AUTO_MODE_SERVER",
];

/** What a person has in the file. */
const MINE = {
  env: { EDITOR: "vim", CLAUDE_CODE_MAX_CONTEXT_TOKENS: "200000" },
  permissions: { allow: ["Bash(ls:*)"] },
  model: "opus",
  modelPicker: { options: [{ model: "opus", label: "My Opus" }] },
};

let astra: Upstream;
let borealis: Upstream;
let corvus: Upstream;
let s: Splice;
let heads = "";

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  borealis = new Upstream("borealis", "borealis-large").start();
  corvus = new Upstream("corvus", "claude-opus-5-5").start();
  heads =
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
    slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" });
  s = await Splice.boot(heads);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  borealis?.stop();
  corvus?.stop();
});

/** The file's text, or null when there is none. */
function text(): string | null {
  return existsSync(settingsFile(s)) ? readFileSync(settingsFile(s), "utf8") : null;
}

describe("desktop-switch", () => {
  test("on says the five variables to set in Desktop's Code environment, and leaves the settings file as it was", async () => {
    writeSettings(s, MINE);
    const before = text();
    const r = s.cli(["desktop", "on"]);
    expect(r.code).toBe(0);
    expect(text()).toBe(before);

    const env = printedEnv(`${r.out}\n${r.err}`);
    expect(Object.keys(env).sort()).toEqual([...NAMES].sort());
    expect(env.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/desktop$/);
    expect((env.AWS_BEARER_TOKEN_BEDROCK ?? "").length).toBeGreaterThan(20);
    expect(Number(env.CLAUDE_CODE_MAX_CONTEXT_TOKENS)).toBeGreaterThan(0);
    expect(env.CLAUDE_CODE_AUTO_MODE_SERVER).toBe("0");
    expect(r.out + r.err).toContain("Environment variables");
    // with no --model and none chosen before, a session starts on the pinned model of the first head in the door
    expect(r.out + r.err).toContain(ASTRA_BIG);

    // those values are enough for the Code tab's Claude Code to be answered, on the default model when it names none
    const t = await doorTurn(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL!, { key: env.AWS_BEARER_TOKEN_BEDROCK, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(astra.reply());
  });

  test("splice desktop says it is on, where the door is and which model a session starts on", () => {
    const d = turnOn(s);
    const desk = desktop(s);
    expect(desk.on).toBe(true);
    expect(desk.door).toBe(d.url);
    expect(desk.default_model).toBe(ASTRA_BIG);
  });

  test("off closes the door: a Code tab turn is refused with the variables to remove, and the file is still as it was", async () => {
    const d = turnOn(s);
    const r = s.cli(["desktop", "off"]);
    expect(r.code).toBe(0);
    for (const name of NAMES) expect(r.out + r.err).toContain(name);
    expect(readSettings(s)).toEqual(MINE);
    expect(desktop(s).on).toBe(false);
    const before = astra.completions().length;
    const t = await doorTurn(d.url, { key: d.key, model: ASTRA_BIG, session: crypto.randomUUID() });
    expect(t.status).toBe(403);
    expect(t.error?.type).toBe("permission_error");
    expect(t.error?.message).toContain("CLAUDE_CODE_USE_MANTLE");
    expect(astra.completions().length).toBe(before);
  });

  test("with no settings file, on and off make none", () => {
    rmSync(settingsFile(s), { force: true });
    turnOn(s);
    expect(existsSync(settingsFile(s))).toBe(false);
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(existsSync(settingsFile(s))).toBe(false);
  });

  test("--model names the model a session starts on, and it is kept until another is named", () => {
    writeSettings(s, MINE);
    expect(turnOn(s, "--model", BOREALIS_SMALL).model).toBe(BOREALIS_SMALL);
    expect(desktop(s).default_model).toBe(BOREALIS_SMALL);
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(turnOn(s).model).toBe(BOREALIS_SMALL);
    expect(turnOn(s, "--model", ASTRA_BIG).model).toBe(ASTRA_BIG);
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(readSettings(s)).toEqual(MINE);
  });

  test("the window is fixed at the first on, and kept by every later one", () => {
    const first = turnOn(s).window;
    expect(turnOn(s, "--model", BOREALIS_SMALL).window).toBe(first);
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(turnOn(s, "--model", ASTRA_BIG).window).toBe(first);
    expect(s.cli(["desktop", "off"]).code).toBe(0);
  });

  test("what an older splice's on wrote into the settings file is taken out when the daemon starts", async () => {
    writeSettings(s, {
      env: {
        EDITOR: "vim",
        CLAUDE_CODE_USE_MANTLE: "1",
        ANTHROPIC_BEDROCK_MANTLE_BASE_URL: "http://127.0.0.1:1/desktop",
        AWS_BEARER_TOKEN_BEDROCK: "splice-desktop-0123456789abcdef0123456789abcdef",
        CLAUDE_CODE_MAX_CONTEXT_TOKENS: "400000",
        CLAUDE_CODE_AUTO_MODE_SERVER: "0",
        ANTHROPIC_MODEL: ASTRA_BIG,
      },
      permissions: MINE.permissions,
      model: ASTRA_BIG,
      modelPicker: { replaceBuiltInOptions: true, options: [{ model: ASTRA_BIG }] },
    });
    chmodSync(settingsFile(s), 0o600);
    await s.restart();
    expect(readSettings(s)).toEqual({ env: { EDITOR: "vim" }, permissions: MINE.permissions });
    expect(statSync(settingsFile(s)).mode & 0o066).not.toBe(0);
  }, 120_000);

  test("a door model an older off left in model is taken out, and the person's own pick is kept", async () => {
    writeSettings(s, { ...MINE, model: BOREALIS_SMALL });
    await s.restart();
    expect(readSettings(s).model).toBeUndefined();
    writeSettings(s, MINE);
    await s.restart();
    expect(readSettings(s)).toEqual(MINE);
  }, 120_000);

  test("on refuses a model that is not in the door, and changes nothing", () => {
    writeSettings(s, MINE);
    const before = text();
    const r = s.cli(["desktop", "on", "--model", "claude-nobody--nothing"]);
    expect(r.code).not.toBe(0);
    expect(r.out + r.err).toContain("claude-nobody--nothing");
    expect(text()).toBe(before);
    expect(desktop(s).on).toBe(false);
  });

  test("on refuses a settings file that points Claude Code at another provider, names it, and changes nothing", () => {
    for (const provider of ["CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY"]) {
      writeSettings(s, { ...MINE, env: { ...MINE.env, [provider]: "1" } });
      const before = text();
      const r = s.cli(["desktop", "on"]);
      expect(r.code).not.toBe(0);
      expect(r.out + r.err).toContain(provider);
      expect(text()).toBe(before);
    }
    writeSettings(s, { env: { CLAUDE_CODE_USE_MANTLE: "1", ANTHROPIC_BEDROCK_MANTLE_BASE_URL: "https://bedrock-mantle.us-east-1.api.aws" } });
    const before = text();
    const r = s.cli(["desktop", "on"]);
    expect(r.code).not.toBe(0);
    expect(r.out + r.err).toContain("CLAUDE_CODE_USE_MANTLE");
    expect(text()).toBe(before);
  });

  test("on refuses when no head is in the door, and changes nothing", async () => {
    s.writeConfig(clientHead({ key: "corvus", command: "claude-corvus", port: await freePort(), url: corvus.url }));
    await s.restart();
    writeSettings(s, MINE);
    const before = text();
    const r = s.cli(["desktop", "on"]);
    expect(r.code).not.toBe(0);
    expect(text()).toBe(before);
  }, 120_000);
});
