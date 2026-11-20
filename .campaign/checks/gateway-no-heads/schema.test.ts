// gnh-schema: a provider carries on its own table what a gateway turn needs that only a head block carried until now
// (pinned_model, model_slots, context_window, the system prompt and its mode, overrides), and `[gateway] aliases` maps
// an older selector prefix to a provider. splice.toml with such a provider and no head boots without a finding; an alias
// that names no provider, or claims another provider's own prefix, is a finding that names it.

import { afterAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { PEBBLE, SLATE, provider } from "./lib/providers.ts";

setDefaultTimeout(240_000);

const made: Splice[] = [];
afterAll(async () => {
  for (const s of made) await s.dispose();
});

async function checked(toml: string): Promise<{ code: number; text: string }> {
  const s = await Splice.create(toml);
  made.push(s);
  const r = s.cli(["check-config"]);
  return { code: r.code, text: `${r.out}\n${r.err}` };
}

async function providers(gateway: string): Promise<string> {
  const pebble = `http://127.0.0.1:${await freePort()}/v1`;
  const slate = `http://127.0.0.1:${await freePort()}/v1`;
  return (
    provider({
      key: "pebble",
      url: pebble,
      model: PEBBLE,
      window: 131072,
      env: "PEBBLE_KEY",
      more: [
        `pinned_model = "${PEBBLE}"`,
        `model_slots = { opus = "${PEBBLE}", sonnet = "${PEBBLE}", haiku = "${PEBBLE}" }`,
        "context_window = 65536",
        'system_prompt = "You are running on a pebble."',
        'system_prompt_mode = "replace"',
      ].join("\n"),
    }) +
    `
[providers.pebble.overrides]
upstreamTimeoutMs = "600000"
` +
    provider({ key: "slate", url: slate, model: SLATE, window: 262144, env: "SLATE_KEY" }) +
    gateway
  );
}

describe("gnh-schema", () => {
  test("a provider with its own serving values, no head and an alias table boots without a finding", async () => {
    const r = await checked(await providers(`\n[gateway]\naliases = { "claude-rock--" = "pebble", "zeta--" = "slate" }\n`));
    expect(r.text).not.toContain("gateway.aliases");
    expect(r.text).not.toContain("providers.pebble");
    expect(r.code).toBe(0);
  });

  test("an alias naming no provider is a finding that names the alias and the provider it names", async () => {
    const r = await checked(await providers(`\n[gateway]\naliases = { "claude-rock--" = "basalt" }\n`));
    expect(r.code).toBe(3);
    expect(r.text).toContain("gateway.aliases");
    expect(r.text).toContain("claude-rock--");
    expect(r.text).toContain("basalt");
  });

  test("an alias that claims another provider's own prefix is a finding", async () => {
    const r = await checked(await providers(`\n[gateway]\naliases = { "claude-slate--" = "pebble" }\n`));
    expect(r.code).toBe(3);
    expect(r.text).toContain("gateway.aliases");
    expect(r.text).toContain("claude-slate--");
  });

  test("an override the daemon does not know on a provider is a finding that names it", async () => {
    const toml = (await providers("")).replace('upstreamTimeoutMs = "600000"', 'upstreamTimeoutMs = "600000"\nnotAKnob = "1"');
    const r = await checked(toml);
    expect(r.code).toBe(3);
    expect(r.text).toContain("providers.pebble.overrides");
    expect(r.text).toContain("notAKnob");
  });
});
