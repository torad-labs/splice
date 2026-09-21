# Web console build contracts

The lattice every console row builds against. Rows run in parallel in one worktree, so the
names below are fixed before any row starts: a builder uses them as written and never invents
a sibling. A change here is an orchestrator edit with a dated note on every row it touches.
Product truth lives in `FEATURES.md`; the look lives in the surface brief and the approved comp
(`webui/.impeccable/mocks/team-board-a.png`); this file carries only what two rows must agree on.

Paths are relative to `webui/`. Layers and their import direction are the existing
eslint-plugin-boundaries wall: `app > pages > widgets > features > entities > shared`.
HTTP only in `entities/*/api` through `shared/api`.

## 1. Tokens (`src/shared/tokens.css`, row M1-01)

Both themes are complete token sets on the root element: `:root, :root[data-theme="dark"]`
is the dark room and the default; `:root[data-theme="light"]` is the paper bay.
Every color below exists in both. Spacing and type sizes are only ever used through
`var(--space-N)` and `var(--text-N)` (ast-grep wall `webui-css-tokens-only`).

Scales (values are the dark theme's; the light theme keeps the same scale):

| token | value | use |
|---|---|---|
| `--space-1` .. `--space-8` | 2, 4, 8, 12, 16, 24, 32, 48 px | every gap, pad, inset |
| `--text-1` .. `--text-6` | 12, 14, 16, 18, 20, 23 px (re-derived 2026-09-18 from the comp's measured cap heights in build/scaffold/layout.css: chat label 14, bay label 16, rule text 17 to 18, wordmark 23; the first ladder was authored, not measured, and rendered 22% small) | captions, labels, field values and figures, rule text and headers, page title, wordmark |
| `--space-0`, `--space-5` | kept, current values | read by the live sheet; `--space-11` and `--text-10` were on this row until the finish row (M3-04) measured 0 readers each and deleted them, M2 having closed |

Colors (name, role):

| token | role |
|---|---|
| `--room` | page ground (dark: the comp's room, measured `#0C1010`, shipped `#0B0E0E`; light: the comp's matte panel) |
| `--room-deep` | rail ground, one step darker than the room |
| `--hairline`, `--hairline-strong` | the COLOR of hairline rails and rules (`--hair` stays the existing 1px width token, used by about twenty old rules; a rail is `border-top: var(--hair) solid var(--hairline)`) |
| `--ink`, `--ink-mute`, `--ink-strong` | text on the room |
| `--strip` | strip paper. Dark: the comp's strips, measured `#DDD8C6`..`#DFD9C8`. Light: NOT a colour, two constraints — cream cast `R - B` within a step of `+24`, and `L <= 242` so the paper can carry its own lit edge. (`#F6F6F3` stood here and was authored: only 232 of the comp's 1,572,864 pixels are within 6 of it, 0.015%. The comp's real paper is `#DED6C4`. The defect was not lightness but the loss of the cream — R-B is +26 in the comp, +24 in the dark token, and was +3 in the light one, so the light theme's strip had silently become white card.) |
| `--strip-ink`, `--strip-ink-mute` | text on a strip |
| `--strip-field`, `--strip-field-line` | boxed field fill and its box line |
| `--edge-green`, `--edge-amber`, `--edge-red`, `--edge-grey` | holder edge states |
| `--scope`, `--scope-ink`, `--scope-grid` | chart inset ground, ink, grid |
| `--focus` | the visible focus ring, 2px, offset 1px |
| `--font-label` | Archivo (variable), labels and copy |
| `--font-figure` | the figure face, `font-variant-numeric: tabular-nums` (JetBrains Mono was authored, not measured, and row M1-15 is re-deriving it from the comp's own advance) |
| `--dur-1`, `--dur-2`, `--dur-3` | 120, 240, 400 ms; every transition under `--dur-3` |
| `--ease-out` | the one easing |

EVERY NUMBER IN THIS TABLE IS A MEASUREMENT OFF `webui/.impeccable/mocks/team-board-a.png`, NEVER A
NAME. This paragraph exists because three of them once were names: the room was written up as
"graphite `#1B1D1C` class" and the strips as "pale `#ECEAE2` class", and neither was ever sampled
from the comp, which measures `#0C1010` and `#DDD8C6`..`#DFD9C8`. The m1 design review's finding
B12 then read the build against this prose, called the room "near-black, not graphite", and was
withdrawn on remeasurement: the build sits 0.98 of a luminance point from the comp, and the
CONTRACT was the thing that disagreed. When a token and this table diverge, sample the comp and
correct whichever one the comp contradicts.

AND FOR THE LIGHT THEME THERE IS NO COMP TO SAMPLE, so a light value is written as a RELATIONSHIP to a
measured dark one and never as a colour of its own. All three comps of record are dark; the only light-ground
artifacts in the tree are under `decision/`, which comp-spec excludes because they are the REJECTED incumbent —
`decision/canon.png` is the blue-accent, pill-button, icon-nav look this world replaced. It is an anti-reference.
Headroom is the reason this is a rule and not a preference: a dark plane has the whole range above it and a light
plane has almost none (paper has 216.7 L below it in dark and 9.2 above it in light), so a material carried across
unchanged clips. Magnitude comes from the comp, sign comes from headroom, and the value is written as an alpha or a
`color-mix` against its own ground — never a hex, which fixes the sign, and never a `filter`, which fixes the
direction. See campaign law 26.

Old tokens: `--font-mono` alone still stands, aliased to `--font-code` and read twice. `--paper-0`, `--ink-900` and `--surface` are GONE, deleted by the finish row (M3-04)
once their last consumer went. New code never uses them. `--ink-900` is the one worth remembering: it read as live because `--tick` named it, and `--tick` itself had no reader anywhere -- dead one level down is still dead.

Theme switch (`src/features/theme`): `useTheme(): { theme: 'dark' | 'light', set(theme) }`.
Opens dark regardless of the OS. A manual choice is stored under `localStorage['splice.theme']`.
Switching is a cut: no transition on `--room` or `--strip`. `prefers-reduced-motion` sets every
`--dur-N` to `0ms`.

## 2. Primitives (`src/shared/ui`, row M1-02)

Every existing export (`Panel`, `StatusPill`, `Metric`, `Btn`, `Field`, `Well`, `EmptyState`,
`ErrorNote`, `SkeletonRows`, `Stale`, `MeterBar`, `ConfirmBtn`) keeps working until M2 removes
its last consumer; the finish row deletes what is left. New primitives, all named exports from
`@shared/ui`:

```ts
type Edge = 'green' | 'amber' | 'red' | 'grey';
type Basis = 'measured' | 'estimated' | 'unavailable' | 'stale';

// A printed strip. Focusable (tabIndex 0, Enter/Space call onOpen). Cocked = holder edge
// amber or red with the edgeLabel printed; struck = line drawn across, edge grey.
// edgeLabel has a SIX CHARACTER budget (2026-09-18, review B1): it sits inside the strip's
// fixed-width edge column, and that fixed width is what makes every strip in a bay start its
// fields at the same x. A longer label used to set each strip's field origin and stagger the rack.
<Strip edge={Edge} edgeLabel="string, 6 characters or fewer" cocked?: boolean struck?: boolean
       selected?: boolean onOpen?: () => void ariaLabel="string">{Field children}</Strip>

// A fixed-width boxed field inside a strip. w is a ch count; the value is clipped, never wrapped.
// `label` is OPTIONAL as of 2026-09-18 (review B9): a rack of homogeneous strips declares its
// columns ONCE on the bay head and omits the label on every row, which is what stops the column
// names being reprinted on all of them. A strip whose fields differ from its neighbours keeps its
// labels. `basis` is optional in the type, so a caller with exactOptionalPropertyTypes must omit
// the prop rather than pass undefined.
<StripField w={number} label?: string value={string | number} basis?: Basis mono?: boolean />

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
  `Disposition = { kind: 'knob' | 'topology' | 'route'; name: string; disposition: 'editable'
  | 'read-only' | 'excluded' | 'pending'; where?: string; reason?: string }`. `excluded`
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
  the exact normalized path (`/api/perf/turns`), never a glob.
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
| six schema files — `topology/Topology.kt`, `topology/QuirksConfig.kt`, `topology/TopologySchema.kt`, `prompt/HeadSystemPrompt.kt`, `model/TokenCost.kt`, `compaction/CompactionScope.kt` | every `@SerialName` value | `TOPOLOGY_SOURCES` |
| `.dev/web-console/FEATURES.md` sections 2.1 and 6 | the backticked spans of each route table's FIRST column | `FEATURES_SOURCE` |

Read that list from `denominator.ts`, never from memory: **half of the "topology" sources are not
in the topology package.** `HeadSystemPrompt.kt`, `TokenCost.kt` and `CompactionScope.kt` live in
`prompt/`, `model/` and `compaction/`, and a seat editing one of those has no reason to suspect a
console wall is reading it. (This table said "the six `topology/*.kt` files" in its first draft —
the right count beside the wrong glob, which would have produced exactly the miss it exists to
prevent.)

That is deliberate and stays: a denominator the console writes for itself cannot fail for a key
the console forgot. **The consequence is a one-way cross-plane dependency.** A daemon row that
adds a `Knob` entry or a `@SerialName` reddens `webui/tests/coverage.test.ts` in the same commit,
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
