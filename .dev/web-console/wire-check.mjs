#!/usr/bin/env node
// wire-check — every field the console DECLARES, against what the daemon actually EMITS (M1-37).
//
// WHY THIS EXISTS. `webui/src/entities/model/model/types.ts` declared `context_window_source` and
// the control route shipped `window_source`; the column rendered `undefined` against a route that
// was already built. Nobody caught it for a reason that is mechanical rather than careless: the
// fixture files spell it the CONSOLE's way, so the fixtures confirm the console's belief about the
// wire and the daemon is never consulted. Two hand-authored lists agreeing with each other and
// disagreeing with reality — and every capture, coverage number, ink measurement and comp-diff
// this campaign has produced came off fixture-fed renders, so not one of them could have caught it.
//
// THERE IS A THIRD LIST, found while building this, and it is why the check is shaped the way it
// is. `webui/src/**/coverage.ts` disposes of each route the console calls, and it is hand-authored
// too. It calls `/api/alerts` and `/api/budgets` EDITABLE and `/api/accounts` READ-ONLY — live, in
// other words — and the control server serves none of the three. So the comparison cannot start at
// the field: a field on a route that does not exist is not a spelling problem, and reporting it as
// one buries the missing routes under three hundred field rows.
//
//   LEVEL 1, ROUTES.  every `request<T>(path)` in `webui/src/entities/*/api/**` against every path
//                     the control server actually serves.
//   LEVEL 2, FIELDS.  for a payload on a route that IS served, every field the console declares
//                     against every key the daemon emits.
//   LEVEL 3, OPTIONALITY. the column that explains why the whole class was invisible.
//
// The dispositions, and a field in NONE of them is a failure by name — absence is not one:
//
//   MATCHES          the daemon emits a key of that exact name
//   MISMATCHED       the daemon emits a NEAR SPELLING and not this one — the context_window_source
//                    class, and the only disposition that is a defect on sight
//   ABSENT           the route this payload comes from is not built, and the row that will build
//                    it is named BY THE CONSOLE'S OWN coverage.ts. A route with no disposition
//                    there, or one disposed as live while the daemon does not serve it, FAILS.
//   CONSOLE-INTERNAL the type is not reachable from any `request<T>`, so it is not a claim about
//                    the wire at all. DERIVED from the api segments, never a hand list — the hand
//                    list is the defect this row exists to end.
//   UNDISPOSITIONED  anything left. Loud, by name, non-zero.
//
// LEVEL 3, and it is the reason the whole class was invisible. `context_window_source: string` is
// declared NON-OPTIONAL, so TypeScript positively asserts a field that is not there. No type error
// fires anywhere and the column does not fail loudly — it renders `undefined`. A field the console
// swears is present that the daemon may omit is a silent undefined BY CONSTRUCTION. Note that
// `x: T | null` is NOT protection: it claims the key is ALWAYS THERE and may hold null, so a wire
// that omits the key gives `undefined`, and `=== null` is false for it. Only `x?: T` is honest.
//
// LAW 23 BINDS THIS HARD, and this is the row where it matters most: a wire check that passes
// because it read nothing is the exact thing it was built to catch. A missing gateway tree, a
// types file that parses to zero fields, a parser that will not load, a control tree with no keys
// or no routes, zero fetch sites — each is DID NOT RUN, each exits non-zero, none is ever clean.
//
// SCOPE. This is the STATIC half and it takes no daemon, which is what lets it sit in the exit
// gate. The LIVE half is V4-140 (claude-splice-main): boot a daemon, fetch each read route under
// the bearer, assert every key these same types declare is present in the payload. Neither
// substitutes for the other — static catches a spelling before anything boots, live catches a
// route that stopped sending a key it used to send. Deliberately NO fetch here.
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. CLEAN BOTH.
//   SHAPE ONE, does every FAIL reach the exit code? YES, and the interesting part is that some
//     FAILs are MEANT not to. M1-45 narrowed the exit rule to badRoutes + MISMATCHED +
//     UNDISPOSITIONED on the ruling that a census which fails the build teaches people to stop
//     running it, and the Level 3b census prints loudly while gating nothing — DELIBERATELY, with
//     the ruling written out and the summary line naming the non-gating count so no reader mistakes
//     it for silence. That is a disposition, not a hole: the file says which of its output gates and
//     why. Everything the exit rule does cover reaches `process.exitCode = failed ? 1 : 0`
//     (exitCode and not exit(), because --json writes more than a pipe buffer holds).
//   SHAPE TWO, if every tree were unreadable, what would it print? IT REFUSES FIVE TIMES OVER, and
//     did before this row: :102 no entity directories, :259 no .kt under a main source set, :260 no
//     control/src/main tree, :452 no entity declares model/types.ts, :465 no fields parsed out of
//     any types file. Each is a fail() by name, not a zero quietly summarised. This is the file the
//     rest of the fence should have been written like — it is the only one that guarded EVERY
//     denominator rather than the one its author happened to think of.
//
// Usage: node .dev/web-console/wire-check.mjs            (from the worktree root)
//        node .dev/web-console/wire-check.mjs --json
//        node .dev/web-console/wire-check.mjs --selftest  mutation-proof, both directions
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import process from 'node:process';

const ROOT = process.cwd();
const ENTITIES = join(ROOT, 'webui/src/entities');
const GATEWAY = join(ROOT, 'gateway');
const CONTROL = '/control/src/main/';

function fail(message) {
  console.error(`wire-check: DID NOT RUN — ${message}`);
  process.exit(1);
}

/** Every file under a directory, recursively. */
function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

/**
 * The TypeScript parser, or DID NOT RUN. A regex over an interface body is what produced the
 * hand-written lists this row exists to replace, so the parser is the compiler's own — and a
 * parser that will not load is a failure, never a reason to fall back to something weaker.
 */
async function parser() {
  try {
    return (await import('typescript')).default;
  } catch (e) {
    fail(`cannot load the typescript parser: ${e.message}\n` +
      '       it resolves from the worktree root; run this from there, not from webui/');
  }
  return null;
}

// ---------------------------------------------------------------- the console side

