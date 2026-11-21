// modes-readme: modes/README.md tells a newcomer what a mode is and how to add one: the folder and each of its files,
// every manifest key, how splice finds a mode, the steps from copying the template to a mode in the jar, how splice.toml
// turns a mode on and off, and where a person sees what the door adds. Each manifest key the template's own manifest
// writes is explained.

import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT, TEMPLATE } from "./lib/modes.ts";

const README = join(ROOT, "modes", "README.md");

describe("modes-readme", () => {
  test("the README names the folder's files, every manifest key, the steps and the switches", () => {
    expect(existsSync(README), "modes/README.md exists").toBe(true);
    const text = readFileSync(README, "utf8");
    const files = ["mode.toml", "prompt.md", "tools/<tool>.json", "tools/<tool>.md", "tools/<tool>.meta.json", "resources/", "build.gradle.kts", 'id("splice.mode")', "src/main/kotlin", "src/test/kotlin"];
    for (const f of files) expect(text, `the folder's ${f}`).toContain(f);
    const keys = ["name", "summary", "server", "handler", "tools", "kind", "on", "[heads]", "only", "off", "[callers]", "[[resources]]", "uri", "mimeType", "_meta", "file"];
    for (const k of keys) expect(text, `the manifest key ${k}`).toMatch(new RegExp(`\`${k.replace(/[[\]]/g, "\\$&")}\``));
    for (const v of ['"door"', '"mcp"', '"desktop"', "mcp__<server>__<tool>", "ServiceLoader", "modes/template", "[modes.<name>]", "heads = {", "GET /api/modes", "splice status", "splice check-config"]) {
      expect(text, v).toContain(v);
    }
  });

  test("every key the template's manifest writes is one the README explains", () => {
    const manifest = readFileSync(join(TEMPLATE, "mode.toml"), "utf8");
    const text = readFileSync(README, "utf8");
    const written = [...manifest.matchAll(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=/gm)].map((m) => m[1]!);
    expect(written.length).toBeGreaterThan(0);
    for (const k of new Set(written)) expect(text, `the template's ${k}`).toContain(`\`${k}\``);
  });
});
