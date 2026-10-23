// The Sharing page says, on each machine's card, how many of that machine's sessions are listed here and how many of
// this machine's it was shown, so the sessions crossing each way can be read without opening the Sessions page. Since
// the round-1 review (the President's decision, Oct 23), each card also carries the machine's Sessions switch, off until
// Marcos turns it on: a machine whose switch is off says nothing crosses and counts nothing, and pressing the switch
// asks the daemon to turn it on that machine's own side. Through the real page against a stub daemon (LAN share,
// milestone 6).
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const share = {
  machine: "torad-server",
  sharing: { enabled: true, port: 3097, listening: true, following: true, fingerprint: "sha256:aa", commands: [] },
  joined: [
    { name: "Marcoss-Mini", address: "192.168.1.243", joined_at: "2026-10-23T09:07:52Z", sessions: true, sessions_here: 2, sessions_there: 1 },
    { name: "laptop", address: "192.168.1.50", joined_at: "2026-10-23T09:07:52Z", sessions: false, sessions_here: 0 },
  ],
  remotes: [{ name: "studio", url: "https://studio.lan:3097", fingerprint: "sha256:bb", token: true, reachable: true,
    since: "2026-10-23T11:00:00Z", commands: [], sessions: true, sessions_here: 1 }],
};
/** Each switch the page asked the daemon to turn: the route and the body it sent. */
const turned: { path: string; body: unknown }[] = [];
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/share") return Response.json(share);
    if (req.method === "PUT" && path.endsWith("/sessions")) {
      turned.push({ path, body: await req.json() });
      return Response.json({ ok: true });
    }
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
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`http://127.0.0.1:${server.port}/sharing.html`);
  await page.locator(".card.m").first().waitFor({ timeout: 20_000 });
}, 40_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

/** What the card named [name] says about sessions, or null when it says nothing. */
async function sessionsOf(name: string): Promise<string | null> {
  return page.evaluate((n) => {
    const card = [...document.querySelectorAll(".card.m")].find((c) => c.querySelector(".name")?.textContent === n);
    if (!card) throw new Error(`no card for ${n}`);
    return card.querySelector(".sessions")?.textContent ?? null;
  }, name);
}

test("a machine that joined says how many of its sessions are listed here and how many of this machine's it lists", async () => {
  expect(await sessionsOf("Marcoss-Mini")).toBe("2 of its listed here · 1 of this machine's listed there");
});

test("a machine this one uses that has not looked yet says this machine's are not listed there yet", async () => {
  expect(await sessionsOf("studio")).toBe("1 of its listed here · this machine's not listed there yet");
});

/** The card named [name]'s Sessions switch: the label of the half pressed, or null when it has none. */
async function switchOf(name: string): Promise<string | null> {
  return page.evaluate((n) => {
    const group = document.querySelector(`[role="group"][aria-label="Sessions with ${n}"]`);
    return group?.querySelector('[aria-pressed="true"]')?.textContent ?? null;
  }, name);
}

/** What the card named [name] says, whole. */
async function cardText(name: string): Promise<string> {
  return page.evaluate((n) => {
    const card = [...document.querySelectorAll(".card.m")].find((c) => c.querySelector(".name")?.textContent === n);
    return card?.textContent ?? "";
  }, name);
}

test("each machine's card has its Sessions switch, pressed as the daemon says", async () => {
  expect(await switchOf("Marcoss-Mini")).toBe("On");
  expect(await switchOf("laptop")).toBe("Off");
  expect(await switchOf("studio")).toBe("On");
});

test("a machine whose Sessions switch is off says nothing crosses, and counts nothing", async () => {
  expect(await sessionsOf("laptop")).toBeNull();
  expect(await cardText("laptop")).toContain("don't see or message each other");
  expect(await cardText("Marcoss-Mini")).not.toContain("don't see or message each other");
});

test("pressing a switch asks the daemon to turn it, on that machine's own side", async () => {
  await page.locator('[aria-label="Sessions with laptop"] button', { hasText: "On" }).click();
  await page.locator('[aria-label="Sessions with studio"] button', { hasText: "Off" }).click();
  const until = Date.now() + 10_000;
  while (turned.length < 2 && Date.now() < until) await Bun.sleep(20);
  expect(turned).toEqual([
    { path: "/api/share/machines/laptop/sessions", body: { on: true } },
    { path: "/api/remotes/studio/sessions", body: { on: false } },
  ]);
});
