# Web console build contracts

The lattice every console row builds against. Rows run in parallel in one worktree, so the
names below are fixed before any row starts: a builder uses them as written and never invents
a sibling. A change here is an orchestrator edit with a dated note on every row it touches.
Product truth lives in `FEATURES.md`; the look lives in the surface brief and the approved comp
(`webui/.impeccable/mocks/team-board-a.png`); this file carries only what two rows must agree on.

Paths are relative to `webui/`. Layers and their import direction are the existing
eslint-plugin-boundaries wall: `app > pages > widgets > features > entities > shared`.
HTTP only in `entities/*/api` through `shared/api`.

## 1. Tokens (`src/shared/tokens.css`, rewritten 2026-09-25 for the console redesign)

The design is `docs/design/DESIGN.md`; this section is the names two rows must agree on. Both
themes are complete token sets on the root element: `:root, :root[data-theme="dark"]` is dark and
the default, `:root[data-theme="light"]` is light. Every colour below exists in both. Spacing, type
size and colour are only ever read through `var(--space-N)`, `var(--text-N)` and the colour tokens
(ast-grep walls `webui-css-tokens-only`, `webui-css-font-size-scale`, `webui-css-no-color-literals`).

Scales (shared by both themes):

| token | value | use |
|---|---|---|
| `--space-0` .. `--space-8` | shared: 0, 2, 4, 8, 12, 16, 24, 32, 48 px | every gap, pad, inset |
| `--text-1` .. `--text-7` | shared: 12, 13, 14, 16, 20, 24, 32 px | captions, table cells, body, section titles, sub-titles, page titles, a number that is the point |
| `--leading`, `--leading-tight` | shared: 1.5, 1.25 | line height |
| `--radius-0` .. `--radius-3`, `--radius-full` | shared: 0, 4, 6, 8 px, pill | marks, controls, panels |
| `--hair`, `--focus-width`, `--focus-offset` | shared: 1, 2, 2 px | hairline width; the focus ring |
| `--dur-1` .. `--dur-3`, `--ease-out` | shared: 120, 180, 240 ms | every transition; `0ms` under reduced motion |
| `--font-sans`, `--font-mono` | shared: IBM Plex Sans, IBM Plex Mono | words; every figure |
| `--sidebar-width` | shared: 232 px | the nav column |

Colours (name, role):

| token | role |
|---|---|
| `--bg`, `--bg-raised`, `--bg-hover`, `--bg-active` | grounds: the page; the sidebar, panels and table heads; a row under the pointer; a selected row |
| `--line`, `--line-strong` | the hairline, and a boundary that must hold (inputs) |
| `--fg`, `--fg-muted`, `--fg-subtle` | text: every one clears 4.5:1 on every ground |
| `--accent`, `--accent-hover`, `--accent-fg`, `--accent-soft` | the ink itself as the accent: the primary button and the focus ring carry no hue |
| `--ok`, `--warn`, `--danger`, `--ok-soft`, `--warn-soft`, `--danger-soft` | status, always beside its word or number |
| `--head-1` .. `--head-8`, `--head-up-1` .. `--head-up-8`, `--head-down-1` .. `--head-down-8`, `--head-none` | one hue per provider family, a second head of the family one step lighter and a third one darker (DESIGN.md section 5); a mark, 3:1 on the grounds |
| `--series-1` .. `--series-3` | chart greys for kinds that are not heads; marks, 3:1 on the grounds |
| `--track`, `--chart-grid` | the unfilled part of a meter or bar, and a chart's grid: not data, no floor |
| `--focus` | the focus ring's colour |
| `--overlay`, `--shadow-float` | the scrim under a modal, and the one floating shadow |

Old tokens: the strip-bay names (`--room`, `--ink`, `--strip`, `--plate`, `--edge-*` and their
siblings) are kept only as aliases in the block after the light theme, each pointing at its nearest
new role, so a page not yet rebuilt renders in the new palette. New code never uses them. The block
is deleted when the last page moves, and `tests/world.test.ts` then fails by name on any reader.