/** Every entity directory, and where its types and api segment would be. From the tree. */
function entityDirs() {
  if (!existsSync(ENTITIES)) fail(`no ${relative(ROOT, ENTITIES)} — this is not the worktree root`);
  const dirs = readdirSync(ENTITIES).filter((name) => statSync(join(ENTITIES, name)).isDirectory());
  if (dirs.length === 0) fail(`${relative(ROOT, ENTITIES)} holds no entity directories`);
  return dirs.map((name) => ({
    name,
    types: join(ENTITIES, name, 'model/types.ts'),
    api: join(ENTITIES, name, 'api'),
  }));
}

/**
 * Every type a types file declares and every property signature in it, plus the named types each
 * declaration references so a payload can be walked from its root.
 *
 * `optional` is the `?`. `nullable` is `| null` / `| undefined`. They are different claims and
 * level 3 needs both: `x?: T` says the key may be missing, `x: T | null` says the key is ALWAYS
 * THERE and may hold null, and only the first is honest about a wire that omits.
 */
function declarationsOf(ts, file) {
  const text = readFileSync(file, 'utf8');
  const src = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  const decls = new Map();
  const line = (node) => src.getLineAndCharacterOfPosition(node.getStart(src)).line + 1;

  const members = (node, owner) => {
    const fields = [];
    for (const member of node.members ?? []) {
      if (!ts.isPropertySignature(member) || member.name === undefined) continue;
      const name = member.name.getText(src).replace(/^['"]|['"]$/g, '');
      const annotation = member.type === undefined ? '' : member.type.getText(src).replace(/\s+/g, ' ');
      let inner = member.type;
      if (inner !== undefined && ts.isArrayTypeNode(inner)) inner = inner.elementType;
      let nested = null;
      if (inner !== undefined && ts.isTypeLiteralNode(inner)) {
        nested = `${owner}.${name}`;
        members(inner, nested);
      }
      fields.push({
        owner,
        name,
        optional: member.questionToken !== undefined,
        nullable: /(^|\|)\s*(null|undefined)\s*($|\|)/.test(annotation),
        type: annotation,
        // The console disposes some fields itself, in the field's own doc comment: `PENDING V4-130.
        // Absent until the daemon resolves it.` That is a row name in the source, which is exactly
        // the disposition this check asks for, so it is READ rather than duplicated in a table here.
        pending: (text.slice(member.pos, member.getStart(src)).match(/\bPENDING\s+((?:V4|WD|M\d)-\d+)/) ?? [])[1] ?? null,
        // every capitalised identifier in the annotation, so the payload graph can be walked
        refs: [...new Set(annotation.match(/\b[A-Z][A-Za-z0-9_]*\b/g) ?? [])].concat(nested ?? []),
        at: `${relative(ROOT, file)}:${line(member)}`,
      });
    }
    decls.set(owner, { fields, at: `${relative(ROOT, file)}:${line(node)}` });
  };

  const visit = (node) => {
    if (ts.isInterfaceDeclaration(node)) members(node, node.name.text);
    else if (ts.isTypeAliasDeclaration(node) && ts.isTypeLiteralNode(node.type)) members(node.type, node.name.text);
    ts.forEachChild(node, visit);
  };
  visit(src);
  return decls;
}

/**
 * Every route the console fetches and the payload type it expects back, read from the api
 * segments. `${…}` becomes `{}` so a path with a parameter compares against a ktor one.
 *
 * This IS the wire-facing set, and deriving it here is the whole point: a hand-written "these are
 * the wire types" list would be the FOURTH hand-written list, and it would rot the way the other
 * three did. A type nobody fetches is not a claim about the daemon.
 */
function fetchSites(dirs) {
  const sites = [];
  for (const dir of dirs) {
    if (!existsSync(dir.api)) continue;
    for (const file of walk(dir.api).filter((p) => p.endsWith('.ts'))) {
      const rel = relative(ROOT, file);
      readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
        for (const m of line.matchAll(/request<\s*([A-Za-z0-9_]+)(\[\])?\s*>\s*\(\s*[`'"]([^`'"]*)/g)) {
          sites.push({
            entity: dir.name,
            type: m[1],
            // `${query}` glued to the end of a path is a query string, not a segment; `/teams/${id}`
            // IS a segment. The difference is the slash before it.
            path: m[3].replace(/\$\{[^}]*\}/g, '{}').split('?')[0].replace(/([^/])\{\}$/, '$1').replace(/\/$/, ''),
            at: `${rel}:${i + 1}`,
          });
        }
      });
    }
  }
  if (sites.length === 0) {
    fail('no `request<T>(path)` site found in any entity api segment — the console side of the\n' +
      '       wire could not be enumerated, so every field would compare against nothing');
  }
  return sites;
}

/**
 * The console's own route dispositions, from `coverage.ts`. This is the THIRD hand-authored list
 * and the check reads it as a CLAIM to be tested, never as truth: a route disposed live that the
 * daemon does not serve is a failure, and so is a fetch with no disposition at all.
 */
function coverageRoutes() {
  const out = new Map();
  for (const root of [join(ROOT, 'webui/src/pages'), join(ROOT, 'webui/src/shared')]) {
    if (!existsSync(root)) continue;
    for (const file of walk(root).filter((p) => /coverage\.ts$|baseline\.ts$/.test(p))) {
      const rel = relative(ROOT, file);
      readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
        const m = line.match(/kind:\s*'route',\s*name:\s*'([^']*)',\s*disposition:\s*'([^']*)'(?:,\s*where:\s*'([^']*)')?/);
        if (m !== null) {
          out.set(m[1].replace(/\{[^}]*\}/g, '{}').replace(/\/$/, ''),
            { disposition: m[2], where: m[3] ?? null, at: `${rel}:${i + 1}` });
        }
      });
    }
  }
  return out;
}

// ---------------------------------------------------------------- the daemon side

