#!/usr/bin/env bun
/** checks/config/dependabot-kotlin-scope.ts — fail-closed guard for the Kotlin ignore block.
 *
 *  The CodeQL Kotlin extractor block (#18/#37) must freeze the Kotlin compiler/toolchain
 *  only. A naive glob like `org.jetbrains.kotlin*` also swallows `org.jetbrains.kotlinx.*`
 *  (kover, coroutines, serialization), silently freezing independently-versioned kotlinx
 *  libraries. This script asserts the ignore block is narrow enough to block the toolchain
 *  and wide enough to release kotlinx.
 *
 *  V4-145: converted to TypeScript (bun). PyYAML is replaced by Bun.YAML (ships in Bun 1.4.0, no
 *  dependency added) and fnmatch.fnmatchcase is reimplemented here, because the whole wall is about
 *  WHICH NAMES A GLOB MATCHES and an approximation would change verdicts. The fail-closed arm is
 *  kept: Bun.YAML is part of the runtime rather than an import, so the equivalent of a missing
 *  PyYAML is a parse failure, and that exits 1 naming the file rather than skipping.
 */
import { readFileSync } from "node:fs";

const KOTLINX_NAMES = [
  "org.jetbrains.kotlinx.kover",
  "org.jetbrains.kotlinx.kover:org.jetbrains.kotlinx.kover.gradle.plugin",
  "org.jetbrains.kotlinx:kover-gradle-plugin",
  "org.jetbrains.kotlinx:kotlinx-coroutines-core",
  "org.jetbrains.kotlinx:kotlinx-serialization-json",
];

const TOOLCHAIN_NAMES = [
  "org.jetbrains.kotlin:kotlin-stdlib",
  "org.jetbrains.kotlin:kotlin-compiler-embeddable",
  "org.jetbrains.kotlin.jvm",
  "org.jetbrains.kotlin.plugin.serialization",
  "org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin",
  "org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin",
];

/** Python repr() of a scalar, for the messages that quote a value back. */
function pyRepr(v: unknown): string {
  if (v === null || v === undefined) return "None";
  if (typeof v === "string") return `'${v.replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`;
  if (Array.isArray(v)) return "[" + v.map(pyRepr).join(", ") + "]";
  if (v === true) return "True";
  if (v === false) return "False";
  return String(v);
}

/** fnmatch.fnmatchcase: `*` -> `.*`, `?` -> `.`, `[seq]` -> a class, `[!seq]` -> a negated one,
 *  everything else escaped. Case-sensitive, and anchored at both ends, which is what makes this a
 *  faithful reimplementation rather than a glob-and-hope. */
export function fnmatchCase(name: string, pattern: string): boolean {
  let re = "";
  let i = 0;
  while (i < pattern.length) {
    const c = pattern[i];
    i += 1;
    if (c === "*") {
      re += ".*";
    } else if (c === "?") {
      re += ".";
    } else if (c === "[") {
      let j = i;
      let negate = false;
      if (j < pattern.length && (pattern[j] === "!" || pattern[j] === "^")) {
        negate = true;
        j += 1;
      }
      let inner = "";
      let closed = false;
      // A `]` immediately after `[` or `[!` is a LITERAL MEMBER, not the class close — Python's
      // fnmatch.translate skips it for exactly this reason, and the generated corpus caught the
      // omission on `[!]a]`, where Python builds [^]a] (one char, not ] or a) and a port that
      // closes the class early builds [^] (any char) and matches a three-character name.
      if (j < pattern.length && pattern[j] === "]") {
        inner += "\\]";
        j += 1;
      }
      while (j < pattern.length) {
        if (pattern[j] === "]") {
          closed = true;
          break;
        }
        if (pattern[j] === "\\") inner += "\\\\";
        else inner += pattern[j];
        j += 1;
      }
      if (closed) {
        i = j + 1;
        re += "[" + (negate ? "^" : "") + inner + "]";
      } else {
        re += "\\[";
      }
    } else {
      re += c.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    }
  }
  return new RegExp("^(?:" + re + ")$").test(name);
}

