// modes-list: `GET /api/modes` (behind the management key) and `splice status` list every mode splice found: its
// name, summary, kind, server, tools as the model is offered them, resources, the callers it is never offered to, and
// whether it is on, for every head and for each head, with where that value came from (the manifest, splice.toml's
// every-head `on`, the head's own entry, or the manifest's never). An mcp mode is listed with its resources and its
// tool's _meta.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { TOOL, Tree, modesTable, requireTemplate, templateKey, templateTool } from "./lib/modes.ts";

const LONG = 3_600_000;

interface Row {
  name: string;
  summary: string;
  kind: string;
  server: string;
  prompt: boolean;
  tools: { name: string; offered_as: string; description: string; _meta?: unknown }[];
  resources: { uri: string; mimeType?: string; name?: string; _meta?: unknown }[];
  callers_off: string[];
  on: { value: boolean; from: string };
  heads: Record<string, { on: boolean; from: string }>;
}

let up: Upstream;

beforeAll(() => {
  requireTemplate();
  up = new Upstream("astra", "astra-large").start();
});

afterAll(() => up?.stop());

const heads = async () =>
  mockHead({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url, model: "astra-large" }) +
  mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: up.url, model: "astra-large" });

async function modes(s: Splice): Promise<Row[]> {
  const r = await s.api("/api/modes");
  expect(r.status).toBe(200);
  return ((await r.json()) as { modes: Row[] }).modes;
}

describe("modes-list", () => {
  test("the template is listed as its folder says, off by its manifest, on for the one head splice.toml names", async () => {
    const s = await Splice.boot((await heads()) + modesTable("template", "heads = { astra = true }"));
    try {
      const bare = await fetch(`http://127.0.0.1:${s.controlPort}/api/modes`);
      expect(bare.status, "the list is behind the management key").toBe(401);
      const all = await modes(s);
      expect(all.map((m) => m.name)).toEqual([...all.map((m) => m.name)].sort());
      const t = all.find((m) => m.name === "template");
      expect(t).toBeDefined();
      expect(t!.summary).toBe(templateKey("summary"));
      expect(t!.kind).toBe("door");
      expect(t!.server).toBe("template");
      expect(t!.prompt).toBe(true);
      expect(t!.tools).toEqual([{ name: "say_back", offered_as: TOOL, description: templateTool().description }]);
      expect(t!.resources).toEqual([]);
      expect(t!.callers_off).toEqual(["desktop"]);
      expect(t!.on).toEqual({ value: false, from: "manifest" });
      expect(t!.heads).toEqual({ astra: { on: true, from: "head" }, borealis: { on: false, from: "manifest" } });

      const r = s.cli(["status"]);
      const line = `${r.out}`.split("\n").find((l) => /\bmode\s+template\b/.test(l));
      expect(line, r.out).toBeDefined();
      expect(line).toMatch(/\bmode\s+template\s+on astra\s+door\s+mcp__template__say_back\b/);
      expect(line).toContain("not for desktop");
    } finally {
      await s.dispose();
    }
  }, 300_000);

  test("splice.toml's every-head on, and a head's own entry over it", async () => {
    const s = await Splice.boot((await heads()) + modesTable("template", "on = true", "heads = { borealis = false }"));
    try {
      const t = (await modes(s)).find((m) => m.name === "template")!;
      expect(t.on).toEqual({ value: true, from: "splice.toml" });
      expect(t.heads).toEqual({ astra: { on: true, from: "splice.toml" }, borealis: { on: false, from: "head" } });
      const line = s.cli(["status"]).out.split("\n").find((l) => /\bmode\s+template\b/.test(l));
      expect(line).toMatch(/\bon astra\s+door\b/);
    } finally {
      await s.dispose();
    }
  }, 300_000);

  test("with nothing in splice.toml the template is off on every head, and status says off", async () => {
    const s = await Splice.boot(await heads());
    try {
      const t = (await modes(s)).find((m) => m.name === "template")!;
      expect(t.heads).toEqual({ astra: { on: false, from: "manifest" }, borealis: { on: false, from: "manifest" } });
      const line = s.cli(["status"]).out.split("\n").find((l) => /\bmode\s+template\b/.test(l));
      expect(line).toMatch(/\bmode\s+template\s+off\s+door\b/);
    } finally {
      await s.dispose();
    }
  }, 300_000);

  test("an mcp mode is listed with its kind, its resources and its tool's _meta, and a head its manifest never offers it on", async () => {
    const tree = Tree.make();
    try {
      tree.copyMode("template", "visual");
      tree.edit("modes/visual/mode.toml", (t) => t.replace(/^kind\s*=.*$/m, 'kind = "mcp"').replace(/^off\s*=\s*\[\]\s*$/m, 'off = ["borealis"]'));
      tree.edit(
        "modes/visual/mode.toml",
        (t) =>
          `${t}\n[[resources]]\nuri = "ui://visual/app.html"\nname = "app"\nmimeType = "text/html;profile=mcp-app"\nfile = "app.html"\n_meta = { ui = { csp = { connectDomains = [] } } }\n`,
      );
      tree.write("modes/visual/resources/app.html", "<!doctype html><title>app</title>\n");
      tree.write("modes/visual/tools/say_back.meta.json", `${JSON.stringify({ ui: { resourceUri: "ui://visual/app.html" } })}\n`);
      expect(tree.read("modes/visual/mode.toml")).toMatch(/^off = \["borealis"\]$/m);
      tree.jar();
      const H = await tree.harness();
      const s = await H.Splice.boot(await heads());
      try {
        const r = await s.api("/api/modes");
        expect(r.status).toBe(200);
        const v = ((await r.json()) as { modes: Row[] }).modes.find((m) => m.name === "visual")!;
        expect(v.kind).toBe("mcp");
        expect(v.server).toBe("visual");
        expect(v.tools.map((x) => x.offered_as)).toEqual(["mcp__visual__say_back"]);
        expect(v.tools[0]!._meta).toEqual({ ui: { resourceUri: "ui://visual/app.html" } });
        expect(v.resources).toHaveLength(1);
        expect(v.resources[0]).toMatchObject({ uri: "ui://visual/app.html", name: "app", mimeType: "text/html;profile=mcp-app", _meta: { ui: { csp: { connectDomains: [] } } } });
        expect(v.heads.borealis).toEqual({ on: false, from: "never" });
        const line = s.cli(["status"]).out.split("\n").find((l) => /\bmode\s+visual\b/.test(l));
        expect(line).toMatch(/\bmcp\b/);
      } finally {
        await s.dispose();
      }
    } finally {
      tree.dispose();
    }
  }, LONG);
});
