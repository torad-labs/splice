// The launch shim's rehearsal: green against the SHIPPED shim, and red against three fixtures.
//
// checks/release/launcher-test.sh had no mutants — its arms ARE the assertions, and the shim is
// the only positive control there can be. What needed proving on the port is that the harness can
// still fail: a shim with no markers, a shim that does nothing, and a shim that behaves exactly
// like the real one except for the unit-first law (the arm that costs the most to get wrong —
// three stray daemons squatted :3096 on 2026-09-21).
//
// The mock daemon is a real loopback server, NOT a fake `curl`: the shim speaks node:http as of
// PR 6, so a curl stub is never called and every arm after the first would be dead while the
// harness still said OK (astra's review of fc67eb5c — checks/release/launcher-test.sh is red at
// that commit for exactly that reason).
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { ARM_NAMES, SELECTORS, launcherRehearsal } from "../src/lib/launcher.ts";
import { shimMarkers, shimPath } from "../src/lib/shim.ts";

const { repoRoot } = layout();
const shipped = shimPath(repoRoot);
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

function fixture(body: string): string {
  const dir = mkdtempSync(join(tmpdir(), "release-shim-"));
  workspaces.push(dir);
  const path = join(dir, "splice-launch");
  writeFileSync(path, `#!${process.execPath}\n${body}`);
  chmodSync(path, 0o755);
  return path;
}

const MARKERS = 'const SPLICE_GATEWAY_VERSION = "0.0.0";\nconst SPLICE_SHIM_VERSION = "shim-0";\n';

describe("the launch shim", () => {
  test("the shipped shim is where the build stages it from, and carries both markers", () => {
    expect(existsSync(shipped), `${shipped} must exist — it is what :app:stageRelease stages`).toBe(true);
    expect(shimMarkers(shipped)).not.toBeInstanceOf(Error);
  });

  // The inventory, so an arm cannot be dropped in a refactor without this number moving: the six
  // behavioural arms of launcher-test.sh (port, port precedence, stale replace, env-key injection,
  // JW-01, JW-04), UF-01 and UF-02, one UF-03 arm per selector the shim's unitDefaults() names, and
  // UF-04 and UF-05.
  test("the rehearsal is the script's arms, all of them", () => {
    expect(ARM_NAMES.length).toBe(6 + 2 + SELECTORS.length + 2);
    expect(ARM_NAMES.length).toBe(19);
    expect(ARM_NAMES.filter((name) => name.startsWith("UF-03")).length).toBe(SELECTORS.length);
    for (const marker of ["JW-01", "JW-04", "UF-01", "UF-02", "UF-04", "UF-05"]) {
      expect(ARM_NAMES.some((name) => name.includes(marker)), `${marker} must still be an arm`).toBe(true);
    }
  });

  test("every arm passes against the shipped shim", async () => {
    expect(await launcherRehearsal(shipped)).toBeNull();
  }, 180_000);

  test("a shim with no readable markers is a refusal, not a pair of blanks", async () => {
    expect(await launcherRehearsal(fixture('const VERSION = "0.0.0";\n'))).toContain("could not read version markers");
  });

  test("a shim that does nothing fails the first arm", async () => {
    expect(await launcherRehearsal(fixture(MARKERS))).toContain("expected the TOML control port");
  });

  // The unit-first law, mutated: the same shim, with the mock supervisor told there is no unit.
  // Every earlier arm sets a selector and takes the raw-spawn path, so the rehearsal reaches UF-01
  // and reds there — the arm is not carried by the arms before it.
  test("a shim that never starts the unit reds on UF-01", async () => {
    // The wrapper carries the SHIPPED markers, not the placeholder ones: the mock daemon's version
    // is derived from the shim under test, so a fixture that lies about its version is a stale
    // daemon to the shim it delegates to, and the rehearsal reds on the handshake instead.
    const real = shimMarkers(shipped) as Exclude<ReturnType<typeof shimMarkers>, Error>;
    const wrapper = fixture(
      `const SPLICE_GATEWAY_VERSION = "${real.gateway}";\nconst SPLICE_SHIM_VERSION = "${real.shim}";\n` +
        'import { spawnSync } from "node:child_process";\n' +
        `const result = spawnSync(${JSON.stringify(shipped)}, process.argv.slice(2), {\n` +
        '  stdio: "inherit",\n' +
        '  env: { ...process.env, LAUNCHER_UNIT_PRESENT: "0" },\n' +
        "});\n" +
        "process.exit(result.status === null ? 1 : result.status);\n",
    );
    expect(await launcherRehearsal(wrapper)).toContain("UF-01");
  }, 180_000);

  test("no fake curl survives: the shim speaks node:http, so PATH mocks are java and systemctl only", () => {
    const source = readFileSync(join(repoRoot, "tools/release/src/lib/launcher.ts"), "utf8");
    const stubbed = [...source.matchAll(/writeStub\(\s*join\(bin, "([^"]+)"\)/g)].map((match) => match[1]);
    expect(stubbed).toEqual(["java", "systemctl"]);
    expect(readFileSync(shipped, "utf8")).toContain('require("node:http")');
  });
});