/**
 * Every path the control server serves, and every JSON key it emits.
 *
 * SCOPE, stated because getting it wrong in either direction breaks this check specifically. The
 * console reads the CONTROL API, so the emitting sites are the control main source set and nothing
 * else — widening to all of gateway made `DoctorReportShape.kt`, a CLI report nobody fetches,
 * offer near spellings for console fields. But the CONSTANTS those sites emit through live
 * elsewhere (`core/perf/PerfKeys.kt`), so the constant table is read from every gateway main source
 * and a holder's vocabulary counts only when a control file names that holder. Test sources are
 * excluded throughout: a key that exists only in a test fixture is not a key the daemon sends, and
 * including them would be this row's own defect committed by its own instrument.
 *
 * The four emission shapes:
 *   put("k", v)    a literal key, read only in files that build JSON — `put()` is also how this
 *                  codebase fills an environment map, and `CLAUDE_CONFIG_DIR` from LaunchService.kt
 *                  is not a wire key
 *   put(KEY, v)    a key held in `const val KEY: String = "k"`, which is how the whole perf
 *                  vocabulary is emitted. Scanning only for literals made 132 fields look absent
 *                  that the daemon sends on every turn (measured while building this; `stall_ms`
 *                  lives in core/perf/PerfKeys.kt:93)
 *   @Serializable  a kotlinx property; `@SerialName("w")` renames it and a default (`= null`) means
 *                  encodeDefaults drops it
 *   put(expr, v)   a key computed at runtime. It CANNOT be enumerated statically, so it is COUNTED
 *                  AND REPORTED rather than quietly treated as nothing.
 *
 * A key is CONDITIONAL when any ONE of its sites can omit it: the console has to survive the worst
 * site, not the average one.
 */
function daemon() {
  if (!existsSync(GATEWAY)) {
    fail(`no ${relative(ROOT, GATEWAY)} — the daemon is what this check reads;\n` +
      '       without it every field would compare against nothing and report clean');
  }
  const all = walk(GATEWAY).filter((p) => p.endsWith('.kt') && p.includes('/src/main/'));
  const control = all.filter((p) => p.includes(CONTROL));
  if (all.length === 0) fail(`${relative(ROOT, GATEWAY)} holds no .kt files under a main source set`);
  if (control.length === 0) fail(`${relative(ROOT, GATEWAY)} has no control/src/main tree — the control API is the console's wire`);

  const constants = new Map();
  for (const file of all) {
    const rel = relative(ROOT, file);
    const holder = file.split('/').pop().replace(/\.kt$/, '');
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      // the `: String` annotation is OPTIONAL in Kotlin, and requiring it missed
      // `private const val TS = "ts"` in PerfRoutes.kt — one false UNDISPOSITIONED from one regex.
      const m = line.match(/\bconst\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*"([^"]+)"/);
      if (m !== null && /^[a-z][a-z0-9_]*$/.test(m[2])) {
        constants.set(m[1], { key: m[2], at: `${rel}:${i + 1}`, holder });
      }
    });
  }

  const keys = new Map();
  const routes = new Map();
  let dynamic = 0;
  const byFile = new Map(); // file -> Set(key), so a route can be compared with the file that serves it
  const note = (name, at, conditional, how) => {
    const file = at.split(':')[0];
    if (!byFile.has(file)) byFile.set(file, new Set());
    byFile.get(file).add(name);
    const slot = keys.get(name) ?? { sites: [], conditional: false, how: new Set() };
    slot.sites.push(at);
    slot.conditional = slot.conditional || conditional;
    slot.how.add(how);
    keys.set(name, slot);
  };

  const controlText = control.map((f) => readFileSync(f, 'utf8')).join('\n');

  // the String-returning ports this module hands payloads through, read from the ports themselves
  const stringPorts = new Set();
  for (const file of control) {
    for (const m of readFileSync(file, 'utf8').matchAll(/fun\s+interface\s+([A-Za-z0-9_]+)\s*\{[^}]*operator\s+fun\s+invoke\s*\([^)]*\)\s*:\s*String/g)) {
      stringPorts.add(m[1]);
    }
  }

  // PASS-THROUGH ROUTES. A route can hand back a payload this module never builds: `DoctorRoute`
  // writes the CLI's report VERBATIM through `fun interface DoctorReport { operator fun invoke():
  // String }`, so its keys are authored in gateway/app and the control module names not one of
  // them. That is not 27 console defects and it is not a clean pass either — it is a bounded DID
  // NOT CHECK, and the only honest third answer.
  //
  // It is a NAMED table and not an inference, because every inference I tried was wrong in one
  // direction or the other: "the file mentions a String port" marked /api/sessions and /api/upgrade,
  // whose payloads this module plainly builds; "none of the payload's fields is a key here" missed
  // the doctor route because DoctorPayload declares `checks`, `logs`, `topology` and `perf`, all of
  // which some OTHER control file emits for its own payload. A table of one, whose claim the check
  // then VERIFIES, beats a rule that silently swallows a real mismatch — which the greedy version
  // did: it took MISMATCHED from 1 to 0.
  const passThrough = {
    '/api/doctor': { port: 'DoctorReport', builtIn: 'gateway/app/src/main/kotlin/splice/app/cli/DoctorReportShape.kt' },
  };
  // the exemption has to still be true, or it is a stale excuse rather than a disposition — but it
  // is only a claim about a route this tree actually serves, so it is verified after the routes are
  // read, below, and not here where it would fail any tree that has no such route at all.
  const verifyExemptions = () => {
  for (const [path, claim] of Object.entries(passThrough)) {
    if (!routes.has(path)) continue;
    if (!stringPorts.has(claim.port)) {
      fail(`${path} is exempted as a pass-through through the ${claim.port} port, and no String-returning\n` +
        `       fun interface of that name exists any more — the exemption is stale and would hide real fields`);
    }
    if (!existsSync(join(ROOT, claim.builtIn))) {
      fail(`${path} is exempted because its payload is built in ${claim.builtIn}, and that file is gone`);
    }
  }
  };

  for (const file of control) {
    const rel = relative(ROOT, file);
    const text = readFileSync(file, 'utf8');
    const buildsJson = /buildJsonObject|JsonObjectBuilder|putJsonObject|addJsonObject/.test(text);
    const lines = text.split('\n');
    // ktor nests `route("/api") { get("/models") }`, so a served path is the concatenation of the
    // open route prefixes. Tracked by brace depth, which is what the nesting actually is.
    const prefixes = [];
    let serializable = false;
    let serialName = null;
    let depth = 0;
    let braces = 0;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].replace(/\/\/.*$/, '');
      const at = `${rel}:${i + 1}`;
      const omits = /\?\.\s*let\s*\{/.test(line) || /\bif\s*\(/.test(line);

      const opened = braces;
      const route = line.match(/\broute\s*\(\s*"([^"]*)"/);
      for (const m of line.matchAll(/\b(?:get|post|put|delete|patch)\s*\(\s*"([^"]*)"/g)) {
        const path = (prefixes.map((p) => p.path).join('') + m[1])
          .replace(/\{[^}]*\}/g, '{}').replace(/\/$/, '');
        if (path.startsWith('/')) routes.set(path, { at });
      }
      if (route !== null) prefixes.push({ path: route[1], at: opened });
      braces += (line.match(/\{/g) ?? []).length - (line.match(/\}/g) ?? []).length;
      while (prefixes.length > 0 && braces <= prefixes[prefixes.length - 1].at) prefixes.pop();

      if (buildsJson) {
        for (const m of line.matchAll(/\b(?:put|putJsonArray|putJsonObject)\s*\(\s*([^,)]+)/g)) {
          const arg = m[1].trim();
          const literal = arg.match(/^"([^"]+)"$/);
          if (literal !== null) { note(literal[1], at, omits, 'literal'); continue; }
          const named = arg.match(/(?:^|\.)([A-Za-z_][A-Za-z0-9_]*)$/);
          const resolved = named === null ? undefined : constants.get(named[1]);
          if (resolved !== undefined) { note(resolved.key, at, omits, `const ${resolved.at}`); continue; }
          dynamic += 1;
        }
      }

      if (/@Serializable\b/.test(line)) { serializable = true; depth = 0; continue; }
      if (serializable) {
        const sn = line.match(/@SerialName\s*\(\s*"([^"]+)"/);
        if (sn !== null) { serialName = sn[1]; continue; }
        const prop = line.match(/\b(?:override\s+)?va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^,)=]+)(=)?/);
        if (prop !== null) {
          note(serialName ?? prop[1], at, prop[3] !== undefined || /\?\s*$/.test(prop[2].trim()), 'kotlinx');
          serialName = null;
        }
        depth += (line.match(/\(/g) ?? []).length - (line.match(/\)/g) ?? []).length;
        if (depth <= 0 && /\)/.test(line)) serializable = false;
      }
    }
  }

  // the vocabulary a payload builder ITERATES rather than names. PerfPayloads emits the keys
  // present in a row, so the constants are the wire's vocabulary even where no `put(NAME, …)` site
  // names them one by one. Carried as its own kind so a reader sees what a MATCH rests on.
  for (const [, { key, at, holder }] of constants) {
    if (keys.has(key)) continue;
    if (!new RegExp(`\\b${holder}\\b`).test(controlText)) continue;
    note(key, at, true, `vocabulary of ${holder}`);
  }

  if (keys.size === 0) fail(`read ${control.length} control kotlin file(s) and found no emitted keys — the emission shapes this check knows have changed`);
  if (routes.size === 0) fail(`read ${control.length} control kotlin file(s) and found no served routes — the routing shapes this check knows have changed`);
  verifyExemptions();
  return { keys, routes, byFile, passThrough, dynamic, files: control.length, constants: constants.size };
}

