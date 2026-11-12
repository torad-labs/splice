// What the MCP servers topic on Settings must still tell him per server, through the real page against a stub daemon:
// for a server sharing would serve that runs copies, what one copy would hold beside what its copies hold now, so the
// saving reads per server as well as in total; and for a server that is not shared and runs no copy, whether it is a
// local server nothing runs right now or one that lives at a web address with no process on this machine. That one is a
// mark on its row, an unlit socket or a globe, and the section's line says each mark's words once.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const MiB = 1_048_576;
const MCP = {
  hosting: true,
  servers: {
    // run one per session because sharing is off: three copies now, one would do
    "ast-grep": { eligible: false, why: "per_session_choice", reason: "sharing is off" },
    // not shared, and nothing runs it right now
    postgres: { eligible: false, why: "variable", value: "${DATABASE_URL}", reason: "variable" },
    // not shared, and it lives at a web address: nothing of it runs here
    posthog: { eligible: false, why: "other_config", value: "https://mcp.posthog.com/sse", reason: "other config" },
  },
  sources: { servers: [] },
};
const MEMORY = {
  claude_processes: 3,
  servers: [
    { name: "ast-grep", label: "ast-grep", class: "per_session", why: "per_session_choice", web: false, copies: 3, bytes_now: 300 * MiB, bytes_if_shared: 100 * MiB, held_by: [] },
    { name: "postgres", label: "postgres", class: "per_session", why: "variable", web: false, copies: 0, bytes_now: 0, bytes_if_shared: 0, held_by: [] },
    { name: "posthog", label: "posthog", class: "per_session", why: "other_config", web: true, copies: 0, bytes_now: 0, bytes_if_shared: 0, held_by: [] },
  ],
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/mcp") return Response.json(MCP);
    if (path === "/api/mcp/memory") return Response.json(MEMORY);
    if (path === "/api/topology") return Response.json({ topology: { daemon: { mcp_share: false } } });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "claude-splice", label: "claude-splice" }] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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
async function openMcp(width = 1440) {
  const page = await browser.newPage({ viewport: { width, height: 1400 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html#mcp`);
  // the first frame draws the lists' ghost rows with the real rows' classes, so the wait is for a row that is not a ghost
  await page.locator(".srvs:not([aria-hidden]) .srv").first().waitFor({ timeout: 15_000 });
  return page;
}
const section = (page: Awaited<ReturnType<typeof openMcp>>, kicker: string) =>
  page.locator("section.psec", { has: page.locator(`.sechead > .kicker:text-is("${kicker}")`) });
const flat = (s: string) => s.replace(/\s+/g, " ").trim();

test("What sharing saves shows, for a server running copies, what one copy would hold beside what its copies hold now", async () => {
  const page = await openMcp();
  try {
    const saves = section(page, "What sharing saves");
    const line = saves.locator(".msrv", { hasText: "ast-grep" });
    await line.waitFor({ timeout: 15_000 });
    expect(await line.count()).toBe(1);
    const text = flat(await line.innerText());
    expect(text).toContain("300 MB");
    expect(text).toContain("3 copies");
    expect(text).toContain("100 MB");
  } finally {
    await page.close();
  }
}, 30_000);

test("a server that is not shared and runs no copy wears a mark for Not running, or for At a web address when it lives at one, named once on the section", async () => {
  const page = await openMcp();
  try {
    const out = section(page, "Not shared");
    const row = (name: string) => out.locator(".srv", { has: page.locator(`.nm:text-is("${name}")`) });
    // an unlit socket for the local server nothing runs now, a globe for the one that lives at a web address
    expect(await row("postgres").locator(".idle.off").count()).toBe(1);
    expect(await row("postgres").locator(".idle.web").count()).toBe(0);
    expect(await row("posthog").locator(".idle.web").count()).toBe(1);
    expect(await row("posthog").locator(".idle.off").count()).toBe(0);
    // each mark has its words for a reader who hears it, and the section's line says them once; the rows do not
    expect(await row("postgres").locator(".idle").getAttribute("aria-label")).toBe("Not running");
    expect(await row("posthog").locator(".idle").getAttribute("aria-label")).toBe("At a web address");
    const key = flat(await out.locator(".idlekey").innerText());
    expect(key).toContain("Not running");
    expect(key).toContain("At a web address");
    expect(flat(await row("postgres").innerText())).not.toContain("Not running");
    expect(flat(await row("posthog").innerText())).not.toContain("At a web address");
  } finally {
    await page.close();
  }
}, 30_000);
