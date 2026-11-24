// Prove the README → docs/wiki split.
// Usage: bun .campaign/checks/wiki-docs/doccheck.ts <links|wiki|readme|disclaimers|disk|all>
// Exit 0 on pass, 1 with a per-problem list on fail.

import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { dirname, join as pjoin, normalize } from "node:path/posix";

const WIKI_PAGES = [
  "README.md",
  "getting-started.md",
  "upgrading-to-0.7.0.md",
  "providers-and-accounts.md",
  "door-and-launchers.md",
  "claude-desktop.md",
  "console-and-sessions.md",
  "mcp-hub-and-sync.md",
  "peers-and-harnesses.md",
  "team-server-mode.md",
  "modes-and-extensions.md",
  "configuration-reference.md",
  "cli-reference.md",
  "disk-and-privacy.md",
  "architecture-and-troubleshooting.md",
] as const;

const DISK_HEADING = "## What splice keeps on your disk";
const HERO_DAY = "splice-horizontal-day.svg";
const HERO_NIGHT = "splice-horizontal-night.svg";
const LAW_DISK = "quality/architecture/src/test/kotlin/splice/quality/DiskWritesLawTest.kt";
const TYPES_LINE = /^export const TYPES = "([^"]+)";$/m;

type Mode = "links" | "wiki" | "readme" | "disclaimers" | "disk";

const root = repoRoot();

function repoRoot(): string {
  const run = spawnSync("git", ["rev-parse", "--show-toplevel"], { encoding: "utf8" });
  if (run.status !== 0) {
    console.error("doccheck: not in a git worktree");
    process.exit(1);
  }
  return run.stdout.trim();
}

function gitLsFiles(): string[] {
  const run = spawnSync("git", ["-C", root, "ls-files", "-z"], { encoding: "buffer" });
  if (run.status !== 0) {
    throw new Error(`git ls-files failed: ${run.stderr.toString().trim()}`);
  }
  return run.stdout.toString("utf8").split("\0").filter(Boolean);
}

function abs(rel: string): string {
  return join(root, rel);
}

function readText(rel: string): string | null {
  const path = abs(rel);
  if (!existsSync(path)) return null;
  try {
    if (!statSync(path).isFile()) return null;
    return readFileSync(path, "utf8");
  } catch (err) {
    throw new Error(`unreadable ${rel}: ${err instanceof Error ? err.message : err}`);
  }
}

function linesOf(text: string): string[] {
  const lines = text.split("\n");
  if (text.endsWith("\n")) lines.pop();
  return lines;
}

function lineCount(text: string): number {
  if (text.length === 0) return 0;
  const parts = text.split("\n");
  return text.endsWith("\n") ? parts.length - 1 : parts.length;
}

function lineAt(text: string, index: number): number {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i++) if (text[i] === "\n") line++;
  return line;
}