Theme switch (`src/features/theme`): `useTheme(): { theme: 'dark' | 'light', set(theme) }`.
Opens dark regardless of the OS. A manual choice is stored under `localStorage['splice.theme']`.
Switching is a cut: nothing transitions across it. `prefers-reduced-motion` sets every `--dur-N`
to `0ms`.

## 2. Primitives (`src/shared/ui`, row M1-02)

Every existing export (`Panel`, `StatusPill`, `Metric`, `Btn`, `Field`, `Well`, `EmptyState`,
`ErrorNote`, `SkeletonRows`, `Stale`, `MeterBar`, `ConfirmBtn`) is deleted: the last four went
with the console redesign (2026-09-25), which also deleted `Strip` and `StripField` once every
rack they drew became a kit DataTable (docs/design/DESIGN.md section 9). The world primitives
still exported from `@shared/ui`:

```ts
type Edge = 'green' | 'amber' | 'red' | 'grey';
type Basis = 'measured' | 'estimated' | 'unavailable' | 'stale';

// A labeled rack. count prints beside the label. When children are empty it renders <Empty>.
// `fields` is the column header row (2026-09-18, review B9): a rack of homogeneous strips
// declares its column names here ONCE, at the same ch widths and starting past the edge column,
// and the strips below omit their own labels.
<Bay label="string" count?: number fields?: ReactNode empty={{ text: string; source: string }} actions?: ReactNode>

// The colored holder edge on its own (rail tabs, rule cells). Always prints its label.
<HolderEdge state={Edge} label="string" />

// A dark chart frame. children is the chart (svg or canvas drawn from data at runtime).
<ScopeInset title="string" basis={Basis}>{children}</ScopeInset>

// A form field with its provenance layer. hot = applies live; otherwise the daemon strip cocks.
<FieldBox label="string" value={string} provenance={'splice.toml' | 'default' | 'defaults table' | 'head override'
         | 'state file' | 'env' | 'patch'} hot?: boolean onChange?: (v: string) => void />

// Hidden until an explicit action. Children render only after the click.
<Reveal label="string">{children}</Reveal>

// An honest empty: what is missing and which source says so.
<Empty text="string" source="string" />

// A number that says what it is. Renders value, unit, and the basis as text, never color alone.
// A `measured` basis prints NOTHING: it is the default reading of any figure and the word is noise
// on every row (review B5, 2026-09-18); `estimated`, `unavailable` and the rest still print.
<Figure value={string | number} unit?: string basis={Basis} />
```

Rules: no border radius above 0, no icons, no gradients, no shadows, no glow. Attention is a
holder edge with a printed label. Color is never the only signal.

### 2b. Controls (`src/shared/controls`, row M1-07)

The five things a page needs that are not a printed readout. They compose section 2 and use
section 1 tokens only. A page that needs to be pressed, typed into, waited on, or told about a
failure imports from `@shared/controls` and NEVER from the old primitives (`Btn`, `ConfirmBtn`,
`Field`, `ErrorNote`, `SkeletonRows`, `Panel`, `StatusPill`, `EmptyState`, `MeterBar`, `Metric`,
`Stale`, `Well`), which carry the released Torad plate system and are deleted in M3-04. The gap
that this section closes is what made the console read as that old world; `webui/tests/world.test.ts`
fails by name on any old import or old token anywhere in the new tree.

