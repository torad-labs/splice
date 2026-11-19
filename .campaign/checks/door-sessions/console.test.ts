// door-session-console: on the Sessions page as it is today, a door session's row names the door where its command
// goes ("door", or "door on <machine>" for another machine's door), never "Command unknown" or an empty command, and
// its "Resume it" line is the command it was started with, resumed by its id. A session splice cannot place reads as
// it always did. The rows are the ones `GET /api/sessions` answers for a door session (door-session-route).

import { afterAll, beforeAll, describe, test } from "bun:test";
import { type Browser, expect } from "@playwright/test";
import { type Daemon, WIDE, browser, daemon, open, session, world } from "../console-night-deck/lib/deck.ts";

const HERE = "dddd1111-0000-4000-8000-000000000001";
const THERE = "eeee2222-0000-4000-8000-000000000002";
const UNPLACED = "ffff3333-0000-4000-8000-000000000003";

const SESSIONS = {
  reading: true,
  sessions: [
    session(HERE, "door-here", "harbor", {
      head: "door", route: "door", served_model: "astra-large",
      door: { command: "claude", address: "http://127.0.0.1:3096/desktop", machine: null },
    }),
    session(THERE, "door-there", "harbor", {
      head: "door", route: "door",
      door: { command: "claude", address: "http://devbox.lan:3097/door", machine: "devbox.lan" },
    }),
    session(UNPLACED, "unplaced", "harbor", { head: "unknown head", route: "unknown" }),
  ],
};

let b: Browser;
let d: Daemon;
beforeAll(async () => {
  b = await browser();
  d = daemon(world({ "/api/sessions": SESSIONS }));
}, 30_000);
afterAll(async () => {
  await b?.close();
  d?.stop();
});

const cmdOf = (id: string) => `article[data-key="s:${id}"] .cmd`;

describe("door-session-console", () => {
  test("a door session's row names its door, on this machine and on another", async () => {
    for (const size of [undefined, WIDE]) {
      const o = await open(b, d, "sessions.html", size);
      try {
        await expect(o.page.locator(cmdOf(HERE))).toHaveText("door", { timeout: 10_000 });
        await expect(o.page.locator(cmdOf(THERE))).toHaveText("door on devbox.lan");
        for (const id of [HERE, THERE]) await expect(o.page.locator(`${cmdOf(id)} [aria-label="Command unknown"]`)).toHaveCount(0);
        // one splice cannot place is still drawn as it was
        await expect(o.page.locator(`${cmdOf(UNPLACED)} [aria-label="Command unknown"]`)).toHaveCount(1);
        expect(o.errors).toEqual([]);
      } finally {
        await o.close();
      }
    }
  }, 90_000);

  test("its Resume it line is the command it was started with, resumed by its id", async () => {
    for (const id of [HERE, THERE]) {
      const o = await open(b, d, `sessions.html#${id}`);
      try {
        await o.page.locator('[data-act="lines"]').first().click({ timeout: 10_000 });
        const lines = o.page.locator("dl.clines");
        await expect(lines).toContainText(`claude -r ${id}`);
        expect(o.errors).toEqual([]);
      } finally {
        await o.close();
      }
    }
  }, 90_000);
});
