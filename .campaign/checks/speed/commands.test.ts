// speed-commands: a GPT head signed in with ChatGPT offers /fast and /ultrafast in Claude Code's own menu, for the
// tiers its model offers, each saying what it costs in the catalog's words, and catches them before they reach the
// model. A head that is not signed in with ChatGPT keeps Claude Code's own /fast. The person's own skills stay theirs.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { CodexBackend, FAST, FAST_ONLY, ULTRAFAST, gptEnv, gptHead } from "./lib/codex.ts";
import { expanded, launch, prompt, skill, type Launched } from "./lib/session.ts";

let backend: CodexBackend;
let s: Splice;
let astra: Launched;
let terra: Launched;
let chat: Launched;

beforeAll(async () => {
  backend = CodexBackend.start([
    { slug: "gpt-6-astra", tiers: [FAST, ULTRAFAST] },
    { slug: "gpt-6.1-terra", tiers: [FAST_ONLY] },
  ]);
  const logins = `/tmp/claude-1000/speed-commands-${process.pid}`;
  const toml =
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
    gptHead({ key: "terra", command: "claude-terra", port: await freePort(), backend: backend.url, model: "gpt-6.1-terra", dir: logins }) +
    mockHead({ key: "chat", command: "claude-chat", port: await freePort(), url: `${backend.url}/v1`, model: "mock-model" });
  s = await Splice.create(toml, gptEnv(backend.url));
  // The person's own skill, in their own Claude home, before any head is launched.
  const mine = join(s.home, ".claude", "skills", "mine");
  mkdirSync(mine, { recursive: true });
  writeFileSync(join(mine, "SKILL.md"), "---\ndescription: The person's own skill\n---\nDo the person's thing.\n");
  await s.start();
  astra = await launch(s, "claude-astra");
  terra = await launch(s, "claude-terra");
  chat = await launch(s, "claude-chat");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
});

describe("speed-commands", () => {
  test("a GPT head's launch offers /fast and /ultrafast, each saying what it costs", () => {
    const fast = skill(astra, "fast");
    const ultra = skill(astra, "ultrafast");
    expect(fast).not.toBeNull();
    expect(ultra).not.toBeNull();
    expect(fast!.meta.description).toContain("Fast");
    expect(fast!.meta.description).toContain(FAST.description);
    expect(ultra!.meta.description).toContain("Ultrafast");
    expect(ultra!.meta.description).toContain(ULTRAFAST.description);
    for (const sk of [fast!, ultra!]) {
      // Only the person runs these: a model that could switch them would spend their plan faster on its own say.
      expect(sk.meta["disable-model-invocation"]).toBe("true");
      expect(sk.meta["argument-hint"] ?? "").toMatch(/on.*off/);
    }
    // Claude Code's own fast mode is off in this session, so it never sends Anthropic's speed setting to a GPT model.
    expect(astra.sets.CLAUDE_CODE_DISABLE_FAST_MODE).toBe("1");
  });

  test("a head whose model offers only Fast gets /fast, in its own words, and no /ultrafast", () => {
    const fast = skill(terra, "fast");
    expect(fast).not.toBeNull();
    expect(fast!.meta.description).toContain(FAST_ONLY.description);
    expect(skill(terra, "ultrafast")).toBeNull();
  });

  test("a head that is not signed in with ChatGPT keeps Claude Code's own /fast", () => {
    expect(skill(chat, "fast")).toBeNull();
    expect(skill(chat, "ultrafast")).toBeNull();
    expect(chat.sets.CLAUDE_CODE_DISABLE_FAST_MODE).toBeUndefined();
  });

  test("the person's own skills stay theirs and stay offered", () => {
    expect(existsSync(join(s.home, ".claude", "skills", "fast"))).toBe(false);
    expect(existsSync(join(s.home, ".claude", "skills", "ultrafast"))).toBe(false);
    expect(skill(astra, "mine")?.meta.description).toBe("The person's own skill");
    expect(skill(chat, "mine")?.meta.description).toBe("The person's own skill");
    // /login is still the head's own sign-in.
    expect(existsSync(join(astra.dir, "commands", "login.md"))).toBe(true);
  });

  test("/fast and /ultrafast are caught before they reach the model, typed or expanded", () => {
    const before = backend.turns().length;
    for (const [name, words] of [["fast", "Fast"], ["ultrafast", "Ultrafast"]] as const) {
      const typed = prompt(astra, crypto.randomUUID(), `/${name}`);
      expect(typed.blocked).toBe(true);
      expect(typed.reason).toContain(words);
      const body = expanded(skill(astra, name)!, "");
      const run = prompt(astra, crypto.randomUUID(), body);
      expect(run.blocked).toBe(true);
      expect(run.reason).toContain(words);
    }
    expect(backend.turns().length).toBe(before);
  });

  test("an ordinary prompt, or one that only starts like /fast, goes to the model untouched", () => {
    for (const text of ["Make it faster, please.", "/fastidious review of this file", "/ultrafastest"]) {
      const said = prompt(astra, crypto.randomUUID(), text);
      expect(said.blocked).toBe(false);
      expect(said.context).not.toMatch(/fast/i);
    }
  });
});
