// gw-heads: a head's own command, while it still works, starts Claude Code through the Splice gateway exactly as
// `claude` does, in the head's own Claude folder and on the head's own model: gateway mode, and no list of the head's
// own anywhere (its folder's settings, its `.claude.json`, the launch). Its `/model` is the gateway's list, the same
// one every session sees, and a model of another login runs on that login. A session it starts is still the head's.
// A head that forwards the person's own Claude login, which the gateway never carries, keeps its own way to Anthropic,
// with no list of splice's in it either.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { lane, toDoor } from "../door/lib/claude.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, json, mockHead, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { carries, gatewayOf, gatewayTurn, launchOf, listsOf, modelsOf, mustGateway, register, stand } from "./lib/gateway.ts";
import { claudeHeadToml } from "./lib/heads.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA = "claude-astra--gpt-6-astra";
const BOREALIS = "claude-borealis--borealis-large";

const logins = `/tmp/claude-1000/catalog-heads-${process.pid}`;
let backend: CodexBackend;
let borealis: Upstream;
let anthropic: Provider;
let s: Splice;
const standing: { stop: () => void }[] = [];

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  anthropic = new Provider("anthropic").start();
  s = await Splice.create(
    claudeHeadToml({ port: await freePort(), url: anthropic.url }) +
      gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large" }),
    gptEnv(backend.url),
  );
  await s.start();
}, 300_000);

afterAll(async () => {
  for (const x of standing) x.stop();
  await s?.dispose();
  backend?.stop();
  borealis?.stop();
  anthropic?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("gw-heads", () => {
  test("a head's command starts Claude Code through the gateway, in the head's own folder, on the head's own model", async () => {
    const x = await launchOf(s, "claude-astra");
    const g = gatewayOf(x);
    expect(g).not.toBeNull();
    expect(g!.base).toBe(lane(s).door!);
    expect(x.sets.ANTHROPIC_MODEL).toBe(ASTRA);
    expect(x.sets.CLAUDE_CONFIG_DIR ?? "").toBe(join(s.home, ".claude-astra"));
  });

  test("no list of the head's own is anywhere: the launch, its folder's settings or its .claude.json", async () => {
    for (const command of ["claude-astra", "claude-borealis"]) {
      const x = await launchOf(s, command);
      expect([command, listsOf(x, s)]).toEqual([command, []]);
    }
  });

  test("its /model is the gateway's list, the same one a session of claude on the gateway is given", async () => {
    const head = mustGateway(await launchOf(s, "claude-astra"));
    const fromHead = (await modelsOf(head.base, head.key)).rows.map((r) => r.id);
    expect(fromHead).toContain(BOREALIS);
    expect(fromHead).toContain(ASTRA);
    toDoor(s);
    const door = mustGateway(await launchOf(s, "claude"));
    expect((await modelsOf(door.base, door.key)).rows.map((r) => r.id)).toEqual(fromHead);
    expect(s.cli(["claude", "head"]).code).toBe(0);
  });

  test("another login's model, picked in a head's session, runs on that login alone", async () => {
    const g = mustGateway(await launchOf(s, "claude-astra"));
    const before = borealis.seen.length;
    expect((await gatewayTurn(g, BOREALIS)).status).toBe(200);
    const saw = borealis.seen.slice(before);
    expect(saw.length).toBeGreaterThan(0);
    expect(carries(saw, "synthetic-astra")).toBe(false);
    const marker = `gw-heads-own-${crypto.randomUUID()}`;
    expect((await gatewayTurn(g, ASTRA, { content: marker })).status).toBe(200);
    expect(backend.turnsWith(marker).length).toBe(1);
  });

  test("the person's own Claude folder is not written by a head's launch", async () => {
    const mine = join(s.home, ".claude", "settings.json");
    const was = (() => {
      try {
        return readFileSync(mine, "utf8");
      } catch {
        return null;
      }
    })();
    await launchOf(s, "claude-astra");
    const now = (() => {
      try {
        return readFileSync(mine, "utf8");
      } catch {
        return null;
      }
    })();
    expect(now).toBe(was);
  });

  test("a session a head's command starts is still that head's", async () => {
    const p = stand();
    standing.push(p);
    const session = crypto.randomUUID();
    await launchOf(s, "claude-astra", { launch: p.pid });
    register(join(s.home, ".claude"), p.pid, session, s.home);
    let entry: Record<string, unknown> | undefined;
    await until("splice sessions lists it", () => {
      const r = s.cli(["sessions", "--json"]);
      entry = json<{ sessions: Record<string, unknown>[] }>(r).sessions.find((e) => e.sessionId === session);
      return entry !== undefined;
    }, 30_000);
    const door = (entry!.door ?? {}) as { command?: string };
    expect(entry!.head === "astra" || door.command === "claude-astra").toBe(true);
  });

  test("claude on its own head, on the person's own Claude login, carries no list of splice's", async () => {
    expect(lane(s).lane).toBe("head");
    const x = await launchOf(s, "claude");
    expect(listsOf(x, s)).toEqual([]);
  });
});
