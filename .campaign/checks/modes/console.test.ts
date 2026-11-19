// modes-console: splice's Settings page, with the parts it has now, has a Modes topic that shows every mode `GET
// /api/modes` lists: its name, its summary, what kind it is, the tools it adds as the model sees them, the callers it is
// never offered to, and an On/Off switch per command. A press saves at once through `PUT /api/modes/<name>` and the
// switch shows what the daemon answered; a press back to the mode's own default clears the command's entry; a command
// the mode is never offered on has no switch to press; a refused save says why and leaves the switch where it was.

import { afterAll, beforeAll, describe, expect as bunExpect, test } from "bun:test";
import { type Browser, expect } from "@playwright/test";
import { NARROW, WIDE, browser, daemon, open, world, type Daemon } from "../console-night-deck/lib/deck.ts";

type HeadRow = { on: boolean; from: string };
type Row = { name: string; on: { value: boolean; from: string }; heads: Record<string, HeadRow>; [k: string]: unknown };

const TEMPLATE: Row = {
  name: "template",
  summary: "Says back the text it is given: a mode to copy.",
  kind: "door",
  server: "template",
  prompt: true,
  tools: [{ name: "say_back", offered_as: "mcp__template__say_back", description: "Say the text back." }],
  resources: [],
  callers_off: ["desktop"],
  on: { value: false, from: "manifest" },
  heads: { "claude-splice": { on: false, from: "manifest" }, acme: { on: false, from: "manifest" } },
};

const VISUAL: Row = {
  name: "visual",
  summary: "Draws a picture beside the answer.",
  kind: "mcp",
  server: "visual",
  prompt: false,
  tools: [{ name: "show_visual", offered_as: "mcp__visual__show_visual", description: "Show a picture.", _meta: { ui: { resourceUri: "ui://visual/app.html" } } }],
  resources: [{ uri: "ui://visual/app.html", name: "app", mimeType: "text/html;profile=mcp-app" }],
  callers_off: [],
  on: { value: true, from: "manifest" },
  heads: { "claude-splice": { on: true, from: "manifest" }, acme: { on: false, from: "never" } },
};

let rows: Record<string, Row>;
const fresh = () => ({ template: structuredClone(TEMPLATE), visual: structuredClone(VISUAL) });

/** What the daemon does with a PUT: a head set to true or false is the head's own, null is the mode's default again.
 *  A save for acme on the template is refused. */
function saved(req: Request, url: URL, body: unknown): Response | unknown {
  if (req.method !== "PUT") return new Response("", { status: 405 });
  const name = decodeURIComponent(url.pathname.split("/").pop() ?? "");
  const row = rows[name];
  if (!row) return Response.json({ error: `no mode is named ${name}` }, { status: 404 });
  const heads = ((body ?? {}) as { heads?: Record<string, boolean | null> }).heads ?? {};
  if (name === "template" && "acme" in heads) return Response.json({ error: "splice.toml could not be written: boom" }, { status: 400 });
  for (const [h, v] of Object.entries(heads)) {
    row.heads[h] = v === null ? { on: row.on.value, from: row.on.from === "splice.toml" ? "splice.toml" : "manifest" } : { on: v, from: "head" };
  }
  return row;
}

let b: Browser;
let d: Daemon;

beforeAll(async () => {
  rows = fresh();
  b = await browser();
  d = daemon(
    world({
      "/api/modes": () => ({ modes: [rows.template, rows.visual] }),
      "/api/modes/template": saved,
      "/api/modes/visual": saved,
    }),
  );
}, 30_000);

afterAll(async () => {
  await b?.close();
  d?.stop();
});

const card = (o: { page: import("@playwright/test").Page }, name: string) => o.page.locator(`[data-mode="${name}"]`);
const sw = (o: { page: import("@playwright/test").Page }, name: string, h: string, v: boolean) =>
  card(o, name).locator(`[data-mode-head="${h}"] button[data-v="${v}"]`);
