// restart-at-once, heads-hand-over: each command on GET /api/heads says whether a turn already answering on it
// finishes on the running daemon at a hand-over (`hands_over`): true on an OpenAI-compatible command, false on a local
// runtime that pins conversations to slots (quirks.slot_affinity), which stops whole at a restart. And a restart can
// hold new compactions on the commands it waits for alone: POST /api/daemon/compactions/hold?ms=N&heads=<keys> holds
// those and no other, answers the heads it held, and refuses a key splice does not have.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead, until } from "./lib/harness.ts";
import { Upstream } from "./lib/upstream.ts";
import { ask, launch, type Launched } from "../speed/lib/session.ts";

setDefaultTimeout(120_000);

let open: Upstream;
let pinnedUp: Upstream;
let s: Splice;
let mock: Launched;
let pinned: Launched;

beforeAll(async () => {
  open = new Upstream("open", "open-model").start();
  pinnedUp = new Upstream("pinned", "pinned-model").start();
  s = await Splice.boot(
    mockHead({ key: "open", command: "claude-open", port: await freePort(), url: open.url, model: "open-model" }) +
      mockHead({
        key: "pinned",
        command: "claude-pinned",
        port: await freePort(),
        url: pinnedUp.url,
        model: "pinned-model",
        provider: "quirks = { slot_affinity = true }",
      }),
  );
  mock = await launch(s, "claude-open");
  pinned = await launch(s, "claude-pinned");
}, 900_000);

afterAll(async () => {
  await s?.dispose();
  open?.stop();
  pinnedUp?.stop();
}, 60_000);

const seenWith = (up: Upstream, marker: string) => up.completions().filter((c) => JSON.stringify(c.body).includes(marker)).length;

describe("heads-hand-over", () => {
  test("GET /api/heads says which commands hand over a turn that is answering", async () => {
    const r = await s.api("/api/heads");
    expect(r.status).toBe(200);
    const heads = ((await r.json()) as { heads: { key: string; hands_over?: unknown }[] }).heads;
    expect(heads.find((h) => h.key === "open")?.hands_over).toBe(true);
    expect(heads.find((h) => h.key === "pinned")?.hands_over).toBe(false);
  });

  test("a hold that names commands holds their new compactions and no other command's", async () => {
    const r = await s.api("/api/daemon/compactions/hold?ms=8000&heads=pinned", { method: "POST" });
    expect(r.status).toBe(200);
    expect(((await r.json()) as { heads?: string[] }).heads).toEqual(["pinned"]);
    const onPinned = `HOLD-PINNED-${crypto.randomUUID().slice(0, 8)}`;
    const onOpen = `HOLD-OPEN-${crypto.randomUUID().slice(0, 8)}`;
    const heldOne = ask(pinned, crypto.randomUUID(), onPinned, { compaction: true });
    const freeOne = ask(mock, crypto.randomUUID(), onOpen, { compaction: true });
    // the command that is not held starts its compaction at once
    await until("the open command's compaction reaching its upstream", () => seenWith(open, onOpen) === 1, 5_000);
    expect(seenWith(pinnedUp, onPinned)).toBe(0);
    expect((await freeOne).status).toBe(200);
    // the held one waits at admission until the hold lapses, then runs whole
    const held = await heldOne;
    expect(held.status).toBe(200);
    expect(held.body).toContain('"message_stop"');
    expect(seenWith(pinnedUp, onPinned)).toBe(1);
  }, 60_000);

  test("a hold naming a command splice does not have is refused and holds nothing", async () => {
    const r = await s.api("/api/daemon/compactions/hold?ms=8000&heads=nobody", { method: "POST" });
    expect(r.status).toBe(400);
    expect(((await r.json()) as { error?: string }).error).toContain("nobody");
    const marker = `HOLD-NONE-${crypto.randomUUID().slice(0, 8)}`;
    const t = ask(pinned, crypto.randomUUID(), marker, { compaction: true });
    await until("the compaction reaching its upstream at once", () => seenWith(pinnedUp, marker) === 1, 5_000);
    expect((await t).status).toBe(200);
  }, 60_000);
});
