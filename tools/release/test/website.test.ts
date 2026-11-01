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
import { afterAll, describe, expect, test } from "bun:test";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import worker from "../downloads/worker.ts";
import { MEDIA } from "../downloads/website/build.ts";

const REPO = join(import.meta.dir, "..", "..", "..");
const BUILD = join(REPO, "tools/release/downloads/website/build.ts");
const CONSOLE = join(REPO, "app/src/main/resources/console");
const KEEP = JSON.parse(readFileSync(join(REPO, "tools/release/downloads/website/keep.json"), "utf8")) as Record<string, string[]>;

/** A fresh folder under the system's temp folder, removed when the file's tests end. */
const made: string[] = [];
function temp(prefix: string): string {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  made.push(dir);
  return dir;
}
afterAll(() => {
  for (const dir of made) rmSync(dir, { recursive: true, force: true });
});

/** A media folder holding a small stand-in for each film file the page loads, minus [without]. */
function media(without: string[] = []): string {
  const dir = temp("splice-site-media-");
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

/** The page's head as keep.json's head list writes it: `key = content` for each meta tag with a name or property (its
 *  media after the key), and `rel = href` for each link but stylesheets and preloads. */
function headEntries(html: string): string[] {
  const head = html.slice(0, html.indexOf("</head>"));
  const attr = (tag: string, a: string) => new RegExp(`\\b${a}="([^"]*)"`).exec(tag)?.[1];
  const found: string[] = [];
  for (const m of head.matchAll(/<meta\b[^>]*>/g)) {
    const key = attr(m[0], "name") ?? attr(m[0], "property");
    const media = attr(m[0], "media");
    if (key) found.push(`${key}${media ? ` ${media}` : ""} = ${attr(m[0], "content") ?? ""}`);
  }
  for (const m of head.matchAll(/<link\b[^>]*>/g)) {
    const rel = attr(m[0], "rel");
    if (rel && rel !== "stylesheet" && rel !== "preload") found.push(`${rel} = ${attr(m[0], "href") ?? ""}`);
  }
  return found;
}

/** The page cut at each band: the text from one `<section class="pg-band` to the next, keyed by the band's id. */
function bands(html: string): Map<string, string> {
  const cut = html.split(/(?=<section class="pg-band)/).slice(1);
  return new Map(cut.map((b) => [/^<section [^>]*\bid="([^"]+)"/.exec(b)?.[1] ?? "", b]));
}

describe("splice.torad.ai's website", () => {
  const out = join(temp("splice-site-"), "site");
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

  // Search engines, link previews and browsers read the head, not the page: what the live page's head told them is still
  // told, and its JSON-LD graph still names the app, the page and the company behind it.
  test("the head still tells search engines, link previews and browsers what the page before it did", () => {
    const have = headEntries(page);
    const gone = KEEP.head!.filter((e) => (e.includes(" = ") ? !have.includes(e) : !have.some((h) => h.startsWith(`${e} = `))));
    expect(gone).toEqual([]);
    const ld = /<script type="application\/ld\+json">([\s\S]*?)<\/script>/.exec(page)?.[1];
    expect(ld).toBeDefined();
    const graph = (JSON.parse(ld!) as { "@graph": Array<{ "@type": string; name?: string; url?: string }> })["@graph"];
    expect(KEEP.schema!.filter((t) => !graph.some((n) => n["@type"] === t))).toEqual([]);
    expect(graph.find((n) => n["@type"] === "SoftwareApplication")).toMatchObject({ name: "splice", url: "https://splice.torad.ai/" });
  });

  // A phone's browser bar takes the page's colour: the console's own ground in each theme, so the bar and the page meet
  // without a seam.
  test("the browser bar wears the page's own ground in each theme", () => {
    const tokens = readFileSync(join(CONSOLE, "tokens.css"), "utf8");
    const token = (name: string) => new RegExp(`--splice-${name}:\\s*(#[0-9A-Fa-f]{6})`).exec(tokens)?.[1]?.toLowerCase();
    const bar = (scheme: string) => headEntries(page).find((e) => e.startsWith(`theme-color (prefers-color-scheme: ${scheme}) = `))?.split(" = ")[1]?.toLowerCase();
    expect(bar("light")).toBe(token("paper"));
    expect(bar("dark")).toBe(token("night"));
  });

  // Links people shared to a section of the page before the redo (#faq, #teamwork ...) open at the band that took its
  // place, not at the top.
  test("a link to a section of the page before the redo opens at the band that took its place", () => {
    const took: Record<string, string> = { "same-setup": "same-setup", switch: "resume", teamwork: "teams", dashboard: "console", limits: "more", setup: "setup", faq: "questions", install: "install" };
    const cut = bands(page);
    const astray = KEEP.anchors!.filter((id) => {
      const band = took[id];
      if (!band || !cut.has(band)) return true;
      return (page.match(new RegExp(`\\bid="${id}"`, "g")) ?? []).length !== 1 || !new RegExp(`\\bid="${id}"`).test(cut.get(band)!);
    });
    expect(astray).toEqual([]);
  });

  // page.js reveals each [data-reveal] as it scrolls into view. A band with none would show everything at once and take no
  // part in the page's progressive disclosure.
  test("every band takes part in the reveal as it scrolls into view", () => {
    const cut = bands(page);
    expect(cut.size).toBeGreaterThan(0);
    expect([...cut].filter(([, b]) => !/\bdata-reveal\b/.test(b)).map(([id]) => id)).toEqual([]);
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

  // "Each release of splice is tested with one version of Claude Code": the version the page names is the one this
  // splice is pinned to (Versions.kt), wherever the page draws Claude Code's version. The status line drawn for a newer
  // one says what the daemon says then, in its words, about a version that is really newer than the pin.
  test("the page names the Claude Code version splice is tested with, and draws a newer one as newer", () => {
    const tested = /TESTED_CLAUDE_CODE: String = "([^"]+)"/.exec(readFileSync(join(REPO, "core/src/main/kotlin/splice/core/Versions.kt"), "utf8"))?.[1];
    expect(tested).toBeDefined();
    const esc = (s: string) => s.replace(/\./g, "\\.");
    const text = words(page);
    const said = new RegExp(`Claude Code (\\d+\\.\\d+\\.\\d+) is newer than the version splice \\d+\\.\\d+\\.\\d+ was tested with \\(${esc(tested!)}\\)`).exec(text);
    expect(said).not.toBeNull();
    expect(Bun.semver.order(said![1]!, tested!)).toBe(1);
    const line = tested!.split(".").slice(0, 2).join(".");
    const drawn = [...text.matchAll(new RegExp(`(?<![\\d.])${esc(line)}\\.\\d+(?!\\d)`, "g"))].map((m) => m[0]);
    expect(drawn.filter((v) => v !== tested && v !== said![1])).toEqual([]);
    expect(drawn.filter((v) => v === tested).length).toBeGreaterThan(1);
  });

  // "Where do my prompts go?" promises how long splice keeps them. Unless someone sets "Keep prompts and answers for",
  // saved prompts and answers follow the history window, which a fresh install sets to HISTORY_DEFAULT_DAYS
  // (SpliceConfig.traceKeptDays): the answer says that number, in its peek and its figure, and no other.
  test("the answer on where prompts go says how long a fresh install keeps them", () => {
    const days = /HISTORY_DEFAULT_DAYS: Int = (\d+)/.exec(readFileSync(join(REPO, "core/src/main/kotlin/splice/core/perf/HistoryWindow.kt"), "utf8"))?.[1];
    expect(days).toBeDefined();
    const answer = /<details class="qa-q" id="qa-q-prompts">[\s\S]*?<\/details>/.exec(page)?.[0] ?? "";
    expect(answer).toContain(`<b>${days}</b> days</span></summary>`);
    expect(answer).toContain(`aria-label="Kept for ${days} days`);
    expect([...words(answer).matchAll(/\b(\d+) days\b/g)].map((m) => m[1]).filter((n) => n !== days)).toEqual([]);
  });

  // Marcos, Nov 1, 2026: "one feature I want us to highlight with its own section, which is the ability to connect
  // another computer to a running splice on another computer, like we do with our mac that uses the subscriptions and
  // heads from this computer by running one command to set things up." Its band shows the machine with the logins
  // sharing them, the one command the other machine runs (its Copy button copies that line alone), and what the address
  // can add (docs/specs/lan-share.md), and the header links to it. Every `splice share` and `splice remote` the page draws
  // is a verb those commands take today, and every address it draws is on the port splice shares on.
  test("a band shows another computer running on this one's plans after one command", () => {
    const band = bands(page).get("share") ?? "";
    expect(band).toContain("data-reveal");
    const source = (path: string) => readFileSync(join(REPO, path), "utf8");
    const port = /SHARE_DEFAULT_PORT: Int = (\d+)/.exec(source("core/src/main/kotlin/splice/core/topology/LanShareConfig.kt"))?.[1];
    expect(port).toBeDefined();
    const one = `splice remote add http://tower.lan:${port}`;
    const text = words(band);
    expect(["splice share on", one, "?auth=", "?models=", "splice remote", "splice share off"].filter((s) => !text.includes(s))).toEqual([]);
    const copies = [...band.matchAll(/\bdata-copy="([^"]*)"/g)].map((m) => m[1]!.replace(/&amp;/g, "&"));
    expect(copies).toContain(one);
    expect(page).toContain('href="#share"');
    const verbs = (path: string, name: string) =>
      new RegExp(`${name} = "([^"]+)"`).exec(source(path))![1]!.split("|").map((p) => p.trim().replace(/^\[|\]$/g, "").split(/\s+/)[0]!);
    const share = verbs("app/src/main/kotlin/splice/app/cli/share/ShareCommand.kt", "SHARE_USAGE");
    const remote = verbs("app/src/main/kotlin/splice/app/cli/share/RemoteCommand.kt", "REMOTE_USAGE");
    const all = words(page);
    const unknown = [
      ...[...all.matchAll(/\bsplice share (\w+)/g)].map((m) => m[1]!).filter((v) => !share.includes(v)).map((v) => `splice share ${v}`),
      ...[...all.matchAll(/\bsplice remote (\w+)/g)].map((m) => m[1]!).filter((v) => !remote.includes(v)).map((v) => `splice remote ${v}`),
      ...[...all.matchAll(/\btower\.lan:(\d+)/g)].map((m) => m[1]!).filter((p) => p !== port).map((p) => `port ${p}`),
    ];
    expect(unknown).toEqual([]);
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
    const out = temp("splice-site-foreign-");
    writeFileSync(join(out, "notes.txt"), "someone's work");
    const r = run(out, media());
    expect(r.code).not.toBe(0);
    expect(r.err).toContain(out);
    expect(readFileSync(join(out, "notes.txt"), "utf8")).toBe("someone's work");
  });

  test("replaces its own earlier output", () => {
    const out = join(temp("splice-site-again-"), "site");
    expect(run(out, media()).code).toBe(0);
    writeFileSync(join(out, "left-over.txt"), "from the last build");
    const again = run(out, media());
    expect(again.err).toBe("");
    expect(again.code).toBe(0);
    expect(existsSync(join(out, "left-over.txt"))).toBe(false);
    expect(existsSync(join(out, "index.html"))).toBe(true);
  });

  test("writes nothing when a file the page loads is missing, and names it", () => {
    const parent = temp("splice-site-missing-");
    const out = join(parent, "site");
    const film = MEDIA.find((m) => m.endsWith(".mp4"));
    expect(film).toBeDefined();
    const r = run(out, media([film!]));
    expect(r.code).not.toBe(0);
    expect(r.err).toContain(film!);
    expect(existsSync(out)).toBe(false);
  });

  // The config says how to build the site, and deploys the folder that command writes, which git never carries.
  test("the wrangler config deploys the folder the build writes", () => {
    const config = readFileSync(join(REPO, "tools/release/downloads/wrangler.jsonc"), "utf8");
    const deploys = /"directory":\s*"([^"]+)"/.exec(config)?.[1];
    const writes = /bun tools\/release\/downloads\/website\/build\.ts (\S+)/.exec(config)?.[1];
    expect(deploys).toBeDefined();
    expect(writes).toBeDefined();
    expect(resolve(REPO, "tools/release/downloads", deploys!)).toBe(resolve(REPO, writes!));
    const ignored = readFileSync(join(REPO, ".gitignore"), "utf8").split("\n").map((l) => l.trim().replace(/^\//, "").replace(/\/$/, ""));
    expect(ignored).toContain(relative(REPO, resolve(REPO, writes!)));
  });
});
