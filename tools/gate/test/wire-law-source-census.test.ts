import { afterEach, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { load } from "../../../.dev/campaigns/proxy-hardening/walls/law_pre_content_wire_type.ts";

const scratch: string[] = [];

afterEach(() => {
  for (const root of scratch.splice(0)) rmSync(root, { recursive: true, force: true });
});

test("the wire law follows declared feature and integration roots, including empty Kotlin files", () => {
  const root = mkdtempSync(join(tmpdir(), "wire-law-census-"));
  scratch.push(root);
  const write = (path: string, text: string) => {
    const target = join(root, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, text);
  };
  write("settings.gradle.kts", [
    'include(":features-turns", ":integrations-new-wire")',
    'project(":features-turns").projectDir = file("features/turns")',
    'project(":integrations-new-wire").projectDir = file("integrations/new-wire")',
  ].join("\n"));
  write("features/turns/build.gradle.kts", "");
  write("integrations/new-wire/build.gradle.kts", "");
  const seam = "features/turns/src/main/kotlin/splice/head/wire/SseEmitter.kt";
  const empty = "integrations/new-wire/src/main/kotlin/Empty.kt";
  write(seam, "object Emitter");
  write(empty, "");

  const [sources, seamText] = load(root);

  expect(Object.keys(sources ?? {}).sort()).toEqual([seam, empty].sort());
  expect(sources?.[empty]).toBe("");
  expect(seamText).toBe("object Emitter");
});
