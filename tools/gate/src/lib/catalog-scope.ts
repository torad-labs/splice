// What a version catalog change makes the pre-push run. Marcos, Nov 2 2026: "can we make sure that the pre push only runs the
// diff please, that is change aware. Let's make it scoped." Until then gradle/libs.versions.toml was a `full` path: any change to
// it checked every module and ran the whole unit suite, and on Nov 2 a guava floor two scripts read, a logback floor, two
// Dependabot bumps and a konsist hold each cost a 15 to 21 minute run.
//
// THE ENTRY IS THE UNIT, NOT THE FILE. Gradle hands a catalog to a build script as typed accessors (`libs.ktor.server.core`,
// `libs.versions.guava.get()`, `libs.plugins.kotlin.jvm`, `libs.bundles.grpc`) and as lookups by literal alias
// (`catalog.version("ktlint")`, `findLibrary("x")`). A script that names none of a changed entry's accessors or aliases, directly
// or through a library that pins a changed version or a bundle that carries a changed library, resolves exactly what it
// resolved before the push, unless its module depends on one whose script does: the reader's `api` puts the new bytes on its
// dependents' classpaths too. So a changed entry selects the modules whose scripts read it and their dependents (selector.ts),
// and runs all of those modules' unit suites whole (test-impact.ts): a version bump changes bytes no reference walk can follow.
//
// WHAT RUNS EVERY MODULE. An entry build-logic, the root build or settings reads, because every module's build runs those, and one
// a module's script reads when that script is itself a scope.json `full` path: quality/compiler-plugin/build.gradle.kts builds the
// jar the root build wires into every module's compileKotlin, an edge the module graph does not carry (review of PR 445: a bump of
// kotlin-compiler-embeddable checked that module alone, and every other module would have loaded the new plugin unchecked).
// An entry no build script reads at all: either it is dead or it is read in a way this file does not know, and the pre-push
// selects more where it is unsure. A catalog the push adds, removes, or leaves unparseable on either side. A lookup whose alias is
// computed (CatalogReader.read() walks every alias for the dependency-hygiene check at the root) is not a reader of any one entry:
// catalogMetadataSync runs only under gateOfRecord, never in a pre-push, and the entries it checks are the ones the module scripts
// resolve.
//
// Pure: the caller reads the catalog before and after the push and the build texts, and nothing here starts a process.

import { catalogRule, fullRule, type ScopeRules } from "./scope.ts";

export type CatalogSection = "versions" | "libraries" | "plugins" | "bundles";

/** One catalog entry: its table and its alias as the TOML spells it (`kotlinx-serialization-json`). */
export interface CatalogKey {
  readonly section: CatalogSection;
  readonly alias: string;
}

/** A catalog as its four tables, each alias to its entry as TOML parsed it. */
export interface Catalog {
  readonly versions: ReadonlyMap<string, unknown>;
  readonly libraries: ReadonlyMap<string, unknown>;
  readonly plugins: ReadonlyMap<string, unknown>;
  readonly bundles: ReadonlyMap<string, unknown>;
}

/** A build text: a gradle script or a build-logic source, with the module it configures, or undefined for the root build,
 *  settings and build-logic, which every module's build runs. */
export interface CatalogText {
  readonly path: string;
  readonly module: string | undefined;
  readonly text: string;
}

export type CatalogImpact =
  /** The catalog changed no entry (a comment, a blank line). */
  | { readonly kind: "none" }
  /** Every module runs, for [why]. */
  | { readonly kind: "full"; readonly why: string }
  /** These modules run, each for its reason; their dependents are the selector's call. */
  | { readonly kind: "modules"; readonly modules: ReadonlyMap<string, string> };

const SECTIONS: readonly CatalogSection[] = ["versions", "libraries", "plugins", "bundles"];

