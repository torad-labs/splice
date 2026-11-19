// df-managed-launcher: when Claude Code's managed settings name a process wrapper (claudeCodeProcessWrapperEnabled),
// Claude Desktop starts the Code tab's Claude Code through it and strips every credential from the launch
// (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, CLAUDE_CODE_OAUTH_TOKEN, AWS_BEARER_TOKEN_BEDROCK), setting
// CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1. Claude Code's Mantle client takes its bearer only from
// AWS_BEARER_TOKEN_BEDROCK, so every Code-tab turn on the door failed: "Mantle credentials are managed by the desktop
// app, but none are available". `splice desktop launch` is the wrapper that gives a launch on splice's door the desktop
// key, runs any other launch as it came, and can sit in front of another wrapper.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, statSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, doorTurn, slottedHead, turnOn } from "../desktop/lib/desktop.ts";

setDefaultTimeout(120_000);

const ASTRA_BIG = "claude-astra--astra-large";
const BEARER = 'printf "%s" "${AWS_BEARER_TOKEN_BEDROCK-}"';

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

/** A launch as Desktop hands it to a managed process wrapper: its credentials stripped, the host managing the provider. */
function managed(mantle: string | null): Record<string, string> {
  const env: Record<string, string> = {
    ANTHROPIC_API_KEY: "",
    ANTHROPIC_AUTH_TOKEN: "",
    CLAUDE_CODE_OAUTH_TOKEN: "",
    AWS_BEARER_TOKEN_BEDROCK: "",
    CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST: "1",
    CLAUDE_CODE_HOST_AUTH_ENV_VAR: "ANTHROPIC_AUTH_TOKEN",
  };
  if (mantle !== null) env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL = mantle;
  return env;
}

function launch(env: Record<string, string>, ...command: string[]) {
  return s.cli(["desktop", "launch", ...command], undefined, env);
}

describe("df-managed-launcher", () => {
  test("a launch on splice's door gets the desktop key as its Mantle bearer, and a turn with it answers", async () => {
    const r = launch(managed(d.url), "sh", "-c", BEARER);
    expect(r.code).toBe(0);
    expect(r.out).toBe(d.key);
    const t = await doorTurn(d.url, { key: r.out, model: ASTRA_BIG, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
  });

  test("a launch anywhere else runs as it came, with no key of splice's", () => {
    expect(launch(managed("https://mantle.example.invalid/v1"), "sh", "-c", BEARER).out).toBe("");
    expect(launch(managed(null), "sh", "-c", BEARER).out).toBe("");
  });

  test("the command it runs keeps its own arguments and exit code, so it can sit in front of another wrapper", () => {
    const r = launch(managed(d.url), "sh", "-c", 'printf "%s|%s" "$1" "$2"; exit 7', "wrapper", "two words", "--flag");
    expect(r.code).toBe(7);
    expect(r.out).toBe("two words|--flag");
  });

  test("it needs no running daemon", async () => {
    await s.stop();
    expect(launch(managed(d.url), "sh", "-c", BEARER).out).toBe(d.key);
    await s.start();
  }, 240_000);

  test("splice desktop on lays it as a file a managed processWrapper can name, which works from Desktop's own environment", () => {
    const file = join(s.state, "desktop-launch");
    expect(existsSync(file)).toBe(true);
    expect(statSync(file).mode & 0o777).toBe(0o700);
    const env = { PATH: process.env.PATH ?? "/usr/bin:/bin", HOME: s.home, ...managed(d.url) };
    const r = spawnSync(file, ["sh", "-c", BEARER], { env, encoding: "utf8", timeout: 120_000, stdio: ["ignore", "pipe", "pipe"] });
    expect(r.status).toBe(0);
    expect((r.stdout ?? "").trim()).toBe(d.key);
  });

  test("splice desktop's usage names launch", () => {
    const r = s.cli(["desktop", "--help"]);
    expect(`${r.out}${r.err}`).toMatch(/\blaunch\b/);
  });
});