```tsx
// The world's button: printed strip paper, radius 0, label of at most two words, an edge mark
// that cocks on hover, press and focus. `armed` is the cocked half of a two-step key.
<Key variant?: 'plain' | 'armed' type?: 'button' | 'submit' onClick?: () => void
     disabled?: boolean busy?: boolean ariaLabel?: string>{children}</Key>

// Inline two-step for anything destructive or restarting, NEVER a dialog. The key cocks to a
// second label beside a cancel and disarms itself after ARM_MS (4s). ConfirmKeys is the
// controlled pair (both keys in the markup, so a static render can show the armed state).
<Confirm label={node} confirmLabel={node} onConfirm={() => void} busy?: boolean />
<ConfirmKeys label={node} confirmLabel={node} armed={boolean} busy?: boolean
             onArm={() => void} onConfirm={() => void} onCancel={() => void} />

// An editable StripField box: the label above, w in ch, the figure face and the numeric keypad
// when numeric — never `input type=number`.
<Input label="string" value={string} onChange={(next: string) => void} numeric?: boolean
       w?: number id?: string placeholder?: string invalid?: boolean disabled?: boolean />

// A bay waiting: n unprinted strips at the strip module's height. No shimmer, no skeleton grey.
<Blank strips={number} label?: string />

// The world's error note: one strip with a red holder edge carrying the daemon's own words, and
// a retry Key only when there is something to retry.
<Fault message="string" onRetry?: () => void retryLabel?: node w?: number />
```

Deliberately absent, and not to be added: a dialog, a menu, a tooltip, a toast, a spinner.
Confirmation is inline, attention is a holder edge, absence is a printed word.

## 3. Shell (`src/app`, `src/widgets/rail`, `src/widgets/rule`, `src/features/palette`, `src/features/views`, row M1-03)

Router: React Router v7, `createBrowserRouter`? No: the artifact is a single file served at `/`
and `/dashboard` by the control server, so the router is `createHashRouter`. Addresses:

```
#/fleet #/turns #/sessions #/teams #/projects #/accounts #/usage
#/settings #/models #/logs #/compaction #/mcp #/doctor
```

The old addresses `#fleet #burn #auth #config #logs #compaction` redirect to
`#/fleet #/usage #/accounts #/settings #/logs #/compaction`. Unknown addresses go to `#/fleet`.

Pages are discovered, never registered: `import.meta.glob('../pages/*/index.tsx')`, keyed by
directory name; each `pages/<name>/index.tsx` has a `default` export that is the page component.
Resolution order for an address: `pages/<address>/index.tsx` when that module exists, else the
alias in `app/rows.ts` (`usage` to `burn`, `accounts` to `auth`, `settings` to `config`), else
`<Empty text="page not built" source="row M2-0N" />` from the same row map. So an M2 row that
creates `pages/usage/` is picked up without a shell edit, and nothing is renamed or deleted in
M1 or M2. M1-03 adds a default export to every existing page index; nothing else in a page
changes in M1.

Rail (`widgets/rail`): thirteen bays in the order above, each a `HolderEdge` (green when active,
grey otherwise) with the address label. Keyboard: arrow keys move, Enter opens.

Rule (`widgets/rule`): fixed top frame, `--space-8` tall, cells left to right: wordmark
`splice`; local clock and UTC clock (`HH:MM:SS`, tabular); daemon health from
`@entities/control-status` (`ok`/`degraded`/`unreachable` with a HolderEdge); the plan window
nearest exhaustion from `@entities/usage` and `@entities/auth` public APIs, printed as
`<head> <account> <window> <pct>%` or `no window reported`; the count `N heads report none`.
Reads entities through their existing exports only.

Palette (`features/palette`, cmdk 1.1.1): opened by `ctrl+k` and `/`; lists the thirteen
addresses, `theme dark`, `theme light`, and the saved views of the current page.

Views (`features/views`):

```ts
type View = { id: string; name: string; layout: string; filter: Record<string, string>;
              sort: { field: string; dir: 'asc' | 'desc' } | null; group: string | null;
              fields: string[] };
useViews(pageId: string, defaults: readonly View[]): {
  views: View[]; active: View; setActive(id): void; add(v): void; rename(id, name): void;
  duplicate(id): void; reorder(ids: string[]): void; remove(id): void; exportJson(): string };
<ViewTabs pageId="string" defaults={View[]} />   // tabs beside the page title, default first
```

Storage key `localStorage['splice.views.<pageId>']`. Removing the last view restores defaults.

## 4. Per-directory conventions (every page, widget and feature row)

