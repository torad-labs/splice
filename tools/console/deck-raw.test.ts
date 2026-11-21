// THE KIT STAYS ONE SOURCE: every Night Deck page's own CSS and JS in the console passes the no-raw-values check
// (deck-raw.ts), so the gate's console row holds every page to it the day the page lands. The check itself is shown
// refusing what it is for, on made-up files: a raw length, a raw colour, a redefined kit token, a literal inline style.
import { afterAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const root = layout().repoRoot;
const run = (...files: string[]) => spawnSync("bun", ["tools/console/deck-raw.ts", ...files], { cwd: root, encoding: "utf8" });
const dir = mkdtempSync(join(tmpdir(), "deck-raw-"));
afterAll(() => rmSync(dir, { recursive: true, force: true }));

test("every page's own stylesheet and script in the console holds no raw value", () => {
  const all = run();
  expect(all.stdout + all.stderr).toContain("clean");
  expect(all.status).toBe(0);
});

test("a raw length, a raw colour, a redefined kit token and a literal inline style are each named with their line", () => {
  const css = join(dir, "deck-made-up.css"), js = join(dir, "deck-made-up.js");
  writeFileSync(css, ":root { --ink: rgb(1 2 3); --made-up-own: 3rem; }\n.a { margin: 7px; }\n.b { color: #00ff00; }\n.c { gap: var(--made-up-own); }\n");
  writeFileSync(js, "const n = document.body;\nn.style.height = '9px';\nn.style.setProperty('--u', String(0.5));\n");
  const r = run(css, js);
  expect(r.status).toBe(1);
  const lines = r.stdout.trim().split("\n");
  expect(lines).toEqual([
    `${css}:1: redefines the kit token --ink | --ink: rgb(1 2 3)`,
    `${css}:2: raw value 7px | margin: 7px`,
    `${css}:3: raw colour #00ff00 | color: #00ff00`,
    `${js}:2: inline style with a literal .style.height = '9px' | n.style.height = '9px';`,
  ]);
});

test("tokens, a page's own new token and a custom property set from a figure pass", () => {
  const css = join(dir, "deck-ok.css"), js = join(dir, "deck-ok.js");
  writeFileSync(css, ":root { --ok-own: 2rem; }\n.a { gap: var(--ok-own); width: 100%; background: rgb(from var(--ink) r g b / var(--fill-a)); }\n");
  writeFileSync(js, "document.body.style.setProperty('--x', String(0.25));\n");
  const r = run(css, js);
  expect(r.stdout.trim()).toBe("clean");
  expect(r.status).toBe(0);
});

test("a file that is not there, or is a folder, is a finding of its own, never a crash", () => {
  const gone = join(dir, "deck-not-there.css");
  const r = run(gone, dir);
  expect(r.status).toBe(1);
  expect(r.stdout.trim().split("\n")).toEqual([`${gone}:0: no such file`, `${dir}:0: no such file`]);
  expect(r.stderr).not.toContain("ENOENT");
});
