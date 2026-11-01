// splice.torad.ai's website: one page whose product parts are the console's own markup, on the console's own
// stylesheets, with made-up data (Marcos, Oct 31, 2026: "redo the website for Spllice, so it uses latest console ui ...
// show actually components with fake data"). The worker beside this folder serves what this writes, at every path but
// /download (../worker.ts).
//
//   bun tools/release/downloads/website/build.ts <out> --media <dir>
//
// page.html is the page. A line `<!-- band NAME -->` in it is replaced by bands/NAME.html; site.css is page.css followed
// by each band's bands/NAME.css that exists, in the page's order, and site.js is page.js and each bands/NAME.js the same
// way. README.md says how a band is written. Every path the page names on this host comes from one place:
//   /console/...                          the console's own files (app/src/main/resources/console), copied as they are,
//                                         so the page always wears the console's current look
//   /landing/products/splice/assets/...   the film, its captions and the social card, from --media; they are too large
//                                         for the repository and live in the torad repository's held assets
//                                         (apps/website/build/held/products/splice/assets), at the paths the page before
//                                         this one used, so links to them keep working
//   /site.css, /site.js                   built here
// A path the page or a stylesheet names that none of them has fails the build before anything is written, so no
// broken page goes up. <out> is replaced only when this build (or the torad build it replaces) wrote it.
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, posix, resolve } from "node:path";

/** The files the page loads from --media. */
export const MEDIA: readonly string[] = ["splice-0.4.0.mp4", "splice-0.4.0.en.vtt", "splice-0.4.0-og.jpg"];

const HERE = import.meta.dir;
const REPO = resolve(HERE, "../../../..");
const CONSOLE = join(REPO, "app/src/main/resources/console");
const MEDIA_PATH = "/landing/products/splice/assets/";
const HOST = "https://splice.torad.ai";
// <out> carries this file, so a rebuild replaces only a folder a build wrote. Wrangler reads it as the list of files not
// to upload, and it names itself.
const MARK = ".assetsignore";
const MARK_TEXT = "# Built by tools/release/downloads/website/build.ts in torad-labs/splice. A rebuild replaces this folder.\n.assetsignore\n";
const OLD_MARK_TEXT = "# Built by apps/website/build/build-splice-site.py in torad-labs/torad. A rebuild replaces this folder.\n.assetsignore\n";

/** The page with its bands in place, and the names of the bands in the page's order. With [only], the other bands'
 *  lines are dropped, so one band can be looked at on the page while the rest are still being built. */
function page(only?: readonly string[]): { html: string; bands: string[] } {
  const bands: string[] = [];
  const html = readFileSync(join(HERE, "page.html"), "utf8").replace(/^[ \t]*<!-- band ([a-z0-9-]+) -->[ \t]*\n?/gm, (_, name: string) => {
    if (only && !only.includes(name)) return "";
    const file = join(HERE, "bands", `${name}.html`);
    if (!existsSync(file)) throw new Error(`page.html names band ${name}, and bands/${name}.html does not exist`);
    bands.push(name);
    return readFileSync(file, "utf8").trimEnd() + "\n";
  });
  return { html: linkConsoleSheets(html), bands };
}

/** A band names the console page stylesheets its parts need on a line `<!-- console: accounts.css teams.css -->`.
 *  Those not already linked are linked once each where page.html's `<!-- console sheets -->` line stands, after the
 *  console's own tokens.css and console.css, so the page's head lists every sheet without a band editing it. */
function linkConsoleSheets(html: string): string {
  const linked = new Set([...html.matchAll(/href="\/console\/([a-z0-9-]+\.css)"/g)].map((m) => m[1]!));
  const wanted: string[] = [];
  const body = html.replace(/<!-- console: ([a-z0-9.\s-]+?) -->\n?/g, (_, names: string) => {
    for (const name of names.split(/\s+/).filter(Boolean)) if (!linked.has(name) && !wanted.includes(name)) wanted.push(name);
    return "";
  });
  const marker = /^([ \t]*)<!-- console sheets -->[ \t]*$/m;
  if (!marker.test(body)) {
    if (wanted.length > 0) throw new Error(`bands need ${wanted.join(", ")}, and page.html has no <!-- console sheets --> line to link them at`);
    return body;
  }
  return body.replace(marker, (_, indent: string) => wanted.map((n) => `${indent}<link rel="stylesheet" href="/console/${n}">`).join("\n"));
}

/** page.EXT followed by each band's bands/NAME.EXT that exists, in the page's order. */
function joined(ext: "css" | "js", bands: string[]): string {
  const comment = (s: string) => (ext === "css" ? `/* ${s} */` : `// ${s}`);
  const parts = [readFileSync(join(HERE, `page.${ext}`), "utf8")];
  for (const name of bands) {
    const file = join(HERE, "bands", `${name}.${ext}`);
    if (existsSync(file)) parts.push(`${comment(`band: ${name}`)}\n${readFileSync(file, "utf8")}`);
  }
  return parts.join("\n");
}

