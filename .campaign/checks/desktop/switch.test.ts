// desktop-switch: `splice desktop on` points the Code tab of Claude Desktop at splice by writing Claude Code's own
// provider switch into ~/.claude/settings.json, keeping everything else the person has there. `off` puts the file
// back as it was, and `splice desktop` says what is so.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { existsSync, readFileSync, rmSync } from "node:fs";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { clientHead, desktop, door, doorTurn, fileEnv, readSettings, settingsFile, slottedHead, turnOn, writeSettings } from "./lib/desktop.ts";

const ASTRA_BIG = "claude-astra--astra-large";
const BOREALIS_SMALL = "claude-borealis--borealis-small";

/** What a person has in the file before splice touches it. */
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
  test("on writes Claude Code's switch, the door and its key into the file, and keeps what the person has there", async () => {
    writeSettings(s, MINE);
    const r = s.cli(["desktop", "on"]);
    expect(r.code).toBe(0);

    const env = fileEnv(s);
    expect(env.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/desktop$/);
    expect((env.AWS_BEARER_TOKEN_BEDROCK ?? "").length).toBeGreaterThan(20);
    expect(Number(env.CLAUDE_CODE_MAX_CONTEXT_TOKENS)).toBeGreaterThan(0);
    expect(env.CLAUDE_CODE_AUTO_MODE_SERVER).toBe("0");
    // with no --model and none chosen before, a session starts on the pinned model of the first head in the door,
    // and on says which
    expect(env.ANTHROPIC_MODEL).toBe(ASTRA_BIG);
    expect(r.out + r.err).toContain(ASTRA_BIG);

    const now = readSettings(s);
    expect((now.modelPicker as { replaceBuiltInOptions?: boolean }).replaceBuiltInOptions).toBe(true);
    expect(env.EDITOR).toBe("vim");
    expect(now.permissions).toEqual(MINE.permissions);
    // the pick the person made stays theirs
    expect(now.model).toBe("opus");

    // what the file holds is enough for the Code tab's Claude Code to be answered
    const d = door(s);
    const t = await doorTurn(d.url, { key: d.key, model: d.model, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(astra.reply());
  });

  test("splice desktop says it is on, where the door is and which model a session starts on", () => {
    const desk = desktop(s);
    expect(desk.on).toBe(true);
    expect(desk.door).toBe(door(s).url);
    expect(desk.default_model).toBe(ASTRA_BIG);
  });

  test("off puts the file back as it was", () => {
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(readSettings(s)).toEqual(MINE);
    expect(desktop(s).on).toBe(false);
  });

  test("with no file, on makes one and off leaves nothing of splice's in it", () => {
    rmSync(settingsFile(s), { force: true });
    turnOn(s);
    expect(fileEnv(s).CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(readSettings(s)).toEqual({});
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

  test("on refuses a model that is not in the door, and changes nothing", () => {
    writeSettings(s, MINE);
    const before = text();
    const r = s.cli(["desktop", "on", "--model", "claude-nobody--nothing"]);
    expect(r.code).not.toBe(0);
    expect(r.out + r.err).toContain("claude-nobody--nothing");
    expect(text()).toBe(before);
  });

  test("on refuses a file that already points Claude Code at a provider, names it, and changes nothing", () => {
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
  });
});