// ---------------------------------------------------------------- the comparison

/** `context_window_source` and `window_source` are the same words; `id` and `idx` are not. */
function tokens(name) {
  return name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().split(/[_-]+/).filter(Boolean);
}

/**
 * A near spelling: the same words in a different shape, or one name's words wholly contained in
 * the other's. The containment rule is what catches the defect this row was cut for —
 * `window_source` against `context_window_source` — and it is deliberately not a string distance,
 * because `pinned` and `pinned_model` differing by one word is exactly the interesting case.
 */
function nearSpellings(name, keys) {
  const mine = tokens(name);
  const key = mine.join('_');
  const near = [];
  for (const other of keys.keys()) {
    if (other === name) continue;
    const theirs = tokens(other);
    if (theirs.join('_') === key) { near.push(other); continue; }
    if (mine.length > 1 && theirs.length > 1) {
      const a = new Set(mine); const b = new Set(theirs);
      const shared = [...a].filter((t) => b.has(t)).length;
      if (shared === Math.min(a.size, b.size) && Math.abs(a.size - b.size) <= 2) near.push(other);
    }
  }
  return near;
}

/** Every type reachable from a fetched payload, which IS the definition of "wire-facing". */
function reachable(roots, decls) {
  const seen = new Set();
  const queue = [...roots];
  while (queue.length > 0) {
    const name = queue.pop();
    if (seen.has(name) || !decls.has(name)) continue;
    seen.add(name);
    for (const field of decls.get(name).fields) for (const ref of field.refs) queue.push(ref);
  }
  return seen;
}

