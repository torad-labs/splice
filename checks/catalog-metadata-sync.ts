#!/usr/bin/env bun
/**
 * Fail when the version catalog outruns dependency-verification metadata.
 *
 * WHY THIS EXISTS (PR #91, and every gradle Dependabot PR before it): Dependabot can edit
 * gradle/libs.versions.toml but cannot run the metadata regeneration, so every catalog bump
 * arrives with gradle/verification-metadata.xml still pinning the OLD versions. With
 * verify-metadata=true that is a guaranteed red — but it surfaces six minutes into the gradle leg
 * of the gate, as a wall of "Dependency verification failed" noise. This check states the same
 * fact statically, in under a second, with the remedy attached.
 *
 * What is checked, per catalog table:
 *   [libraries]  every entry carrying a resolvable version must appear in the metadata as a
 *                component (group, name, version). Versionless entries (BOM riders) are skipped.
 *   [plugins]    checked as their marker artifact: (id, id + ".gradle.plugin", version). A plugin
 *                applied by bare id inside build-logic never resolves its marker, so the obligation
 *                degrades to version presence (kotlin-serialization is that case today).
 *   [versions]   keys referenced by neither table are FLOOR pins (netty-style: declared so the
 *                resolver and Dependabot have a line to hold/bump, materialised only as
 *                transitives). No (group, name) is derivable statically, so the obligation is
 *                presence: at least one metadata component at that version. A floor that matches
 *                nothing is either an unregenerated bump or an inert floor that should be dropped —
 *                both worth a red.
 *
 * Transitive drift is out of scope by design: it cannot be seen statically, and the remediation
 * below repins transitives and directs anyway. Unparseable catalog shapes are a loud death, never
 * a silent skip — a skipped entry is exactly how drift would hide (brain #924).
 *
 * XML WITHOUT A DOM, AND WHY THAT IS SAFE HERE. This file read the metadata with Python's
 * xml.etree; bun ships no XML parser (no DOMParser, and HTMLRewriter is an HTML parser that would
 * fold case and mangle self-closing tags). So the components are read by scanning `<component ...>`
 * START TAGS and pulling the three attributes out of each by name — order-independent, and
 * indifferent to whether the element is self-closing or has children, which are the only two shapes
 * Gradle emits. THE SCANNER IS BUILT TO DIE LOUDLY WHERE THE PARSER WOULD HAVE BEEN SILENT, which is
 * this file's own law applied to its own implementation: a missing xmlns declaration, a start tag
 * the three attributes cannot be read out of, or a document yielding no components at all is a
 * `die`, never an empty set. An empty set means every entry reads as "not pinned" — a wall of false
 * reds — and a partly-read document would hide exactly the drift this check exists to find. The
 * differential against the previous implementation is what proves the two agree: 627 components,
 * same tuples, on the real file.
 *
 * Usage:
 *     bun checks/catalog-metadata-sync.ts [catalog.toml] [verification-metadata.xml]
 *
 * THERE IS NO FLAG MODE, and that is not an oversight: the bare invocation here IS the gate (it is
 * how checks/gate.sh runs it), so there is no non-gating default to mis-invoke. Compare the checkers
 * that WRITE — those grew an explicit flag precisely because their bare run was the dangerous path.
 */
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const NS = "https://schema.gradle.org/dependency-verification";

const REMEDY = `catalog-metadata-sync: gradle/libs.versions.toml declares versions that
gradle/verification-metadata.xml does not pin. Regenerate from the repository root —
BOTH passes, the shadowJar license pass fetches poms that \`check\` alone never resolves:

    ./gradlew --write-verification-metadata sha256 clean check
    ./gradlew --write-verification-metadata sha256 :app:shadowJar --no-daemon --no-parallel

then commit the regenerated gradle/verification-metadata.xml (precedent: PR #91).`;

function die(msg: string): never {
  process.stderr.write(`catalog-metadata-sync: ${msg}\n`);
  process.exit(2);
}

/** Python's repr for the shapes these messages embed (a string, or a table). Kept because the
 *  messages are part of the port: `!r` printed a Python literal, and a JSON rendering would make
 *  even an unreachable failure path read differently from the implementation it replaces. */
