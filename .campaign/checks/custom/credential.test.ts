// cp-credential: a provider on `auth = { kind = "command", credential_command = "..." }` gets its key from the
// team's own command, as Claude Code's apiKeyHelper does: splice runs it when it holds no key, when the key it holds
// expires within five minutes, and once after the provider answers 401, and then sends the turn again, once. With no
// credential to be had, the turn is refused in Anthropic's shape, naming the provider and how to sign in, and nothing
// goes upstream. The key is held in memory alone: it is in no file, no log, and no environment of a process splice
// starts. Such a provider is in the door and in /model whether or not it holds a key now.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { ask, lane, picker, session, toDoor } from "../door/lib/claude.ts";
import { Gateway, SELECTOR, TeamCli, acme, errorOf, holding, withCli } from "./lib/team.ts";

setDefaultTimeout(120_000);

let cli: TeamCli;
let gw: Gateway;
let s: Splice;

/** One door turn on the team's model, carrying a marker the gateway's record of it can be found by. */
async function turn(): Promise<{ status: number; reply: string; error: { type: string; message: string } | null; tried: string[] }> {
  const marker = `cp-credential-${crypto.randomUUID()}`;
  const t = await ask(await session(s), { model: SELECTOR, content: marker });
  return { status: t.status, reply: t.reply, error: errorOf(t), tried: gw.turnsWith(marker).map(Gateway.bearer) };
}

beforeAll(async () => {
  cli = new TeamCli();
  cli.signIn();
  gw = new Gateway(cli).start();
  s = await Splice.boot(acme({ url: gw.url, port: await freePort(), cli }), withCli(cli));
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  gw?.stop();
  cli?.dispose();
});

describe("cp-credential", () => {
  test("with no key held, a turn carries the key the credential command printed", async () => {
    const t = await turn();
    expect(t.status, JSON.stringify(t.error)).toBe(200);
    expect(t.reply).toBe(gw.model.reply());
    expect(t.tried.length).toBe(1);
    expect(cli.issued()).toContain(t.tried[0]!);
  });

  test("a key far from its expiry is used again", async () => {
    const a = await turn();
    const b = await turn();
    expect(a.tried.length).toBe(1);
    expect(b.tried).toEqual(a.tried);
  });

  test("a key the provider refuses with 401 is renewed once and the turn sent again, and the session sees the answer", async () => {
    const before = (await turn()).tried[0]!;
    gw.revoked.add(before);
    // the key it renews to expires in a minute
    cli.expiresIn(60);
    const t = await turn();
    expect(t.status, JSON.stringify(t.error)).toBe(200);
    expect(t.reply).toBe(gw.model.reply());
    expect(t.tried.length).toBe(2);
    expect(t.tried[0]).toBe(before);
    expect(t.tried[1]).not.toBe(before);
    expect(cli.issued()).toContain(t.tried[1]!);
  });

  test("a key that expires within five minutes is renewed before the turn is sent", async () => {
    const held = cli.issued().at(-1)!;
    cli.expiresIn(3600);
    const t = await turn();
    expect(t.status).toBe(200);
    expect(t.tried.length).toBe(1);
    expect(t.tried[0]).not.toBe(held);
    expect(t.tried[0]).toBe(cli.issued().at(-1)!);
  });

  test("a second 401 is refused in Anthropic's shape, naming the provider and how to sign in, after two tries", async () => {
    gw.refuseAll = true;
    try {
      const t = await turn();
      expect(t.status).toBeGreaterThanOrEqual(400);
      expect(t.status).toBeLessThan(500);
      expect(t.error).not.toBeNull();
      expect(t.error!.message).toContain("acme");
      expect(t.error!.message).toContain("/login");
      expect(t.error!.message).toContain("splice login acme");
      expect(t.tried.length).toBe(2);
    } finally {
      gw.refuseAll = false;
    }
  });

  test("with no credential to be had, the turn is refused and nothing goes upstream", async () => {
    cli.signOut();
    await s.restart();
    toDoor(s);
    const t = await turn();
    expect(t.status).toBeGreaterThanOrEqual(400);
    expect(t.status).toBeLessThan(500);
    expect(t.error).not.toBeNull();
    expect(t.error!.message).toContain("acme");
    expect(t.error!.message).toContain("/login");
    expect(t.error!.message).toContain("splice login acme");
    expect(t.tried).toEqual([]);
  });

  test("a provider with no key held is in the door and in /model", async () => {
    expect(cli.signedIn()).toBe(false);
    expect((picker(await session(s)) ?? []).map((r) => r.model)).toContain(SELECTOR);
    expect((lane(s).models ?? []).map((m) => m.model)).toContain(SELECTOR);
    expect((lane(s).left_out ?? []).some((h) => h.head === "acme")).toBe(false);
  });

  test("a credential command that fails is refused the same way, and nothing goes upstream", async () => {
    cli.signIn();
    cli.failWith("acme-ai: your Acme SSO session ended");
    try {
      await s.restart();
      toDoor(s);
      const t = await turn();
      expect(t.status).toBeGreaterThanOrEqual(400);
      expect(t.status).toBeLessThan(500);
      expect(t.error!.message).toContain("acme");
      expect(t.error!.message).toContain("splice login acme");
      expect(t.tried).toEqual([]);
    } finally {
      cli.failWith(null);
    }
  });

  test("no key it printed is in a file, a log, a session's environment or the credential command's own", async () => {
    // one more key, so the command has run again while splice held one
    expect((await turn()).status).toBe(200);
    expect((await turn()).status).toBe(200);
    const keys = cli.issued();
    expect(keys.length).toBeGreaterThan(2);
    const x = await session(s);
    for (const k of keys) {
      expect(holding(s, k)).toEqual([]);
      expect(Object.values(x.env).some((v) => v.includes(k))).toBe(false);
      expect(cli.envOf("token")).not.toContain(k);
    }
  });
});