/** The catalog [text] holds, or undefined when it is not TOML. */
export function parseCatalog(text: string): Catalog | undefined {
  let parsed: unknown;
  try {
    parsed = Bun.TOML.parse(text);
  } catch {
    return undefined;
  }
  if (parsed === null || typeof parsed !== "object") return undefined;
  const tables = parsed as Record<string, unknown>;
  const table = (name: CatalogSection): ReadonlyMap<string, unknown> => {
    const value = tables[name];
    if (value === null || typeof value !== "object" || Array.isArray(value)) return new Map();
    return new Map(Object.entries(value as Record<string, unknown>));
  };
  return { versions: table("versions"), libraries: table("libraries"), plugins: table("plugins"), bundles: table("bundles") };
}

/** [value] as a text that is equal for equal entries whatever the key order. */
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value !== null && typeof value === "object") {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, inner]) => `${JSON.stringify(key)}:${canonical(inner)}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

/** The entries whose value differs between [before] and [after], added and removed ones included, in table order then alias order. */
export function catalogChanges(before: Catalog, after: Catalog): CatalogKey[] {
  const changed: CatalogKey[] = [];
  for (const section of SECTIONS) {
    const aliases = new Set([...before[section].keys(), ...after[section].keys()]);
    for (const alias of [...aliases].sort()) {
      const was = before[section].get(alias);
      const now = after[section].get(alias);
      if (was === undefined || now === undefined ? was !== now : canonical(was) !== canonical(now)) changed.push({ section, alias });
    }
  }
  return changed;
}

/** The version alias an entry pins by `version.ref`, or undefined when it pins none. */
function versionRef(entry: unknown): string | undefined {
  if (entry === null || typeof entry !== "object") return undefined;
  const version = (entry as { version?: unknown }).version;
  if (version === null || typeof version !== "object") return undefined;
  const ref = (version as { ref?: unknown }).ref;
  return typeof ref === "string" ? ref : undefined;
}

/** The library aliases a bundle entry carries. */
function bundled(entry: unknown): string[] {
  return Array.isArray(entry) ? entry.filter((item): item is string => typeof item === "string") : [];
}

const keyOf = (key: CatalogKey): string => `${key.section} ${key.alias}`;

/** [key] and every entry that pins or carries it, in either catalog: a version's libraries and plugins, a library's bundles. A
 *  script that reads one of these reads the changed entry. */
function readersOf(key: CatalogKey, catalogs: readonly Catalog[]): CatalogKey[] {
  const found = new Map<string, CatalogKey>([[keyOf(key), key]]);
  const add = (next: CatalogKey): void => void found.set(keyOf(next), next);
  const libraries: string[] = key.section === "libraries" ? [key.alias] : [];
  if (key.section === "versions") {
    for (const catalog of catalogs) {
      for (const [alias, entry] of catalog.libraries) {
        if (versionRef(entry) === key.alias) {
          add({ section: "libraries", alias });
          libraries.push(alias);
        }
      }
      for (const [alias, entry] of catalog.plugins) if (versionRef(entry) === key.alias) add({ section: "plugins", alias });
    }
  }
  if (libraries.length > 0) {
    for (const catalog of catalogs) {
      for (const [alias, entry] of catalog.bundles) if (bundled(entry).some((library) => libraries.includes(library))) add({ section: "bundles", alias });
    }
  }
  return [...found.values()];
}

/** An alias as a typed accessor spells it: Gradle turns `-` and `_` into `.`. */
const accessorForm = (alias: string): string => alias.replace(/[-_]/g, ".");

const LOOKUPS: Readonly<Record<string, CatalogSection>> = {
  version: "versions",
  findVersion: "versions",
  library: "libraries",
  findLibrary: "libraries",
  plugin: "plugins",
  findPlugin: "plugins",
  bundle: "bundles",
  findBundle: "bundles",
};

const escapeRegExp = (text: string): string => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * The catalog entries [text] reads under [accessor]: each typed accessor chain (`libs.grpc.netty.shaded`, `libs.versions.guava`,
 * `libs.plugins.kotlin.jvm`, `libs.bundles.grpc`), resolved to the longest alias of that table any of [catalogs] holds, and each
 * lookup by literal alias (`version("ktlint")`, `findLibrary("x")`). A chain that names no alias, such as the file's own name in a
 * string, reads nothing. Both sides of a push are passed as [catalogs], so a reference to an alias the push adds or removes counts.
 */
export function catalogReferences(text: string, accessor: string, catalogs: readonly Catalog[]): CatalogKey[] {
  const found = new Map<string, CatalogKey>();
  const add = (key: CatalogKey): void => void found.set(keyOf(key), key);
  const aliasesOf = (section: CatalogSection): Map<string, string> => {
    const byForm = new Map<string, string>();
    for (const catalog of catalogs) for (const alias of catalog[section].keys()) byForm.set(accessorForm(alias), alias);
    return byForm;
  };
  const forms = new Map(SECTIONS.map((section) => [section, aliasesOf(section)] as const));
  const chains = new RegExp(`(?<![\\w.])${escapeRegExp(accessor)}((?:\\.[A-Za-z_]\\w*)+)`, "g");
  for (const match of text.matchAll(chains)) {
    const segments = (match[1] ?? "").slice(1).split(".");
    const [first] = segments;
    const typed = first === "versions" || first === "plugins" || first === "bundles";
    const section: CatalogSection = typed ? (first as CatalogSection) : "libraries";
    const rest = typed ? segments.slice(1) : segments;
    const table = forms.get(section) ?? new Map<string, string>();
    for (let length = rest.length; length > 0; length--) {
      const alias = table.get(rest.slice(0, length).join("."));
      if (alias !== undefined) {
        add({ section, alias });
        break;
      }
    }
  }
  const lookups = /\b(findVersion|findLibrary|findPlugin|findBundle|version|library|plugin|bundle)\s*\(\s*"([^"\\]*)"\s*\)/g;
  for (const match of text.matchAll(lookups)) {
    const section = LOOKUPS[match[1] ?? ""];
    const alias = match[2] ?? "";
    if (section !== undefined && catalogs.some((catalog) => catalog[section].has(alias))) add({ section, alias });
  }
  return [...found.values()];
}

export interface CatalogImpactInput {
  /** The catalog's path, for the reasons. */
  readonly path: string;
  /** The name build scripts read the catalog under. */
  readonly accessor: string;
  /** The catalog's text before the push, or undefined when the push adds it. */
  readonly before: string | undefined;
  /** The catalog's text in the pushed commit, or undefined when the push removes it. */
  readonly after: string | undefined;
  /** Every build text of the pushed commit. */
  readonly texts: readonly CatalogText[];
  /** Whether the build text at [path] is an input of every module's build though a module owns it (a scope.json `full` path, such as
   *  the compiler plugin's script, whose jar every module compiles with: an edge the module graph does not carry). */
  readonly everyModule?: (path: string) => boolean;
}

const spelled = (aliases: readonly string[]): string => aliases.map((alias) => `\`${alias}\``).join(", ");

