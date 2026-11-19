// cp-overlay: Claude Code takes one `--settings` per launch, so a `--settings` the person or a wrapper passes to a
// `claude` on the door (env, otelHeadersHelper, hooks of its own), inline JSON or a file, is merged into the one overlay
// splice hands Claude Code, beside splice's picker and /login hook, instead of replacing it or being dropped. What keeps
// the session on the door, and the door's picker, are splice's: a wrapper's settings cannot override them. The person's
// other arguments pass through in order.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { picker, session, toDoor } from "../door/lib/claude.ts";
import { recipeWith, settingsOf } from "./lib/team.ts";

setDefaultTimeout(120_000);

let model: Upstream;
let s: Splice;
let dir: string;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), "cp-overlay-"));
  model = new Upstream("acme", "acme-model").start();
  s = await Splice.boot(mockHead({ key: "acme", command: "claude-acme-head", port: await freePort(), url: model.url, model: "acme-model" }));
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  model?.stop();
  rmSync(dir, { recursive: true, force: true });
});

describe("cp-overlay", () => {
  test("a --settings passed inline and as a file is merged into the one overlay, beside splice's picker and /login hook", async () => {
    const file = join(dir, "wrapper.json");
    writeFileSync(file, JSON.stringify({ hooks: { SessionStart: [{ hooks: [{ type: "command", command: "acme-ai session-start" }] }] } }));
    const inline = JSON.stringify({
      env: { OTEL_EXPORTER_OTLP_ENDPOINT: "https://otel.acme.example" },
      otelHeadersHelper: "acme-ai otel-headers",
    });
    const plain = picker(await session(s)) ?? [];
    expect(plain.length).toBeGreaterThan(0);
    const r = await recipeWith(s, "claude", ["--settings", inline, "--verbose", `--settings=${file}`, "-p", "hello"]);
    const all = settingsOf(r.argv);
    expect(all.length).toBe(1);
    const one = all[0]!;
    expect((one.env as Record<string, string>).OTEL_EXPORTER_OTLP_ENDPOINT).toBe("https://otel.acme.example");
    expect(one.otelHeadersHelper).toBe("acme-ai otel-headers");
    const hooks = one.hooks as Record<string, { hooks: { command: string }[] }[]>;
    expect(JSON.stringify(hooks.SessionStart ?? [])).toContain("acme-ai session-start");
    expect((hooks.UserPromptSubmit ?? []).length).toBeGreaterThan(0);
    const rows = (one.modelPicker as { options?: { model: string }[] } | undefined)?.options ?? [];
    expect(rows.map((o) => o.model)).toEqual(plain.map((p) => p.model));
    // the person's other arguments, in their order
    const rest = r.argv.filter((a) => ["--verbose", "-p", "hello"].includes(a));
    expect(rest).toEqual(["--verbose", "-p", "hello"]);
  });

  test("a hook of the wrapper's on the same event as splice's runs beside it, not in its place", async () => {
    const inline = JSON.stringify({ hooks: { UserPromptSubmit: [{ hooks: [{ type: "command", command: "acme-ai prompt-audit" }] }] } });
    const r = await recipeWith(s, "claude", ["--settings", inline]);
    const one = settingsOf(r.argv)[0]!;
    const submit = JSON.stringify((one.hooks as Record<string, unknown>).UserPromptSubmit ?? []);
    expect(submit).toContain("acme-ai prompt-audit");
    const own = settingsOf((await recipeWith(s, "claude", [])).argv)[0]!;
    for (const h of (own.hooks as Record<string, { hooks: { command: string }[] }[]>).UserPromptSubmit ?? []) {
      for (const c of h.hooks) expect(submit).toContain(c.command);
    }
  });

  test("a wrapper's settings cannot take the session off the door or replace the door's picker", async () => {
    const plain = picker(await session(s)) ?? [];
    const inline = JSON.stringify({
      env: { CLAUDE_CODE_USE_MANTLE: "0", ANTHROPIC_BEDROCK_MANTLE_BASE_URL: "https://elsewhere.example" },
      modelPicker: { replaceBuiltInOptions: true, options: [{ model: "elsewhere-model" }] },
    });
    const r = await recipeWith(s, "claude", ["--settings", inline]);
    const one = settingsOf(r.argv)[0]!;
    const env = { ...r.env, ...((one.env as Record<string, string> | undefined) ?? {}) };
    expect(env.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL).toBe((await session(s)).url);
    const rows = (one.modelPicker as { options?: { model: string }[] }).options ?? [];
    expect(rows.map((o) => o.model)).toEqual(plain.map((p) => p.model));
  });

  test("a --settings that is neither JSON nor a readable file refuses the launch, naming it", async () => {
    const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: s.home, launch: String(process.pid) });
    form.append("arg", "--settings");
    form.append("arg", join(dir, "no-such-settings.json"));
    const r = await s.api("/launch/claude", { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
    const text = await r.text();
    expect(r.status).not.toBe(200);
    expect(text).toContain("no-such-settings.json");
  });
});
