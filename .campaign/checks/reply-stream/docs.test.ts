// live-docs: the live route is documented for anyone who runs splice, beside the other control routes: what it
// answers, every event and its fields, the snapshot on joining, the ping, the key it needs, and what is never sent.
// The repository is public, so the page names no person, app or machine.

import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dir, "..", "..", "..");
const PAGE = join(ROOT, "docs", "live-reply.md");

describe("live-docs", () => {
  test("docs/live-reply.md describes the route, its events and their fields", () => {
    expect(existsSync(PAGE)).toBe(true);
    const page = readFileSync(PAGE, "utf8");
    for (const s of ["GET /api/sessions/{id}/live", "text/event-stream", "management key", "401", ": ping", "15 s", "/api/sessions"]) {
      expect(page).toContain(s);
    }
    for (const event of ["hello", "turn", "text", "thinking", "tool", "end"]) expect(page).toContain(`\`${event}\``);
    for (const field of ["session", "live", "message", "agent", "model", "head", "compact", "at", "index", "delta", "id", "name", "stop_reason", "error"]) {
      expect(page).toContain(`\`${field}\``);
    }
    expect(page).toContain("x-claude-code-agent-id");
    expect(page.toLowerCase()).toContain("signature");
  });

  test("the page names no person, app or machine", () => {
    const page = readFileSync(PAGE, "utf8");
    for (const word of [/marcos/i, /\beli\b/i, /telegram/i, /torad/i, /\bpanel\b/i, /vast\.ai/i, /macbook/i, /mac mini/i, /\/home\//]) {
      expect(page).not.toMatch(word);
    }
  });

  test("the README's control plane section links the page", () => {
    const readme = readFileSync(join(ROOT, "README.md"), "utf8");
    expect(readme).toContain("docs/live-reply.md");
  });
});