- `strings.ts` beside the component: `export const S = { ... } as const;` every user-visible
  label of that directory, lowercase, three words or fewer, no em-dash. The label wall
  (row M1-04) globs `src/**/strings.ts`. Honest empties and Doctor fix commands are not labels
  and live in the component.
- `coverage.ts` beside a page: `export const dispositions: Disposition[] = [...]` where
  `Disposition = { kind: 'knob' | 'topology' | 'route' | 'verb'; name: string; disposition: 'editable'
  | 'read-only' | 'excluded' | 'pending'; where?: string; reason?: string; action?: string; via?: string }`.
  A `verb` (V4-219, V4-220) names its route in `via`, which the daemon must serve and whose own
  disposition must cover it (editable for an editable verb); an editable verb's `action` names one of
  its page's built `job` actions, and a pending verb whose named action is built fails as answered. `excluded`
  requires a reason; `pending` requires `where`: in the baseline (`shared/coverage/baseline.ts`)
  it names the M2 page row that will disposition the item; in a page's own `coverage.ts` it
  names the v0.4.0 row (V4-126 .. V4-133) that serves the route the page is waiting for.
  The coverage wall (row M1-04) globs `src/**/coverage.ts` plus the baseline
  `src/shared/coverage/baseline.ts`; a page declaration overrides the baseline for that name;
  two page declarations for one name fail. The denominator is parsed at test time, never
  hand-listed: knobs from `gateway/core/src/main/kotlin/splice/core/config/Knob.kt` (enum entries,
  45 today); topology keys from every `@SerialName("...")` value in
  `gateway/core/src/main/kotlin/splice/core/topology/Topology.kt`, `QuirksConfig.kt`,
  `TopologySchema.kt`, `.../core/prompt/HeadSystemPrompt.kt`, `.../core/model/TokenCost.kt`,
  `.../core/compaction/CompactionScope.kt` (58 distinct today; FEATURES.md section 2.3 is prose
  and never the source); routes from the tables in `.dev/web-console/FEATURES.md` sections 2.1
  and 6, first column, backticked spans, normalized by one rule: strip the method prefix
  (`GET `, `POST `, `GET/PUT `) and any `?query`; expand `{a,b}` alternates and slash-joined
  methods into one item each; drop a span that does not start with `/`. A disposition names
  the exact normalized path (`/api/perf/turns`), never a glob. CLI verbs from the `verbs` table of
  `app/src/main/kotlin/splice/app/cli/CommandParser.kt`, by their bare names (21 today).
- Numbers: every figure goes through `<Figure basis=...>`; a window a provider does not report
  reads `not reported by provider`, never `0`.
