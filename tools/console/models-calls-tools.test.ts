// A local command's tier says so when the runtime on this computer reports that its model calls tools, and says nothing
// about tools when the runtime reports nothing: the two are different facts, and only the runtime's own "no" is the
// warning "No tool calls" (spec 9i: /api/models/local lists each model with what its runtime says of tool calls).
// Through the real Models page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const model = (id: string, label: string, slot: string) => ({ id, label, resolved: true, slot, context_window: 32768 });
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "lm", label: "claude-lm", running: true, healthy: true, authKind: "none" }] },
  "/api/topology": { topology: {
    heads: { lm: { provider: "lmstudio" } },
    providers: { lmstudio: { base_url: "http://127.0.0.1:1234/v1", local: true } },
  } },
  "/api/models": { heads: [{ head: "lm", pinned_model: null, models: [
    model("tool-user", "Tool User", "opus"),
    model("quiet-one", "Quiet One", "sonnet"),
    model("no-tools", "No Tools", "haiku"),
  ] }] },
  // the runtime says the first model calls tools, says nothing about the second, and says the third does not
  "/api/models/local": { providers: [{ provider: "lmstudio", runtime: "LM Studio", version: "0.3.30", url: "http://127.0.0.1:1234",
    models: [{ id: "tool-user", tools: true }, { id: "quiet-one" }, { id: "no-tools", tools: false }] }] },
  "/api/doctor": { checks: [] },
  "/api/sessions": { sessions: [] },
  "/api/projects": { projects: [], folders: [] },
  "/api/claude-head": { mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" },
  "/api/add/profiles": { profiles: [] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

test("a tier whose runtime says its model calls tools says so, and one the runtime says nothing about says nothing", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/models.html?cmd=lm`);
  const pane = page.locator(".pane");
  await expect(pane.getByText("Tool User").first()).toBeVisible({ timeout: 15_000 });
  // one mark on the page for the one model its runtime says calls tools, and the runtime's own no as the warning
  await expect(pane.getByRole("img", { name: "Calls tools" })).toHaveCount(1);
  await expect(pane.getByText("No tool calls")).toHaveCount(1);
  // each on its own tier: the model that calls tools, the one the runtime says nothing about, the one that does not
  const tier = (name: string) => pane.getByRole("group", { name, exact: true });
  await expect(tier("Opus").getByRole("img", { name: "Calls tools" })).toHaveCount(1);
  await expect(tier("Sonnet").getByRole("img", { name: "Calls tools" })).toHaveCount(0);
  await expect(tier("Sonnet")).not.toContainText("No tool calls");
  await expect(tier("Haiku")).toContainText("No tool calls");
  await page.close();
}, 30_000);