function pyRepr(v: unknown): string {
  if (typeof v === "string") return `'${v.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
  if (v !== null && typeof v === "object") {
    const body = Object.entries(v as Record<string, unknown>)
      .map(([k, x]) => `${pyRepr(k)}: ${pyRepr(x)}`)
      .join(", ");
    return `{${body}}`;
  }
  return String(v);
}

type Versions = Record<string, string>;

/** Resolve an entry's version to a literal, undefined when legitimately absent, death otherwise. */
function versionOf(key: string, entry: Record<string, unknown>, versions: Versions, used: Set<string>): string | undefined {
  const v = entry.version;
  if (v === undefined) return undefined;
  if (typeof v === "string") return v;
  if (v !== null && typeof v === "object" && typeof (v as Record<string, unknown>).ref === "string") {
    const ref = (v as Record<string, unknown>).ref as string;
    if (!(ref in versions)) die(`${key}: version.ref ${pyRepr(ref)} not in [versions]`);
    used.add(ref);
    return versions[ref];
  }
  die(`${key}: unsupported version shape ${pyRepr(v)} — extend this checker, do not skip`);
}

function main(argv: string[]): number {
  const catalogPath = argv[0] ?? resolve(ROOT, "gradle/libs.versions.toml");
  const metadataPath = argv[1] ?? resolve(ROOT, "gradle/verification-metadata.xml");

  const catalog = Bun.TOML.parse(readFileSync(catalogPath, "utf8")) as Record<string, unknown>;
  const versions = (catalog.versions ?? {}) as Record<string, unknown>;
  for (const [key, v] of Object.entries(versions)) {
    if (typeof v !== "string") die(`[versions] ${key}: rich version ${pyRepr(v)} — extend this checker, do not skip`);
  }
  const versionsTyped = versions as Versions;

  const components = readComponents(metadataPath);
  // SPLIT, do not index: components holds joined strings, so `c[2]` is the third CHARACTER of
  // "group|name|version", not the version. The Python iterated tuples, where `_ , _, v` was real
  // unpacking — this is the one place the joined-string representation differs from it, and the
  // differential caught it as two false findings about the jackson and netty floor pins.
  const pinnedVersions = new Set([...components].map((c) => c.split("|")[2]));

  const used = new Set<string>();
  const missing: string[] = [];

  for (const [key, raw] of Object.entries((catalog.libraries ?? {}) as Record<string, unknown>)) {
    let group: string;
    let name: string;
    let version: string | undefined;
    if (typeof raw === "string") {
      const parts = raw.split(":");
      if (parts.length !== 3) die(`libraries.${key}: unsupported shorthand ${pyRepr(raw)}`);
      [group, name, version] = parts;
    } else if (raw !== null && typeof raw === "object") {
      const entry = raw as Record<string, unknown>;
      const module = entry.module;
      if (typeof module === "string" && module.split(":").length - 1 === 1) {
        [group, name] = module.split(":");
      } else if (typeof entry.group === "string" && typeof entry.name === "string") {
        group = entry.group;
        name = entry.name;
      } else {
        die(`libraries.${key}: no module/group+name — extend this checker, do not skip`);
      }
      version = versionOf(`libraries.${key}`, entry, versionsTyped, used);
      if (version === undefined) continue; // BOM rider: version supplied at resolution time
    } else {
      die(`libraries.${key}: unsupported entry ${pyRepr(raw)}`);
    }
    if (!components.has(`${group}|${name}|${version}`)) {
      missing.push(`libraries.${key}: ${group}:${name}:${version} not pinned in metadata`);
    }
  }

  for (const [key, raw] of Object.entries((catalog.plugins ?? {}) as Record<string, unknown>)) {
    if (raw === null || typeof raw !== "object" || typeof (raw as Record<string, unknown>).id !== "string") {
      die(`plugins.${key}: unsupported entry ${pyRepr(raw)}`);
    }
    const id = (raw as Record<string, unknown>).id as string;
    const version = versionOf(`plugins.${key}`, raw as Record<string, unknown>, versionsTyped, used);
    if (version === undefined) continue;
    const marker = `${id}|${id}.gradle.plugin|${version}`;
    // A plugin requested through the plugins DSL resolves its marker; one applied by bare id
    // inside build-logic (implementation jar on that classpath) never does, so regeneration
    // cannot pin a marker for it. Degrade to version presence — an unregenerated bump still
    // has no component at the new version and stays red.
    if (!components.has(marker) && !pinnedVersions.has(version)) {
      missing.push(
        `plugins.${key}: ${id}:${id}.gradle.plugin:${version} (marker) not pinned, ` +
          `and no component at ${version}`,
      );
    }
  }

  for (const key of Object.keys(versionsTyped)) {
    if (used.has(key)) continue;
    if (!pinnedVersions.has(versionsTyped[key])) {
      missing.push(
        `[versions] ${key} = "${versionsTyped[key]}": floor version matches no pinned component ` +
          "(unregenerated bump, or an inert floor to drop)",
      );
    }
  }

  if (missing.length > 0) {
    for (const line of missing.sort()) process.stdout.write(`  ${line}\n`);
    process.stdout.write(`${REMEDY}\n`);
    return 1;
  }
  return 0;
}

/** Every `(group, name, version)` in the metadata, by start-tag scan. Dies loudly on a document it
 *  cannot read as the shape it expects — see the header for why silence here would be the defect. */
function readComponents(metadataPath: string): Set<string> {
  const xml = readFileSync(metadataPath, "utf8");
  if (!xml.includes(`xmlns="${NS}"`)) {
    die(`${metadataPath}: no dependency-verification namespace declaration — unreadable metadata shape`);
  }
  const tags = xml.match(/<component\s[^>]*>/g) ?? [];
  if (tags.length === 0) {
    die(`${metadataPath}: no <component> elements found — an unread metadata file must not read as empty`);
  }
  const out = new Set<string>();
  for (const tag of tags) {
    const g = /\bgroup="([^"]*)"/.exec(tag);
    const n = /\bname="([^"]*)"/.exec(tag);
    const v = /\bversion="([^"]*)"/.exec(tag);
    if (!g || !n || !v) {
      die(`${metadataPath}: a <component> start tag carries no group/name/version: ${tag.slice(0, 120)}`);
    }
    out.add(`${g[1]}|${n[1]}|${v[1]}`);
  }
  return out;
}

process.exit(main(process.argv.slice(2)));