function decodeSafe(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function trackedMatching(needle: string): string[] {
  return gitLsFiles().filter((path) => path.includes(needle));
}

// --- code masking -----------------------------------------------------------

function fenceMarker(line: string): { char: string; len: number; info: string } | null {
  const matched = /^( {0,3})(`+|~+)(.*)$/.exec(line);
  const run = matched?.[2];
  if (!matched || !run || run.length < 3) return null;
  return { char: run.charAt(0), len: run.length, info: matched[3] ?? "" };
}

/** 1 = inside a fenced block or an inline code span. Length matches `text`. */
export function maskCode(text: string): Uint8Array {
  const mask = new Uint8Array(text.length);
  const lines = text.split("\n");
  let offset = 0;
  let inFence = false;
  let fenceChar = "";
  let fenceLen = 0;
  for (const line of lines) {
    const marker = fenceMarker(line);
    if (!inFence) {
      if (marker && !(marker.char === "`" && marker.info.includes("`"))) {
        inFence = true;
        fenceChar = marker.char;
        fenceLen = marker.len;
        mask.fill(1, offset, offset + line.length);
      }
    } else {
      mask.fill(1, offset, offset + line.length);
      if (marker && marker.char === fenceChar && marker.len >= fenceLen && marker.info.trim() === "") {
        inFence = false;
      }
    }
    offset += line.length + 1;
  }

  // Inline code is parsed inside a block, after fences and headings are blocks.
  // An unmatched backtick must not swallow the next paragraph or heading.
  let i = 0;
  while (i < text.length) {
    if (mask[i] || text[i] !== "`" || (i > 0 && text[i - 1] === "\\")) {
      i++;
      continue;
    }
    let width = 0;
    while (i + width < text.length && text[i + width] === "`" && !mask[i + width]) width++;
    let j = i + width;
    let closer = -1;
    while (j < text.length && !mask[j] && !inlineBoundary(text, i, j)) {
      if (text[j] !== "`") {
        j++;
        continue;
      }
      let run = 0;
      while (j + run < text.length && text[j + run] === "`" && !mask[j + run]) run++;
      if (run === width) {
        closer = j;
        break;
      }
      j += run;
    }
    if (closer < 0) {
      i += width;
      continue;
    }
    mask.fill(1, i, closer + width);
    i = closer + width;
  }
  return mask;
}

/** A fence, a blank line, or a heading ends the block an inline span may cross. */
function inlineBoundary(text: string, open: number, at: number): boolean {
  if (text[at] !== "\n") return false;
  const next = at + 1;
  if (next >= text.length || text[next] === "\n") return true;
  if (/^ {0,3}#{1,6}[ \t]/.test(text.slice(next, next + 10))) return true;
  const lineStart = text.lastIndexOf("\n", open - 1) + 1;
  return /^ {0,3}#{1,6}[ \t]/.test(text.slice(lineStart, lineStart + 10));
}

function masked(text: string, mask: Uint8Array): string {
  let out = "";
  for (let i = 0; i < text.length; i++) out += mask[i] ? (text[i] === "\n" ? "\n" : " ") : text[i];
  return out;
}

// --- heading slugs ----------------------------------------------------------
// GitHub heading slugs, as this tree's own anchors use them: lowercase, strip
// punctuation except "-" and "_", spaces become "-", unicode letters and digits
// stay. A repeated slug is slug, slug-1, slug-2 (github-slugger's counter).

export function slugifyHeading(heading: string): string {
  let text = heading.replace(/[ \t]+#+\s*$/, "");
  text = text.replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1");
  text = text.replace(/\[([^\]]*)\]\([^)]*\)/g, "$1");
  text = text.replace(/<[^>]*>/g, "");
  text = text
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'");
  text = text.toLowerCase();
  text = text.replace(/[^\p{L}\p{Nd}\-_\s]/gu, "");
  text = text.trim().replace(/\s+/g, "-").replace(/^-+|-+$/g, "");
  return text;
}

function uniqueSlug(slug: string, seen: Map<string, number>): string {
  const original = slug;
  while (seen.has(slug)) {
    const next = (seen.get(original) ?? 0) + 1;
    seen.set(original, next);
    slug = `${original}-${next}`;
  }
  seen.set(slug, seen.get(slug) ?? 0);
  return slug;
}