/** What the change from [before] to [after] of the catalog at [path] makes the pre-push run. */
export function catalogImpact(input: CatalogImpactInput): CatalogImpact {
  const { path } = input;
  if (input.before === undefined && input.after === undefined) return { kind: "full", why: `${path} changed and neither side of the push holds it` };
  if (input.before === undefined) return { kind: "full", why: `${path} is added by the push, so what every module resolves is new` };
  if (input.after === undefined) return { kind: "full", why: `${path} is removed by the push, so what every module resolves is new` };
  const before = parseCatalog(input.before);
  const after = parseCatalog(input.after);
  if (before === undefined || after === undefined) {
    return { kind: "full", why: `${path} cannot be read as TOML ${before === undefined ? "before" : "after"} the push, so what changed in it is unknown` };
  }
  const changed = catalogChanges(before, after);
  if (changed.length === 0) return { kind: "none" };

  const catalogs = [before, after];
  // Which changed entry each readable key stands for: the entry itself, or the changed version it pins or changed library it carries.
  const standsFor = new Map<string, CatalogKey[]>();
  for (const key of changed) {
    for (const reader of readersOf(key, catalogs)) {
      const list = standsFor.get(keyOf(reader)) ?? [];
      list.push(key);
      standsFor.set(keyOf(reader), list);
    }
  }
  const placed = new Set<string>();
  const modules = new Map<string, string>();
  for (const text of input.texts) {
    const hits = catalogReferences(text.text, input.accessor, catalogs).filter((key) => standsFor.has(keyOf(key)));
    if (hits.length === 0) continue;
    // Placed by table and alias (keyOf), not by alias: ktlint, detekt, kover, cyclonedx, shadow and konsist name a version and a plugin
    // each in the real catalog, and a read of one must not place a change to the other. The alias alone is for the reason.
    const entries = [...new Map(hits.flatMap((hit) => standsFor.get(keyOf(hit)) ?? []).map((key) => [keyOf(key), key] as const)).values()];
    for (const entry of entries) placed.add(keyOf(entry));
    const through = hits.filter((hit) => !changed.some((key) => keyOf(key) === keyOf(hit))).map((hit) => hit.alias);
    const via = through.length === 0 ? "" : ` through ${spelled(through)}`;
    const aliases = entries.map((key) => key.alias);
    const reason = `${spelled(aliases)} in ${path} changed, and ${text.path} reads ${entries.length === 1 ? "it" : "them"}${via}`;
    if (text.module === undefined || input.everyModule?.(text.path) === true) return { kind: "full", why: `${reason}, as every module's build does` };
    const existing = modules.get(text.module);
    modules.set(text.module, existing === undefined ? reason : `${existing}; ${reason}`);
  }
  const unplaced = changed.filter((key) => !placed.has(keyOf(key))).map((key) => key.alias);
  if (unplaced.length > 0) {
    return {
      kind: "full",
      why: `${spelled(unplaced)} in ${path} changed, and no build script reads ${unplaced.length === 1 ? "it" : "them"}, so the selection cannot place the change`,
    };
  }
  return { kind: "modules", modules };
}