/** Every path on this host [text] names, resolved against [base] (a folder path ending in "/"). */
export function hostPaths(text: string, base: string): string[] {
  const found = new Set<string>();
  const add = (url: string) => {
    const u = url.trim();
    if (u === "" || /^(data:|https?:|mailto:|tel:|#|\/\/)/.test(u)) return;
    found.add(posix.normalize(u.startsWith("/") ? u : base + u).split(/[?#]/)[0]!);
  };
  for (const m of text.matchAll(/\b(?:href|src|poster)="([^"]*)"/g)) add(m[1]!);
  for (const m of text.matchAll(/\bsrcset="([^"]+)"/g)) for (const c of m[1]!.split(",")) add(c.trim().split(/\s+/)[0] ?? "");
  // A data: URL carries its own content, and may hold url()s of its own (console.css's grain is an SVG whose filter is
  // url(#n)); they name nothing on this host.
  const css = text.replace(/url\(\s*"data:[^"]*"\s*\)|url\(\s*'data:[^']*'\s*\)|url\(\s*data:[^)]*\)/g, "");
  for (const m of css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)) add(m[1]!);
  for (const m of text.matchAll(/https:\/\/splice\.torad\.ai(\/[^"\s#?)]*)/g)) if (!m[1]!.startsWith("/download")) add(m[1]!);
  found.delete("/");
  return [...found].sort();
}

/** Where a path on this host comes from, or null when nothing has it. */
function source(path: string, media: string): string | null {
  const file = (p: string) => (existsSync(p) && statSync(p).isFile() ? p : null);
  if (path.startsWith("/console/")) return file(join(CONSOLE, path.slice("/console/".length)));
  if (path.startsWith(MEDIA_PATH)) {
    const name = path.slice(MEDIA_PATH.length);
    return MEDIA.includes(name) ? file(join(media, name)) : null;
  }
  return null;
}

export function build(out: string, media: string, only?: readonly string[]): { files: number; bytes: number } {
  const { html, bands } = page(only);
  const made = new Map<string, string>([["/index.html", html], ["/site.css", joined("css", bands)], ["/site.js", joined("js", bands)]]);

  // Walk everything the page loads, and everything those stylesheets load in turn.
  const copied = new Map<string, string>();
  const missing: string[] = [];
  const wanted = hostPaths(html, "/").map((p) => ({ p, from: "index.html" }));
  for (const [p, text] of made) if (p.endsWith(".css")) wanted.push(...hostPaths(text, "/").map((q) => ({ p: q, from: p.slice(1) })));
  while (wanted.length > 0) {
    const { p, from } = wanted.pop()!;
    if (made.has(p) || copied.has(p)) continue;
    const found = source(p, media);
    if (!found) {
      missing.push(`${p} (named by ${from})`);
      continue;
    }
    copied.set(p, found);
    if (p.endsWith(".css")) wanted.push(...hostPaths(readFileSync(found, "utf8"), posix.dirname(p) + "/").map((q) => ({ p: q, from: p.slice(1) })));
  }
  for (const p of [...made.keys(), ...copied.keys()]) if (p === "/download" || p.startsWith("/download/")) missing.push(`${p} (a site file under /download, which the worker keeps for releases)`);
  if (missing.length > 0) throw new Error(`the page loads files the site does not have: ${missing.sort().join(", ")}`);

  if (existsSync(out) && readdirSync(out).length > 0) {
    const mark = join(out, MARK);
    const text = existsSync(mark) ? readFileSync(mark, "utf8") : "";
    if (text !== MARK_TEXT && text !== OLD_MARK_TEXT) throw new Error(`${out} holds files this build did not write; refusing to replace it`);
    rmSync(out, { recursive: true });
  }
  mkdirSync(out, { recursive: true });
  writeFileSync(join(out, MARK), MARK_TEXT);
  let bytes = 0;
  for (const [p, text] of made) {
    mkdirSync(dirname(join(out, p)), { recursive: true });
    writeFileSync(join(out, p), text);
    bytes += Buffer.byteLength(text);
  }
  for (const [p, from] of copied) {
    mkdirSync(dirname(join(out, p)), { recursive: true });
    cpSync(from, join(out, p));
    bytes += statSync(from).size;
  }
  return { files: made.size + copied.size, bytes };
}

if (import.meta.main) {
  const args = process.argv.slice(2);
  const flag = (name: string) => {
    const at = args.indexOf(name);
    if (at < 0) return undefined;
    const value = args[at + 1];
    args.splice(at, 2);
    return value;
  };
  const media = flag("--media");
  const only = flag("--bands")?.split(",").filter(Boolean);
  if (args.length !== 1 || !media) {
    console.error(
      "usage: bun tools/release/downloads/website/build.ts <out> --media <dir holding " + MEDIA.join(", ") + "> [--bands a,b]\n" +
        "  --bands builds the page with only those bands, to look at them while the others are being built",
    );
    process.exit(2);
  }
  try {
    const out = resolve(args[0]!);
    const { files, bytes } = build(out, resolve(media), only);
    console.log(`built ${HOST}/ into ${out}: ${files} files, ${(bytes / 1e6).toFixed(1)} MB`);
  } catch (e) {
    console.error(`website build: ${(e as Error).message}`);
    process.exit(1);
  }
}