- Fixtures: a page may ship `fixtures/*.ts` for design captures. They load only when
  `import.meta.env.DEV` is true and the address carries the fixture name in the HASH query,
  `#/<address>?fixture=<name>` (pinned 2026-09-18: the shell keeps the hash query through boot,
  and under a hash router the router's `useLocation().search` IS that query, so read it there);
  the shipped `dist` contains no fixture bytes. TWO import shapes both ship, and only the second was
  ever written down. A STATIC import of the fixture module survives the
  DEV guard (the bundler includes the module and its strings ship; found on the hero row), so the
  fixture is loaded with a specifier COMPOSED AT RUNTIME inside the DEV branch and the page
  renders its board-as-prop while it loads; `node .dev/web-console/fixture-leak.mjs` (M2-12) fails
  by name on any fixture literal found in `dist`. AND A LITERAL `await import('./fixtures/x')` SHIPS TOO,
  which this contract used to prescribe: the specifier is statically analyzable, so the bundler keeps it
  as a dependency edge through the single-file build even when its branch is provably dead. Measured
  2026-09-18 (row M1-20): 44 fixture literals in `dist` across five fixtures while the leak wall
  reported zero, and converting one page from a literal specifier to a composed one took its two to
  zero with nothing else changed. So the rule is not "dynamic import" — it is that the BUNDLER MUST NOT
  BE ABLE TO NAME THE MODULE, which means composing the specifier from a value at runtime. Every page
  also carries `data-sample` on its root inside the same DEV guard, carrying the fixture's own file
  name, so an instrument can tell a fixture-fed frame from a live one without reading the page's prose. A fixture page makes no request of its own (the
  shell's polls still run). A fixture is labeled with a `HolderEdge grey "sample data"` in the
  bay label when rendered.
- Tests are `tests/<row>.test.ts` (vitest, node environment, no jsdom). A `.ts` file cannot
  hold JSX (TS1161), so component tests build elements with `React.createElement` (`const h =
  React.createElement`) and assert on the string `renderToStaticMarkup` from `react-dom/server`
  returns; stores and derivations are tested directly. A `.tsx` test is not collected and not
  in any fence. A page exports its BOARD as a component that takes the payload as a prop,
  beside the store-reading default export (M2-02 pattern): a static render only ever sees a
  zustand store's initial state (v5 serves `getInitialState` as the server snapshot), so a test
  that seeds a store renders the empty page; the test renders the board with the payload.
- Two compilers grade the tree: `npx tsc` resolves to TypeScript 7 (`@typescript/native`) and
  refuses file arguments while a tsconfig exists, so run it only as `npx tsc --noEmit`; eslint
  parses with TypeScript 6 (`typescript` alias). Both are intentional; never edit the aliases in
  `webui/package.json`.
- Dependencies are hoisted to the repo-root `node_modules` (npm workspaces). Check presence with
  `node -e "require.resolve('cmdk')"` from `webui/`, never with `ls webui/node_modules`.
- Captures: start the dev server pinned, `npm run dev -w webui -- --port 5173 --strictPort`
  (from the worktree root; if 5173 is already serving, use it and leave it running); delete the
  target PNG; then `node .dev/web-console/capture.mjs '<url>' <absolute path> [<w> <h>]`
  (default 1536x1024; the url is `http://localhost:5173/#/<address>...`, and it must say
  `localhost`, since vite binds `::1` only and `127.0.0.1` is refused). A plain `--screenshot=` captures the management-key gate, not the page,
  because a headless profile has no key: the script drives Chrome over CDP, seeds the key from
  `~/.claude-codex/state/mgmt-key` into localStorage before boot, then captures the frame; the
  key is never printed or written anywhere else. Then confirm the file's mtime is new and its
  PNG header reads `<w>x<h>`. Never the snap chromium (`/usr/bin/chromium-browser`,
  `/snap/bin/chromium`): it cannot write under a dot-directory and exits 0 anyway. Stop the dev
  server if you started it.
- Motion in M1 and M2: only `swell` (a strip opening into its detail column, `--dur-2`) and
  `cock` (the holder edge state change, `--dur-1`). Print, hand off and strike arrive in M3.

## 5. Ownership in M2 (who may edit which entity)

| directory | owner row | readers |
|---|---|---|
| `entities/heads`, `entities/usage` | M2-01 Fleet | rule, accounts, turns |
| `entities/auth`, `entities/account` | M2-04 Accounts | fleet, rule |
| `entities/session`, `entities/project`, `entities/transcript` | M2-02 Sessions | teams |
| `entities/perf`, `entities/logs` | M2-03 Turns | usage |
| `entities/config`, `entities/topology`, `entities/claude-head` | M2-05 Settings | fleet, doctor |
| `entities/economics`, `entities/compact-stats`, `entities/model` | M2-06 Usage | turns |
| `entities/mcp`, `entities/doctor`, `entities/budget`, `entities/alert` | M2-07 MCP and Doctor | settings |

The data rows M2-D1, M2-D2 and M2-D3 own the same entity directories first; the CLI refuses a
page row's claim until its data row is done. The playground is a Reveal panel on the doctor
page, not a fourteenth address. M2 packages, installed by the orchestrator before dispatch at
exact versions: `@tanstack/react-table`, `@tanstack/react-virtual`, `@codemirror/state`,
`@codemirror/view`, `@codemirror/language`, `@codemirror/legacy-modes` (its `mode/toml`),
`@codemirror/merge`.
| `entities/team` | M2-08 Teams | sessions |
| `entities/control-status` | nobody in M2 (read only) | rule, logs |

A reader that needs a field the owner's public API lacks asks the orchestrator, who notes the
request on the owner row; it never edits the owner's directory.

## 6. Events (row M3-01)

`entities/events` exports `connect(): void` and
`subscribe(kind: EventKind, fn: (e: Event) => void): () => void`. The connection (row M3-00,
split from M3-01 on 2026-09-18) is ONE streaming `fetch` of `/api/events` from the entity's api
segment with the `Authorization: Bearer` header (the fetch wall allows fetch in
`entities/*/api`; the browser `EventSource` API cannot send a header, so it is not used): SSE
frames (`id:` monotonic integer, `event:` kind, `data:` one JSON object, `: heartbeat` comments)
parsed incrementally from the `ReadableStream` by `shared/lib/live.ts`, `Last-Event-ID` sent as
a request header on reconnect from the last id seen, backoff 1s doubling to 30s and reset on a
frame, a stop on 401 (the shared client's lock), and a store carrying the state (`live`,
`reconnecting`, `off`) plus the last frame time, which the rule prints beside health. Kinds
follow `FEATURES.md` section 6: `head.state`, `turn.start`, `turn.end`, `session.change`,
`message.edge`, `account.switch`. Entity stores subscribe and refetch (row M3-01); pages never
subscribe directly.

## 7. Deletions are deferred to the finish row

No row before M3-04 deletes a file. A replaced page, widget, feature, primitive, token or font
stays in the tree unreferenced (the router never routes it, the build never inlines it) until
M3-04, the one row whose fence is enumerated from the tree at dispatch, removes it. The receipt
and stage verbs prove files that exist, so a deletion in a parallel row cannot be receipted.

Waiting for M3-04: `pages/burn`, `pages/auth`, `pages/config`; `widgets/head-plate`,
`widgets/fleet-banner`; `features/refresh-auth`, `features/edit-config`; the IBM Plex font
files and `OFL.txt`; the old primitives and tokens named in section 1 and 2; any `fixtures/`
directory no longer needed for captures.

## 8. Route contracts

Payload shapes for routes that do not exist yet are the ones in `FEATURES.md` section 6. A row
building against a pending route types the payload from that table, renders the honest empty
with `source` naming the v0.4.0 row, and never ships mocked data.

How an entity calls a route (decided 2026-09-18 on M2-D1's finding): `shared/api` exports the
typed `request<T>(path, init?)` helper that carries the management key, the 401 lockout and the
error envelope. An entity's `api/index.ts` imports `request` from `@shared/api`, declares its own
payload types, and calls its routes directly: `request<PerfTurnsPayload>('/api/perf/turns?...')`.
No entity edits `shared/api`, and no row waits on it. The existing `control` object stays for
the routes that predate the rebuild; only the orchestrator extends it. A pending route is
detected by its response: `@shared/api` exports `PendingRoute` (`{ pending: string }`) and
`pendingOf(err, 'V4-1NN')`, which returns that shape for a 404 or an `error.message` naming an
unknown route and `null` for everything else; a store carries the returned value, never mocked
rows, and any other failure is shown as an error. Entities import both; nothing redeclares them
(M2-D1's local copies are removed by the finish row).

### 8.1 The transcript reader's denominator (declared 2026-09-18, from V4-130)

The transcript reader publishes three fields beside its records and `entities/transcript` declares
all three: `unparseable_lines`, `skipped_records` (by type) and `sidechain_records`. They are not
diagnostics and they are not optional — they are the reader's **denominator disclosure**. A page
that prints N messages without saying it skipped M records and failed to parse K lines is a ratio
claim with a hidden denominator, which is the one defect this console has paid for repeatedly in
another plane. Three transcripts on the development box already carry pre-existing unparseable
lines, and sidechain records are excluded from the conversation by design, so all three counts are
routinely non-zero and a reader who is not told will read N as the whole.

A non-zero count is shown, not logged. Zero may be silent.

### 8.2 The error envelope is read wrong today (V4-140, fix in `m3-review`)

Section 8 above says `request<T>` "carries the management key, the 401 lockout and the error
envelope". It does not carry the envelope. `shared/api/index.ts` reads `error?.message`, while the
daemon sends `{"error": "<text>"}` — `error` is a **string** — so every refusal falls through to
`HTTP <status>` and the daemon's sentence is discarded at the transport. The cast on that line
declares the shape it expected, which is why TypeScript never objected: a declaration standing in
for a measurement.

Two consequences, both already paid for:

- The turns page has 400d on every poll since it was written (`pages/turns/index.tsx` and
  `widgets/rule/wire.ts` both call for perf turns with no head; `PerfRoutes.kt` refuses a blank
  head) and nothing in the UI could say so.
- `pendingOf(err, 'V4-1NN')`'s second branch is dead. It is documented to match "a 404 **or** an
  `error.message` naming an unknown route", and the message is never the daemon's text, so only
  the 404 branch can ever fire. A route that refuses with a 400 naming itself unknown is not
  detected as pending.

Until this is fixed, no console row may treat `MgmtError.message` as the daemon's words.

### 8.3 The coverage wall reads DAEMON files, and no daemon gate knows it (declared 2026-09-18)

The console's coverage denominator is parsed at test time out of three sources, none of which the
console owns (`src/shared/coverage/denominator.ts`):

| source | what is parsed | constant |
|---|---|---|
| `gateway/core/.../config/Knob.kt` | every enum entry name | `KNOB_SOURCE` |
| `core/src/test/resources/topology-fields.tsv`, `Topology.serializer()`'s descriptor walked and pinned by `TopologyFieldsManifestTest` (V4-312) | every field's dotted path that holds a value (a leaf) | `TOPOLOGY_MANIFEST` |
| `.dev/web-console/FEATURES.md` sections 2.1 and 6 | the backticked spans of each route table's FIRST column | `FEATURES_SOURCE` |

Read that list from `denominator.ts`, never from memory. The topology row was a grep of seven
source files for `@SerialName` until V4-312, which missed every field named by its property
(`[projects]`, all of `[compaction]`: 41 of 115 fields) and counted enum values as keys. The
manifest is the serializer's own walk, so a field in any package is counted, and a daemon row that
adds one fails `TopologyFieldsManifestTest` in its own module before the console wall sees it.

That is deliberate and stays: a denominator the console writes for itself cannot fail for a key
the console forgot. **The consequence is a one-way cross-plane dependency.** A daemon row that
adds a `Knob` entry or a splice.toml field reddens `webui/tests/coverage.test.ts` in the same commit,
and the daemon row's own verify — gradle, ast-grep, knob-keys-documented, schema-keys-consumed —
cannot see it. Measured: `f9e19d00` added `ACTIVITY_RETENTION_DAYS` and `ACTIVITY_STORE_HEADS`,
the wall went red, and every gate that row ran was green.

So: **a row that edits any file in that table runs `cd webui && npx vitest run tests/coverage.test.ts`
before its receipt.** It needs no build and no browser, it is seconds, and it is the only leg in
either plane that reads both sides of the seam. The remedy for a red is one line: the new name
gets a disposition in the page that owns it, or `pending` naming the row that will.

The console half of the same seam: a name is dispositioned by the vocabulary its SOURCE declares
— knobs by enum entry name, topology by `@SerialName` value — never by the config key the payload
carries. A page's `coverage.ts` and the tests around it compare against the parsed denominator as
a SET, never against a count: a count beside a parser is pinned to the day it was written, and it
goes stale silently on the side that matters (`toHaveLength(45)` stood beside this wall for a day
while the daemon reported 47, and the test whose title claimed every knob was covered was proving
it over 45 of them).
