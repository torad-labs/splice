// console-own keeps to a home of its own: a home whose path would resolve into the person's own Splice or Claude Code
// is refused before anything is written there, a home is made directly under /tmp, and `down` removes nothing but a
// console-own home.
import { afterAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, rmSync, symlinkSync } from "node:fs";
import { homedir, userInfo } from "node:os";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";
import { homeAt, makeHome, PREFIX, refuseForeign, TMP } from "./home.ts";

const made: string[] = [];
afterAll(() => {
  for (const d of made) rmSync(d, { recursive: true, force: true });
});

test("a home whose folder resolves into the person's own Claude Code is refused", () => {
  const dir = mkdtempSync(join(TMP, PREFIX));
  made.push(dir);
  const link = join(dir, "home");
  symlinkSync(join(userInfo().homedir, ".claude"), link);
  expect(() => refuseForeign(homeAt(link))).toThrow(/belongs to the person's own setup/);
});

test("a home outside the temp folder is refused", () => {
  expect(() => refuseForeign(homeAt(join(homedir(), "console-own-made-up")))).toThrow(/refusing/);
});

test("a home of its own is made directly under /tmp", () => {
  const h = makeHome();
  made.push(h.root);
  expect(h.root.startsWith(`${TMP}/${PREFIX}`)).toBe(true);
  expect(existsSync(h.state)).toBe(true);
});

test("a home under the per-user temp folder is refused, and a refused home leaves no folder behind", () => {
  const before = readdirSync(TMP).filter((n) => n.startsWith(PREFIX)).length;
  expect(() => refuseForeign(homeAt(join(homedir(), "Library", "Caches", `${PREFIX}made-up`)))).toThrow(/is outside \/tmp/);
  expect(readdirSync(TMP).filter((n) => n.startsWith(PREFIX)).length).toBe(before);
});

test("down removes only a console-own home", () => {
  const other = mkdtempSync(join(TMP, "not-console-own-"));
  made.push(other);
  const r = spawnSync("bun", ["tools/console-own", "down", other], { cwd: layout().repoRoot, encoding: "utf8" });
  expect(r.status).toBe(1);
  expect(r.stderr).toContain("is not a console-own home");
  expect(existsSync(other)).toBe(true);
});
