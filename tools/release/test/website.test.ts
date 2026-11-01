// Goal (Marcos, Oct 31, 2026, through the President): "redo the website for Spllice, so it uses latest console ui ...
// instead of showing full screenshots maybe we can show actually components with fake data just to highlight features
// ... looked like a real product page ... show dont tell ... Progressive disclosure as people scroll through the website."
//
// So splice.torad.ai is one page whose product parts are the console's own markup on the console's own stylesheets,
// with made-up data, and a visitor can tell it works by opening it:
//   - every file the page loads is on the host, and no site file sits under /download, which the worker keeps for
//     releases (worker.ts);
//   - the install command the page shows is the one the host's /download page gives;
//   - the console stylesheets the page loads are the console's current ones, byte for byte, so the site keeps up with
//     the console;
//   - nothing the page offered before the redo is lost: every command, path, question, link and control in keep.json;
//   - nothing private is on it: no address, no key, no path or host of the machine that built it.
// The build is run as it is used, `bun tools/release/downloads/website/build.ts <out> --media <dir>`, and it never
// replaces a folder it did not write, and writes nothing when a file the page loads is missing.
import { describe, expect, test } from "bun:test";
import { existsSync, mkdtempSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import worker from "../downloads/worker.ts";
import { MEDIA } from "../downloads/website/build.ts";

const REPO = join(import.meta.dir, "..", "..", "..");
const BUILD = join(REPO, "tools/release/downloads/website/build.ts");
const CONSOLE = join(REPO, "app/src/main/resources/console");
const KEEP = JSON.parse(readFileSync(join(REPO, "tools/release/downloads/website/keep.json"), "utf8")) as Record<string, string[]>;

/** A media folder holding a small stand-in for each film file the page loads, minus [without]. */
function media(without: string[] = []): string {
  const dir = mkdtempSync(join(tmpdir(), "splice-site-media-"));
  for (const name of MEDIA) if (!without.includes(name)) writeFileSync(join(dir, name), `stand-in ${name}`);
  return dir;
}

function run(out: string, mediaDir: string) {
  const r = Bun.spawnSync([process.execPath, BUILD, out, "--media", mediaDir], { cwd: REPO, stderr: "pipe", stdout: "pipe" });
  return { code: r.exitCode, err: r.stderr.toString() };
}

function files(dir: string): string[] {
  return readdirSync(dir, { recursive: true, encoding: "utf8" }).filter((f) => statSync(join(dir, f)).isFile()).map((f) => f.split("\\").join("/"));
}

/** Every path on this host a page or stylesheet names: href, src, poster, srcset candidates, CSS url()s and the
 *  host's own full address. */
function rootPaths(text: string, base: string): string[] {
  const found = new Set<string>();
  const add = (url: string) => {
    if (/^(data:|https?:|mailto:|#|\/\/)/.test(url) || url === "") return;
    const path = url.startsWith("/") ? url : `${base}${url}`;
    found.add(path.split(/[?#]/)[0]!);
  };
  for (const m of text.matchAll(/\b(?:href|src|poster)="([^"]*)"/g)) add(m[1]!);
  for (const m of text.matchAll(/\bsrcset="([^"]+)"/g)) for (const c of m[1]!.split(",")) add(c.trim().split(/\s+/)[0] ?? "");
  const css = text.replace(/url\(\s*"data:[^"]*"\s*\)|url\(\s*'data:[^']*'\s*\)|url\(\s*data:[^)]*\)/g, "");
  for (const m of css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)) add(m[1]!.trim());
  for (const m of text.matchAll(/https:\/\/splice\.torad\.ai(\/[^"\s#?)]*)/g)) if (!m[1]!.startsWith("/download")) found.add(m[1]!);
  return [...found];
}

/** The page's words as a reader sees them: no scripts, styles or tags, entities decoded, quotes straightened. */
function words(html: string): string {
  return html
    .replace(/<(script|style)\b[\s\S]*?<\/\1>/g, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;|&#34;/g, '"')
    .replace(/&#39;|&apos;|&#x27;/g, "'").replace(/&amp;/g, "&")
    .replace(/[\u2018\u2019]/g, "'").replace(/[\u201C\u201D]/g, '"')
    .replace(/\s+/g, " ");
}

describe("splice.torad.ai's website", () => {
  const out = join(mkdtempSync(join(tmpdir(), "splice-site-")), "site");
  const built = run(out, media());
  const page = existsSync(join(out, "index.html")) ? readFileSync(join(out, "index.html"), "utf8") : "";

  test("the build writes the page", () => {
    expect(built.err).toBe("");
    expect(built.code).toBe(0);
    expect(page).toContain("<html");
  });

  test("every file the page and its stylesheets load is on the host", () => {
    const have = new Set(files(out).map((f) => `/${f}`));
    const missing: string[] = [];
    for (const p of rootPaths(page, "/")) if (p !== "/" && !have.has(p)) missing.push(`index.html -> ${p}`);
    for (const css of files(out).filter((f) => f.endsWith(".css"))) {
      const base = `/${css.slice(0, css.lastIndexOf("/") + 1)}`;
      for (const p of rootPaths(readFileSync(join(out, css), "utf8"), base)) if (!have.has(p)) missing.push(`${css} -> ${p}`);
    }
    expect(missing).toEqual([]);
  });

  test("no site file sits under /download, which the worker keeps for releases", () => {
    expect(files(out).filter((f) => f === "download" || f.startsWith("download/"))).toEqual([]);
  });

  test("the install command on the page is the one the host's /download page gives", async () => {
    const none = { async get() { return null; }, async head() { return null; } };
    const home = await worker.fetch(new Request("https://splice.torad.ai/download"), { RELEASES: none, ASSETS: { fetch: async () => new Response("") } });
    const command = (await home.text()).split("\n").map((l) => l.trim()).find((l) => l.startsWith("curl "));
    expect(command).toBeDefined();
    expect(words(page)).toContain(command!);
  });

  test("the page draws on the console's current stylesheets, byte for byte", () => {
    for (const name of ["tokens.css", "console.css"]) {
      expect(page).toContain(`href="/console/${name}"`);
      expect(readFileSync(join(out, "console", name), "utf8")).toBe(readFileSync(join(CONSOLE, name), "utf8"));
    }
    for (const css of files(join(out, "console")).filter((f) => f.endsWith(".css"))) {
      expect(readFileSync(join(out, "console", css), "utf8")).toBe(readFileSync(join(CONSOLE, css), "utf8"));
    }
  });

  test("every command, path, question and control the page offered before is still on it", () => {
    const text = words(page);
    const gone = [...KEEP.commands!, ...KEEP.code!, ...KEEP.questions!, ...KEEP.controls!].filter((item) => !text.includes(item));
    expect(gone).toEqual([]);
  });

  test("every link the page offered before is still on it", () => {
    const hrefs = new Set([...page.matchAll(/\bhref="([^"]*)"/g)].map((m) => m[1]!.replace(/&amp;/g, "&")));
    expect(KEEP.links!.filter((link) => !hrefs.has(link))).toEqual([]);
  });

  // A link to the page shared in a chat or a post shows the social card; those who cannot see it read what the page
  // before the redo told them it shows.
  test("a shared link shows the social card, described for those who cannot see it", () => {
    const meta = (p: string) => new RegExp(`<meta property="${p}" content="([^"]*)"`).exec(page)?.[1];
    expect(meta("og:image")).toBe("https://splice.torad.ai/landing/products/splice/assets/splice-0.4.0-og.jpg");
    expect(meta("og:image:alt")).toBe(
      "splice. The plumbing for your coding agents. Free and open source. Runs on your computer. github.com/torad-labs/splice. " +
        "Below it, five drawn Claude Code windows: claude-splice, claude-grok, claudex, claude-kimi and claude-muse.",
    );
  });

  // Browsers, bookmarks, home screens and link previews ask the host's root for its icons without reading the page, and
  // the page before the redo answered all of them.
  test("the icons asked for at the host's root are there", () => {
    const root = ["favicon.ico", "favicon.svg", "favicon-32.png", "apple-touch-icon.png", "site.webmanifest"];
    expect(root.filter((f) => !existsSync(join(out, f)))).toEqual([]);
    const png = [0x89, 0x50, 0x4e, 0x47];
    const ico = readFileSync(join(out, "favicon.ico"));
    expect([...ico.subarray(0, 6)]).toEqual([0, 0, 1, 0, 1, 0]); // an icon file holding one image
    expect([...ico.subarray(ico.readUInt32LE(18), ico.readUInt32LE(18) + 4)]).toEqual(png);
    expect([...readFileSync(join(out, "apple-touch-icon.png")).subarray(0, 4)]).toEqual(png);
    expect([...readFileSync(join(out, "favicon-32.png")).subarray(0, 4)]).toEqual(png);
    const manifest = JSON.parse(readFileSync(join(out, "site.webmanifest"), "utf8")) as { name: string; icons: Array<{ src: string }> };
    expect(manifest.name).toBe("splice");
    expect(manifest.icons.length).toBeGreaterThan(0);
    expect(manifest.icons.map((i) => i.src).filter((src) => !existsSync(join(out, src.replace(/^\//, ""))))).toEqual([]);
  });

  // The page before the redo copied only the install line. Pasting more lines at once hands the later ones to whatever
  // reads the terminal next: `splice add codex` would run before splice is on the PATH, and `claudex` would answer the
  // sign-in's prompt.
  test("a Copy button that copies the install command copies that line alone", () => {
    const copies = [...page.matchAll(/\bdata-copy="([^"]*)"/g)].map((m) => m[1]!.replace(/&#10;/g, "\n").replace(/&amp;/g, "&"));
    const install = copies.filter((c) => c.includes("install.sh"));
    expect(install.length).toBeGreaterThan(0);
    expect(install.filter((c) => c !== "curl -fsSL https://splice.torad.ai/download/install.sh | bash")).toEqual([]);
  });

  // "A newer one usually works, and when a session runs a newer one, splice doctor and the status line say so": the
  // status line drawn under that question shows what it says then, in the daemon's words.
  test("the status line drawn for a newer Claude Code says so, as the status line does", () => {
    expect(words(page)).toMatch(/Claude Code 2\.1\.\d+ is newer than the version splice \d+\.\d+\.\d+ was tested with \(2\.1\.285\)/);
  });

  // The product parts are pictures of the console with made-up data, so their Allow, Save or preset buttons do nothing
  // here. A visitor's Tab key never lands on one: every button and field on the page does something (copies, picks
  // a theme, opens the menu, plays or closes the film), switches a tab or step, or is drawn only and out of the focus
  // order.
  test("every button and field on the page does something, or is drawn only and skipped by the Tab key", () => {
    expect(page).toContain("<main");
    const acts = /\bdata-(copy|theme-choice|menu|film|film-close)\b/;
    const dead: string[] = [];
    for (const m of page.matchAll(/<(button|input|select|textarea)\b([^>]*)>/g)) {
      const [tag, kind, attrs] = [m[0], m[1]!, m[2]!];
      const type = /\btype="([^"]*)"/.exec(attrs)?.[1] ?? (kind === "input" ? "text" : "");
      if (kind === "input" && ["radio", "checkbox", "hidden"].includes(type)) continue;
      if (acts.test(attrs)) continue;
      if (/\bdata-drawn\b/.test(attrs) && /\btabindex="-1"/.test(attrs)) continue;
      dead.push(tag.length > 120 ? `${tag.slice(0, 117)}...` : tag);
    }
    expect(dead).toEqual([]);
  });

  test("nothing private is on the page or in any file it ships", () => {
    const leaks: string[] = [];
    const patterns: Array<[string, RegExp]> = [
      ["an email address", /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/],
      ["a path of the machine that built it", /file:\/\/|\/home\/|\/Users\/|\/tmp\//],
      ["a local host", /127\.0\.0\.1|localhost|0\.0\.0\.0/],
      ["a key or token", /\b(sk-[A-Za-z0-9_-]{12,}|sk-ant-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{20,}|gh[ousr]_[A-Za-z0-9]{20,}|xox[abpr]-[A-Za-z0-9-]{10,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,})/],
    ];
    for (const f of files(out).filter((f) => /\.(html|css|js|json|svg|vtt|webmanifest|txt)$/.test(f))) {
      const text = readFileSync(join(out, f), "utf8");
      for (const [what, re] of patterns) if (re.test(text)) leaks.push(`${f}: ${what} (${re.exec(text)![0]})`);
    }
    expect(leaks).toEqual([]);
  });
});

describe("the website build", () => {
  test("never replaces a folder it did not write", () => {
    const out = mkdtempSync(join(tmpdir(), "splice-site-foreign-"));
    writeFileSync(join(out, "notes.txt"), "someone's work");
    const r = run(out, media());
    expect(r.code).not.toBe(0);
    expect(r.err).toContain(out);
    expect(readFileSync(join(out, "notes.txt"), "utf8")).toBe("someone's work");
  });

  test("replaces its own earlier output", () => {
    const out = join(mkdtempSync(join(tmpdir(), "splice-site-again-")), "site");
    expect(run(out, media()).code).toBe(0);
    writeFileSync(join(out, "left-over.txt"), "from the last build");
    const again = run(out, media());
    expect(again.err).toBe("");
    expect(again.code).toBe(0);
    expect(existsSync(join(out, "left-over.txt"))).toBe(false);
    expect(existsSync(join(out, "index.html"))).toBe(true);
  });

  test("writes nothing when a file the page loads is missing, and names it", () => {
    const parent = mkdtempSync(join(tmpdir(), "splice-site-missing-"));
    const out = join(parent, "site");
    const film = MEDIA.find((m) => m.endsWith(".mp4"));
    expect(film).toBeDefined();
    const r = run(out, media([film!]));
    expect(r.code).not.toBe(0);
    expect(r.err).toContain(film!);
    expect(existsSync(out)).toBe(false);
  });

  test("the wrangler config deploys the folder the build writes", () => {
    const config = readFileSync(join(REPO, "tools/release/downloads/wrangler.jsonc"), "utf8");
    expect(config).toContain('"directory": "./site"');
    expect(config).toContain("website/build.ts");
    expect(relative(REPO, join(REPO, "tools/release/downloads/site"))).toBe("tools/release/downloads/site");
  });
});
