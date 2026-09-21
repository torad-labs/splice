// THE DEPENDABOT KOTLIN-SCOPE GUARD — the config guard's fourth guard (checks/config/
// dependabot-kotlin-scope.ts until PR 6, when it moved in-process here: Bun.YAML is the one YAML
// reader this repo runs, and the JVM plugin classpath the build's other hygiene checks live on has
// none without a new pinned dependency).
//
// The CodeQL Kotlin extractor block (#18/#37) must freeze the Kotlin compiler/toolchain only. A
// naive glob like `org.jetbrains.kotlin*` also swallows `org.jetbrains.kotlinx.*` (kover,
// coroutines, serialization), silently freezing independently-versioned kotlinx libraries. This
// asserts the ignore block is narrow enough to block the toolchain and wide enough to release
// kotlinx, and that the grouping contract still delivers released deps as one PR.
//
// fnmatch.fnmatchcase is reimplemented rather than approximated, because the whole wall is about
// WHICH NAMES A GLOB MATCHES and an approximation would change verdicts. The fail-closed arm is
// kept: an unparseable dependabot.yml fails naming the file rather than skipping.

export const KOTLINX_NAMES = [
  "org.jetbrains.kotlinx.kover",
  "org.jetbrains.kotlinx.kover:org.jetbrains.kotlinx.kover.gradle.plugin",
  "org.jetbrains.kotlinx:kover-gradle-plugin",
  "org.jetbrains.kotlinx:kotlinx-coroutines-core",
  "org.jetbrains.kotlinx:kotlinx-serialization-json",
] as const;

export const TOOLCHAIN_NAMES = [
  "org.jetbrains.kotlin:kotlin-stdlib",
  "org.jetbrains.kotlin:kotlin-compiler-embeddable",
  "org.jetbrains.kotlin.jvm",
  "org.jetbrains.kotlin.plugin.serialization",
  "org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin",
  "org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin",
] as const;

/** fnmatch.fnmatchcase: `*` -> `.*`, `?` -> `.`, `[seq]` -> a class, `[!seq]` -> a negated one,
 *  everything else escaped. Case-sensitive, and anchored at both ends, which is what makes this a
 *  faithful reimplementation rather than a glob-and-hope. */
export function fnmatchCase(name: string, pattern: string): boolean {
  let re = "";
  let i = 0;
  while (i < pattern.length) {
    const c = pattern[i]!;
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
        inner += pattern[j] === "\\" ? "\\\\" : pattern[j];
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

/** Python repr() of a scalar, for the messages that quote a value back. */
function pyRepr(v: unknown): string {
  if (v === null || v === undefined) return "None";
  if (typeof v === "string") return `'${v.replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`;
  if (Array.isArray(v)) return "[" + v.map(pyRepr).join(", ") + "]";
  if (v === true) return "True";
  if (v === false) return "False";
  return String(v);
}

/** Every way .github/dependabot.yml's gradle block fails the scope contract, by name; [] is green. */
export function dependabotScopeProblems(yamlText: string): string[] {
  let dependabot: Yaml;
  try {
    dependabot = Bun.YAML.parse(yamlText) as Yaml;
  } catch (exc) {
    return [
      `.github/dependabot.yml is not parseable YAML (${(exc as Error).name}); the Kotlin scope cannot be checked, so this guard refuses to pass`,
    ];
  }
  const gradle = ((dependabot["updates"] as Yaml[] | undefined) ?? []).find((u) => u["package-ecosystem"] === "gradle");
  if (gradle === undefined) return ["no gradle update found in .github/dependabot.yml"];

  const problems: string[] = [];
  const globs = ((gradle["ignore"] as Yaml[] | undefined) ?? []).filter((e) => "dependency-name" in e).map((e) => String(e["dependency-name"]));
  if (globs.length === 0) return ["no gradle ignore globs found in .github/dependabot.yml"];

  // 1. kotlinx library/plugin names must NOT match any gradle ignore glob.
  for (const name of KOTLINX_NAMES) {
    for (const glob of globs) if (fnmatchCase(name, glob)) problems.push(`ignore glob '${glob}' swallows kotlinx name '${name}'`);
  }
  // 2. Kotlin toolchain/compiler names MUST still be blocked by at least one glob.
  for (const name of TOOLCHAIN_NAMES) {
    if (!globs.some((glob) => fnmatchCase(name, glob))) problems.push(`toolchain name '${name}' is not blocked by any ignore glob`);
  }
  // 3. The grouping contract must remain intact so released deps arrive as one PR.
  const group = ((gradle["groups"] as Yaml | undefined) ?? {})["gradle-minor-patch"] as Yaml | undefined;
  if (group === undefined || group === null) return [...problems, "gradle-minor-patch group missing"];
  if (group["applies-to"] !== "version-updates") problems.push(`applies-to is ${pyRepr(group["applies-to"])}, expected 'version-updates'`);
  const patterns = group["patterns"];
  if (!Array.isArray(patterns) || patterns.length !== 1 || patterns[0] !== "*") problems.push(`patterns is ${pyRepr(patterns)}, expected ['*']`);
  const updateTypes = group["update-types"];
  if (!Array.isArray(updateTypes) || updateTypes.length !== 2 || updateTypes[0] !== "minor" || updateTypes[1] !== "patch") {
    problems.push(`update-types is ${pyRepr(updateTypes)}, expected ['minor', 'patch']`);
  }
  return problems;
}