const puts = () => d.seen.filter((x) => x.method === "PUT" && x.path.startsWith("/api/modes/"));

describe("modes-console", () => {
  test("Settings has a Modes topic, and it opens from the rail", async () => {
    const o = await open(b, d, "settings.html");
    try {
      const topic = o.page.locator('[data-open="modes"]');
      await expect(topic).toHaveCount(1, { timeout: 10_000 });
      await expect(topic).toContainText("Modes");
      await topic.click();
      await expect(card(o, "template")).toBeVisible({ timeout: 10_000 });
      bunExpect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }, 90_000);

  test("each mode shows its name, summary, kind, tools and who it leaves out, at both widths and in both themes", async () => {
    for (const size of [NARROW, WIDE]) {
      for (const theme of ["night", "day"] as const) {
        const o = await open(b, d, "settings.html#modes", size, theme);
        try {
          const t = card(o, "template");
          await expect(t).toBeVisible({ timeout: 10_000 });
          for (const words of ["template", TEMPLATE.summary as string, "mcp__template__say_back", "Answered inside splice", "Not offered to Claude Desktop's own sessions"]) {
            await expect(t).toContainText(words);
          }
          const v = card(o, "visual");
          for (const words of ["visual", VISUAL.summary as string, "mcp__visual__show_visual", "MCP server"]) await expect(v).toContainText(words);
          await expect(v).not.toContainText("Not offered to Claude Desktop's own sessions");
          await expect(sw(o, "template", "claude-splice", true)).toHaveAttribute("aria-pressed", "false");
          await expect(sw(o, "template", "claude-splice", false)).toHaveAttribute("aria-pressed", "true");
          await expect(sw(o, "visual", "claude-splice", true)).toHaveAttribute("aria-pressed", "true");
          bunExpect(o.errors).toEqual([]);
        } finally {
          await o.close();
        }
      }
    }
  }, 180_000);

  test("a press saves at once and the switch shows what the daemon answered; back to the default clears the entry", async () => {
    rows = fresh();
    const o = await open(b, d, "settings.html#modes");
    try {
      const before = puts().length;
      await sw(o, "template", "claude-splice", true).click();
      await expect(sw(o, "template", "claude-splice", true)).toHaveAttribute("aria-pressed", "true", { timeout: 10_000 });
      bunExpect(puts().slice(before).map((x) => [x.path, x.body])).toEqual([["/api/modes/template", { heads: { "claude-splice": true } }]]);
      await sw(o, "template", "claude-splice", false).click();
      await expect(sw(o, "template", "claude-splice", false)).toHaveAttribute("aria-pressed", "true", { timeout: 10_000 });
      bunExpect(puts().slice(before + 1).map((x) => [x.path, x.body])).toEqual([["/api/modes/template", { heads: { "claude-splice": null } }]]);
      bunExpect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }, 90_000);

  test("a command the mode is never offered on has no switch to press, and says why", async () => {
    rows = fresh();
    const o = await open(b, d, "settings.html#modes");
    try {
      await expect(card(o, "visual")).toBeVisible({ timeout: 10_000 });
      for (const v of [true, false]) await expect(sw(o, "visual", "acme", v)).toBeDisabled();
      await expect(card(o, "visual").locator('[data-mode-head="acme"]').locator("..")).toContainText(/never/i);
    } finally {
      await o.close();
    }
  }, 90_000);

  test("a refused save says why, and the switch stays where it was", async () => {
    rows = fresh();
    const o = await open(b, d, "settings.html#modes");
    try {
      await sw(o, "template", "acme", true).click();
      await expect(card(o, "template")).toContainText("boom", { timeout: 10_000 });
      await expect(sw(o, "template", "acme", true)).toHaveAttribute("aria-pressed", "false");
      await expect(sw(o, "template", "acme", false)).toHaveAttribute("aria-pressed", "true");
    } finally {
      await o.close();
    }
  }, 90_000);
});
