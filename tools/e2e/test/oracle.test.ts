// tools/e2e/test/oracle.test.ts — the oracle verb's own net. Everything here runs without the
// daemon: the corpus integrity gate, the graders, the argv contract and the provenance arms.
// The live 11-scenario replay is the ladder's oracleReplay leg (tools/gate/config/ladder.json); this file
// is its oracleSelftest leg.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import {
  DIVERGENCE_EXIT,
  HARNESS_EXIT,
  ORACLE_DIR,
  canonicalize,
  corpusDrift,
  enrolmentDrift,
  gradeUpstream,
  isSanctioned,
  jsonDiff,
  oracle,
  sanctionedFields,
  sanctionedScenarios,
} from "../src/commands/oracle.ts";

const CLI = resolve(import.meta.dir, "../index.ts");
const run = (...args: string[]) =>
  spawnSync(process.execPath, [CLI, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });

/** A scratch copy of the frozen corpus that a test may tamper with. */
function corpusCopy(): { dir: string; done: () => void } {
  const dir = mkdtempSync(join(tmpdir(), "oracle-test-"));
  cpSync(ORACLE_DIR, dir, { recursive: true });
  return { dir, done: () => rmSync(dir, { recursive: true, force: true }) };
}

describe("corpus integrity", () => {
  test("the committed corpus is intact (mock region and every fixture hash to the manifest)", () => {
    expect(corpusDrift(ORACLE_DIR)).toBeNull();
  });

  test("RED: one tampered fixture byte is FATAL and the replay exits 2 before touching a jar", async () => {
    const { dir, done } = corpusCopy();
    try {
      const f = join(dir, "basic.json");
      const fx = JSON.parse(readFileSync(f, "utf8")) as { expected_client_sse: string };
      fx.expected_client_sse += " ";
      writeFileSync(f, JSON.stringify(fx));
      expect(corpusDrift(dir)).toMatch(/^FATAL fixture tampered: basic\.json/);
      expect(await oracle(["replay", "--fixtures", dir, "--artifact", "/nonexistent/app-all.jar"])).toBe(HARNESS_EXIT);
    } finally {
      done();
    }
  });

  test("RED: an edited vendored mock region is FATAL mock drift", () => {
    const { dir, done } = corpusCopy();
    try {
      const f = join(dir, "mock-upstream.vendored.mjs");
      writeFileSync(f, readFileSync(f, "utf8").replace("import http from 'node:http';", "import http from 'node:http';\n// drift"));
      expect(corpusDrift(dir)).toMatch(/^FATAL mock drift/);
    } finally {
      done();
    }
  });

  test("a missing fat jar is a harness failure (exit 2) that names the producer, never a nested gradle", async () => {
    expect(await oracle(["replay", "--artifact", "/nonexistent/app-all.jar"])).toBe(HARNESS_EXIT);
  });
});

