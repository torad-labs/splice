// cp-ready: a splice command run while the daemon is up but slow to answer its first /health runs on that daemon.
// Today the CLI's cold-start check (DaemonLaunch.ensureDaemon) takes one missed /health probe (400 ms) for no daemon,
// goes the spawn path, finds the control port bound, and refuses with "control port N is still bound (a daemon is
// still shutting down); retry in a moment" and "the daemon isn't running and couldn't be started": the person's
// command does nothing, and the reason it gives is false. Seen Nov 18, 2026 on runs of the custom-provider tiers and
// parity checks, whose first `splice claude door` comes right after boot; not on every run, so the second test makes
// the slow first answer happen every time by holding the daemon still (SIGSTOP) while the CLI looks for it.

import { describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync, readdirSync } from "node:fs";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";

setDefaultTimeout(300_000);

const said = (r: { out: string; err: string }) => `${r.out}\n${r.err}`;

/** The pids of the daemon process of [s]: a java whose command line runs `daemon` in that home's state. */
function daemonPids(s: Splice): number[] {
  return readdirSync("/proc")
    .filter((d) => /^\d+$/.test(d) && Number(d) !== process.pid)
    .map(Number)
    .filter((pid) => {
      try {
        const env = readFileSync(`/proc/${pid}/environ`, "latin1").split("\0");
        const argv = readFileSync(`/proc/${pid}/cmdline`, "latin1").split("\0");
        return env.includes(`SPLICE_STATE_DIR=${s.state}`) && argv.includes("daemon");
      } catch {
        return false;
      }
    });
}

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

  test("a daemon that is up but answers /health late is waited for, and the command runs on it", async () => {
    const model = new Upstream("acme", "acme-model").start();
    const s = await Splice.boot(mockHead({ key: "acme", command: "claude-acme", port: await freePort(), url: model.url, model: "acme-model" }));
    try {
      const pids = daemonPids(s);
      expect(pids.length, "the harness daemon is running").toBeGreaterThan(0);
      for (const pid of pids) process.kill(pid, "SIGSTOP");
      let out = "";
      let code: number | null = null;
      try {
        const cli = s.spawnCli(["claude", "--json"]);
        cli.stdout?.on("data", (b) => (out += b));
        cli.stderr?.on("data", (b) => (out += b));
        const exited = new Promise<number | null>((r) => cli.on("exit", (c) => r(c)));
        // The daemon stays still for 10 s: long past the CLI's start and its one /health probe, and inside the
        // cold-start budget (62 s) the CLI already gives a daemon it started itself.
        code = await Promise.race([exited, Bun.sleep(10_000).then(() => undefined as unknown as number | null)]);
        for (const pid of pids) process.kill(pid, "SIGCONT");
        if (code === undefined) code = await exited;
      } finally {
        for (const pid of pids) {
          try {
            process.kill(pid, "SIGCONT");
          } catch {}
        }
      }
      expect(out).not.toContain("still shutting down");
      expect(out).not.toContain("couldn't be started");
      expect(code, out).toBe(0);
      expect(JSON.parse(out.slice(out.indexOf("{")))).toBeTruthy();
      // It ran on the daemon that was there: no second daemon was started beside it.
      expect(daemonPids(s).sort()).toEqual(pids.sort());
    } finally {
      await s.dispose();
      model.stop();
    }
  });
});
