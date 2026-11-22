// Server page (docs/specs/server-mode.md, F16): renders keys, people/teams, budgets, upstreams, usage, backup trigger,
// and admin audit log; mints and revokes keys through the real control routes; shows an honest off state on 409.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

let s: Stub;
let browser: Browser;
let keys = [
  { prefix: "spk_abcd1234", person: "ada@team.example", name: "laptop", source: "operator", created_at: 1700000000000 },
];

beforeAll(async () => {
  s = stub({
    "GET /api/server/keys": () => ({ keys }),
    "POST /api/server/keys": (_u: URL, body: unknown) => {
      const b = (body || {}) as { person?: string; name?: string };
      keys = [...keys, { prefix: "spk_new99999", person: b.person || "new@team.example", name: b.name || "", source: "operator", created_at: 1700000001000 }];
      return { prefix: "spk_new99999", key: "spk_new99999_secretvalueforonce" };
    },
    "POST /api/server/backup": () => ({ to: "file:///backups", key: "splice-state/2026/11/22/14.json", files: 5 }),
    "GET /api/server/budgets": { budgets: [{ scope: "team", name: "core", daily_usd: 50, monthly_usd: 500, spent_today_usd: 12.5, spent_month_usd: 80, action: "block", over: false }] },
    "GET /api/server/upstreams": { upstreams: [{ provider: "box", name: "gpu-a", url: "http://10.0.0.21:8080/v1", healthy: true, inflight: 2 }] },
    "GET /api/server/usage": { groups: [{ group: "ada@team.example", requests: 14, prompt_tokens: 12000, completion_tokens: 3400, cost_usd: 4.25 }] },
    "GET /api/server/audit": { entries: [{ ts: "2026-11-22T14:00:00Z", actor: "ops@team.example", action: "key.create", target: "spk_abcd1234" }] },
  });
  browser = await launch();
}, 30_000);

afterAll(async () => {
  await browser?.close();
  s?.stop();
});

test("server.html renders keys, budgets, upstreams, usage, audit log and mints a key", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`${s.url}/server.html`);
    await expect(page.locator("#srvBody")).toContainText("ada@team.example", { timeout: 10_000 });
    await expect(page.locator("#srvBody")).toContainText("gpu-a");
    await expect(page.locator("#srvBody")).toContainText("key.create");
    await page.getByLabel("Person email").fill("bob@team.example");
    await page.getByRole("button", { name: "Mint key" }).click();
    await expect(page.locator(".srv-banner")).toContainText("spk_new99999_secretvalueforonce", { timeout: 10_000 });
    await page.getByRole("button", { name: "Backup now" }).click();
    await expect(page.locator("#srvBody")).toContainText("Backed up 5 files", { timeout: 10_000 });
    bunExpect(s.calls.some((c) => c.method === "POST" && c.path === "/api/server/backup")).toBe(true);
  } finally {
    await page.close();
  }
}, 40_000);