describe("enrolment", () => {
  // Retired here from cx_19_oracle_replay.ts (2026-09-21). The denominator is readdirSync of the
  // fixture directory, never the expectations table: a checker whose denominator is the list it
  // checks cannot fail for what the list omits, which is bun#34441 restated.
  /** The committed table, with one edit applied to a scratch copy. Throws when the edit matched
   *  nothing — a mutation that changed no bytes proves nothing about the checker. */
  function drifted(edit: (text: string) => string): string {
    const { dir, done } = corpusCopy();
    try {
      const f = join(dir, "expectations.toml");
      const before = readFileSync(f, "utf8");
      const after = edit(before);
      expect(after).not.toBe(before);
      writeFileSync(f, after);
      return enrolmentDrift(dir) ?? "NULL — the checker is blind to this";
    } finally {
      done();
    }
  }
  /** Drop one `key = ...` line from the block that names [row]. */
  const cut = (text: string, row: string, key: string): string => {
    const at = text.indexOf(`name = "${row}"`);
    const line = text.indexOf(`${key} = `, at);
    return text.slice(0, line) + text.slice(text.indexOf("\n", line) + 1);
  };
  // The string "[[divergence]]" also appears inside basic's proof text, so indexOf() on the bare
  // tag anchors on prose and the leg silently mutates a scenario row instead (caught 2026-09-21
  // because the finding named `multipart`). Anchor on the line.
  const DIVERGENCE_AT = "\n[[divergence]]\n";

  test("the committed corpus is fully enrolled — every fixture graded, every row disposed", () => {
    expect(enrolmentDrift(ORACLE_DIR)).toBeNull();
  });

  test("RED: a fixture on disk with no expectations row is captured and unprotected", () => {
    const { dir, done } = corpusCopy();
    try {
      cpSync(join(dir, "basic.json"), join(dir, "orphan.json"));
      expect(enrolmentDrift(dir)).toMatch(/UNENROLLED\s+orphan/);
    } finally {
      done();
    }
  });

  test("RED: an enrolled row whose fixture is gone", () => {
    const { dir, done } = corpusCopy();
    try {
      rmSync(join(dir, "prefill.json"));
      expect(enrolmentDrift(dir)).toMatch(/NO FIXTURE\s+prefill/);
    } finally {
      done();
    }
  });

  test("RED: an excluded scenario contradicted by a fixture on disk", () => {
    const { dir, done } = corpusCopy();
    try {
      cpSync(join(dir, "basic.json"), join(dir, "idle.json"));
      expect(enrolmentDrift(dir)).toMatch(/CONTRADICTED\s+idle/);
    } finally {
      done();
    }
  });

  test("RED: the two states this table calls unfinished are refused, not tolerated", () => {
    const demote = (status: string) => (t: string) =>
      t.replace('name = "basic"\nstatus = "passing"', `name = "basic"\nstatus = "${status}"`);
    expect(drifted(demote("not-yet-replayed"))).toMatch(/UNREPLAYED\s+basic/);
    expect(drifted(demote("kotlin-wrong"))).toMatch(/KOTLIN-WRONG basic/);
  });

  test("RED: a status outside the table's own vocabulary is refused, never assumed benign", () => {
    expect(drifted((t) => t.replace('name = "basic"\nstatus = "passing"', 'name = "basic"\nstatus = "fine-probably"'))).toMatch(
      /UNKNOWN\s+basic: status "fine-probably"/,
    );
  });

  test("RED: every disposition cites its reason — proof, authority, pin, exclusion", () => {
    expect(drifted((t) => cut(t, "basic", "proof"))).toMatch(/UNPROVEN\s+basic/);
    expect(drifted((t) => cut(t, "multipart", "authority"))).toMatch(/UNCITED\s+multipart/);
    expect(drifted((t) => cut(t, "multipart", "pinned_sha256"))).toMatch(/UNPINNED\s+multipart/);
    expect(drifted((t) => cut(t, "drip", "reason"))).toMatch(/UNEXPLAINED\s+drip/);
  });

  test("RED: a [[divergence]] row obeys the sanctioned-scenario law through the same branch", () => {
    const first = (edit: (t: string, at: number) => string) => (t: string) => edit(t, t.indexOf(DIVERGENCE_AT));
    expect(
      drifted(first((t, at) => {
        const line = t.indexOf("authority = ", at);
        return t.slice(0, line) + t.slice(t.indexOf("\n", line) + 1);
      })),
    ).toMatch(/UNCITED\s+expected_upstream_requests\[\]\.stream_options/);
    expect(
      drifted(first((t, at) => {
        const line = t.indexOf("pinned_sha256 = ", at);
        return t.slice(0, line) + t.slice(t.indexOf("\n", line) + 1);
      })),
    ).toMatch(/UNPINNED\s+expected_upstream_requests\[\]\.stream_options/);
    expect(
      drifted(first((t, at) => {
        const line = t.indexOf('status = "sanctioned"', at);
        return t.slice(0, line) + 'status = "kotlin-wrong"' + t.slice(line + 'status = "sanctioned"'.length);
      })),
    ).toMatch(/UNKNOWN\s+expected_upstream_requests\[\]\.stream_options/);
  });

  test("a DELETED [[divergence]] row is caught by the replay, not by this table — the roster has no denominator for it", () => {
    // Honest limit, measured: enrolmentDrift and cx_19's wall are BOTH green when the blocks are
    // deleted, because nothing says how many divergences should exist. The replay is the
    // denominator — the diff those rows sanctioned goes unsanctioned the moment they leave.
    const text = readFileSync(join(ORACLE_DIR, "expectations.toml"), "utf8");
    const without = text.slice(0, text.indexOf(DIVERGENCE_AT));
    expect(without).not.toBe(text);
    const entry = { path: "[0].stream_options", exp: undefined, obs: { reasoning_summary_delivery: "sequential_cutoff" } };
    expect(isSanctioned(entry, sanctionedFields(text))).toBe(true);
    expect(isSanctioned(entry, sanctionedFields(without))).toBe(false);
  });

  test("RED: enrolment drift is a harness failure (exit 2) that NAMES the drift, before any jar", () => {
    const { dir, done } = corpusCopy();
    try {
      // An ORPHAN fixture, not a deleted one: corpusDrift walks the manifest, so an extra file is
      // invisible to it and only the enrolment check can produce this. Asserting the exit code
      // alone proved nothing — a missing jar exits 2 as well, so this arm stayed green with the
      // enrolment call deleted from replay(). The DIAGNOSTIC is what discriminates.
      cpSync(join(dir, "basic.json"), join(dir, "orphan.json"));
      expect(corpusDrift(dir)).toBeNull();
      const r = run("oracle", "--fixtures", dir, "--artifact", "/nonexistent/app-all.jar");
      expect(r.status).toBe(HARNESS_EXIT);
      expect(r.stderr).toMatch(/FATAL enrolment drift/);
      expect(r.stderr).toMatch(/UNENROLLED\s+orphan/);
    } finally {
      done();
    }
  });
});

