// gnh-docs: the decision that the Splice gateway serves every provider itself is written where splice records its
// decisions (docs/specs/door-default.md), in the README's provider section and in the splice skill's reference, in the
// same words: the selector `claude-<provider>--<model id>` (a Claude model keeping its own Anthropic id), the
// `[gateway] aliases` table, and the values a provider now carries for the gateway. The repository is public, so no
// example names a provider or model of anyone's own setup.

import { describe, expect, test } from "bun:test";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "../server/lib/harness.ts";

const read = (p: string) => readFileSync(join(ROOT, p), "utf8");

/** The text of `doc` from the heading that matches `heading` to the next heading of the same level or higher. */
function section(doc: string, heading: RegExp): string {
  const lines = doc.split("\n");
  const start = lines.findIndex((l) => /^#{2,4} /.test(l) && heading.test(l));
  if (start < 0) return "";
  const level = lines[start]!.match(/^#+/)![0].length;
  const end = lines.findIndex((l, i) => i > start && /^#+ /.test(l) && l.match(/^#+/)![0].length <= level);
  return lines.slice(start, end < 0 ? undefined : end).join("\n");
}

const doorDefault = read("docs/specs/door-default.md");
const decision = section(doorDefault, /gateway serves every provider/i);
const readme = section(read("README.md"), /^## Provider support/);
const referenceDir = "app/src/main/resources/manager-home/.claude/skills/splice/reference";
const reference = readdirSync(join(ROOT, referenceDir))
  .map((f) => read(join(referenceDir, f)))
  .join("\n");

describe("gnh-docs", () => {
  test("door-default records the decision, with the selector, the alias table and the provider's values", () => {
    expect(decision).not.toBe("");
    for (const words of ["claude-<provider>--<model id>", "Anthropic id", "[gateway]", "aliases", "context_window", "model_slots", "system_prompt_mode", "overrides", "splice doctor", "splice status"]) {
      expect(decision).toContain(words);
    }
    expect(decision.toLowerCase()).toContain("no head");
  });

  test("the README's provider section says a provider needs no head and how its models are named", () => {
    expect(readme).toContain("claude-<provider>--<model id>");
    expect(readme).toContain("[gateway]");
    expect(readme.toLowerCase()).toContain("no head");
  });

  test("the splice skill's reference documents the alias table and the provider's values", () => {
    for (const words of ["[gateway]", "aliases", "claude-<provider>--<model id>", "system_prompt_mode", "model_slots"]) {
      expect(reference).toContain(words);
    }
  });

  test("no example names a provider of anyone's own setup", () => {
    for (const text of [decision, readme, reference]) expect(text.toLowerCase()).not.toContain("inkling");
  });
});
