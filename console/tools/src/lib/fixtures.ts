// THE ADDRESS-TO-FIXTURE TABLE, IN ONE PLACE, AND CHECKED AGAINST THE PAGES.
//
// WHY THIS MODULE EXISTS: the mapping from a console address to the fixture it captures with lived
// in three copies and drifted twice in one day, both times silently, both times producing numbers
// that looked exactly like good numbers. gate.mjs named 'demo' for turns, sessions, projects and
// logs — a name no page loads — the guarded dynamic import swallowed the failure, and a full run of
// captures came back showing live daemon data (M1-19). comp-check.mjs carried a third copy with the
// same four names plus accounts and doctor, so M1-14's table measured six addresses against live
// data and the punch list filed from it inherits that (M1-28, this row).
//
// A duplicated table drifts, so there is now exactly one, here, and every instrument imports it.
//
// AND IT IS NOT TRUSTED. The truth is what the PAGES do, so `check()` reads them and fails BY NAME
// on both directions of disagreement:
//   - the table names a fixture the address does not ship (the 'demo' class, caught statically)
//   - the address ships a fixture the table does not name (caught statically)
//   - the page, given the table's name, does not end up with its fixture loaded (caught at runtime
//     by `probe()`/`verdict()`, which ask the page rather than the source — this is the half that
//     caught accounts and doctor, whose modules are imported statically but whose accessor
//     re-checks the name and returns null for anything else)
//
// The runtime half deliberately reads the marker M1-20 is putting on every fixture-fed page root
// (`data-fixture="<file name>"`, agreed through the ledger on M1-19/M1-20). One mechanism, not two:
// while the marker is absent the same slot is filled by the page's own `sample data` chrome.
//
// Usage: bun console/tools fixtures          check the table against the pages
//        bun console/tools fixtures --json   the same, as JSON
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '../../../..');
const PAGES = join(ROOT, 'console/src/pages');

/**
 * The address→fixture mapping. `file` is the module on disk under pages/<address>/fixtures/; `name`
 * is the value the address bar must carry. They are the same string on every address today, and the
 * check below enforces that, because a page either interpolates the query value into its import path
 * (`./fixtures/${name}.ts`) or re-checks it against the file name itself.
 *
 * `mark` says whether the page prints its own `sample data` chrome — the fallback for the runtime
 * half while M1-20's marker does not exist yet. Every fixture page but teams and doctor prints it.
 */
export const FIXTURES = {
  fleet: null,
  turns: { name: 'board', file: 'board', mark: true },
  sessions: { name: 'board', file: 'board', mark: true },
  teams: { name: 'hero', file: 'hero', mark: false },
  projects: { name: 'list', file: 'list', mark: true },
  accounts: { name: 'accounts', file: 'accounts', mark: true },
  usage: { name: 'usage', file: 'usage', mark: true },
  settings: { name: 'settings', file: 'settings', mark: true },
  models: { name: 'models', file: 'models', mark: true },
  logs: { name: 'tail', file: 'tail', mark: true },
  compaction: { name: 'compaction', file: 'compaction', mark: true },
  mcp: null,
  doctor: { name: 'doctor', file: 'doctor', mark: false },
};

/** The addresses, read from the shell's own table rather than copied: an address added by a later
 *  row has to appear here without an edit, and a list in this file could not fail for one missing
 *  from itself. */
export function addresses() {
  const source = readFileSync(join(ROOT, 'console/src/app/rows.ts'), 'utf8');
  const block = source.match(/export const ADDRESSES = \[([\s\S]*?)\] as const;/);
  if (block === null) throw new Error('rows.ts: the ADDRESSES table was not found');
  return [...block[1].matchAll(/'([a-z0-9-]+)'/g)].map((match) => match[1]);
}

/** The capture URL for an address: the fixture rides in the hash query, the way the shell keeps it. */
export function urlFor(address, base = 'http://localhost:5173') {
  const fixture = FIXTURES[address];
  return `${base}/#/${address}${fixture === null || fixture === undefined ? '' : `?fixture=${fixture.name}`}`;
}