describe("graders", () => {
  test("canonicalize rewrites message ids and drops SSE keepalive comments only", () => {
    expect(canonicalize("id msg_17_2 and msg_9")).toBe("id msg_CANON and msg_CANON");
    expect(canonicalize("event: ping\n\n: ping\n\ndata: x\n\n")).toBe("event: ping\n\ndata: x\n\n");
  });

  test("jsonDiff reports absent keys, length mismatches and leaf changes by path", () => {
    expect(jsonDiff({ a: 1, b: [1, 2] }, { a: 2, b: [1] }, "r")).toEqual([
      { path: "r.a", exp: 1, obs: 2 },
      { path: "r.b.length", exp: 2, obs: 1 },
    ]);
    expect(jsonDiff({ a: 1 }, { a: 1, extra: true }, "r")).toEqual([{ path: "r.extra", exp: "<absent>", obs: true }]);
    expect(jsonDiff({ a: 1 }, { a: 1 })).toEqual([]);
  });

  const toml = `
[[divergence]]
field = "expected_upstream_requests[].prompt_cache_key"
status = "sanctioned"
expected_without_session_header = "fallback-key"

[[divergence]]
field = "expected_upstream_requests[].stream_options"
status = "sanctioned"
pinned_value = '{"include_obfuscation":false}'

[[divergence]]
field = "expected_upstream_requests[].wildcard"
status = "sanctioned"

[[divergence]]
field = "expected_upstream_requests[].store"
status = "kotlin-wrong"

[[scenario]]
name = "truncated"
status = "sanctioned"
pinned_sha256 = "abc"
pinned_upstream_sha256 = "def"

[[scenario]]
name = "basic"
status = "passing"
`;

  test("sanctionedFields keeps only sanctioned rows, with their runner-readable pins", () => {
    expect(sanctionedFields(toml)).toEqual([
      { field: "expected_upstream_requests[].prompt_cache_key", without: "fallback-key" },
      { field: "expected_upstream_requests[].stream_options", pinnedValue: '{"include_obfuscation":false}' },
      { field: "expected_upstream_requests[].wildcard" },
    ]);
    expect(sanctionedScenarios(toml)).toEqual({ truncated: { pinnedSha: "abc", pinnedUpstreamSha: "def" } });
  });

  test("a sanction is a pin, never a wildcard", () => {
    const s = sanctionedFields(toml);
    expect(isSanctioned({ path: "upstream[0].prompt_cache_key", exp: "x", obs: "fallback-key" }, s)).toBe(true);
    expect(isSanctioned({ path: "upstream[0].prompt_cache_key", exp: "x", obs: "other" }, s)).toBe(false);
    expect(isSanctioned({ path: "upstream[0].stream_options", exp: null, obs: { include_obfuscation: false } }, s)).toBe(true);
    expect(isSanctioned({ path: "upstream[0].stream_options", exp: null, obs: { include_obfuscation: true } }, s)).toBe(false);
    // no runner-readable pin => fail closed, whatever was observed
    expect(isSanctioned({ path: "upstream[0].wildcard", exp: 1, obs: 2 }, s)).toBe(false);
    // kotlin-wrong rows never sanction anything
    expect(isSanctioned({ path: "upstream[0].store", exp: false, obs: true }, s)).toBe(false);
  });

  test("gradeUpstream: count mismatch, unsanctioned diff, sanctioned diff", () => {
    const s = sanctionedFields(toml);
    let problems: string[] = [];
    gradeUpstream([{ a: 1 }], [], s, problems);
    expect(problems).toEqual(["upstream request count: expected 1, got 0"]);
    problems = [];
    gradeUpstream([{ a: 1, prompt_cache_key: "x" }], [{ a: 2, prompt_cache_key: "fallback-key" }], s, problems);
    expect(problems).toEqual(['upstream[0].a: expected 1, got 2']);
  });
});

