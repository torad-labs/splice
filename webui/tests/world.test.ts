// THE WORLD WALL (row M1-07, red first). The console read as the released Torad plate system
// because nothing stopped a page from importing the old primitives or painting with the old
// tokens: the gap in CONTRACTS.md section 2 let every new page reach for them. This is the wall
// that makes the next such import fail the build.
//
// BOTH DENOMINATORS ARE COMPUTED, NEVER HAND-LISTED (the completeness law: a list that checks
// itself cannot fail for anything missing from itself):
//   - the OLD TOKENS are `git show main:webui/src/shared/tokens.css` minus the names section 1
//     sanctions. Section 1's names come from the token sheet's two world blocks plus the names the
//     contract prints in backticks above its "Old tokens" line - so a token the world adopts stops
//     being old the moment the contract or the sheet says so, and neither list is edited here.
//   - the OLD PRIMITIVES are what @shared/ui exports as plain components minus the ones the world
//     re-exports from its own files. Adding a world primitive therefore shrinks this set by itself.
//
// WHAT IS EXEMPT: the deletion list (CONTRACTS.md section 7). A row before M3-04 deletes nothing,
// and the old pages, the old widgets and the old features keep working until the finish row removes
// them. shared/ui/ui.css is exempt above its first world rule, and the token sheet itself is the
// one file that must still define an old token to keep those surfaces rendering.
//
// IT FAILS ON THE TREE TODAY - that is the point. The sweep row M1-08 turns it green, and every
// milestone gate runs it from then on.
import { execSync } from 'node:child_process';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const webuiRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(webuiRoot, '..');

/** Every custom property a stylesheet defines. */
function declaredNames(css: string): string[] {
  return [...css.matchAll(/--[a-z0-9-]+\s*:/g)].map((match) => match[0].replace(/\s*:$/, ''));
}