type Yaml = Record<string, unknown>;

export function main(): number {
  let dependabot: Yaml;
  try {
    dependabot = Bun.YAML.parse(readFileSync(".github/dependabot.yml", "utf8")) as Yaml;
  } catch (exc) {
    process.stderr.write(
      `dependabot-kotlin-scope: FAIL — .github/dependabot.yml is not parseable YAML (${(exc as Error).name}); ` +
        "the Kotlin scope cannot be checked, so this check refuses to pass\n",
    );
    return 1;
  }

  let gradleUpdate: Yaml | null = null;
  for (const update of ((dependabot["updates"] as Yaml[] | undefined) ?? [])) {
    if (update["package-ecosystem"] === "gradle") {
      gradleUpdate = update;
      break;
    }
  }

  if (gradleUpdate === null) {
    process.stderr.write("dependabot-kotlin-scope: FAIL — no gradle update found\n");
    return 1;
  }

  const ignores = ((gradleUpdate["ignore"] as Yaml[] | undefined) ?? []);
  const globs = ignores.filter((e) => "dependency-name" in e).map((e) => String(e["dependency-name"]));

  if (globs.length === 0) {
    process.stderr.write("dependabot-kotlin-scope: FAIL — no gradle ignore globs found\n");
    return 1;
  }

  // --- assertion 1: kotlinx library/plugin names must NOT match any gradle ignore glob ---
  for (const name of KOTLINX_NAMES) {
    for (const glob of globs) {
      if (fnmatchCase(name, glob)) {
        process.stderr.write(
          `dependabot-kotlin-scope: FAIL — ignore glob '${glob}' swallows kotlinx name '${name}'\n`,
        );
        return 1;
      }
    }
  }
  process.stdout.write("dependabot-kotlin-scope: all kotlinx names released\n");

  // --- assertion 2: Kotlin toolchain/compiler names MUST still be blocked by at least one glob ---
  for (const name of TOOLCHAIN_NAMES) {
    const blocked = globs.some((glob) => fnmatchCase(name, glob));
    if (!blocked) {
      process.stderr.write(
        `dependabot-kotlin-scope: FAIL — toolchain name '${name}' is not blocked by any ignore glob\n`,
      );
      return 1;
    }
  }
  process.stdout.write("dependabot-kotlin-scope: all toolchain names blocked\n");

  // --- assertion 3: grouping contract must remain intact so released deps arrive as one PR ---
  const groups = (gradleUpdate["groups"] as Yaml | undefined) ?? {};
  const group = groups["gradle-minor-patch"] as Yaml | undefined;
  if (group === undefined || group === null) {
    process.stderr.write("dependabot-kotlin-scope: FAIL — gradle-minor-patch group missing\n");
    return 1;
  }
  if (group["applies-to"] !== "version-updates") {
    process.stderr.write(
      `dependabot-kotlin-scope: FAIL — applies-to is ${pyRepr(group["applies-to"])}, expected 'version-updates'\n`,
    );
    return 1;
  }
  const patterns = group["patterns"];
  if (!Array.isArray(patterns) || patterns.length !== 1 || patterns[0] !== "*") {
    process.stderr.write(
      `dependabot-kotlin-scope: FAIL — patterns is ${pyRepr(patterns)}, expected ['*']\n`,
    );
    return 1;
  }
  const updateTypes = group["update-types"];
  if (
    !Array.isArray(updateTypes) ||
    updateTypes.length !== 2 ||
    updateTypes[0] !== "minor" ||
    updateTypes[1] !== "patch"
  ) {
    process.stderr.write(
      `dependabot-kotlin-scope: FAIL — update-types is ${pyRepr(updateTypes)}, expected ['minor', 'patch']\n`,
    );
    return 1;
  }
  process.stdout.write("dependabot-kotlin-scope: gradle-minor-patch grouping contract intact\n");

  process.stdout.write("dependabot-kotlin-scope: PASS\n");
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
