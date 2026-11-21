// modes-routes: a mode keeps what it needs per session in a store splice gives it, which survives a restart, and may
// answer management routes, which splice serves under /api/ behind the management key. The template keeps every text
// it said back in a session and answers `GET /api/template/said?session=<id>` with them. Two modes claiming one path,
// or a mode claiming one of splice's own, is a finding that refuses boot.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { AgentProvider, answer, call, text, toolId } from "../harness/lib/agent.ts";
import { KEYS, head, opening, send } from "../harness/lib/capability.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { TOOL, Tree, mark, modesTable, requireTemplate } from "./lib/modes.ts";

setDefaultTimeout(120_000);
const LONG = 3_600_000;

let up: AgentProvider;

beforeAll(() => {
  requireTemplate();
  up = new AgentProvider("the provider").start();
});

afterAll(() => up?.stop());

describe("modes-routes: the template's store and route", () => {
  let s: Splice;
  let astra: Launched;

  beforeAll(async () => {
    s = await Splice.boot(head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) + modesTable("template", "on = true"), KEYS);
    astra = await launch(s, "claude-astra");
  }, 300_000);

  afterAll(async () => {
    await s?.dispose();
  });

  /** One turn of `session` in which the model has the template say `m` back. */
  async function sayBack(session: string, m: string): Promise<void> {
    up.plan(m, [{ blocks: [call(toolId(), TOOL, { text: m })], stop: "tool_use" }, answer(text(`Said ${m}.`))]);
    const sent = await send(astra, session, [{ role: "user", content: opening(`say back ${m}`) }]);
    expect(sent.status).toBe(200);
  }

  const said = async (session: string) => {
    const r = await s.api(`/api/template/said?session=${session}`);
    expect(r.status).toBe(200);
    return (await r.json()) as { session: string; said: string[] };
  };

  test("what the template said back in a session is listed by its route, behind the management key, and kept across a restart", async () => {
    const session = crypto.randomUUID();
    const [m1, m2] = [mark(), mark()];
    await sayBack(session, m1);
    await sayBack(session, m2);
    expect(await said(session)).toEqual({ session, said: [m1, m2] });
    expect(await said(crypto.randomUUID()).then((r) => r.said)).toEqual([]);

    const bare = await fetch(`http://127.0.0.1:${s.controlPort}/api/template/said?session=${session}`);
    expect(bare.status, "a mode's route is behind the management key").toBe(401);
    const none = await s.api("/api/template/said");
    expect(none.status, "the route refuses a request that names no session").toBe(400);

    await s.restart();
    expect(await said(session)).toEqual({ session, said: [m1, m2] });
  }, 300_000);
});

describe("modes-routes: claims on one path", () => {
  let tree: Tree;

  beforeAll(() => {
    tree = Tree.make();
    tree.copyMode("template", "clash");
    for (const f of tree.git(["ls-files", "-o", "--exclude-standard", "modes/clash"]).out.split("\n").filter(Boolean)) {
      tree.edit(f, (t) => t.replaceAll("/api/clash/said", "/api/template/said"));
    }
    tree.copyMode("template", "grab");
    for (const f of tree.git(["ls-files", "-o", "--exclude-standard", "modes/grab"]).out.split("\n").filter(Boolean)) {
      tree.edit(f, (t) => t.replaceAll("/api/grab/said", "/api/sessions"));
    }
    tree.commit("two modes that claim taken paths");
  }, LONG);

  afterAll(() => tree?.dispose());

  test("two modes claiming one path, and a mode claiming one of splice's, refuse boot, each finding naming the mode and the path", async () => {
    const claims = tree.git(["grep", "-l", "/api/template/said", "--", "modes/template", "modes/clash"]).out.split("\n").filter(Boolean);
    expect(claims.some((f) => f.startsWith("modes/template/")), "the template claims /api/template/said").toBe(true);
    expect(claims.some((f) => f.startsWith("modes/clash/")), "the copy claims it too").toBe(true);
    tree.jar();
    const H = await tree.harness();
    const s = await H.Splice.create(head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }), KEYS);
    try {
      await expect(s.start()).rejects.toThrow();
      const lines = readFileSync(s.log, "utf8").split("\n");
      const finding = (...words: string[]) => lines.some((l) => words.every((w) => l.includes(w)));
      expect(finding("clash", "/api/template/said"), "two modes claiming one path").toBe(true);
      expect(finding("grab", "/api/sessions"), "a mode claiming a path of splice's").toBe(true);
    } finally {
      await s.dispose();
    }
  }, LONG);
});
