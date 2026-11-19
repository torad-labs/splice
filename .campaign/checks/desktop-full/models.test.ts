// df-models: the door answers Claude Desktop's model discovery, `GET /desktop/v1/models`, with the rows of the
// terminal's picker in the shape Desktop reads, so every model splice serves is in Desktop's picker and passes its
// filter. A row's id is splice's own, reading as Claude, and a turn naming it runs the row's model; rows are found
// here by their labels, which are unique. Each row carries a tier, never a family default, and 1M only where the model takes 1M. The rows are read
// from the lineup at each request, so a head added to splice is listed at Desktop's next discovery.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, json, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, doorTurn, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { type ModelRow, addClaudeAccount, allModels, claudeHeadToml, desktopKeeps, modelsPage } from "./lib/full.ts";

setDefaultTimeout(120_000);

let anthropic: Provider;
let astra: Upstream;
let orion: Upstream;
let vega: Upstream;
let s: Splice;
let d: Door;
let heads: string;

beforeAll(async () => {
  anthropic = new Provider("anthropic").start();
  astra = new Upstream("astra", "gpt-astra-large").start();
  orion = new Upstream("orion", "gpt-orion").start();
  vega = new Upstream("vega", "grok-vega").start();
  heads =
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "gpt-astra-large", small: "gpt-astra-small" }) +
    mockHead({ key: "orion", command: "claude-orion", port: await freePort(), url: orion.url, model: "gpt-orion", window: 1_048_576 }) +
    claudeHeadToml({ port: await freePort(), url: anthropic.url });
  s = await Splice.create(heads);
  addClaudeAccount(s, "claude", "work", "sk-ant-oat01-models-check");
  await s.start();
  // The saved default is a model that is not first in the picker, so moving it first is seen.
  d = turnOn(s, "--model", DEFAULT);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  anthropic?.stop();
  astra?.stop();
  orion?.stop();
  vega?.stop();
});

/** The rows the terminal's picker lists, as `splice claude --json` says them. */
function terminalPicker(): { model: string; label: string; command: string }[] {
  const r = s.cli(["claude", "--json"]);
  if (r.code !== 0) throw new Error(`splice claude --json exited ${r.code}:\n${r.out}\n${r.err}`);
  return json<{ models: { model: string; label: string; command: string }[] }>(r).models;
}

const DEFAULT = "claude-orion--gpt-orion";

/** The rows by the terminal picker's selector, matched through the label each carries (labels are unique). */
const bySelector = (rows: ModelRow[]): Record<string, ModelRow> => {
  const byLabel = Object.fromEntries(rows.map((r) => [r.display_name ?? "", r]));
  return Object.fromEntries(terminalPicker().map((p) => [p.model, byLabel[p.label]]).filter(([, r]) => r !== undefined));
};