async function run() {
  const ts = await parser();
  const dirs = entityDirs();
  const withTypes = dirs.filter((d) => existsSync(d.types));
  const withoutTypes = dirs.filter((d) => !existsSync(d.types));
  if (withTypes.length === 0) fail(`none of the ${dirs.length} entity directories declares model/types.ts`);

  const decls = new Map();
  const owners = new Map();
  const empty = [];
  for (const dir of withTypes) {
    const own = declarationsOf(ts, dir.types);
    if ([...own.values()].every((d) => d.fields.length === 0)) empty.push(relative(ROOT, dir.types));
    for (const [name, decl] of own) { decls.set(name, decl); owners.set(name, dir.name); }
  }
  if (empty.length > 0) {
    fail(`${empty.length} types file(s) parsed to ZERO fields, so they were not checked: ${empty.join(', ')}`);
  }
  if ([...decls.values()].flatMap((d) => d.fields).length === 0) fail('no fields parsed out of any types file');

  const sites = fetchSites(dirs);
  const coverage = coverageRoutes();
  const wire = daemon();

  // level 1: routes. A ktor `{}` segment matches any value, so a fetch matches a served path when
  // their segment lists line up with `{}` as the wildcard.
  const served = (path) => {
    const want = path.split('/');
    for (const have of wire.routes.keys()) {
      const got = have.split('/');
      if (got.length !== want.length) continue;
      if (got.every((seg, i) => seg === '{}' || want[i] === '{}' || seg === want[i])) {
        return { path: have, ...wire.routes.get(have) };
      }
    }
    return null;
  };
  const routeRows = sites.map((site) => {
    const cov = coverage.get(site.path) ?? null;
    const hit = served(site.path);
    let verdict;
    // OFF-MODULE needs BOTH halves, because "the file mentions a String port" alone marked
    // /api/sessions and /api/upgrade, whose payloads this module plainly does build. The second
    // half is the measurement: a payload the control module builds has SOME of its fields among
    // the module's keys, and one written out verbatim has NONE of them.
    const offModule = hit !== null && wire.passThrough[site.path] !== undefined;
    if (hit !== null) verdict = offModule ? 'SERVED-OFF-MODULE' : 'SERVED';
    else if (cov === null) verdict = 'ABSENT-UNDISPOSITIONED';
    else if (cov.where !== null) verdict = 'ABSENT-PENDING';
    else verdict = 'ABSENT-BUT-COVERAGE-SAYS-LIVE';
    return { ...site, servedBy: hit, coverage: cov, verdict, offModule };
  });

  // level 2 + 3: fields, disposed by the route their payload arrives on
  const servedTypes = reachable(routeRows.filter((r) => r.servedBy !== null).map((r) => r.type), decls);
  // a type reached ONLY through a field the console marked PENDING inherits that row: SessionRepo
  // exists solely under `repo?: SessionRepo  /** PENDING V4-130 */`, so its own fields are pending too
  const pendingByType = new Map();
  for (const decl of decls.values()) {
    for (const field of decl.fields) {
      if (field.pending === null) continue;
      for (const t of reachable(field.refs, decls)) if (!pendingByType.has(t)) pendingByType.set(t, field.pending);
    }
  }
  const rows = [];
  for (const [owner, decl] of decls) {
    const root = owner.split('.')[0];
    const absentRoute = routeRows.find((r) => r.servedBy === null && reachable([r.type], decls).has(root));
    for (const field of decl.fields) {
      const hit = wire.keys.get(field.name);
      let disposition;
      let detail;
      let viaRoute = null;
      const offModule = routeRows.find((r) => r.offModule && reachable([r.type], decls).has(root));
      const route = routeRows.find((r) => reachable([r.type], decls).has(root));
      if (offModule !== undefined && wire.keys.get(field.name) === undefined) {
        disposition = 'OFF-MODULE';
        detail = `${offModule.path} returns a payload built outside the control module, through the ` +
          `${wire.passThrough[offModule.path].port} port — its keys are authored in ` +
          `${wire.passThrough[offModule.path].builtIn}, which this check does not read`;
      } else if (servedTypes.has(root)) {
        if (hit !== undefined) {
          disposition = 'MATCHES';
          detail = `${hit.sites.length} site(s), e.g. ${hit.sites[0]} [${[...hit.how][0]}]`;
        } else {
          const near = nearSpellings(field.name, wire.keys);
          const pending = field.pending ?? pendingByType.get(root) ?? null;
          const routePending = route !== undefined && route.coverage?.disposition === 'pending' && route.coverage.where !== null;
          const spellings = near.length === 0 ? ''
            : ` (near spellings on the wire: ${near.map((n) => `'${n}' at ${wire.keys.get(n).sites[0]}`).join(', ')})`;
          // A near spelling is a DEFECT ON SIGHT only on a route nobody is still building. On a
          // route coverage marks pending, `default_context_window` against the per-model
          // `context_window` is a vocabulary coincidence, not a typo — so the row is dispositioned
          // by its pending row and the candidate is carried in the detail rather than thrown away.
          if (near.length > 0 && !routePending && pending === null) {
            disposition = 'MISMATCHED';
            detail = near.map((n) => `wire spells it '${n}' at ${wire.keys.get(n).sites[0]}`).join('; ');
          } else if (pending !== null) {
            disposition = 'ABSENT';
            detail = `on a SERVED route, not emitted yet; the declaration names ${pending}${spellings}`;
          } else if (routePending) {
            disposition = 'ABSENT';
            detail = `${route.path} is served but coverage marks it pending ${route.coverage.where} at ${route.coverage.at}${spellings}`;
          } else {
            disposition = 'UNDISPOSITIONED';
            detail = `on a SERVED route the coverage plane calls live, and the daemon emits no key of this name${spellings}`;
          }
        }
      } else if (absentRoute !== undefined) {
        if (absentRoute.verdict === 'ABSENT-PENDING') {
          disposition = 'ABSENT';
          detail = `${absentRoute.path} is not served; coverage names ${absentRoute.coverage.where} at ${absentRoute.coverage.at}`;
        } else {
          disposition = 'UNDISPOSITIONED';
          viaRoute = absentRoute.path;
          detail = absentRoute.verdict === 'ABSENT-BUT-COVERAGE-SAYS-LIVE'
            ? `${absentRoute.path} is not served, and ${absentRoute.coverage.at} disposes it '${absentRoute.coverage.disposition}' — the console believes a route the daemon does not have`
            : `${absentRoute.path} is not served and no coverage.ts disposes it at all`;
        }
      } else {
        disposition = 'CONSOLE-INTERNAL';
        detail = 'not reachable from any request<T>, so it is not a claim about the wire';
      }
      // THE CONDITIONAL FLAG IS PER-NAME AND NOT PER-SITE, AND THAT IS THIS CHECK'S LARGEST KNOWN
      // IMPRECISION (M1-45). `hit` is looked up by field NAME across the whole control module, so
      // `hit.conditional` means "SOME site anywhere emits a key of this name conditionally" — not
      // "the site that builds THIS payload can omit it". The console has to survive the worst site
      // of the payload it actually receives; this asks it to survive the worst site in the daemon.
      //
      // MEASURED ON THE TREE THAT LANDED THIS ROW, so the next reader does not re-derive it. Nine
      // entries stood in Level 3a when M1-41 was cut. ONE was real — `ModelRates` on
      // ModelsRoute.kt:130, `entry.rates?.let { put("rates", ratesJson(it)) }`, the daemon genuinely
      // omitting the key — and M1-41 fixed it. The other EIGHT are this artefact, every one:
      //
      //   PerfStats.max        its own site is PerfSummary.kt:177 `put("max", sorted.last())`,
      //                        unconditional. The flag comes off HeadResolver.kt:97, the GATE
      //                        snapshot — a different payload, which entities/heads already handles.
      //   SessionRow.pid       SessionsRoutes.kt:26, unconditional. Flag from McpStatus.kt:45,
      //                        `server?.pid?.let { out.put("pid", it) }` — the MCP payload.
      //   SessionRow.started_at SessionsRoutes.kt:34, unconditional. Flag from McpStatus.kt:50.
      //   SessionRow.version   SessionsRoutes.kt:30, unconditional. Flag from HeadResolver.kt:90.
      //   SessionsPayload.note SessionsRoutes.kt:19 `put("note", HEADLESS_NOTE)`, unconditional.
      //                        Flag from AuthRoutes.kt:61, the refresh failure note.
      //   SessionRow.head      SessionsRoutes.kt:37 `put("head", s.head ?: UNKNOWN_HEAD)` — the
      //   TurnRow.head         elvis GUARANTEES the key. Flag from ConfigRoutes.kt:34,
      //   CaptureState.head    `headKey?.let { put("head", it) }`, the config payload. The two perf
      //                        types are worse than per-name: there is no put("head") in the perf
      //                        payloads at all, so even their MATCH is a name collision.
      //
      // THREE OF THE EIGHT ARE ALSO PER-LINE. `omits` is a regex for `if (` or `?.let {` ON THE
      // LINE, so a line that emits the key on BOTH branches reads as conditional:
      // HeadResolver.kt:97 is `if (h.gateLimit <= 0) put("max", "unlimited") else put("max",
      // h.gateLimit)` — two of `max`'s three recorded sites are that one line, and it cannot omit
      // the key under any input. HeadResolver.kt:90 does the same with a null VALUE, not an absent
      // key, which is the exact distinction this file exists to teach.
      //
      // IT IS LEFT AS IT IS, DELIBERATELY. Fixing it means resolving each field to the payload
      // builder that serves ITS route, which is the live half's job (V4-140) and not a static one —
      // and the error is in the SAFE direction: it over-reports a field as possibly-omitted, never
      // under-reports one. That is also why 3a and 3b do not gate: an over-reporting census that
      // failed the build would fail it eight times for nothing, and the ruling beside the exit rule
      // is what that would cost.
      const mayOmit = disposition !== 'CONSOLE-INTERNAL' &&
        (disposition !== 'MATCHES' || hit.conditional);
      rows.push({
        ...field,
        entity: owners.get(root) ?? '?',
        disposition,
        detail,
        viaRoute,
        risk: !field.optional && mayOmit && disposition !== 'CONSOLE-INTERNAL',
      });
    }
  }

  return { rows, routeRows, wire, withoutTypes, typeFiles: withTypes.length };
}

