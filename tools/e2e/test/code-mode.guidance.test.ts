/** The code-mode GUIDANCE fixtures' own net: the prompts hide the answers, the oracles accept only
 *  real evidence, the LSP tool excludes the decoys, and the Python-subset parser still reproduces
 *  what CPython's ast gave for every fixture file. The gate leg is
 *  `bun tools/e2e code-mode guidance --selftest`, which runs this file.
 *
 *  V4-145 (carried): converted from code_mode_guidance.py's GuidanceTests.
 *
 *  Restructure PR 5: a real `bun test` file. The ASSERTIONS stay `check.*` from the compat layer,
 *  which compares the tagged Python tree (see code-mode.test.ts's note).
 */
import { describe, expect, test } from "bun:test";
import { dumps, loads, obj, type PyObj, type PyValue } from "../src/compat/python-json.ts";
import { check, get, setKey, ValueError } from "../src/compat/python-values.ts";
import {
  CONTRACTS, FILES, GUIDANCE, GUIDANCE_CASES, GUIDANCE_SYSTEM, GuidanceWorkspace, pythonDefs, REQUIRED_LINES,
} from "../src/commands/code-mode.ts";

// ---------------------------------------------------------------------------------------------

const read = (path: string): PyObj => obj([["file_path", path]]);
const call = (name: string, input: PyValue) => ({ name, input });
const contractText = (c: string) => dumps(CONTRACTS[c] as PyObj);

describe("guidance", () => {
  test("prompts do not supply the answers", () => {
    const prompts = Object.fromEntries(GUIDANCE_CASES);
    check.notIn('"timeout_seconds":15', prompts["configuration-audit"] as string);
    check.notIn('"app/service.py:start"', prompts["function-callers"] as string);
    check.notIn('"consumer":"app/service.py:start"', prompts["config-consumer-trace"] as string);
    check.notIn('"bug":"normalize_timeout accepts 0"', prompts["timeout-boundary-bug"] as string);
    check.in("<code_mode_orchestration>", GUIDANCE);
    check.in("tools.call('Read', args)", GUIDANCE);
    check.notIn("Promise.all", GUIDANCE_SYSTEM);
  });

  test("source name alone is not fact evidence", () => {
    const w = new GuidanceWorkspace();
    for (const path of ["config/settings.toml", "config/worker.toml", "config/cache.toml"]) {
      w.execute(call("Grep", obj([["pattern", "["], ["path", path]])));
    }
    check.false(w.correct("configuration-audit", contractText("configuration-audit")));
  });

  test("all oracles accept read or shell evidence", () => {
    for (const c of Object.keys(CONTRACTS)) {
      for (const name of ["Read", "Bash"]) {
        const w = new GuidanceWorkspace();
        for (const path of new Set((REQUIRED_LINES[c] as [string, number][]).map(([p]) => p))) {
          w.execute(call(name, name === "Read" ? read(path) : obj([["command", "cat " + path]])));
        }
        const answer = obj((CONTRACTS[c] as PyObj).__pyObj.map(([k, v]) => [k, v]));
        setKey(answer, "citations", [...(get(CONTRACTS[c] as PyObj, "citations") as PyValue[])].reverse());
        check.true(w.correct(c, dumps(answer)), `${c} ${dumps(w.metrics())}`);
      }
    }
  });

  test("lsp derives callers excluding decoys", () => {
    const w = new GuidanceWorkspace();
    const refs = loads(w.execute(call("LSP", obj([
      ["operation", "findReferences"], ["file_path", "app/limits.py"], ["symbol", "build_client"],
    ])))) as PyValue[];
    check.equal(new Set(["start", "worker_client"]), new Set(refs.map((r) => get(r, "caller") as string)));
    expect(refs.length).toBe(2);
    check.true(w.correct("function-callers", contractText("function-callers")));
  });

  test("wrong facts and unread citations fail", () => {
    for (const [c, contract] of Object.entries(CONTRACTS)) {
      const w = new GuidanceWorkspace();
      check.false(w.correct(c, dumps(contract)));
      for (const [path] of FILES) w.execute(call("Read", read(path)));
      check.true(w.correct(c, dumps(contract)));
      let bad = obj(contract.__pyObj.map(([k, v]) => [k, v]));
      setKey(bad, "citations", ["not-a-file:1"]);
      check.false(w.correct(c, dumps(bad)));
      bad = obj(contract.__pyObj.map(([k, v]) => [k, v]));
      const key = contract.__pyObj.map(([k]) => k).find((k) => k !== "citations") as string;
      setKey(bad, key, null);
      check.false(w.correct(c, dumps(bad)));
    }
  });

  test("redundancy tracks new lines not just file names", () => {
    const w = new GuidanceWorkspace();
    w.execute(call("Grep", obj([["pattern", "build_client"], ["path", "app/service.py"]])));
    w.execute(call("Read", read("app/service.py")));
    check.equal(0, get(w.metrics(), "no_new_evidence_calls"));
    w.execute(call("Bash", obj([["command", "cat app/service.py"]])));
    w.execute(call("Read", read("app/service.py")));
    check.equal(2, get(w.metrics(), "no_new_evidence_calls"));
    check.equal(1, w.repeated_calls);
  });

  test("invalid operations do not touch the host", async () => {
    for (const t of [
      call("Bash", obj([["command", "cat /etc/passwd"]])),
      call("Read", read("../anything")),
      call("Bash", obj([["command", "find . -type f; true"]])),
      call("mcp__deploy__status", obj([])),
    ]) {
      await check.raises((e) => e instanceof ValueError, () => new GuidanceWorkspace().execute(t));
    }
  });

  // ADDED IN THE PORT: the Python-subset parser must reproduce what ast.parse/ast.walk gave for
  // every fixture file — pinned from CPython 3.13 at conversion time.
  test("the Python-subset parser matches ast for every fixture", () => {
    const got = FILES.filter(([p]) => p.endsWith(".py")).map(([p, src]) =>
      [p, pythonDefs(src).map((d) => [d.name, d.line, d.kind, d.calls.map((c) => [c.callee, c.line])])]);
    check.equal([
      ["app/config.py", [["load_timeout", 1, "FunctionDef", []]]],
      ["app/limits.py", [["build_client", 1, "FunctionDef", []],
        ["normalize_timeout", 5, "FunctionDef", [["min", 6], ["max", 6]]]]],
      ["app/service.py", [["start", 5, "FunctionDef", [["build_client", 6], ["load_timeout", 6]]]]],
      ["app/worker.py", [["worker_client", 4, "FunctionDef", [["build_client", 5]]]]],
      ["tests/test_limits.py", [["test_zero_is_currently_accepted", 4, "FunctionDef", [["normalize_timeout", 5]]]]],
    ], got);
  });
});
