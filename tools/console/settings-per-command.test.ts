// A command's own value on Settings' rows, through the real page against a stub daemon that answers the per-command
// PATCH /api/config?head=<command> the way HeadOverrideWrite.kt does. What he does: give one command its own value,
// see what the change did, take it back to the shared value, and bring in a value splice reads only at start. A command
// set here keeps its line until the page reloads; one that already holds its own value reads as a chip, a door to that
// command's setting on Models, where it edits (gap 457, Oct 13).
import { afterAll, beforeAll, beforeEach, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HEADS = ["alpha", "beta"];
let perHead: Record<string, Record<string, unknown>> = {};
let refuse: string | null = null; // the reason the daemon refuses the next write with, if any
let restartBound: string[] = []; // the keys the running daemon reads only at start
let restarts = 0;
const writes: { head: string | null; body: Record<string, unknown> }[] = [];

const server = Bun.serve({
  port: Number(process.env.SETTINGS_PER_COMMAND_PORT ?? 0),
  async fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/api/config" && req.method === "PATCH") {
      const head = url.searchParams.get("head");
      const body = (await req.json()) as Record<string, unknown>;
      writes.push({ head, body });
      const [key, value] = Object.entries(body)[0] ?? ["", null];
      if (refuse) return Response.json({ error: refuse, head, rejected: { [key]: refuse }, applied: {}, persisted: null }, { status: 400 });
      if (head) {
        const mine = { ...(perHead[head] ?? {}) };
        if (value === null) delete mine[key]; else mine[key] = value;
        perHead = { ...perHead, [head]: mine };
      }
      const restart = restartBound.includes(key);
      return Response.json({
        head, applied: { [key]: value === null ? null : String(value) }, rejected: {},
        restart_required: restart ? [key] : [], live: restart ? [] : [key], persisted: "splice.toml",
      });
    }
    if (path === "/api/config") return Response.json({ layers: { perHead }, knobs: { maxInflight: { value: 12, scope: "default" }, maxQueued: { value: 512, scope: "default" } } });
    if (path === "/api/daemon/restart") {
      if (req.method === "POST") { restarts++; return Response.json({ status: "draining" }, { status: 202 }); }
      return Response.json({ status: "idle" });
    }
    if (path === "/api/heads") return Response.json({ heads: HEADS.map((key) => ({ key, label: key })) });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});
beforeEach(() => {
  perHead = {};
  refuse = null;
  restartBound = [];
  restarts = 0;
  writes.length = 0;
});

/** Many agents at once, open, with the daemon holding [held] as each command's own values. */
async function busy(held: Record<string, Record<string, unknown>> = {}): Promise<Page> {
  perHead = held;
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Many agents at once").first().click();
  await expect(page.locator('[data-row="maxInflight"]')).toBeVisible({ timeout: 15_000 });
  return page;
}
/** The control cell beside a setting's name. */
const ctl = (page: Page, key: string) => page.locator(`[data-row="${key}"] + .ctl`);

test("Set for one command gives a command its own value, saved for that command alone, and Undo takes it back", async () => {
  const page = await busy();
  try {
    const row = ctl(page, "maxInflight");
    // the slot for one command's own value is drawn on the row, and its words are said once for the whole view
    const said = page.locator(".legend .lg.one");
    await row.getByRole("button", { name: "Set for one command" }).click();
    await expect(row.locator(".menu [role=menuitem]")).toHaveText(["alpha", "beta"]);
    await row.locator(".menu [role=menuitem]", { hasText: "beta" }).click();
    const line = row.locator(".over", { hasText: "beta" });
    await line.locator(".menu [role=menuitem]", { hasText: "100" }).click(); // its menu opened with the line
    await expect(line).toContainText("Applied");
    expect(writes).toEqual([{ head: "beta", body: { maxInflight: 100 } }]);
    await expect(line.locator(".pickbtn")).toHaveText("100");
    // the shared value stands for every other command; the row says no "Every other command" and the view says once what the slot is
    await expect(row.locator(":scope > .line .pickbtn")).toHaveText("12");
    await expect(said).toHaveCount(1);
    await expect(said).toHaveText("A value for one command");
    await expect(row.getByRole("button", { name: "Set for one command" })).toBeVisible(); // alpha can still have its own

    // the pick shows the value it replaced, struck, with Undo, which sends that value back: none of its own (gap 458)
    await expect(line.locator(".was")).toHaveText("12");
    await line.getByRole("button", { name: "Undo" }).click();
    await expect(row.locator(".over")).toHaveCount(0);
    expect(writes[1]).toEqual({ head: "beta", body: { maxInflight: null } });
    // back to one value for every command: no command is named on the row
    expect(await row.locator(".owners .chip.own").count()).toBe(0);
    await expect(row.locator(":scope > .line .pickbtn")).toHaveText("12");
    await expect(said).toHaveCount(1);
  } finally {
    await page.close();
  }
}, 40_000);

/** A command's line on a setting's row, picked from Set for one command, with its value's menu open. */
async function setFor(page: Page, key: string, cmd: string) {
  const row = ctl(page, key);
  await row.getByRole("button", { name: "Set for one command" }).click();
  await row.locator(".menu [role=menuitem]", { hasText: cmd }).click();
  return row.locator(".over", { hasText: cmd });
}

test("a value refused by splice reads Not saved, and the reason goes to the console only", async () => {
  const page = await busy();
  const warned: string[] = [];
  page.on("console", (m) => { if (m.type() === "warning") warned.push(m.text()); });
  try {
    refuse = "must be at least 256";
    const line = await setFor(page, "maxQueued", "alpha");
    await line.locator(".menu [role=menuitem]", { hasText: "128" }).click(); // the menu holds 128
    await expect(line).toContainText("Not saved");
    await expect(line).not.toContainText("must be at least 256");
    expect(warned.some((w) => w.includes("must be at least 256"))).toBe(true);
  } finally {
    await page.close();
  }
}, 40_000);

test("a value splice reads at start reads Saved with Restart splice beside it, then Applied once splice is back", async () => {
  const page = await busy();
  try {
    restartBound = ["maxInflight"];
    const line = await setFor(page, "maxInflight", "beta");
    await line.locator(".menu [role=menuitem]", { hasText: "16" }).click();
    await expect(line.locator(".said")).toHaveText("Saved");
    await line.getByRole("button", { name: "Restart splice" }).click();
    await expect(line.locator(".said")).toHaveText("Applied", { timeout: 15_000 });
    expect(restarts).toBe(1);
    await expect(line.getByRole("button", { name: "Restart splice" })).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 40_000);

test("a command that holds its own value reads as a chip with that value, its door to the setting on Models", async () => {
  const page = await busy({ alpha: { maxQueued: 256 } });
  try {
    const row = ctl(page, "maxQueued");
    const chip = row.locator(".owners .chip.own");
    await expect(chip).toHaveText("alpha 256");
    await expect(chip).toHaveAttribute("href", "models.html?cmd=alpha&setting=maxQueued");
    await expect(row.locator(".over")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 40_000);