/** Every custom property a file USES, as `var(--name` with its line number. */
function usedNames(text: string): { name: string; line: number }[] {
  const found: { name: string; line: number }[] = [];
  text.split('\n').forEach((line, index) => {
    for (const match of line.matchAll(/var\((--[a-z0-9-]+)/g)) found.push({ name: match[1], line: index + 1 });
  });
  return found;
}

/**
 * The names section 1 sanctions: the two world blocks of the token sheet (they are contiguous, from
 * the dark selector to the motion section that follows the light one) plus every `--name` the
 * contract prints in backticks BEFORE it starts listing the old ones.
 */
function sectionOneNames(): Set<string> {
  const sheet = readFileSync(path.join(webuiRoot, 'src/shared/tokens.css'), 'utf8');
  const darkAt = sheet.indexOf(':root,\n:root[data-theme="dark"]');
  const motionAt = sheet.indexOf(':root[data-theme-cut]');
  const names = new Set(declaredNames(sheet.slice(darkAt === -1 ? 0 : darkAt, motionAt === -1 ? sheet.length : motionAt)));

  const contract = readFileSync(path.join(repoRoot, '.dev/web-console/CONTRACTS.md'), 'utf8');
  const start = contract.indexOf('## 1. Tokens');
  const end = contract.indexOf('## 2. Primitives');
  const section = contract.slice(start === -1 ? 0 : start, end === -1 ? contract.length : end);
  const oldAt = section.indexOf('Old tokens');
  const sanctioned = section.slice(0, oldAt === -1 ? section.length : oldAt);
  for (const match of sanctioned.matchAll(/`(--[a-z0-9-]+)`/g)) names.add(match[1]);
  return names;
}

/**
 * The base branch's token sheet — the denominator this wall's whole design rests on, so it is read
 * or the wall FAILS, never skipped.
 *
 * `main` is a local branch on a developer clone and is NOT one on a CI runner: actions/checkout
 * fetches the single PR merge ref, so `git show main:` there is `fatal: invalid object name
 * 'main'` — which is how this leg went red on 2026-09-20 with a message naming neither the wall
 * nor the cause. The candidates are tried in order and the failure, if every one misses, says what
 * was looked for and how to supply it. A wall that quietly passed when it could not read its own
 * denominator would be the completeness failure this file's header is about, one level up.
 */
const BASE_REFS = ['main', 'origin/main', 'refs/remotes/origin/main'] as const;

function baseTokenSheet(): string {
  for (const ref of BASE_REFS) {
    try {
      return execSync(`git show ${ref}:webui/src/shared/tokens.css`, {
        cwd: repoRoot,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      });
    } catch {
      // Next candidate. The throw below is what reports an exhausted list.
    }
  }
  throw new Error(
    `the world wall cannot read its denominator: none of ${BASE_REFS.join(', ')} resolves ` +
      'webui/src/shared/tokens.css. On a runner, fetch the base branch before the webui tests ' +
      '(git fetch --depth=1 origin main:refs/remotes/origin/main).',
  );
}

/** The old tokens: what main's sheet defines that section 1 does not sanction. */
function oldTokens(): Set<string> {
  const sanctioned = sectionOneNames();
  return new Set(declaredNames(baseTokenSheet()).filter((name) => !sanctioned.has(name)));
}

/** The old primitives: what @shared/ui exports minus the world's own re-exports. */
function oldPrimitives(): Set<string> {
  const barrel = readFileSync(path.join(webuiRoot, 'src/shared/ui/index.tsx'), 'utf8');
  const exported = [...barrel.matchAll(/export (?:function|const) ([A-Z][A-Za-z]*)/g)].map((match) => match[1]);
  const world = new Set([...barrel.matchAll(/export \{ ([A-Za-z]+) \} from '\.\//g)].map((match) => match[1]));
  return new Set(exported.filter((name) => !world.has(name)));
}

/** CONTRACTS.md section 7: rows before M3-04 delete nothing, so these still render the old world. */
const DELETION_LIST = [
  // M1-48 deleted the three legacy page directories this list used to exempt: pages/burn,
  // pages/auth and pages/config were unreachable — pageModuleKey checks an address's OWN directory
  // first, and usage, accounts and settings all exist — but they still SHIPPED, because the page
  // glob matches every directory and the single-file build inlines every chunk. An entry naming a
  // path that is gone is a denominator that lies about what it excludes, so the three come out.
  // M3-04 deleted widgets/head-plate, widgets/fleet-banner, features/edit-config and
  // features/refresh-auth, so their four entries came out by the same rule. unlock-mgmt stays:
  // it is live and still renders the old Field and Well.
  'src/features/unlock-mgmt',
  'src/shared/tokens.css', 'src/shared/fonts.css',
];

const isExempt = (relative: string): boolean =>
  DELETION_LIST.some((entry) => relative === entry || relative.startsWith(`${entry}/`));

function sourceFiles(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) sourceFiles(full, found);
    else if (/\.(ts|tsx|css)$/.test(entry)) found.push(full);
  }
  return found;
}

function scan(): string[] {
  const tokens = oldTokens();
  const primitives = oldPrimitives();
  const findings: string[] = [];

  for (const file of sourceFiles(path.join(webuiRoot, 'src'))) {
    const relative = path.relative(webuiRoot, file);
    if (isExempt(relative)) continue;
    const text = readFileSync(file, 'utf8');
    const lines = text.split('\n');
    // shared/ui/ui.css is exempt ABOVE its first world rule: below it is the world's own sheet, and
    // a world rule using a world token is the point of the file.
    const from = relative === 'src/shared/ui/ui.css'
      ? Math.max(0, lines.findIndex((line) => line.includes('.myx-strip')))
      : 0;

    lines.forEach((line, index) => {
      if (index < from) return;
      for (const use of usedNames(line)) {
        if (tokens.has(use.name)) findings.push(`${relative}:${index + 1} old token var(${use.name})`);
      }
      const imported = line.match(/import\s*\{([^}]*)\}\s*from\s*'@shared\/ui'/);
      if (imported !== null) {
        for (const raw of imported[1].split(',')) {
          const name = raw.trim();
          if (primitives.has(name)) findings.push(`${relative}:${index + 1} old primitive ${name}`);
        }
      }
    });
  }
  return findings.sort();
}

describe('the world wall', () => {
  test('both denominators are computed and non-empty', () => {
    const tokens = oldTokens();
    const primitives = oldPrimitives();
    console.log(`world wall: ${tokens.size} old tokens, ${primitives.size} old primitives`);

    // A zero would mean the parse broke, not that the tree is clean.
    expect(tokens.size).toBeGreaterThan(0);
    expect(primitives.size).toBeGreaterThan(0);
    // Spot checks against the sources the denominators are derived from: the plate palette and the
    // old button are exactly what this wall exists to keep out.
    expect([...tokens]).toContain('--paper-0');
    expect([...tokens]).toContain('--surface-raised');
    // And what the world sanctions must NOT be in it.
    expect([...tokens]).not.toContain('--strip-field');
    expect([...tokens]).not.toContain('--room');
    expect([...primitives]).toContain('Btn');
    expect([...primitives]).not.toContain('Strip');
    expect([...primitives]).not.toContain('StripField');
  });

  test('nothing under webui/src paints or imports the old world', () => {
    const findings = scan();
    expect(
      findings,
      `the console still reaches into the retired plate world in ${findings.length} places:\n` +
        'fix them (or, if a surface is waiting for M3-04 to delete it, add it to the deletion list in CONTRACTS.md section 7):\n' +
        findings.join('\n'),
    ).toEqual([]);
  });
});
