// THE NIGHT DECK'S NO-RAW-VALUES CHECK. Every length, colour and radius a console page draws comes from the kit
// (deck.css's tokens and shared classes): a page keeps only its own rules, and none writes a raw value or redefines a kit
// token, so the kit stays one source. This ports the approved prototype kit's own checks (kit/rawcheck.ts for a page's
// CSS, kit/jscheck.ts for its JS, and check.ts's rule against redefining a kit token) into the repo's tooling.
//
//   bun tools/console/deck-raw.ts [files...]
//
// With no files it checks every page's own deck-<page>.css and deck-<page>.js in the console folder (never the kit's
// files, which hold the tokens). It prints one line per finding, `<file>:<line>: <why> | <text>`, and exits 1 when
// there is any, or prints `clean` and exits 0. A page's own new tokens, in a :root block of its CSS, may hold any value.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

export const CONSOLE = join(layout().repoRoot, "app/src/main/resources/console");
/** The kit's own files: they hold the tokens and the shell, so they are what a page is checked against, not checked. */
const KIT = new Set(["deck.css", "deck.js", "deck-lens.js", "deck-composer.js", "deck-common.js"]);

export interface Finding {
  file: string;
  line: number;
  why: string;
  text: string;
}

const lineAt = (src: string, index: number) => src.slice(0, index).split("\n").length;
/** [src] with its comments blanked, newlines kept, so every line keeps its number. */
const blank = (src: string, re: RegExp) => src.replace(re, (m) => m.replace(/[^\n]/g, " "));

/** Every custom property deck.css declares in a :root block: the kit's tokens, which no page redefines. */
export function kitTokens(css = readFileSync(join(CONSOLE, "deck.css"), "utf8")): Set<string> {
  const tokens = new Set<string>();
  eachDeclaration(blank(css, /\/\*[\s\S]*?\*\//g), (d) => {
    if (d.inRoot && d.name.startsWith("--")) tokens.add(d.name);
  });
  return tokens;
}

interface Declaration {
  name: string;
  value: string;
  index: number;
  inRoot: boolean;
}

/** Walks [css] block by block and hands every declaration (a name and its value) to [each], with whether it sits in a
 *  :root block (a token block, the theme's included). Quoted text is stepped over whole. */
function eachDeclaration(css: string, each: (d: Declaration) => void): void {
  const stack: string[] = [];
  let start = 0;
  const flush = (end: number) => {
    const text = css.slice(start, end);
    const m = /^(\s*)(-{0,2}[A-Za-z_][\w-]*)\s*:\s*([\s\S]*)$/.exec(text);
    if (m && stack.length) {
      each({ name: m[2]!, value: m[3]!.trim(), index: start + m[1]!.length, inRoot: stack.some((s) => /^:root\b/.test(s)) });
    }
  };
  for (let i = 0; i < css.length; i++) {
    const c = css[i];
    if (c === '"' || c === "'") {
      const close = css.indexOf(c, i + 1);
      i = close < 0 ? css.length : close;
    } else if (c === "{") {
      stack.push(css.slice(start, i).trim());
      start = i + 1;
    } else if (c === "}") {
      flush(i);
      stack.pop();
      start = i + 1;
    } else if (c === ";") {
      flush(i);
      start = i + 1;
    }
  }
}

