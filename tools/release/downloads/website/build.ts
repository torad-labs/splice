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
//   /favicon.ico, /favicon.svg, /favicon-32.png, /apple-touch-icon.png, /site.webmanifest
//                                         the console's brand icons at the paths browsers ask the root for (rootFiles)
// A band writes {{name}} where the page states a number splice's own source defines (the Claude Code version it is
// tested with, how long a fresh install keeps prompts), and the build fills it in from that source (facts).
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

/** What the page states that the product itself defines, read from splice's source at each build so the page never
 *  drifts from the release it describes: the Claude Code version this splice is tested with, one newer than it for the
 *  drawn "is newer than" status line, and the days a fresh install keeps prompts and answers (they follow the history
 *  window unless someone sets their own, SpliceConfig.traceKeptDays). A band writes {{name}}. */
function facts(): Record<string, string> {
  const read = (path: string, re: RegExp) => {
    const value = re.exec(readFileSync(join(REPO, path), "utf8"))?.[1];
    if (!value) throw new Error(`${path} no longer matches ${re.source}, which the page's {{...}} values are read from`);
    return value;
  };
  const tested = read("core/src/main/kotlin/splice/core/Versions.kt", /TESTED_CLAUDE_CODE: String = "(\d+\.\d+\.\d+)"/);
  const [major, minor, patch] = tested.split(".").map(Number);
  return {
    "tested-claude-code": tested,
    "newer-claude-code": `${major}.${minor}.${patch! + 5}`,
    "history-days": read("core/src/main/kotlin/splice/core/perf/HistoryWindow.kt", /HISTORY_DEFAULT_DAYS: Int = (\d+)/),
    "share-port": read("core/src/main/kotlin/splice/core/topology/LanShareConfig.kt", /SHARE_DEFAULT_PORT: Int = (\d+)/),
  };
}

/** [html] with each {{name}} replaced by its value in [values]; a name with no value fails the build. */
function fill(html: string, values: Record<string, string>): string {
  return html.replace(/\{\{([a-z0-9-]+)\}\}/g, (_, name: string) => {
    const value = values[name];
    if (value === undefined) throw new Error(`the page names {{${name}}}, and the build has no value for it (facts in build.ts)`);
    return value;
  });
}

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
  return { html: linkConsoleSheets(fill(html, facts())), bands };
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

/** What browsers, bookmarks, home screens and link previews ask the host's root for without reading the page, as the
 *  page before this one answered: the console's own brand icons, an icon file holding the 32 px one, and a manifest
 *  naming them. */
function rootFiles(): { made: Array<[string, Uint8Array | string]>; copied: Array<[string, string]> } {
  const brand = join(CONSOLE, "brand");
  const png = readFileSync(join(brand, "favicon-32.png"));
  const side = (at: number) => { const n = png.readUInt32BE(at); return n >= 256 ? 0 : n; }; // the PNG header's width, height
  const ico = Buffer.alloc(22);
  ico.writeUInt16LE(1, 2); // an icon file
  ico.writeUInt16LE(1, 4); // holding one image
  ico.writeUInt8(side(16), 6);
  ico.writeUInt8(side(20), 7);
  ico.writeUInt16LE(1, 10); // planes
  ico.writeUInt16LE(32, 12); // bits per pixel
  ico.writeUInt32LE(png.length, 14);
  ico.writeUInt32LE(22, 18); // where the PNG starts
  const manifest = {
    name: "splice",
    short_name: "splice",
    start_url: "/",
    display: "browser",
    icons: [
      { src: "/favicon.svg", sizes: "any", type: "image/svg+xml" },
      { src: "/favicon-32.png", sizes: "32x32", type: "image/png" },
      { src: "/apple-touch-icon.png", sizes: "256x256", type: "image/png" },
    ],
  };
  return {
    made: [["/favicon.ico", Buffer.concat([ico, png])], ["/site.webmanifest", JSON.stringify(manifest, null, 2) + "\n"]],
    copied: [["/favicon.svg", join(brand, "favicon.svg")], ["/favicon-32.png", join(brand, "favicon-32.png")], ["/apple-touch-icon.png", join(brand, "splice-app-256.png")]],
  };
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
  const root = rootFiles();
  const made = new Map<string, Uint8Array | string>([["/index.html", html], ["/site.css", joined("css", bands)], ["/site.js", joined("js", bands)], ...root.made]);

  // Walk everything the page loads, and everything those stylesheets load in turn.
  const copied = new Map<string, string>(root.copied);
  const missing: string[] = [];
  const wanted = hostPaths(html, "/").map((p) => ({ p, from: "index.html" }));
  for (const [p, text] of made) if (p.endsWith(".css") && typeof text === "string") wanted.push(...hostPaths(text, "/").map((q) => ({ p: q, from: p.slice(1) })));
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
    bytes += typeof text === "string" ? Buffer.byteLength(text) : text.byteLength;
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
