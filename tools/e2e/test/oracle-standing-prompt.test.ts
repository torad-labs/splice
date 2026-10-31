// Since a493ed3e5 (Marcos, 2026-10-28: "ship Splice by default with a pre defined configured append system prompt,
// that the user can change if they'd like"), a Responses head that sets no prompt of its own leads every request's
// input with splice's standing prompt, a developer item the frozen oracle never recorded. The replay grades that item
// as a sanctioned insertion pinned to the bytes splice ships, and stays red when it is missing, moved or different.
import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ORACLE_DIR, gradeUpstream, sanctionedFields, sanctionedInserts, withoutSanctionedInserts } from "../src/commands/oracle.ts";

type Request = { input: unknown[] } & Record<string, unknown>;

const ledger = readFileSync(join(ORACLE_DIR, "expectations.toml"), "utf8");
const shippedPrompt = readFileSync(
  join(import.meta.dir, "../../../integrations/dialects/openai-responses/src/main/resources/splice/dialect/responses/standing-prompt.md"),
  "utf8",
).trimEnd();
// The developer item the Responses dialect appends for a head prompt: role, then content, as the daemon serializes it.
const shipped = { role: "developer", content: shippedPrompt };
const basic = JSON.parse(readFileSync(join(ORACLE_DIR, "basic.json"), "utf8")) as { expected_upstream_requests: Request[] };
const recorded = basic.expected_upstream_requests;
const leading = (item: unknown) => recorded.map((request) => ({ ...request, input: [item, ...request.input] }));

function grade(observed: unknown[]): string[] {
  const problems: string[] = [];
  const graded = withoutSanctionedInserts(observed, sanctionedInserts(ledger), problems);
  gradeUpstream(recorded as never, graded, sanctionedFields(ledger), problems);
  return problems;
}

test("the ledger pins the standing prompt splice ships, by the bytes a request carries", () => {
  const sha256 = createHash("sha256").update(JSON.stringify(shipped)).digest("hex");
  expect(sanctionedInserts(ledger)).toEqual([{ field: "expected_upstream_requests[].input[0]", at: 0, sha256 }]);
});

test("a request that leads with the shipped standing prompt grades as the recording", () => {
  expect(grade(leading(shipped))).toEqual([]);
});

test("a request without the standing prompt is red: the shipped default no longer reaches the wire", () => {
  const problems = grade(recorded);
  expect(problems).toHaveLength(1);
  expect(problems[0]).toContain("upstream[0].input[0]");
  expect(problems[0]).toContain("sanctioned");
});

test("a leading developer item with other text is red, never sanctioned", () => {
  const edited = { role: "developer", content: `${shippedPrompt}\n\nOne more rule.` };
  expect(grade(leading(edited)).some((p) => p.includes("upstream[0].input[0]") && p.includes("sanctioned"))).toBe(true);
});

test("the shipped item anywhere but first is red", () => {
  const behind = recorded.map((request) => ({ ...request, input: [...request.input, shipped] }));
  expect(grade(behind).some((p) => p.includes("upstream[0].input[0]"))).toBe(true);
});