export function headingSlugs(text: string, mask: Uint8Array): string[] {
  const slugs: string[] = [];
  const seen = new Map<string, number>();
  const lines = text.split("\n");
  let offset = 0;
  for (const line of lines) {
    const matched = /^( {0,3})(#{1,6})[ \t]+(.*)$/.exec(line);
    if (matched) {
      const hashAt = offset + (matched[1] ?? "").length;
      if (!mask[hashAt]) slugs.push(uniqueSlug(slugifyHeading(matched[3] ?? ""), seen));
    }
    offset += line.length + 1;
  }
  return slugs;
}

function explicitAnchors(visible: string): string[] {
  const ids: string[] = [];
  for (const tag of visible.matchAll(/<a\b[^>]*>/gi)) {
    for (const attr of ["id", "name"]) {
      const found = new RegExp(`\\b${attr}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s"'=<>]+))`, "i").exec(tag[0]);
      const value = found?.[1] ?? found?.[2] ?? found?.[3];
      if (value) ids.push(decodeSafe(value));
    }
  }
  return ids;
}

const anchorCache = new Map<string, Set<string> | null>();

function anchorsIn(rel: string): Set<string> | null {
  const cached = anchorCache.get(rel);
  if (cached !== undefined) return cached;
  const text = readText(rel);
  if (text === null) {
    anchorCache.set(rel, null);
    return null;
  }
  const anchors = new Set(anchorsOfText(text));
  anchorCache.set(rel, anchors);
  return anchors;
}

// --- link extraction --------------------------------------------------------

type Ref = { line: number; target: string; kind: "link" | "asset" };

function isExternal(target: string): boolean {
  const trimmed = target.trim();
  return /^[a-z][a-z0-9+.-]*:/i.test(trimmed) || trimmed.startsWith("//");
}

function parseDestination(text: string, openParen: number): { target: string; end: number } | null {
  let i = openParen + 1;
  while (i < text.length && /\s/.test(text.charAt(i))) i++;
  if (i >= text.length) return null;
  let target: string;
  if (text[i] === "<") {
    const end = text.indexOf(">", i + 1);
    if (end < 0) return null;
    target = text.slice(i + 1, end);
    i = end + 1;
  } else {
    const start = i;
    let depth = 0;
    while (i < text.length) {
      const ch = text[i];
      if (ch === "\\" && i + 1 < text.length) {
        i += 2;
        continue;
      }
      if (ch === "(") depth++;
      else if (ch === ")") {
        if (depth === 0) break;
        depth--;
      } else if (/\s/.test(ch ?? "") && depth === 0) break;
      i++;
    }
    target = text.slice(start, i).replace(/\\(.)/g, "$1");
  }
  while (i < text.length && /\s/.test(text.charAt(i))) i++;
  if (text[i] === '"' || text[i] === "'") {
    const quote = text[i];
    i++;
    while (i < text.length && text[i] !== quote) {
      if (text[i] === "\\") i++;
      i++;
    }
    if (text[i] === quote) i++;
  }
  while (i < text.length && /\s/.test(text.charAt(i))) i++;
  if (text[i] !== ")") return null;
  return { target: target.trim(), end: i };
}

function matchingBracket(text: string, open: number, mask: Uint8Array): number {
  let depth = 1;
  for (let i = open + 1; i < text.length; i++) {
    if (mask[i]) continue;
    if (text[i] === "[") depth++;
    else if (text[i] === "]") {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

function markdownLinks(text: string, mask: Uint8Array, from: number, to: number, out: Ref[]): void {
  let i = from;
  while (i < to) {
    if (mask[i] || text[i] !== "[") {
      i++;
      continue;
    }
    const close = matchingBracket(text, i, mask);
    if (close < 0 || close + 1 >= text.length || text[close + 1] !== "(" || mask[close + 1]) {
      i++;
      continue;
    }
    const dest = parseDestination(text, close + 1);
    if (!dest) {
      i++;
      continue;
    }
    out.push({ line: lineAt(text, i), target: dest.target, kind: "link" });
    markdownLinks(text, mask, i + 1, close, out);
    i = dest.end + 1;
  }
}

function attrValues(tag: string, name: string): string[] {
  const values: string[] = [];
  const re = new RegExp(`\\b${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s"'=<>]+))`, "gi");
  for (const matched of tag.matchAll(re)) values.push(matched[1] ?? matched[2] ?? matched[3] ?? "");
  return values;
}

function srcsetUrls(value: string): string[] {
  return value
    .split(",")
    .map((part) => part.trim().split(/\s+/)[0] ?? "")
    .filter(Boolean);
}

function htmlRefs(visible: string, source: string): Ref[] {
  const refs: Ref[] = [];
  const push = (index: number, target: string, kind: Ref["kind"]) => {
    if (target) refs.push({ line: lineAt(source, index), target, kind });
  };
  for (const tag of visible.matchAll(/<a\b[^>]*>/gi)) {
    for (const href of attrValues(tag[0], "href")) push(tag.index ?? 0, href, "link");
  }
  for (const tag of visible.matchAll(/<img\b[^>]*>/gi)) {
    for (const src of attrValues(tag[0], "src")) push(tag.index ?? 0, src, "asset");
  }
  for (const tag of visible.matchAll(/<source\b[^>]*>/gi)) {
    for (const srcset of attrValues(tag[0], "srcset")) {
      for (const url of srcsetUrls(srcset)) push(tag.index ?? 0, url, "asset");
    }
  }
  return refs;
}

export function refsInText(text: string): Ref[] {
  const mask = maskCode(text);
  const links: Ref[] = [];
  markdownLinks(text, mask, 0, text.length, links);
  return [...links, ...htmlRefs(masked(text, mask), text)];
}

function refsIn(rel: string): Ref[] {
  const text = readText(rel);
  return text === null ? [] : refsInText(text);
}

export function anchorsOfText(text: string): string[] {
  const mask = maskCode(text);
  return [...headingSlugs(text, mask), ...explicitAnchors(masked(text, mask))];
}

function splitTarget(target: string): { path: string; fragment: string | null } {
  const hash = target.indexOf("#");
  if (hash < 0) return { path: target, fragment: null };
  const fragment = decodeSafe(target.slice(hash + 1));
  return { path: target.slice(0, hash), fragment: fragment === "" ? null : fragment };
}

function stripQuery(path: string): string {
  const query = path.indexOf("?");
  return query < 0 ? path : path.slice(0, query);
}

/** Repo-relative path, or null when the target leaves the worktree. */
function resolveRel(fromRel: string, urlPath: string): string | null {
  const decoded = decodeSafe(stripQuery(urlPath)).trim();
  if (decoded === "") return dirname(fromRel) === "." ? fromRel : dirname(fromRel);
  const rel = decoded.startsWith("/")
    ? normalize(decoded.replace(/^\/+/, ""))
    : normalize(pjoin(dirname(fromRel), decoded));
  if (rel === ".." || rel.startsWith("../") || rel.startsWith("/")) return null;
  return rel;
}

function isMarkdown(rel: string): boolean {
  return /\.markdown$/i.test(rel) || /\.md$/i.test(rel);
}

function existsInTree(rel: string): boolean {
  const path = abs(rel);
  if (!existsSync(path)) return false;
  const st = statSync(path);
  return st.isFile() || st.isDirectory();
}

// --- modes ------------------------------------------------------------------

function checkLinks(): string[] {
  const problems: string[] = [];
  const files = ["README.md"];
  const wiki = abs("docs/wiki");
  if (existsSync(wiki) && statSync(wiki).isDirectory()) {
    for (const name of readdirSync(wiki)) {
      if (name.endsWith(".md")) files.push(`docs/wiki/${name}`);
    }
  }
  for (const rel of files) {
    if (readText(rel) === null) {
      problems.push(`${rel}: missing`);
      continue;
    }
    for (const ref of refsIn(rel)) {
      const raw = ref.target.trim();
      if (raw === "" || isExternal(raw)) continue;
      const { path, fragment } = splitTarget(raw);
      const targetRel = path === "" ? rel : resolveRel(rel, path);
      if (targetRel === null) {
        problems.push(`${rel}:${ref.line}: ${raw}: resolves outside the worktree`);
        continue;
      }
      if (path !== "" && !existsInTree(targetRel)) {
        problems.push(`${rel}:${ref.line}: ${raw}: not found`);
        continue;
      }
      if (fragment === null) continue;
      if (!isMarkdown(targetRel)) continue;
      const anchors = anchorsIn(targetRel);
      if (anchors === null) {
        problems.push(`${rel}:${ref.line}: ${raw}: cannot read ${targetRel} to check #${fragment}`);
        continue;
      }
      if (!anchors.has(fragment)) {
        problems.push(`${rel}:${ref.line}: ${raw}: anchor "#${fragment}" not in ${targetRel}`);
      }
    }
  }
  return problems;
}

function wikiDirEntries(): string[] | null {
  const dir = abs("docs/wiki");
  if (!existsSync(dir) || !statSync(dir).isDirectory()) return null;
  return readdirSync(dir);
}

function breadcrumbOk(text: string): boolean {
  return linesOf(text)
    .slice(0, 5)
    .some((line) => line.includes("[README](../../README.md)") && line.includes("[Wiki Home](README.md)"));
}

function relatedOk(text: string): boolean {
  const lines = linesOf(text);
  const tail = lines.slice(Math.max(0, lines.length - 25));
  return tail.some((line) => {
    if (!/Related|Next/i.test(line)) return false;
    return /^ {0,3}#{1,6}[ \t]/.test(line) || /\*\*.+?\*\*/.test(line) || /__.+?__/.test(line);
  });
}

function pageLinked(fromRel: string, wantRel: string): boolean {
  for (const ref of refsIn(fromRel)) {
    if (ref.kind !== "link" || isExternal(ref.target)) continue;
    const { path } = splitTarget(ref.target.trim());
    if (path === "") continue;
    if (resolveRel(fromRel, path) === wantRel) return true;
  }
  return false;
}

function checkWiki(): string[] {
  const problems: string[] = [];
  const entries = wikiDirEntries();
  if (entries === null) {
    for (const name of WIKI_PAGES) problems.push(`docs/wiki/${name}: missing`);
    return problems;
  }
  const have = new Set(entries);
  for (const name of WIKI_PAGES) {
    const rel = `docs/wiki/${name}`;
    if (!have.has(name)) {
      problems.push(`${rel}: missing`);
      continue;
    }
    const text = readText(rel);
    if (text === null) {
      problems.push(`${rel}: not a file`);
      continue;
    }
    if (!breadcrumbOk(text)) {
      problems.push(`${rel}: first 5 lines lack [README](../../README.md) and [Wiki Home](README.md) on one line`);
    }
    if (!relatedOk(text)) {
      problems.push(`${rel}: last 25 lines lack a heading or bold line matching /Related|Next/i`);
    }
  }
  for (const name of entries) {
    if (!WIKI_PAGES.includes(name as (typeof WIKI_PAGES)[number])) {
      problems.push(`docs/wiki/${name}: unexpected (docs/wiki must contain exactly the 15 pages)`);
    }
  }
  const home = "docs/wiki/README.md";
  const homeText = readText(home);
  if (homeText !== null) {
    for (const name of WIKI_PAGES) {
      if (name === "README.md") continue;
      if (!pageLinked(home, `docs/wiki/${name}`)) problems.push(`${home}: does not link to ${name}`);
    }
  }
  return problems;
}

function pictureBlocks(text: string): string[] {
  return [...text.matchAll(/<picture\b[^>]*>[\s\S]*?<\/picture>/gi)].map((matched) => matched[0]);
}

function pictureAssets(block: string): string[] {
  const urls = [...attrValues(block, "src")];
  for (const srcset of attrValues(block, "srcset")) urls.push(...srcsetUrls(srcset));
  return urls;
}

function referencesBothHeroes(fromRel: string, block: string): boolean {
  const names = new Set<string>();
  for (const url of pictureAssets(block)) {
    if (isExternal(url)) continue;
    const target = resolveRel(fromRel, stripQuery(splitTarget(url).path));
    if (target) names.add(target.slice(target.lastIndexOf("/") + 1));
  }
  return names.has(HERO_DAY) && names.has(HERO_NIGHT);
}

function checkReadme(): string[] {
  const problems: string[] = [];
  const rel = "README.md";
  const text = readText(rel);
  if (text === null) return [`${rel}: missing`];
  const count = lineCount(text);
  if (count < 150 || count > 260) problems.push(`${rel}: ${count} lines, want between 150 and 260`);
  if (!text.includes("splice-launch")) problems.push(`${rel}: lacks splice-launch`);
  if (!hasTomlFence(text)) problems.push(`${rel}: no \`\`\`toml fenced block`);

  const hero = pictureBlocks(text).find((block) => referencesBothHeroes(rel, block));
  if (!hero) {
    problems.push(`${rel}: no <picture> hero referencing docs/assets/${HERO_DAY} and docs/assets/${HERO_NIGHT}`);
  } else {
    for (const url of pictureAssets(hero)) {
      if (isExternal(url)) continue;
      const target = resolveRel(rel, stripQuery(splitTarget(url).path));
      if (target === null) {
        problems.push(`${rel}: hero asset ${url} resolves outside the worktree`);
        continue;
      }
      if (!target.startsWith("docs/assets/")) problems.push(`${rel}: hero asset ${url} is not under docs/assets/`);
      if (!existsInTree(target)) problems.push(`${rel}: hero asset ${url} is missing (${target})`);
    }
  }

  for (const name of WIKI_PAGES) {
    if (!pageLinked(rel, `docs/wiki/${name}`)) problems.push(`${rel}: does not link to docs/wiki/${name}`);
  }

  const headingLine = linesOf(text).findIndex((line) => line.trim() === DISK_HEADING);
  if (headingLine >= 0) problems.push(`${rel}:${headingLine + 1}: contains heading "${DISK_HEADING}"`);
  if (text.includes("encrypted CoT")) problems.push(`${rel}: contains "encrypted CoT"`);
  if (text.includes("bin/claudex login")) problems.push(`${rel}: contains "bin/claudex login"`);
  return problems;
}

function hasTomlFence(text: string): boolean {
  let inFence = false;
  let fenceChar = "";
  let fenceLen = 0;
  for (const line of linesOf(text)) {
    const marker = fenceMarker(line);
    if (!inFence) {
      if (marker && !(marker.char === "`" && marker.info.includes("`"))) {
        inFence = true;
        fenceChar = marker.char;
        fenceLen = marker.len;
        if (marker.char === "`" && /^toml\b/.test(marker.info.trim())) return true;
      }
    } else if (marker && marker.char === fenceChar && marker.len >= fenceLen && marker.info.trim() === "") {
      inFence = false;
    }
  }
  return false;
}

function matchingParen(source: string, openIndex: number): number {
  let depth = 0;
  let i = openIndex;
  while (i < source.length) {
    if (source.startsWith("//", i)) {
      const nl = source.indexOf("\n", i);
      i = nl < 0 ? source.length : nl;
      continue;
    }
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    if (source.startsWith('"""', i)) {
      const end = source.indexOf('"""', i + 3);
      i = end < 0 ? source.length : end + 3;
      continue;
    }
    if (source[i] === '"') {
      i++;
      while (i < source.length && source[i] !== '"') {
        if (source[i] === "\\") i++;
        i++;
      }
      i++;
      continue;
    }
    if (source[i] === "(") depth++;
    else if (source[i] === ")") {
      depth--;
      if (depth === 0) return i;
    }
    i++;
  }
  return -1;
}

function unescapeKotlin(body: string): string {
  let out = "";
  for (let i = 0; i < body.length; i++) {
    if (body[i] !== "\\") {
      out += body[i];
      continue;
    }
    const next = body[++i];
    if (next === "n") out += "\n";
    else if (next === "t") out += "\t";
    else if (next === "r") out += "\r";
    else if (next === "u") {
      out += String.fromCharCode(Number.parseInt(body.slice(i + 1, i + 5), 16));
      i += 4;
    } else out += next ?? "";
  }
  return out;
}

/** String literals inside `DISCLAIMERS = listOf(...)`, or null when the list cannot be parsed. */
export function disclaimersFrom(source: string): string[] | null {
  const decl = /(?:^|\n)[^\n]*\bDISCLAIMERS\b\s*=\s*listOf\s*\(/m.exec(source);
  if (!decl) return null;
  const open = decl.index + decl[0].length - 1;
  const close = matchingParen(source, open);
  if (close < 0) return null;
  const region = source.slice(open + 1, close);
  const phrases: string[] = [];
  let i = 0;
  while (i < region.length) {
    if (region.startsWith("//", i)) {
      const nl = region.indexOf("\n", i);
      i = nl < 0 ? region.length : nl;
      continue;
    }
    if (region.startsWith("/*", i)) {
      const end = region.indexOf("*/", i + 2);
      i = end < 0 ? region.length : end + 2;
      continue;
    }
    if (region.startsWith('"""', i)) {
      const end = region.indexOf('"""', i + 3);
      if (end < 0) return null;
      const raw = region.slice(i + 3, end);
      if (/\$\{|\$[A-Za-z_]/.test(raw)) return null;
      phrases.push(raw);
      i = end + 3;
      continue;
    }
    if (region[i] === '"') {
      let j = i + 1;
      let body = "";
      while (j < region.length && region[j] !== '"') {
        if (region[j] === "\\") {
          body += region[j] + (region[j + 1] ?? "");
          j += 2;
          continue;
        }
        body += region[j];
        j++;
      }
      if (j >= region.length) return null;
      const value = unescapeKotlin(body);
      if (/\$\{|\$[A-Za-z_]/.test(value)) return null;
      phrases.push(value);
      i = j + 1;
      continue;
    }
    i++;
  }
  return phrases.length > 0 ? phrases : null;
}

function disclaimerHits(rel: string, phrases: string[]): string[] {
  const text = readText(rel);
  if (text === null) return [`${rel}: missing`];
  const problems: string[] = [];
  for (const [index, line] of linesOf(text).entries()) {
    const lower = line.toLowerCase();
    const hit = phrases.find((phrase) => lower.includes(phrase.toLowerCase()));
    if (hit) problems.push(`${rel}:${index + 1}: carries '${hit}': ${line.trim()}`);
  }
  return problems;
}

/** ConventionalType.runPattern + hasHit: backticks become spaces, then a run of 3+ types. */
export function conventionalRunPattern(types: string[]): RegExp {
  const alternation = types.map((type) => type.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|");
  return new RegExp(`(?<![A-Za-z])(?:${alternation})(?:[\\s·,|/]+(?:${alternation})){2,}(?![A-Za-z])`, "g");
}

export function vocabularyFrom(source: string): string[] | null {
  const matched = TYPES_LINE.exec(source);
  if (!matched) return null;
  const types = (matched[1] ?? "").split("|").filter((type) => type.length > 0);
  return types.length >= 3 ? types : null;
}

function conventionalHits(rel: string, pattern: RegExp): string[] {
  const text = readText(rel);
  if (text === null) return [`${rel}: missing`];
  const stripped = text.replaceAll("`", " ");
  const problems: string[] = [];
  pattern.lastIndex = 0;
  for (const hit of stripped.matchAll(pattern)) {
    const line = lineAt(stripped, hit.index ?? 0);
    const shown = hit[0].replace(/\s+/g, " ").trim();
    problems.push(`${rel}:${line}: restates 3+ conventional types ("${shown}"); the list lives once in tools/gate/src/lib/conventional.ts`);
  }
  return problems;
}

function checkDisclaimers(): string[] {
  const problems: string[] = [];
  const lawFiles = trackedMatching("ReleaseReadinessLawTest");
  if (lawFiles.length === 0) return ["ReleaseReadinessLawTest.kt: not in git ls-files"];
  const law = lawFiles.find((path) => readText(path)?.includes("DISCLAIMERS")) ?? lawFiles[0] ?? "";
  const lawText = readText(law);
  if (lawText === null) return [`${law}: missing`];
  const phrases = disclaimersFrom(lawText);
  if (phrases === null) return [`${law}: DISCLAIMERS list could not be parsed — refusing to pass`];

  const installers = trackedMatching("install.sh");
  const files = ["README.md", "CHANGELOG.md", "app/src/main/resources/splice.example.toml", ...installers];
  if (installers.length === 0) problems.push("install.sh: not in git ls-files");
  const wiki = wikiDirEntries();
  if (wiki) for (const name of wiki) if (name.endsWith(".md")) files.push(`docs/wiki/${name}`);

  for (const rel of files) problems.push(...disclaimerHits(rel, phrases));

  const typeFiles = trackedMatching("lib/conventional.ts");
  const typePath = typeFiles.find((path) => path.endsWith("tools/gate/src/lib/conventional.ts")) ?? typeFiles[0];
  if (!typePath) return [...problems, "tools/gate/src/lib/conventional.ts: not in git ls-files — refusing to pass"];
  const typeSource = readText(typePath);
  const types = typeSource === null ? null : vocabularyFrom(typeSource);
  if (types === null) {
    problems.push(`${typePath ?? "conventional.ts"}: no usable TYPES assignment — refusing to pass`);
    return problems;
  }
  const pattern = conventionalRunPattern(types);
  const prose = ["README.md"];
  if (wiki) for (const name of wiki) if (name.endsWith(".md")) prose.push(`docs/wiki/${name}`);
  for (const rel of prose) problems.push(...conventionalHits(rel, pattern));
  return problems;
}

function checkDisk(): string[] {
  const problems: string[] = [];
  const page = "docs/wiki/disk-and-privacy.md";
  const pageText = readText(page);
  if (pageText === null) problems.push(`${page}: missing`);
  else if (!linesOf(pageText).some((line) => line.trim() === DISK_HEADING)) {
    problems.push(`${page}: lacks a line "${DISK_HEADING}"`);
  }
  const law = readText(LAW_DISK);
  if (law === null) {
    problems.push(`${LAW_DISK}: missing`);
  } else {
    if (!law.includes('"docs/wiki/disk-and-privacy.md"')) {
      problems.push(`${LAW_DISK}: does not contain "docs/wiki/disk-and-privacy.md"`);
    }
    if (law.includes('= "README.md"')) problems.push(`${LAW_DISK}: contains '= "README.md"'`);
  }
  return problems;
}

const CHECKS: Record<Mode, () => string[]> = {
  links: checkLinks,
  wiki: checkWiki,
  readme: checkReadme,
  disclaimers: checkDisclaimers,
  disk: checkDisk,
};

export function main(): number {
  const asked = process.argv[2];
  const known: Mode[] = ["links", "wiki", "readme", "disclaimers", "disk"];
  if (asked !== "all" && !known.includes(asked as Mode)) {
    console.error(
      "usage: bun .campaign/checks/wiki-docs/doccheck.ts <links|wiki|readme|disclaimers|disk|all>",
    );
    return 1;
  }
  const modes: Mode[] = asked === "all" ? known : [asked as Mode];
  let failed = 0;
  for (const mode of modes) {
    let problems: string[];
    try {
      problems = CHECKS[mode]();
    } catch (err) {
      problems = [`${mode}: crashed: ${err instanceof Error ? err.message : err}`];
    }
    if (problems.length === 0) {
      console.log(`${mode}: ok`);
      continue;
    }
    failed += problems.length;
    console.log(`${mode}: ${problems.length} problem(s)`);
    for (const problem of problems) console.log(`  - ${problem}`);
  }
  return failed === 0 ? 0 : 1;
}

if (import.meta.main) process.exit(main());