describe("df-models", () => {
  test("discovery lists the terminal picker's rows under their own labels, the saved default first and the rest in the picker's order", async () => {
    const rows = await allModels(d.url, d.key);
    const picker = terminalPicker();
    expect(picker.length).toBeGreaterThanOrEqual(5);
    expect(picker[0]?.model).not.toBe(DEFAULT);
    const first = picker.filter((p) => p.model === DEFAULT);
    expect(first.length).toBe(1);
    const order = [...first, ...picker.filter((p) => p.model !== DEFAULT)];
    expect(rows.map((r) => r.display_name)).toEqual(order.map((p) => p.label));
    expect(new Set(rows.map((r) => r.id)).size).toBe(rows.length);
  });

  test("Desktop starts a new session on the first row it keeps, so the first row is the door's saved default", async () => {
    const first = (await allModels(d.url, d.key))[0];
    expect(first?.display_name).toBe(terminalPicker().find((p) => p.model === DEFAULT)?.label);
    expect(first && desktopKeeps(first)).toBe(true);
  });

  test("a turn naming a row's id runs that row's model, and a turn naming the selector still does", async () => {
    const rows = bySelector(await allModels(d.url, d.key));
    for (const [model, up] of [["gpt-astra-large", astra], ["gpt-orion", orion]] as const) {
      const selector = terminalPicker().find((p) => p.model.endsWith(`--${model}`))?.model ?? "";
      for (const name of [rows[selector]?.id ?? "(no row)", selector]) {
        const before = up.seen.length;
        const t = await doorTurn(d.url, { key: d.key, model: name, content: "hi" });
        expect([name, t.status]).toEqual([name, 200]);
        expect([name, up.seen.slice(before).some((q) => JSON.stringify(q.body).includes(model))]).toEqual([name, true]);
      }
    }
  });

  test("each row says which command serves it", async () => {
    const rows = bySelector(await allModels(d.url, d.key));
    for (const p of terminalPicker()) expect([p.model, rows[p.model]?.description ?? ""]).toEqual([p.model, expect.stringContaining(p.command)]);
  });

  test("every row passes Desktop's filter: its id reads as Claude, and it carries a tier Desktop knows", async () => {
    for (const r of await allModels(d.url, d.key)) {
      expect([r.id, desktopKeeps(r)]).toEqual([r.id, true]);
      expect(r.id).toMatch(/^claude-splice-\d{12}$/);
      expect([r.id, ["opus", "sonnet", "haiku", "fable"].includes(String(r.anthropic_family_tier))]).toEqual([r.id, true]);
    }
  });

  test("a row's tier is the one its head names it for, its own for a Claude model, and opus for a model no tier names", async () => {
    const rows = bySelector(await allModels(d.url, d.key));
    expect(rows["claude-astra--gpt-astra-large"]?.anthropic_family_tier).toBe("opus");
    expect(rows["claude-astra--gpt-astra-small"]?.anthropic_family_tier).toBe("haiku");
    expect(rows["claude--claude-opus-5-5"]?.anthropic_family_tier).toBe("opus");
    expect(rows["claude--claude-haiku-5-5"]?.anthropic_family_tier).toBe("haiku");
    expect(rows["claude-orion--gpt-orion"]?.anthropic_family_tier).toBe("opus");
  });

  test("no row is a family default, so a tier alias stays Claude's own ID on the wire", async () => {
    for (const r of await allModels(d.url, d.key)) expect([r.id, r.is_family_default === true]).toEqual([r.id, false]);
  });

  test("1M only where the model takes 1M; max_input_tokens is the model's own window", async () => {
    const rows = bySelector(await allModels(d.url, d.key));
    expect(rows["claude-orion--gpt-orion"]?.supports_1m).toBe(true);
    expect(rows["claude-orion--gpt-orion"]?.max_input_tokens).toBe(1_048_576);
    expect(rows["claude-astra--gpt-astra-large"]?.supports_1m).toBe(false);
    expect(rows["claude-astra--gpt-astra-large"]?.max_input_tokens).toBe(131_072);
    expect(rows["claude--claude-opus-5-5"]?.supports_1m).toBe(false);
  });

  test("no two rows share a display name, so Desktop never folds two models into one row", async () => {
    const names = (await allModels(d.url, d.key)).map((r) => r.display_name);
    expect(new Set(names).size).toBe(names.length);
  });

  test("pages follow limit, has_more and last_id, and together list every row once", async () => {
    const all = (await allModels(d.url, d.key)).map((r) => r.id);
    const first = await modelsPage(d.url, { key: d.key, limit: 2 });
    expect(first.status).toBe(200);
    expect(first.data.map((r) => r.id)).toEqual(all.slice(0, 2));
    expect(first.has_more).toBe(true);
    expect(first.first_id).toBe(all[0]);
    expect(first.last_id).toBe(all[1]);
    expect((await allModels(d.url, d.key, 2)).map((r) => r.id)).toEqual(all);
  });

  test("the desktop key opens it as a Bearer or as x-api-key, never redirected", async () => {
    for (const scheme of ["bearer", "x-api-key"] as const) expect([scheme, (await modelsPage(d.url, { key: d.key, scheme })).status]).toEqual([scheme, 200]);
  });

  test("no key, a wrong key or the management key is refused 401 in Anthropic's shape, naming splice desktop", async () => {
    for (const key of [undefined, "sk-wrong", s.mgmtKey()]) {
      const p = await modelsPage(d.url, { key });
      expect(p.status).toBe(401);
      expect(p.error?.type).toBe("authentication_error");
      expect(p.error?.message ?? "").toContain("splice desktop");
    }
  });

  test("a head added to splice is listed at the next discovery, and every row it had keeps its id", async () => {
    const before = bySelector(await allModels(d.url, d.key));
    s.writeConfig(heads + mockHead({ key: "vega", command: "claude-vega", port: await freePort(), url: vega.url, model: "grok-vega" }));
    await s.restart();
    const rows = bySelector(await allModels(d.url, d.key));
    expect(rows["claude-vega--grok-vega"]?.anthropic_family_tier).toBe("opus");
    expect(rows["claude-vega--grok-vega"] && desktopKeeps(rows["claude-vega--grok-vega"])).toBe(true);
    // Desktop keeps the model id in each conversation it stores, so a row's id is the same at every launch.
    for (const [selector, row] of Object.entries(before)) expect([selector, rows[selector]?.id]).toEqual([selector, row.id]);
  }, 240_000);
});
