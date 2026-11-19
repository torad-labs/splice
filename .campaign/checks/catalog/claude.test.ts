// gw-claude: `claude` on the Splice gateway is a Claude Code in gateway mode: pointed at the gateway's address with
// the terminal's key as a bearer, discovery on, no provider switch, and no list of models from splice anywhere (no
// picker rows, no allowlist, no tier variables). Its turns run each model on that model's own login and nothing else,
// a name the gateway does not serve is refused by name with nothing sent, a subagent and a selector of today's form run
// as the main session does, and a turn's counts are the same share of the window the session holds that they are of
// the model's own.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { rmSync } from "node:fs";
import { lane } from "../door/lib/claude.ts";
import { reported } from "../desktop/lib/desktop.ts";
import { Splice, freePort, mockHead, json } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { SWITCHES, carries, gatewayOf, gatewayTurn, launchOf, listsOf, mustGateway } from "./lib/gateway.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA = "claude-astra--gpt-6-astra";
const BOREALIS = "claude-borealis--borealis-large";
const BOREALIS_WINDOW = 131_072;
const ASTRA_LOGIN = "synthetic-astra";
const BOREALIS_KEY = "mock-upstream-key";

const logins = `/tmp/claude-1000/catalog-claude-${process.pid}`;
let backend: CodexBackend;
let borealis: Upstream;
let s: Splice;

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  // borealis reports a prompt that fills half of its model's own window
  borealis = new Upstream("borealis", "borealis-large", { usage: { prompt: BOREALIS_WINDOW / 2, cached: 0, completion: 40 } }).start();
  s = await Splice.create(
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large", window: BOREALIS_WINDOW }),
    gptEnv(backend.url),
  );
  await s.start();
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
  borealis?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("gw-claude", () => {
  test("claude's launch points Claude Code at the gateway in gateway mode", async () => {
    expect(lane(s).lane).toBe("door");
    const x = await launchOf(s, "claude");
    const g = gatewayOf(x);
    expect(g).not.toBeNull();
    expect(g!.base).toBe(lane(s).door!);
    expect(g!.key.length).toBeGreaterThan(20);
    expect(x.sets.ANTHROPIC_MODEL).toBe(lane(s).default_model!);
    expect(x.sets.DISABLE_LOGIN_COMMAND).toBe("1");
    expect(x.sets.CLAUDE_CONFIG_DIR).toBeUndefined();
    // every way past the gateway, and every switch that turns discovery off, is taken out of what the session inherits
    for (const name of [...SWITCHES, "ANTHROPIC_BEDROCK_MANTLE_BASE_URL", "AWS_BEARER_TOKEN_BEDROCK", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN"]) {
      expect([name, name in x.sets, x.unset.includes(name)]).toEqual([name, false, true]);
    }
  });

  test("the launch carries no model list: no picker rows, no allowlist, no tier variables, set or inherited", async () => {
    const x = await launchOf(s, "claude");
    expect(listsOf(x, s)).toEqual([]);
    for (const name of ["ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_SMALL_FAST_MODEL"]) {
      expect([name, x.unset.includes(name)]).toEqual([name, true]);
    }
  });

  test("splice claude --json says the window every session on the gateway holds", () => {
    const r = s.cli(["claude", "--json"]);
    const said = json<{ window?: number }>(r);
    expect(Number(said.window)).toBeGreaterThan(0);
  });

  test("a turn naming a model runs on that model's provider with its own login, and no other credential goes with it", async () => {
    const g = mustGateway(await launchOf(s, "claude"));
    const marker = `gw-claude-astra-${crypto.randomUUID()}`;
    const b0 = borealis.completions().length;
    expect((await gatewayTurn(g, ASTRA, { content: marker })).status).toBe(200);
    const astraSaw = backend.turnsWith(marker);
    expect(astraSaw.length).toBe(1);
    expect(carries(astraSaw, ASTRA_LOGIN)).toBe(true);
    expect(carries(astraSaw, BOREALIS_KEY)).toBe(false);
    expect(carries(astraSaw, g.key)).toBe(false);
    expect(borealis.completions().length).toBe(b0);

    const before = borealis.seen.length;
    expect((await gatewayTurn(g, BOREALIS)).status).toBe(200);
    const borealisSaw = borealis.seen.slice(before);
    expect(borealisSaw.length).toBeGreaterThan(0);
    expect(carries(borealisSaw, BOREALIS_KEY)).toBe(true);
    expect(carries(borealisSaw, ASTRA_LOGIN)).toBe(false);
    expect(carries(borealisSaw, g.key)).toBe(false);
  });

  test("a name the gateway does not serve is refused by name, and nothing is sent anywhere", async () => {
    const g = mustGateway(await launchOf(s, "claude"));
    const a0 = backend.turns().length;
    const b0 = borealis.seen.length;
    const t = await gatewayTurn(g, "claude-nowhere--no-such-model");
    expect(t.status).toBeGreaterThanOrEqual(400);
    expect(t.status).toBeLessThan(500);
    expect(t.error?.message ?? "").toContain("claude-nowhere--no-such-model");
    expect(backend.turns().length).toBe(a0);
    expect(borealis.seen.length).toBe(b0);
  });

  test("a subagent's turn naming a gateway model runs on it, beside a main session on another", async () => {
    const g = mustGateway(await launchOf(s, "claude"));
    const session = crypto.randomUUID();
    const marker = `gw-claude-main-${crypto.randomUUID()}`;
    expect((await gatewayTurn(g, ASTRA, { session, content: marker })).status).toBe(200);
    const b0 = borealis.completions().length;
    expect((await gatewayTurn(g, BOREALIS, { session, agent: crypto.randomUUID() })).status).toBe(200);
    expect(borealis.completions().length).toBe(b0 + 1);
    expect(backend.turnsWith(marker).length).toBe(1);
  });

  test("a turn's counts are the same share of the window the session holds that they are of the model's own", async () => {
    const g = mustGateway(await launchOf(s, "claude"));
    const window = Number(json<{ window?: number }>(s.cli(["claude", "--json"])).window);
    for (const stream of [false, true]) {
      const t = await gatewayTurn(g, BOREALIS, { stream });
      expect(t.status).toBe(200);
      expect(reported(t) / window).toBeCloseTo(0.5, 1);
    }
  });
});