const ORDER = ['MATCHES', 'CONSOLE-INTERNAL', 'ABSENT', 'OFF-MODULE', 'MISMATCHED', 'UNDISPOSITIONED'];
const badRows = (rows) => rows.filter((r) => r.disposition === 'MISMATCHED' || r.disposition === 'UNDISPOSITIONED');
const badRoutes = (routeRows) => routeRows.filter((r) => r.verdict.startsWith('ABSENT-') && r.verdict !== 'ABSENT-PENDING');

function report({ rows, routeRows, wire, withoutTypes, typeFiles }) {
  console.log(`wire-check: ${typeFiles} entity types file(s), ${rows.length} declared field(s); the control API ` +
    `serves ${wire.routes.size} route(s) and emits ${wire.keys.size} key(s) from ${wire.files} file(s)`);
  console.log(`  ${wire.constants} key constant(s) resolved; ${wire.dynamic} put() site(s) name their key at RUNTIME and cannot be enumerated statically`);
  if (withoutTypes.length > 0) {
    console.log(`  ${withoutTypes.length} entity(s) declare no model/types.ts and are OUTSIDE this denominator: ${withoutTypes.map((d) => d.name).join(', ')}`);
  }

  console.log(`\nLEVEL 1 — the ${routeRows.length} route(s) the console fetches:`);
  for (const r of [...routeRows].sort((a, b) => a.path.localeCompare(b.path))) {
    const cov = r.coverage === null ? 'NO coverage disposition'
      : `coverage: ${r.coverage.disposition}${r.coverage.where === null ? '' : ` (${r.coverage.where})`}`;
    const label = r.servedBy === null ? 'NOT SERVED' : (r.verdict === 'SERVED-OFF-MODULE' ? 'SERVED*' : 'SERVED');
    const line = `  ${label.padEnd(11)}${r.path.padEnd(32)} ${r.type.padEnd(20)} ${cov}`;
    if (r.verdict.startsWith('SERVED') || r.verdict === 'ABSENT-PENDING') console.log(line);
    else {
      // the ROUTE is the finding; its fields are the consequence. Printing all of them buries the
      // four route failures under sixty-three field rows saying the same thing once each.
      const carried = rows.filter((f) => f.viaRoute === r.path).length;
      console.error(`${line}\n      ^ ${r.verdict} — fetched at ${r.at}, and ${carried} declared field(s) rest on it`);
    }
  }

  console.log('\nLEVEL 2 — fields:');
  for (const d of ORDER) console.log(`  ${d.padEnd(18)} ${String(rows.filter((r) => r.disposition === d).length).padStart(4)}`);
  const own = badRows(rows).filter((r) => r.viaRoute === null);
  if (own.length > 0) console.error(`\n  ${own.length} of them are the console's own, on routes the daemon DOES serve:`);
  for (const r of own) console.error(`${r.disposition} ${r.entity} ${r.owner}.${r.name}: ${r.at}\n    ${r.detail}`);

  // Two very different things wear this label. A non-optional field on a route the daemon does not
  // serve AT ALL is trivially undefined and will be settled when the route lands. A non-optional
  // field on a route that IS served, which the daemon may omit, is undefined ON THE OPERATOR'S
  // SCREEN RIGHT NOW — that is the context_window_source class, and it is the one worth reading.
  const risky = rows.filter((r) => r.risk);
  // OFF-MODULE fields are excluded here on purpose: this check has SAID it cannot read their keys,
  // and turning "I did not look" into "this is broken" is the same two-outcome error law 23 is
  // about, pointed the other way.
  const offModuleTypes = new Set(rows.filter((r) => r.disposition === 'OFF-MODULE').map((r) => r.owner.split('.')[0]));
  const live = risky.filter((r) => r.viaRoute === null && r.disposition !== 'ABSENT' &&
    !offModuleTypes.has(r.owner.split('.')[0]));
  if (live.length > 0) {
    // READ THE PER-NAME LIMITATION BESIDE `mayOmit` IN run() BEFORE ACTING ON THESE. Every one of
    // the eight standing here when M1-45 landed is an artefact of the conditional flag being keyed
    // by field NAME rather than by emission SITE; each is named there with the site that actually
    // builds its payload. This section is a place to LOOK, not a list of defects, which is the
    // other half of why it does not gate.
    console.error(`\nLEVEL 3a — SILENT UNDEFINED ON A ROUTE THE DAEMON SERVES TODAY (${live.length}):`);
    for (const r of live) console.error(`  ${r.at}  ${r.owner}.${r.name}: ${r.type}\n      ${r.detail}`);
  }
  if (risky.length > 0) {
    console.error(`\nLEVEL 3b — declared non-optional where the wire may omit, all of them (${risky.length}):`);
    const byOwner = new Map();
    for (const r of risky) byOwner.set(r.owner, (byOwner.get(r.owner) ?? []).concat(r));
    for (const [owner, list] of byOwner) {
      console.error(`  ${owner} — ${list.length} field(s), ${list[0].at}`);
      console.error(`      ${list.map((r) => r.name).join(', ')}`);
      console.error(`      ${list[0].detail}`);
    }
  }
}

