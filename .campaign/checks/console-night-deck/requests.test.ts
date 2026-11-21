// nd-requests: what the old Requests page showed lives on Activity: a request's glass opens the full request, the
// document splice kept of what it sent and what came back, in Requests' own words (Sent, Returned, Body, Response),
// in an extra-wide glass. The Requests page and its own files are gone.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, lens, NOW, open, world, type Daemon } from "./lib/deck.ts";

const sent = { messages: [{ role: "user", content: "Name three tide tables." }], payload: "sent-document-marker" };
const returned = { output: "returned-document-marker" };
function documentPage(_req: Request, url: URL) {
  let value: unknown = url.searchParams.get("section") === "answer" ? returned : sent;
  for (const part of (url.searchParams.get("path") || "").split("/").slice(1)) value = (value as Record<string, unknown>)[part.replace(/~1/g, "/").replace(/~0/g, "~")];
  if (typeof value === "string") return { type: "string", value, next: null };
  const entries = Object.entries(value as object);
  return {
    type: Array.isArray(value) ? "array" : "object",
    children: entries.map(([key, child]) => typeof child === "object"
      ? { key, type: Array.isArray(child) ? "array" : "object" }
      : { key, type: typeof child, value: child, complete: true }),
    next: null,
  };
}
const outline = { kind: "turn", ts: NOW, client: { headers: {}, body_deferred: true }, answer: { status: 200, body_deferred: true } };

let b: Browser;
let d: Daemon;
beforeAll(async () => {
  b = await browser();
  d = daemon(world({
    "/api/heads/acme/trace/document": documentPage,
    "/api/heads/acme/trace": (_req: Request, url: URL) => ({ records: [url.searchParams.get("view") === "outline" ? outline
      : { ...outline, client: { body: JSON.stringify(sent) }, answer: { status: 200, body: JSON.stringify(returned) } }] }),
    "/api/heads/acme/capture": { enabled: true, retention_days: 7 },
  }));
}, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("a request's glass opens the full request, and its response reads what came back", async () => {
  const o = await open(b, d, "activity.html#request=r-ok");
  try {
    const l = await lens(o.page);
    await l.getByText("Full request", { exact: true }).click();
    const doc = o.page.locator(".lens.xwide:not([hidden])");
    await expect(doc).toBeVisible({ timeout: 10_000 });
    await expect(doc.getByText("Sent", { exact: true }).first()).toBeVisible();
    await doc.getByText("Returned", { exact: true }).first().click();
    await doc.getByText("Response", { exact: true }).first().click();
    await expect(doc).toContainText("returned-document-marker", { timeout: 10_000 });
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("the Requests page and its own files are gone", () => {
  for (const f of ["requests.html", "requests.js", "requests.css"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
