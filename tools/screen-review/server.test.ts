// NEW: local review persistence, discovery, and HTTP boundary controls over synthetic files only.
import { describe, expect, test } from "bun:test";
import { mkdirSync, readFileSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { discover, parseMutation, ReviewStore } from "./store.ts";
import { startReview } from "./server.ts";
import { fixture } from "./fixtures/fixture.ts";

describe("screen review", () => {
  test("verdict and normalized note position survive a new store, with private file permissions", () => {
    const f = fixture();
    const at = "2026-10-07T12:00:00-05:00";
    const store = new ReviewStore(f.feedbackFile, () => at);
    store.apply({ action: "verdict", screen: "01-overview", verdict: "approved" });
    store.apply({ action: "pin", screen: "01-overview", id: "synthetic-pin", x: .25, y: .72, width: 1440, text: "Synthetic note" });
    const saved = new ReviewStore(f.feedbackFile).read().screens["01-overview"]!;
    expect(saved.verdict).toBe("approved");
    expect(saved.pins).toEqual([{ screen: "01-overview", id: "synthetic-pin", x: .25, y: .72, width: 1440, text: "Synthetic note", at }]);
    expect(statSync(f.feedbackFile).mode & 0o777).toBe(0o600);
  });
  test("editing a pin is idempotent and deletion survives reload", () => {
    const f = fixture();
    const store = new ReviewStore(f.feedbackFile);
    const pin = { action: "pin" as const, screen: "01-overview", id: "synthetic-pin", x: .2, y: .8, width: 1440, text: "First" };
    store.apply(pin);
    store.apply({ ...pin, text: "Revised" });
    expect(store.read().screens["01-overview"]!.pins).toHaveLength(1);
    expect(store.read().screens["01-overview"]!.pins[0]!.text).toBe("Revised");
    store.apply({ screen: "01-overview", action: "delete-pin", id: pin.id });
    expect(new ReviewStore(f.feedbackFile).read().screens["01-overview"]!.pins).toEqual([]);
  });
  test("adding a numbered folder discovers a screen without code changes", () => {
    const f = fixture();
    expect(discover(f.screensRoot).map((s) => s.id)).toEqual(["01-overview", "02-activity"]);
    const added = join(f.screensRoot, "03-added");
    mkdirSync(added);
    writeFileSync(join(added, "index.html"), "<html><body>Synthetic added screen</body></html>");
    writeFileSync(join(added, "justification.json"), JSON.stringify({ title: "Added", why: "Why", value: "Value", dashboard: "Fit" }));
    expect(discover(f.screensRoot).map((s) => s.id)).toEqual(["01-overview", "02-activity", "03-added"]);
  });
  test("incomplete folders do not hide ready screens and become ready after their files land", () => {
    const f = fixture();
    const added = join(f.screensRoot, "03-building");
    mkdirSync(added);
    expect(discover(f.screensRoot).map((screen) => screen.ready)).toEqual([true, true, false]);
    writeFileSync(join(added, "index.html"), "<html><body>Synthetic</body></html>");
    writeFileSync(join(added, "justification.json"), "{partial");
    expect(discover(f.screensRoot).map((screen) => screen.ready)).toEqual([true, true, false]);
    writeFileSync(join(added, "justification.json"), JSON.stringify({ title: "Added", why: "Why", value: "Value", dashboard: "Fit" }));
    expect(discover(f.screensRoot).map((screen) => screen.ready)).toEqual([true, true, true]);
  });
  test("invalid positions and actions are rejected rather than saved", () => {
    for (const x of [-.01, 1.01, NaN, Infinity]) {
      expect(() => parseMutation({ screen: "01-overview", action: "pin", id: "p", x, y: .5, width: 1440, text: "Synthetic" })).toThrow();
    }
    for (const width of [0, -1, 1.5, 100_001, NaN, Infinity, undefined]) {
      expect(() => parseMutation({ screen: "01-overview", action: "pin", id: "p", x: .5, y: .5, width, text: "Synthetic" })).toThrow();
    }
    expect(() => parseMutation({ screen: "../private", action: "verdict", verdict: "approved" })).toThrow();
    expect(() => parseMutation({ screen: "01-overview", action: "verdict", verdict: "invented" })).toThrow();
  });
  test("corrupt feedback is never replaced by an empty document", () => {
    const f = fixture();
    writeFileSync(f.feedbackFile, "{broken");
    expect(() => new ReviewStore(f.feedbackFile)).toThrow();
    expect(readFileSync(f.feedbackFile, "utf8")).toBe("{broken");
  });
  test("symlinked proposal files cannot expose unrelated local data", () => {
    const f = fixture();
    const folder = join(f.screensRoot, "03-linked");
    mkdirSync(folder);
    symlinkSync(f.feedbackFile, join(folder, "index.html"));
    writeFileSync(join(folder, "justification.json"), "{}");
    expect(discover(f.screensRoot).map((screen) => [screen.id, screen.ready])).toEqual([
      ["01-overview", true], ["02-activity", true], ["03-linked", false],
    ]);
  });
  test("the server binds loopback, rejects foreign writes, and retains approved feedback", async () => {
    const f = fixture();
    const server = await startReview({ ...f, port: 0 });
    try {
      expect(server.hostname).toBe("127.0.0.1");
      const origin = server.url.origin;
      const mutation = { screen: "01-overview", action: "verdict", verdict: "approved" };
      const post = (source: string) => fetch(new URL("/api/feedback", origin), {
        method: "POST", headers: { Origin: source, "Content-Type": "application/json" }, body: JSON.stringify(mutation),
      });
      expect((await post("https://unrelated.invalid")).status).toBe(403);
      expect((await post(origin)).status).toBe(200);
      const response = await fetch(new URL("/api/feedback", origin));
      expect((await response.json()).screens["01-overview"].verdict).toBe("approved");
      const mock = await fetch(new URL("/screens/01-overview/index.html", origin));
      expect(await mock.text()).toContain("screen-review:size");
      expect(mock.headers.get("Content-Security-Policy")).toContain("default-src 'none'");
      expect((await fetch(new URL("/screens/01-overview/justification.json", origin))).status).toBe(404);
      mkdirSync(join(f.screensRoot, "03-building"));
      const incomplete = await fetch(new URL("/api/feedback", origin), {
        method: "POST", headers: { Origin: origin, "Content-Type": "application/json" },
        body: JSON.stringify({ ...mutation, screen: "03-building" }),
      });
      expect(incomplete.status).toBe(404);
      expect((await fetch(new URL("/screens/03-building/index.html", origin))).status).toBe(404);
    } finally { server.stop(true); }
  });
});