// ---------------------------------------------------------------- the selftest

/**
 * Mutation-proof, both directions. Every case plants a real condition and asserts the verdict; a
 * check that only ever ran against a healthy tree has proved nothing about what it does to a sick
 * one. The law-23 cases are last, and the POSITIVE control is last of all: without it, every red
 * above would prove only that this check always fails.
 */
async function selftest() {
  const ts = await parser();
  const { writeFileSync, rmSync, mkdtempSync, mkdirSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { spawnSync } = await import('node:child_process');
  const cases = [];
  const ok = (name, got, want) => cases.push({ name, pass: JSON.stringify(got) === JSON.stringify(want), got, want });

  const keys = new Map([
    ['context_window', { sites: ['k:1'], conditional: false, how: new Set(['literal']) }],
    ['window_source', { sites: ['k:2'], conditional: false, how: new Set(['literal']) }],
    ['pinned', { sites: ['k:3'], conditional: false, how: new Set(['literal']) }],
  ]);
  // both `context_window` and `window_source` are near spellings of `context_window_source`, and
  // offering both is right: the check names candidates for a human to rule on, it does not guess.
  ok('the defect this row was cut for is among the candidates', nearSpellings('context_window_source', keys).includes('window_source'), true);
  ok('and the other real key is offered beside it rather than hidden', nearSpellings('context_window_source', keys).length, 2);
  ok('a real field is not called a near spelling of something else', nearSpellings('pinned', keys), []);
  ok('a one-word field with no wire key raises no false near spelling', nearSpellings('nonesuch', keys), []);

  const tmp = join(ROOT, 'webui/src/entities/.wire-check-selftest.ts');
  writeFileSync(tmp, [
    'export interface Probe {',
    '  plain: string;',
    '  optional?: string;',
    '  nullable: string | null;',
    '  nested: { inner_key: number };',
    '  rows: Row[];',
    '}',
    'export interface Row { row_key: string }',
  ].join('\n'));
  try {
    const decls = declarationsOf(ts, tmp);
    const probe = decls.get('Probe').fields;
    ok('every property signature is a field', probe.map((f) => f.name), ['plain', 'optional', 'nullable', 'nested', 'rows']);
    ok('a nested object literal declares its own type', decls.has('Probe.nested'), true);
    ok('`?` is read as optional', probe.find((f) => f.name === 'optional').optional, true);
    ok('`| null` is NOT optional — it claims the key is present', probe.find((f) => f.name === 'nullable').optional, false);
    ok('`| null` is read as nullable', probe.find((f) => f.name === 'nullable').nullable, true);
    ok('a referenced interface is reachable from its root', reachable(['Probe'], decls).has('Row'), true);
    ok('an unrelated interface is NOT reachable', reachable(['Row'], decls).has('Probe'), false);
  } finally {
    rmSync(tmp, { force: true });
  }

  // law 23: every way this check could read nothing, each of which must exit non-zero
  const self = process.argv[1];
  // BOTH STREAMS, because the exit-rule cases below assert what the check SAID and not only what it
  // returned. A case that asserts exit 0 alone would pass just as well if Level 3b were deleted
  // outright, which is the failure it exists to prevent — law 27, and the vacuous-wall shape this
  // campaign has now found twice.
  const runIn = (cwd) => {
    const r = spawnSync(process.execPath, [self], { cwd, encoding: 'utf8' });
    return { status: r.status ?? 1, out: `${r.stdout ?? ''}${r.stderr ?? ''}` };
  };
  const exit = (cwd) => runIn(cwd).status;
  const bare = mkdtempSync(join(tmpdir(), 'wire-check-'));
  const put = (path, body) => {
    mkdirSync(join(bare, path.split('/').slice(0, -1).join('/')), { recursive: true });
    writeFileSync(join(bare, path), body);
  };
  ok('a tree with no entities directory is DID NOT RUN, not a pass', exit(bare) !== 0, true);
  put('webui/src/entities/probe/model/types.ts', 'export interface P { a: string }\n');
  ok('entity types but no api segment is DID NOT RUN, not a pass', exit(bare) !== 0, true);
  put('webui/src/entities/probe/api/index.ts', "const x = request<P>('/api/probe');\n");
  ok('a console side but no gateway tree is DID NOT RUN, not a pass', exit(bare) !== 0, true);
  put('daemon/control/src/main/kotlin/Empty.kt', '// no keys and no routes here\n');
  ok('control kotlin that emits no keys at all is DID NOT RUN, not a pass', exit(bare) !== 0, true);
  put('daemon/control/src/main/kotlin/Empty.kt', 'val x = buildJsonObject { put("a", 1) }\n');
  ok('keys but no served route is DID NOT RUN, not a pass', exit(bare) !== 0, true);
  put('daemon/control/src/main/kotlin/Empty.kt', 'fun r() { get("/api/probe") { } }\nval x = buildJsonObject { put("a", 1) }\n');
  ok('a console and a daemon that AGREE come back clean', exit(bare), 0);
  put('webui/src/entities/probe/model/types.ts', 'export interface P { a_typo: string }\n');
  ok('a planted MISMATCH is red', exit(bare) !== 0, true);

  // THE EXIT RULE ITSELF (M1-45). Every case above proves the check can FAIL; not one proves WHICH
  // rule fired, and this row changes exactly that. Both cases run on the same bare tree, one
  // condition apart, so the difference between them IS the rule.
  put('webui/src/entities/probe/model/types.ts', 'export interface P { a: string }\n');
  // `if (` on the put's own line is what marks the key conditional, so `a` is a non-optional
  // declaration against a key the wire may omit: a Level 3a AND 3b entry, and nothing else wrong.
  put('daemon/control/src/main/kotlin/Empty.kt',
    'fun r() { get("/api/probe") { } }\nval x = buildJsonObject { if (flag) put("a", 1) }\n');
  const census = runIn(bare);
  ok('a tree whose ONLY finding is the 3b census comes back CLEAN', census.status, 0);
  // and it is clean because the census does not gate, NOT because the census vanished — assert the
  // check still said it out loud, or this pair would pass against a wire-check with Level 3 deleted
  ok('and it still printed that census, loudly, on the way to exit 0',
    census.out.includes('LEVEL 3b') && census.out.includes('PRINTED, NOT GATED'), true);

  put('webui/src/entities/probe/model/types.ts', 'export interface P { a: string }\nexport interface Q { }\n');
  put('webui/src/entities/probe/api/index.ts',
    "const x = request<P>('/api/probe');\nconst y = request<Q>('/api/nope');\n");
  const undisposed = runIn(bare);
  // Q declares no fields, so the ONLY thing wrong with this tree is the route — the red cannot come
  // from a field row, and the assertion on the message is what proves that rather than assuming it.
  ok('one route the console fetches that nobody serves and no coverage disposes is RED', undisposed.status !== 0, true);
  ok('and the rule that fired is named the route rule, not a field or census rule',
    undisposed.out.includes('no coverage row disposes'), true);
  rmSync(bare, { recursive: true, force: true });

  for (const c of cases) {
    console.log(`  ${c.pass ? 'PASS' : 'FAIL'}  ${c.name}${c.pass ? '' : ` — wanted ${JSON.stringify(c.want)}, got ${JSON.stringify(c.got)}`}`);
  }
  const passed = cases.filter((c) => c.pass).length;
  console.log(`\nselftest ${passed}/${cases.length} ${passed === cases.length ? 'PASS' : 'FAIL'}`);
  process.exitCode = passed === cases.length ? 0 : 1;
}

// ---------------------------------------------------------------- main

const ARGS = process.argv.slice(2);
if (ARGS.includes('--selftest')) {
  await selftest();
} else {
  const result = await run();
  if (ARGS.includes('--json')) console.log(JSON.stringify({ routes: result.routeRows, rows: result.rows }, null, 2));
  else report(result);

  // ------------------------------------------------------------------- THE EXIT RULE (M1-45)
  //
  // THREE THINGS TURN THIS CHECK RED, AND EVERY ONE OF THEM IS A DEFECT RATHER THAN A CENSUS:
  //
  //   a route the console fetches that nobody serves and no coverage row disposes — including one
  //                     disposed LIVE while the daemon does not serve it, because a false
  //                     disposition is not a weaker disposition, it is a claim that is wrong
  //   MISMATCHED        the daemon emits a NEAR SPELLING and not this name — the
  //                     context_window_source class, the defect this whole file was cut for
  //   UNDISPOSITIONED   a field in NONE of the dispositions. This is M1-37's core law — absence is
  //                     not a disposition — and it is the only thing standing between this check
  //                     and a new field arriving unnoticed.
  //
  // LEVEL 3a AND 3b PRINT LOUDLY AND GATE NOTHING, and the ruling is this: A CENSUS THAT FAILS THE
  // BUILD TEACHES PEOPLE TO STOP RUNNING IT. 3b is 137 fields on the tree that landed this row, and
  // all but eight of them are non-optional declarations against routes the daemon has not built yet
  // — V4-127 through V4-133, every single one named by the console's own coverage.ts. Not one is
  // actionable before its route lands, so gating on the count holds the check permanently red for
  // work that is already scheduled, and a permanently red check is one people stop reading and then
  // stop running. It would also invert the row order: with M1-41's six dispositions landed, the
  // route line and the field line are GONE — not reduced, absent — and the 137-field census was the
  // only thing holding a bare run at exit 1.
  //
  // THE CENSUS BECOMES A DEFECT THE MOMENT ITS ROUTE LANDS, and that transition is the reason it is
  // safe to print rather than gate. On that day the field stops being "declared against a route
  // that does not exist" and becomes a silent undefined on the operator's screen — and the entry
  // MOVES: out of 3b, into 3a, and into MISMATCHED or UNDISPOSITIONED at Level 2, which do gate. A
  // route landing is a row, and a row's milestone re-runs this check, so the catch is the milestone
  // re-run and not a count that was already red before the route existed.
  //
  // NOTHING IS SOFTENED BY THIS. 3a and 3b still go to STDERR, still print every entry with its
  // file:line, and the summary still says the count out loud on an otherwise clean run — so exit 0
  // cannot be read as "there is nothing here". What changed is which sentence the build listens to.
  const bad = badRows(result.rows);
  const routeBad = badRoutes(result.routeRows);
  const risky = result.rows.filter((r) => r.risk);
  let failed = false;
  if (routeBad.length > 0) {
    console.error(`\nwire-check: ${routeBad.length} route(s) the console fetches that the daemon does not serve and no coverage row disposes`);
    failed = true;
  }
  if (bad.length > 0) {
    console.error(`wire-check: ${bad.length} field(s) the console declares that the daemon does not emit under that name`);
    failed = true;
  }
  // COUNTED AND NAMED, NEVER A GATE — the ruling is written above, and it is written here too so a
  // reader who deletes this branch to "make the check strict" meets the reason first.
  if (risky.length > 0) {
    console.error(`\nwire-check: ${risky.length} field(s) are a silent undefined by construction — PRINTED, NOT GATED (M1-45)`);
  }
  // LAW 27: this line asserts what must be TRUE, and a census entry is not a claim it can make. It
  // used to end "and none is a silent undefined", which on a clean run with 137 of them in 3b above
  // would have been the check contradicting its own stderr in its own last sentence.
  if (!failed) {
    console.log(`\nwire-check: every route is served or disposed, every field is dispositioned, ` +
      `and none is MISMATCHED or UNDISPOSITIONED${risky.length > 0 ? ` (${risky.length} in the 3b census above, which does not gate)` : ''}`);
  }
  // `process.exitCode` and NOT `process.exit()`: --json writes a report larger than a pipe buffer
  // and exiting kills the flush mid-write. Measured while building this — the JSON came back cut
  // at 8,178 bytes and a reader downstream saw a parse error instead of a report.
  process.exitCode = failed ? 1 : 0;
}
