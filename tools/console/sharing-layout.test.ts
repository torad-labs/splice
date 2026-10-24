// The Sharing page at Marcos's width in dark mode (3394, his Oct 7 screenshot), measured in the rendered page: each
// section's heading is seen whole above its cards, the machines' cards line up with the card above them, and the field
// that adds a machine by name is a field's width, not the column's. Through the real page against a stub daemon.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const share = {
  machine: "torad-server",
  sharing: {
    enabled: true, port: 3097, listening: true, following: true, fingerprint: "sha256:858f565e09c68945b88f66f458ca1b7fb4ad5b584016c6feb06aa8838aff9b2c",
    commands: [
      { key: "codex", command: "claudex", model: "gpt-6.1-sol", offered: true, serving: true },
      { key: "claude", command: "claude", model: "claude-fable-5-1", offered: false, serving: false, why: "it forwards the caller's own Claude login, so there is nothing to share" },
    ],
  },
  joined: [{ name: "Marcoss-Mini", address: "192.168.1.243", joined_at: "2026-10-23T09:07:52Z", last_seen: "2026-10-23T11:58:02Z", turns_today: 3 }],
  remotes: [{ name: "studio", url: "https://studio.lan:3097", fingerprint: "sha256:aa", token: true, reachable: true, since: "2026-10-23T11:00:00Z",
    commands: [{ key: "studio-grok", command: "claude-grok", model: "grok-5", down: false }] }],
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/share") return Response.json(share);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "sharing.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});

let browser: Browser;
let page: Page;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
  page = await browser.newPage({ viewport: { width: 3394, height: 1889 }, colorScheme: "dark" });
  await page.goto(`http://127.0.0.1:${server.port}/sharing.html`);
  await page.locator(".card.m").first().waitFor({ timeout: 20_000 });
}, 40_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("each section's heading is seen whole, above the cards it heads", async () => {
  const covered = await page.evaluate(() => [...document.querySelectorAll("h3.sect")].map((h) => {
    const r = h.getBoundingClientRect();
    const next = h.nextElementSibling?.getBoundingClientRect();
    const top = document.elementFromPoint(r.left + 4, r.top + r.height / 2);
    return { text: h.textContent, seen: top === h || h.contains(top), above: !next || r.bottom <= next.top };
  }));
  expect(covered.length).toBeGreaterThan(0);
  for (const h of covered) expect(h).toEqual({ text: h.text, seen: true, above: true });
});

test("the machines that joined line up with the card above them", async () => {
  const [shared, joined] = await page.evaluate(() =>
    [".card.shared", ".here .card.m"].map((s) => Math.round(document.querySelector(s)!.getBoundingClientRect().left)));
  expect(joined).toBe(shared);
});

test("the field that adds a machine is a field's width, not the column's", async () => {
  const width = await page.evaluate(() => document.querySelector(".addhost")!.getBoundingClientRect().width);
  expect(width).toBeLessThanOrEqual(800);
});

test("nothing runs past the right edge", async () => {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(over).toBeLessThanOrEqual(0);
});
