// A session's fallback model, set from its Login section (Nov 13, 2026: every Fable login on one machine ran out for the
// week and every session asking for Fable waited until Sunday). The line under its logins says what it does while no
// login has its model, Waits, and opens the command's other models; Use sends that model to PUT
// /api/sessions/{id}/fallback and the line then says it runs on it, and Remove sends DELETE and it waits again. Through
// the real Sessions page against a stub daemon in the routes' own shapes.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const session = {
  session_id: "s-fable", name: "builder", head: "claude", route: "head", availability: "live", status: "idle", cwd: "/work/atlas",
  model: "claude-fable-5-2", account: "first", last: { role: "assistant", tool: null, text: "Waiting for Fable.", ts: now - 60_000 },
  updated_at: new Date(now - 60_000).toISOString(),
};
const models = [
  { id: "claude-fable-5-2", label: "Claude Fable 5.2" },
  { id: "claude-opus-5-5", label: "Claude Opus 5.5" },
  { id: "claude-sonnet-5-5", label: "Claude Sonnet 5.5" },
];
const login = (label: string, name: string) => ({
  label, display_name: name, heads: ["claude"], plan: "max", credential_present: true,
  five_hour_used_percent: 12, seven_day_used_percent: 100, seven_day_reset_epoch_seconds: Math.floor(now / 1000) + 3 * 86_400,
});
const fallbacks: Record<string, string> = {};
const sent: { method: string; body: unknown }[] = [];
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, sessions: [session] });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "claude", label: "claude" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "claude", provider: "anthropic", models }] });
    if (path === "/api/accounts") return Response.json({ accounts: [login("first", "First"), login("second", "Second")] });
    if (path === "/api/sessions/fallbacks") return Response.json({ sessions: fallbacks });
    if (path === "/api/sessions/s-fable/fallback" && req.method === "PUT") {
      const body = (await req.json()) as { model: string };
      sent.push({ method: "PUT", body });
      fallbacks["s-fable"] = body.model;
      return Response.json({ session: "s-fable", model: body.model, said: "set" });
    }
    if (path === "/api/sessions/s-fable/fallback" && req.method === "DELETE") {
      sent.push({ method: "DELETE", body: null });
      delete fallbacks["s-fable"];
      return Response.json({ session: "s-fable", model: null, said: "cleared" });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("a session waits with no fallback, runs on the model Use chose, and waits again after Remove", async () => {
  const page = await browser.newPage({ viewport: { width: 2548, height: 1240 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#s-fable`);
    const line = page.locator(".pane .fbline");
    await expect(line.locator(".fbhead")).toContainText("When no login has Claude Fable 5.2", { timeout: 15_000 });
    await expect(line.locator(".fbhead")).toContainText("Waits");

    await line.locator(".fbhead").click();
    const opus = line.locator(".fbrow", { hasText: "Claude Opus 5.5" });
    await expect(line.locator(".fbrow")).toHaveCount(2); // the command's other models, never the one it asks for
    await opus.getByRole("button", { name: "Use" }).click();
    await expect.poll(() => sent.length, { timeout: 10_000 }).toBe(1);
    bunExpect(sent[0]).toEqual({ method: "PUT", body: { model: "claude-opus-5-5" } });
    await expect(line.locator(".fbhead")).toContainText("Runs on Claude Opus 5.5", { timeout: 10_000 });

    await opus.getByRole("button", { name: "Remove" }).click();
    await expect.poll(() => sent.length, { timeout: 10_000 }).toBe(2);
    bunExpect(sent[1]).toEqual({ method: "DELETE", body: null });
    await expect(line.locator(".fbhead")).toContainText("Waits", { timeout: 10_000 });
  } finally {
    await page.close();
  }
}, 60_000);
