// Two things the Models page's Every model view and its view switch owe the one who uses them. (1) The figure over the board
// ("N models on M commands") is the door to every card's whole list: pressed, each card that folds past its first models opens
// by one step; pressed again, each folds back; while a search is typed every match is already shown, so it presses nothing.
// (2) A view that is switched to while its read is still out draws its ghost fading in, and when the read lands part way
// through the 240 ms the incoming view goes on fading from where it was, never standing at full opacity in one frame.
// (3) A command whose roster cannot be shown says so in its card: its models could not be read (Try again), it has not reported
// any yet (Check again), or every login behind it is at its limit, when its rows stay, dimmed, under the time it comes back.
// Through the real page in Chromium against a stub daemon whose Projects read is held until the test lets it go.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = process.env.CONSOLE_DIR || join(layout().repoRoot, "app/src/main/resources/console");
// alpha holds thirty models and folds past its first four, beta holds three and shows them all
// gamma's runtime does not answer, delta has no roster in the daemon's list, and every login behind alpha is at its limit
const HEADS = [{ key: "alpha", n: 30 }, { key: "beta", n: 3 }, { key: "gamma", n: 0 }, { key: "delta", n: 0 }];
let modelReads = 0;
let release: () => void = () => {};
let held: Promise<void> = Promise.resolve();
const hold = () => { held = new Promise<void>((r) => { release = r; }); };
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/__hold") { hold(); return Response.json({}); }
    if (path === "/__release") { release(); return Response.json({}); }
    if (path === "/api/projects") {
      await held;
      return Response.json({ projects: [{ id: "/work/site", root: "/work/site", name: "site", label: "site", source: "folder", folder: "/work" }], folders: [] });
    }
    if (path === "/api/heads") return Response.json({ heads: HEADS.map((h) => ({ key: h.key, label: `claude-${h.key}`, running: true, healthy: true, dialect: "anthropic-messages", ...(h.key === "gamma" ? { runtimeNotAnswering: true } : {}) })) });
    if (path === "/api/topology") {
      return Response.json({ topology: {
        heads: Object.fromEntries(HEADS.map((h) => [h.key, { provider: `${h.key}-co` }])),
        providers: Object.fromEntries(HEADS.map((h) => [`${h.key}-co`, { base_url: `https://api.${h.key}.example` }])),
      } });
    }
    if (path === "/api/accounts") {
      return Response.json({ accounts: [{ heads: ["alpha"], five_hour_used_percent: 100, five_hour_reset_epoch_seconds: Math.floor(Date.now() / 1000) + 7200 }] });
    }
    if (path === "/api/models") {
      modelReads++;
      return Response.json({ heads: HEADS.filter((h) => h.n).map((h) => ({ head: h.key, pinned_model: null,
        models: Array.from({ length: h.n }, (_, i) => ({ id: `${h.key}-m${String(i + 1).padStart(2, "0")}`, label: `${h.key} Model ${String(i + 1).padStart(2, "0")}`, resolved: true, slot: null, context_window: 200_000 })) })) });
    }
    if (path === "/api/doctor") return Response.json({ checks: [] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/models/local") return Response.json({ providers: [] });
    if (path === "/api/claude-head") return Response.json({ mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" });
    if (path === "/api/add/profiles") return Response.json({ profiles: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});
const url = (q = "") => `http://127.0.0.1:${server.port}/models.html${q}`;
const rows = (p: Page, cmd: string) => p.locator(`.em-card[data-cmd="${cmd}"] .em-item`);

test("the figure over the board opens each folding card one step and folds it back", async () => {
  const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await p.goto(url("?view=every"));
    const door = p.locator("button.em-all");
    await expect(door).toBeVisible({ timeout: 20_000 });
    await expect(door).toHaveAttribute("aria-expanded", "false");
    await expect(rows(p, "alpha")).toHaveCount(4);
    await expect(rows(p, "beta")).toHaveCount(3);
    await door.click();
    // one step past the four shown: twenty more, not the thirty at once; the card with three models is untouched
    await expect(rows(p, "alpha")).toHaveCount(24, { timeout: 5_000 });
    await expect(rows(p, "beta")).toHaveCount(3);
    await expect(door).toHaveAttribute("aria-expanded", "true");
    await expect(p.locator('.em-card[data-cmd="alpha"] .em-cnt')).toHaveAttribute("aria-expanded", "true");
    await door.click();
    await expect(rows(p, "alpha")).toHaveCount(4, { timeout: 5_000 });
    await expect(door).toHaveAttribute("aria-expanded", "false");
  } finally {
    await p.close();
  }
}, 60_000);

test("while a search is typed the figure presses nothing, every match being shown already", async () => {
  const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await p.goto(url("?view=every"));
    await expect(p.locator("button.em-all")).toBeVisible({ timeout: 20_000 });
    await p.getByPlaceholder("Search models").fill("alpha-m0");
    await expect(rows(p, "alpha")).toHaveCount(9, { timeout: 5_000 });
    await expect(p.locator("button.em-all")).toHaveCount(0);
    const door = p.locator(".em-all");
    await expect(door).toHaveClass(/open/);
    await door.click();
    await p.waitForTimeout(400);
    await expect(rows(p, "alpha")).toHaveCount(9);
  } finally {
    await p.close();
  }
}, 60_000);

test("a read that lands part way through a view switch leaves the incoming view fading, not at full opacity", async () => {
  const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    // the Projects read is held from the page's first round, so Projects is first switched to with its read still out
    hold();
    await p.goto(url("?view=every"));
    await expect(p.locator(".em-card").first()).toBeVisible({ timeout: 20_000 });
    const seen = await p.evaluate(() => new Promise<{ at: number; cls: string; op: number; anims: number }[]>((done) => {
      const m = document.getElementById("models")!;
      const out: { at: number; cls: string; op: number; anims: number }[] = [];
      const incoming = () => [...m.children].find((k) => !k.matches(".views, .mt-leaving")) as HTMLElement | undefined;
      let t0 = 0;
      // every redraw of the tab is looked at the moment it is made: what is standing as the incoming view, and how it is fading
      new MutationObserver(() => {
        const k = incoming();
        if (k) out.push({ at: Math.round(performance.now() - t0), cls: k.className, op: +(+getComputedStyle(k).opacity).toFixed(2), anims: k.getAnimations().length });
      }).observe(m, { childList: true });
      t0 = performance.now();
      (m.querySelector('[data-act="view"][data-v="projects"]') as HTMLElement).click();
      fetch("/__release");
      setTimeout(() => done(out), 800);
    }));
    // the switch runs 240 ms: the redraw that the landing read makes, inside it, goes on fading from where the view stood
    expect(seen.length).toBeGreaterThan(1);
    for (const r of seen.filter((x) => x.at < 200)) expect(r.op).toBeLessThan(0.99);
  } finally {
    release();
    await p.close();
  }
}, 60_000);

test("a command whose models could not be read, or never reported, says so and asks again from its card", async () => {
  const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await p.goto(url("?view=every"));
    const failed = p.locator('.em-card[data-cmd="gamma"]'), unread = p.locator('.em-card[data-cmd="delta"]');
    await expect(failed.locator(".em-note")).toContainText("Could not read this command's models", { timeout: 20_000 });
    await expect(unread.locator(".em-note")).toContainText("Not reported yet");
    await expect(failed.locator(".em-rows")).toHaveCount(0);
    await expect(unread.locator(".em-rows")).toHaveCount(0);
    const before = modelReads;
    await failed.getByRole("button", { name: "Try again" }).click();
    await expect.poll(() => modelReads, { timeout: 10_000 }).toBeGreaterThan(before);
    await expect(unread.getByRole("button", { name: "Check again" })).toBeVisible();
  } finally {
    await p.close();
  }
}, 60_000);

test("a command whose every login is at its limit keeps its rows, dimmed, under the time it comes back", async () => {
  const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await p.goto(url("?view=every"));
    const held = p.locator('.em-card[data-cmd="alpha"]');
    await expect(held.locator(".em-lim")).toContainText("Limit reached until", { timeout: 20_000 });
    await expect(held).toHaveClass(/em-held/);
    await expect(rows(p, "alpha")).toHaveCount(4);
    expect(await held.locator(".em-rows").evaluate((e) => +getComputedStyle(e).opacity)).toBeLessThan(0.6);
    await expect(p.locator('.em-card[data-cmd="beta"] .em-lim')).toHaveCount(0);
  } finally {
    await p.close();
  }
}, 60_000);