export interface CatalogImpactOfInput {
  /** The paths the push changes. */
  readonly changed: readonly string[];
  readonly scope: ScopeRules;
  /** A path's text in the pushed commit, or undefined when the commit does not hold it. */
  readonly read: (path: string) => string | undefined;
  /** A path's text before the push, or undefined when the base does not hold it. */
  readonly before: (path: string) => string | undefined;
  /** The build texts of the pushed commit, read once and only when a catalog changed. */
  readonly texts: () => readonly CatalogText[];
}

/** The impact of every catalog the push changes, or undefined when it changes none: every module when any one says so, else the
 *  union of the modules they select, else none. */
export function catalogImpactOf(input: CatalogImpactOfInput): CatalogImpact | undefined {
  const catalogs = input.changed.flatMap((path) => {
    const rule = catalogRule(path, input.scope);
    return rule === undefined ? [] : [{ path, rule }];
  });
  if (catalogs.length === 0) return undefined;
  const texts = input.texts();
  const modules = new Map<string, string>();
  for (const { path, rule } of catalogs) {
    const impact = catalogImpact({
      path,
      accessor: rule.accessor,
      before: input.before(path),
      after: input.read(path),
      texts,
      everyModule: (script) => fullRule(script, input.scope) !== undefined,
    });
    if (impact.kind === "full") return impact;
    if (impact.kind === "none") continue;
    for (const [module, why] of impact.modules) {
      const existing = modules.get(module);
      modules.set(module, existing === undefined ? why : `${existing}; ${why}`);
    }
  }
  return modules.size === 0 ? { kind: "none" } : { kind: "modules", modules };
}
