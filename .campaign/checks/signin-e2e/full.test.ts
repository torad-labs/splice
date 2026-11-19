// signin-e2e full: the check as a release runs it, `bun tools/e2e signin` with no kind named: one run covers every kind
// the code's registry has, each passes, no kind is missing a case, and the run exits 0.

import { expect, setDefaultTimeout, test } from "bun:test";
import { judge, registryKinds, runCheck } from "./lib/report.ts";

setDefaultTimeout(60 * 60_000);

test("one run signs in every kind splice has, end to end", () => {
  const r = runCheck([]);
  expect(r.report, `no report was written:\n${r.out.slice(-6000)}`).not.toBeNull();
  const report = r.report!;
  expect(report.missing, "every kind has a case").toEqual([]);
  expect(report.kinds.map((k) => k.kind).sort(), "the run covers the registry's kinds").toEqual(registryKinds().sort());
  for (const k of report.kinds) judge(k);
  expect(report.ok).toBe(true);
  expect(r.code, r.out.slice(-6000)).toBe(0);
});