// rawcheck.ts's numbers: a figure, with or without its unit, other than the few the kit allows bare
const NUMBER = /(?<![\w-])-?\d*\.?\d+(%|rem|px|em|ms|s|deg|fr|vh|vw)?(?![\w-])/g;
const ALLOWED = new Set(["0", "1", "-1", "50%", "100%", "1fr"]);
const HEX = /#[0-9a-fA-F]{3,8}\b/g;
// a colour function of literal channels (rgb(from var(--c) r g b) derives from a token, and is allowed)
const COLOUR_FN = /\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\((?!\s*from\b)/g;

/** A page's CSS: raw lengths, figures and colours in its rules, and kit tokens its :root redefines. */
export function checkCss(file: string, src: string, tokens = kitTokens()): Finding[] {
  const out: Finding[] = [];
  const css = blank(src, /\/\*[\s\S]*?\*\//g);
  eachDeclaration(css, (d) => {
    const line = lineAt(css, d.index);
    const text = `${d.name}: ${d.value}`.replace(/\s+/g, " ").slice(0, 110);
    if (d.inRoot) {
      if (tokens.has(d.name)) out.push({ file, line, why: `redefines the kit token ${d.name}`, text });
      return;
    }
    const value = d.value.replace(/url\([^)]*\)/g, "url()").replace(/"[^"]*"|'[^']*'/g, '""');
    const hex = value.match(HEX) || [];
    const fns = (value.match(COLOUR_FN) || []).map((f) => f.slice(0, -1));
    const numbers = (value.replace(HEX, "#").match(NUMBER) || []).filter((n) => !ALLOWED.has(n));
    if (hex.length || fns.length) out.push({ file, line, why: `raw colour ${[...hex, ...fns].join(" ")}`, text });
    if (numbers.length) out.push({ file, line, why: `raw value ${numbers.join(" ")}`, text });
  });
  return out;
}

// jscheck.ts's rules: page JS may set a custom property (--u, --x) or read a token, never write a literal style
const JS_RULES: [RegExp, string][] = [
  [/\.style\.(?!setProperty\(\s*['"`]--)[a-zA-Z]+\s*=\s*['"`][^'"`]*\d[^'"`]*['"`]/, "inline style with a literal"],
  [/\.style\.setProperty\(\s*['"`](?!--)[\w-]+['"`]\s*,\s*['"`][^'"`]*\d[^'"`]*['"`]/, "inline style with a literal"],
  [/\.style\.cssText\s*=/, "cssText"],
  [/style=\\?["'][^"']*\d(px|rem|em|%)/, "style attribute with a literal"],
  [/setAttribute\(\s*['"](r|rx|ry|stroke-width|fill|stroke|color|width|height)['"]\s*,\s*['"`]?[#\d][^'"`),]*/, "SVG attribute with a literal"],
  [/(?<![\w-])(r|rx|stroke-width)="\d[^"]*"/, "SVG radius or stroke in markup"],
  [/#[0-9a-fA-F]{3,8}\b(?![\w-])/, "hex colour"],
  [/\brgba?\(\s*\d[^)]*\)?/, "rgb colour"],
];

/** A page's JS: literal lengths, colours and radii written into styles or markup. Icon glyphs (a 24-unit viewBox) are
 *  drawing geometry, like a font, and are exempt. */
export function checkJs(file: string, src: string): Finding[] {
  const out: Finding[] = [];
  const js = blank(blank(src, /\/\*[\s\S]*?\*\//g), /^\s*\/\/.*$/gm);
  js.split("\n").forEach((line, i) => {
    if (/viewBox="0 0 24 24"/.test(line)) return;
    for (const [re, why] of JS_RULES) {
      const m = re.exec(line);
      if (m) out.push({ file, line: i + 1, why: `${why} ${m[0]}`, text: line.trim().slice(0, 110) });
    }
  });
  return out;
}

/** Every page's own stylesheet and script in the console folder: deck-<page>.css and deck-<page>.js. */
export function pageFiles(dir = CONSOLE): string[] {
  return readdirSync(dir).filter((f) => /^deck-[a-z0-9-]+\.(css|js)$/.test(f) && !KIT.has(f)).sort().map((f) => join(dir, f));
}

export function check(files: string[]): Finding[] {
  // the kit's tokens are what a page is checked against: without deck.css there is nothing to check by, and that is the
  // one finding, said as one line like every other
  const kit = join(CONSOLE, "deck.css");
  if (!existsSync(kit)) return [{ file: kit, line: 0, why: "no kit stylesheet to check the pages against", text: "" }];
  const tokens = kitTokens();
  return files.flatMap((f) => {
    // one line per problem is the whole contract: a file that is not there is a finding, never a stack
    if (!existsSync(f) || !statSync(f).isFile()) return [{ file: f, line: 0, why: "no such file", text: "" }];
    const src = readFileSync(f, "utf8");
    if (f.endsWith(".css")) return checkCss(f, src, tokens);
    if (f.endsWith(".js")) return checkJs(f, src);
    return [{ file: f, line: 0, why: "neither a stylesheet nor a script", text: basename(f) }];
  });
}

if (import.meta.main) {
  const args = process.argv.slice(2);
  const found: Finding[] = !args.length && !existsSync(CONSOLE) ? [{ file: CONSOLE, line: 0, why: "no console folder", text: "" }] : check(args.length ? args : pageFiles());
  for (const f of found) console.log(`${f.file}:${f.line}: ${f.why}${f.text ? ` | ${f.text}` : ""}`);
  if (found.length) process.exit(1);
  console.log("clean");
}
