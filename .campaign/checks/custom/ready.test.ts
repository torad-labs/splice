// cp-ready: a splice command run in the first seconds after the daemon says it is up runs on that daemon. Today the
// CLI's /health probe can miss a daemon that is busy with its first requests; the CLI then takes it for one that is
// shutting down, refuses with "control port N is still bound (a daemon is still shutting down)" and "the daemon isn't
// running and couldn't be started", and the person's command does nothing. Seen Nov 18, 2026 on every run of the
// custom-provider tiers and parity checks, whose first `splice claude door` comes right after boot.

import { describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";

setDefaultTimeout(300_000);

const said = (r: { out: string; err: string }) => `${r.out}\n${r.err}`;

describe("cp-ready", () => {
  test("splice claude door, run as soon as the daemon is up, runs on it, boot after boot", async () => {
    const model = new Upstream("acme", "acme-model").start();
    try {
      for (let i = 0; i < 3; i++) {
        const s = await Splice.boot(mockHead({ key: "acme", command: "claude-acme", port: await freePort(), url: model.url, model: "acme-model" }));
        try {
          const door = s.cli(["claude", "door"]);
          expect(said(door)).not.toContain("still shutting down");
          expect(door.code, said(door)).toBe(0);
          const json = s.cli(["claude", "--json"]);
          expect(json.code, said(json)).toBe(0);
        } finally {
          await s.dispose();
        }
      }
    } finally {
      model.stop();
    }
  });
});
