// THE STAGED BUNDLE, READ BACK AS DATA — the asset set and the checksum table that
// `:app:stageRelease` wrote, plus the enumeration that holds dist/ to it.
//
// DR-25, redone: the asset list used to be hand-written in three places (stage.sh, accept.sh and
// release.yml) with a checker comparing the copies; §24 calls that two lists checking each other,
// not a check against reality. The ONE authority is now the Kotlin `releaseAssets` list in
// app/build.gradle.kts, and everything downstream reads the MANIFEST it staged — sha256sums.txt,
// whose second column IS the published asset set, in order.
//
// The denominator for "is dist/ exactly the bundle" therefore comes from the directory itself
// (`readdirSync`), never from the list being checked: every real entry is published, or excluded
// with a written reason, or it fails BY NAME.
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

/** The manifest file itself: staged beside the assets, and published as an asset in its own right. */
export const SUMS = "sha256sums.txt";

export interface StagedManifest {
  /** the published assets, in the order `:app:stageRelease` wrote them */
  readonly assets: readonly string[];
  /** asset -> lowercase hex sha256, as `sha256sum` spells it */
  readonly sums: ReadonlyMap<string, string>;
}

/** Parse dist/sha256sums.txt. A line that is not `<64 hex>  <name>` is a refusal, never a skip. */
export function readManifest(distDir: string): StagedManifest | Error {
  const path = join(distDir, SUMS);
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    return new Error(`release accept: missing or EMPTY ${path}`);
  }
  if (text.length === 0) return new Error(`release accept: missing or EMPTY ${path}`);
  const assets: string[] = [];
  const sums = new Map<string, string>();
  for (const line of text.split("\n")) {
    if (line === "") continue;
    const match = /^([0-9a-f]{64}) {2}(.+)$/.exec(line);
    if (!match) return new Error(`release accept: ${SUMS} has a line that is not a checksum: ${line}`);
    assets.push(match[2]!);
    sums.set(match[2]!, match[1]!);
  }
  if (assets.length === 0) return new Error(`release accept: missing or EMPTY ${path}`);
  return { assets, sums };
}

// §24: a blank OR placeholder exclusion reason is an absence wearing a label, not a disposition.
// Reject a reason that is blank or is — AS A WHOLE — a known placeholder token (TODO/TBD/etc). The
// match is on the whole normalized reason, never a substring, so a substantive reason that merely
// MENTIONS one of these words ("see TODO-1234 for the port plan") still dispositions.
const PLACEHOLDER_REASONS: ReadonlySet<string> = new Set([
  "todo", "tbd", "tba", "fixme", "wip", "xxx", "n/a", "na", "none", "null", "placeholder", "?", "-", "--", "...",
]);

/** `reason` trimmed, lowercased and stripped of the punctuation set " .:;!?-" at both ends, as the old checker did. */
function normalizeReason(reason: string): string {
  return reason.trim().toLowerCase().replace(/^[ .:;!?-]+/, "").replace(/[ .:;!?-]+$/, "");
}

function absentReason(reason: string): boolean {
  const normalized = normalizeReason(reason);
  return normalized === "" || PLACEHOLDER_REASONS.has(normalized);
}

/** A sorted list of names rendered as ['a', 'b'], so the message reads as it always did. */
function asList(names: readonly string[]): string {
  return `[${names.map((name) => `'${name}'`).join(", ")}]`;
}

/**
 * Every entry of `distDir` accounted for: published (the staged manifest plus the manifest file), or
 * excluded with a written reason. `excluded` is EMPTY in production — it exists so an exclusion, if
 * one is ever needed, must carry a reason that is checked before it is allowed to account for a file.
 * Returns the first problem, or null.
 */
export function enumerationProblem(
  distDir: string,
  published: Iterable<string>,
  excluded: Readonly<Record<string, string>> = {},
): string | null {
  // Fail-closed BEFORE a key is allowed to account for a dist/ entry below.
  const absent = Object.entries(excluded)
    .filter(([, reason]) => absentReason(reason))
    .map(([name]) => name)
    .sort();
  if (absent.length > 0) {
    return `release verify: excluded entries with a blank or placeholder reason (§24, an absence wearing a label): ${asList(absent)}`;
  }
  const publishedSet = new Set(published);
  const actual = new Set(readdirSync(distDir)); // dirs too: EVERY entry needs a disposition
  const unaccounted = [...actual].filter((name) => !publishedSet.has(name) && !Object.hasOwn(excluded, name)).sort();
  const missing = [...publishedSet].filter((name) => !actual.has(name)).sort();
  if (unaccounted.length > 0) {
    return `release verify: dist/ files with NO disposition (publish or exclude-with-reason): ${asList(unaccounted)}`;
  }
  if (missing.length > 0) {
    return `release verify: published set missing from actual dist/: ${asList(missing)}`;
  }
  return null;
}