/** The fixture files an address actually ships, read off disk. `null` when it ships none. */
export function shippedFiles(address) {
  const dir = join(PAGES, address, 'fixtures');
  try {
    if (!statSync(dir).isDirectory()) return null;
    return readdirSync(dir).filter((name) => name.endsWith('.ts')).map((name) => name.replace(/\.ts$/, '')).sort();
  } catch { return null; }
}

/**
 * The table checked against the pages, by name. Returns one entry per disagreement:
 * `{ address, kind, detail }`, where kind is 'missing-file', 'unnamed-file', 'name-not-file' or
 * 'shipped-without-fixture'.
 */
export function check() {
  const problems = [];
  for (const address of addresses()) {
    const fixture = FIXTURES[address] ?? null;
    const shipped = shippedFiles(address);
    if (fixture === null) {
      if (shipped !== null) {
        problems.push({ address, kind: 'shipped-without-fixture', detail: `the page ships ${shipped.join(', ')} and the table captures it live` });
      }
      continue;
    }
    if (shipped === null) {
      problems.push({ address, kind: 'missing-file', detail: `the table names '${fixture.file}' and the page ships no fixtures directory` });
      continue;
    }
    if (!shipped.includes(fixture.file)) {
      problems.push({ address, kind: 'missing-file', detail: `the table names '${fixture.file}'; the page ships ${shipped.join(', ')}` });
    }
    if (fixture.name !== fixture.file) {
      problems.push({ address, kind: 'name-not-file', detail: `query name '${fixture.name}' but module '${fixture.file}' — a page that interpolates the name would import a file that is not there` });
    }
    const unnamed = shipped.filter((file) => file !== fixture.file);
    if (unnamed.length > 0) {
      problems.push({ address, kind: 'unnamed-file', detail: `the page also ships ${unnamed.join(', ')}, which no capture names`, soft: true });
    }
  }
  return problems;
}

/**
 * The in-page probe. Returns the fixture module's HTTP status, whether the page reveals a fixture
 * (M1-20's marker, else the page's own chrome) and which. It asks the PAGE, so a statically imported
 * module whose accessor rejects the name is caught here and not by reading source.
 */
export function probe(address, fixture) {
  return `(async () => {
    const url = '/src/pages/${address}/fixtures/${fixture.file}.ts';
    let status = 0;
    try { status = (await fetch(url)).status; } catch (e) { status = -1; }
    const root = document.querySelector('[data-fixture]');
    return JSON.stringify({
      url,
      status,
      marker: root === null ? null : root.getAttribute('data-fixture'),
      mark: document.body.innerText.toLowerCase().includes('sample data'),
    });
  })()`;
}

/** Did the fixture actually load? A capture whose fixture did not load is a FAILED capture. */
export function verdict(fixture, answer) {
  if (fixture === null) return { ok: true, note: 'live' };
  const loaded = answer.status === 200;
  const byMarker = answer.marker !== null && answer.marker !== undefined;
  const revealed = byMarker ? answer.marker === fixture.file : (fixture.mark ? answer.mark : true);
  return {
    ok: loaded && revealed,
    note: `${fixture.file}:${answer.status}`
      + (byMarker ? (revealed ? ` +marker=${answer.marker}` : ` MARKER=${answer.marker}`)
        : (fixture.mark ? (answer.mark ? ' +mark' : ' NO-MARK') : '')),
  };
}

/** The sentence a failing capture prints, in one place so both instruments say it identically. */
export const FAILURE_HEADLINE = 'captures did not load their fixture';

if (process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`) {
  const problems = check();
  const hard = problems.filter((problem) => problem.soft !== true);
  if (process.argv.includes('--json')) {
    console.log(JSON.stringify({ addresses: addresses().length, problems }, null, 1));
  } else {
    console.log(`fixture table checked against the pages: ${addresses().length} addresses, ${hard.length} failures, ${problems.length - hard.length} notes`);
    for (const problem of hard) console.error(`FAIL ${problem.address} ${problem.kind}: ${problem.detail}`);
    for (const problem of problems.filter((p) => p.soft === true)) console.log(`  note ${problem.address} ${problem.kind}: ${problem.detail}`);
  }
  process.exit(hard.length === 0 ? 0 : 1);
}