describe("argv contract", () => {
  test("capture and check are the frozen corpus's provenance: exit 2 with the explanation", async () => {
    expect(await oracle(["capture"])).toBe(HARNESS_EXIT);
    const r = run("oracle", "check");
    expect(r.status).toBe(HARNESS_EXIT);
    expect(r.stderr).toContain("INOPERABLE");
    expect(r.stderr).toContain("P8-CUT");
  });

  test("an unknown arm or verb exits 2 and names the choices", () => {
    expect(run("oracle", "bogus").status).toBe(HARNESS_EXIT);
    const r = run("nothing");
    expect(r.status).toBe(2);
    expect(r.stderr).toContain("oracle");
  });

  test("help lists the oracle verb", () => {
    const r = run("--help");
    expect(r.status).toBe(0);
    expect(r.stdout).toContain("oracle [replay]");
  });

  test("RED: a present but valueless --artifact or --fixtures is refused before anything is read", () => {
    // Each arm is shaped so that the OLD reading (a bare option = its default) could not reach a
    // daemon either: the option that follows is swallowed as the value, so the jar or corpus is bogus.
    const artifact = run("oracle", "replay", "--artifact", "--fixtures", ORACLE_DIR);
    expect(artifact.status).toBe(HARNESS_EXIT);
    expect(artifact.stderr).toContain("--artifact takes a value");
    expect(artifact.stderr).not.toContain("fat jar missing");
    const both = run("oracle", "replay", "--fixtures", "--artifact");
    expect(both.status).toBe(HARNESS_EXIT);
    expect(both.stderr).toContain("--fixtures, --artifact take a value");
    // An EMPTY value is the same hole through truthiness: `--fixtures ""` chose the frozen corpus.
    const emptyFixtures = run("oracle", "replay", "--fixtures", "", "--artifact", "/nonexistent/app-all.jar");
    expect(emptyFixtures.status).toBe(HARNESS_EXIT);
    expect(emptyFixtures.stderr).toContain("--fixtures takes a value");
    expect(emptyFixtures.stderr).not.toContain("fat jar missing");
    const emptyDir = mkdtempSync(join(tmpdir(), "oracle-empty-"));
    try {
      const emptyArtifact = run("oracle", "replay", "--artifact", "", "--fixtures", emptyDir);
      expect(emptyArtifact.status).toBe(HARNESS_EXIT);
      expect(emptyArtifact.stderr).toContain("--artifact takes a value");
      expect(emptyArtifact.stderr).not.toContain("ENOENT");
    } finally {
      rmSync(emptyDir, { recursive: true, force: true });
    }
  });

  test("exit code vocabulary is stable", () => {
    expect(DIVERGENCE_EXIT).toBe(1);
    expect(HARNESS_EXIT).toBe(2);
  });
});
