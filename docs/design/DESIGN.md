---
name: splice console
description: The operator console of a Claude Code gateway daemon. A departure board for the sessions in flight, grey everywhere else, and one colour per head carried to every place that head appears.
colors:
  bg: "#0b0c0e"
  bg-raised: "#131418"
  bg-hover: "#1a1c20"
  bg-active: "#20232a"
  line: "#23252b"
  line-strong: "#30333a"
  fg: "#ededf0"
  fg-muted: "#a3a6ae"
  fg-subtle: "#878a93"
  ok: "#4fbf87"
  warn: "#e0a44a"
  danger: "#f07171"
  head-1: "#6eadfe"
  head-2: "#ee8f58"
  head-3: "#00c5be"
  head-4: "#e686be"
  head-5: "#a0b74d"
  head-6: "#b896f3"
  head-7: "#1fbcea"
  head-8: "#c9a732"
typography:
  ui:
    fontFamily: "'IBM Plex Sans', ui-sans-serif, system-ui, sans-serif"
    fontSize: "var(--text-1)..var(--text-6)"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  figure:
    fontFamily: "'IBM Plex Mono', ui-monospace, monospace"
    fontSize: "var(--text-1)..var(--text-7)"
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: "normal"
    fontFeature: "font-variant-numeric: tabular-nums"
rounded:
  mark: "4px"
  control: "6px"
  panel: "8px"
  pill: "999px"
spacing:
  1: "2px"
  2: "4px"
  3: "8px"
  4: "12px"
  5: "16px"
  6: "24px"
  7: "32px"
  8: "48px"
components:
  button:
    backgroundColor: "{colors.bg}"
    textColor: "{colors.fg}"
    rounded: "{rounded.control}"
    padding: "4px 12px"
  button-primary:
    backgroundColor: "{colors.fg}"
    textColor: "{colors.bg}"
    rounded: "{rounded.control}"
    padding: "4px 12px"
  nav-item-current:
    backgroundColor: "{colors.bg-active}"
    textColor: "{colors.fg}"
    rounded: "{rounded.control}"
    padding: "4px 8px"
  head-mark:
    backgroundColor: "{colors.head-1}"
    rounded: "{rounded.mark}"
    size: "8px x 16px"
  board-row:
    backgroundColor: "{colors.bg}"
    textColor: "{colors.fg-muted}"
    height: "44px"
---

# The splice console

This file is the console's identity: what it looks like, why, and the rules a page follows. It
replaces the strip-bay world ("paper strips racked in steel bays"), which the operator retired on
2026-09-24. `docs/design/PRODUCT.md` still says who the console is for; this file says how it
looks. The tokens it names live in `console/src/shared/tokens.css`, and the walls that hold them
are in `quality/rules/console/`.

## 1. The idea in one paragraph

A developer has several Claude Code sessions running on several heads. They open the console
for one answer and go back to the terminal. So the console is built like an airport departure
board: one line per session, fixed columns, the state printed in words, and the one thing that
matters most shown in colour. Here that thing is the head. Every head gets its own colour, and
that colour follows the head everywhere: its sessions on the board, its turns, its bars in the
usage charts, its lines in the log, its scope in settings. The rest of the console is grey. When
the operator sees orange anywhere, it means the same head.

## 2. What we studied

Twenty products: ten consoles in splice's own trade, and ten designs known for being well made
and fun to use. Screenshots stayed in the study seats' scratchpads; this list cites public pages
only, and nothing needed a login.

### Ten gateway and proxy consoles

Weighted as asked: claude-code-router and Langfuse count most, Kong and TrueFoundry least.

| # | Product | Public source | Why it counts | What we took |
|---|---|---|---|---|
| 1 | claude-code-router | github.com/musistudio/claude-code-router | 37.4k stars; the nearest product to splice (it routes Claude Code) | One card shows a provider's several quota windows side by side ("primary quota (4h) 97%", "secondary quota (7d) 94%"); an endpoint pill with a live dot |
| 2 | Langfuse | langfuse.com/docs/observability | 35k stars; the open reference for LLM tracing | The session → trace → step hierarchy; a waterfall bar that prints its duration and cost inline |
| 3 | LiteLLM | github.com/BerriAI/litellm, docs.litellm.ai | 57.9k stars, 240M+ Docker pulls | A grouped sidebar (gateway, observability, access control); ↑K/↓J to step between requests in the drawer |
| 4 | OpenRouter | openrouter.ai, openrouter.ai/blog/announcements/activity-dashboard | 500T+ tokens a month (its own claim); ranks Claude Code as its top app | The request drawer's routing waterfall ("routing 16ms → provider 420ms"); the per-message token bar coloured by role |
| 5 | Portkey | portkey.ai, docs.portkey.ai/docs/product/observability | 24,000+ organisations (GlobeNewswire, 2026-03-24) | An icon strip on every log row saying what the gateway did (cache, retry, fallback) without opening it |
| 6 | Helicone | helicone.ai, docs.helicone.ai/features/sessions | YC W23; 11.1B requests on its live counter | Timing, call tree and transcript on one screen |
| 7 | Vercel AI Gateway | vercel.com/ai-gateway, vercel.com/docs/observability | 200k+ teams | A log filter rail on the left, and a detail panel with its own address |
| 8 | Cloudflare AI Gateway | developers.cloudflare.com/ai-gateway | 500M requests in beta, 100k+ developers | Stat tiles over a tab bar; nothing else worth copying |
| 9 | TrueFoundry AI Gateway | truefoundry.com/ai-gateway | $19M Series A | p50, p75, p90 and p99 as tabs over one chart |
| 10 | Kong AI Gateway | konghq.com/products/kong-ai-gateway | 900+ enterprise customers | A query builder for analytics; too heavy for one operator, so we took nothing |

Two findings shaped the plan. None of the ten show a rolling five-hour or seven-day plan window
with its reset time, except claude-code-router's quota card, so splice's accounts and usage pages
start from that card. And every one of them uses one brand accent on the chrome, usually blue or
indigo, so a console with a grey frame already stands apart.

### Ten acclaimed designs

Chosen by claude-builder for acclaim you can cite and for being fun without being busy.

| # | Design | Public source | Acclaim | Its one bold place |
|---|---|---|---|---|
| 1 | Flighty | flighty.com | Apple Design Award 2023, Interaction | The departure board: one line per flight, and status carries the colour |
| 2 | Things 3 | culturedcode.com/things | Apple Design Award 2017 | One control with real physics (the Magic Plus button) |
| 3 | Halide Mark II | halide.cam | Apple Design Award 2022, Visuals | An instrument, not an app; one yellow on near-black |
| 4 | (Not Boring) Habits | notboring.software | Apple Design Award 2022, Delight and Fun | One rendered object per screen, everything around it quiet |
| 5 | CARROT Weather | meetcarrot.com/weather | Apple Design Award 2021, Interaction | The fun is in the words, with a dial that turns it off |
| 6 | Linear | linear.app | A whole design trend is named after it | Speed: every action is on the keyboard, and colour is only for status |
| 7 | Raycast | raycast.com | Product Hunt Golden Kitty 2024 | The command palette is the product |
| 8 | Teenage Engineering OP-1 field | teenage.engineering/products/op-1 | Design S Gold 2012; SFMOMA collection | Colour as a binding: the blue knob moves the blue thing |
| 9 | Playdate | play.date | "All it's cranked up to be" (The Verge, 2022) | One playful affordance, the crank, and one yellow |
| 10 | landonorris.com | landonorris.com | Awwwards Site of the Year (announced 2026-02) | One electric accent, and a small "next race" card that is always there |

The lesson common to all ten: each spends its boldness in one place and keeps the rest quiet,
and the bold part always does a job.

## 3. Principles

1. **Colour means a head.** The one bold place is the head colour (OP-1). Eight hues, assigned in
   the daemon's own head order, used for that head and nothing else. The chrome has no hue of its
   own. A status colour (ok, warn, danger) only appears beside its word.
2. **The name always travels with the colour.** A colour mark is never alone: the head's name sits
   beside it, so the screen still reads in greyscale and for colour-blind readers.
3. **One line per thing.** A session, a turn, a log line and a knob each take one row with fixed
   columns (Flighty). Details open beside the list, never in a modal, and the list stays in view
   (Vercel, OpenRouter).
4. **Big numbers only where the number is the point.** Plan left, turns in flight and cache hit
   rate get a large figure. Everything else is table-sized.
5. **Always know the state of the daemon.** A slim status strip sits on top of every page: link
   state, daemon health, the nearest plan limit and when it resets (Lando's race card, the
   claude-code-router endpoint pill).
6. **Everything is one keystroke away.** ⌘K or / opens the palette, which reaches every page,
   view, head and theme (Raycast, Linear). The sidebar shows the shortcut so people learn it.
7. **Say it plainly.** Lowercase, short, the fact first (PRODUCT.md's voice). Fun lives in the
   empty states and never in the data.

## 4. The default check

What a Linear or Vercel clone would pick on each axis, what splice picks, and why.

| Axis | A Linear / Vercel clone | splice | Why |
|---|---|---|---|
| Type | Inter (Linear) or Geist (Vercel); mono only for code | IBM Plex Sans for words, IBM Plex Mono for every figure: times, tokens, percentages, ids | Plex was drawn for engineering instruments and its figures read like a readout. Mono figures don't jitter as live numbers tick. And it is neither of the two defaults |
| Colour | Linear: grey plus one brand accent (indigo) on the chrome. Vercel: the chrome is its grey scale (gray 100 to 1000 for grounds, borders and text, vercel.com/geist/colors), the primary button is the ink, and blue is kept for links and focus | The same ink-on-grey chrome as Vercel, with the focus ring in the ink too. Eight head colours are the only hues, plus status colours next to their words | On the chrome splice agrees with Vercel, and departs from it only by the head hues. The question the operator brings is "which head?", and colour that answers it is worth more than colour that brands the frame |
| Layout | A sidebar, a page header, a grid of cards | A sidebar, an always-on status strip, one board per page, detail opening beside the list; stat tiles only on number pages | A card grid hides row order, and row order is the data here |
| Nav | A flat list of features, or workspace first | Four groups named for splice's own objects (in flight, routing, plans, daemon). Home is sessions. ⌘K reaches all of it | See section 6: the grouping follows the session, its head, its plan and the daemon under them |
| Motion | Springs and fades on everything | Nothing a keyboard triggers animates. Hover and open take 120 to 180ms. A status change on the board cross-fades its word, and that is the only motion with meaning | A console opened between terminal sessions must feel instant |
| Density | Airy, 48px rows | 44px board rows, 36px table rows, 13px table text | The operator reads many sessions and heads at once |

## 5. The bold place: head colour

- **Palette.** `--head-1` to `--head-8`: blue, orange, teal, pink, lime, violet, sky, gold. They
  were picked in OKLCH at even lightness, with the most distinct hues first, so a daemon with
  three heads gets the three most distinct colours. Dark values sit at L 0.74, and light values at
  L 0.54 so they stay above 4:1 on white. Every value clears 3:1 against its ground (non-text
  contrast, WCAG 1.4.11); most clear 7:1 in dark.
- **Assignment.** A head's colour is its index in the daemon's registry (`GET /api/status`,
  `registry`), mod 8. The registry keeps topology order, so adding a head never changes an
  existing head's colour. A session with no splice head, or a head the registry does not list,
  gets the neutral grey.
- **Shape.** A head mark is a rounded 8×16 bar before the head's name (`HeadMark`, in
  `@entities/control-status`). In a chart the head's series takes the hue. A board grouped by head
  gives the group title a mark. Status never uses this shape: status is a round dot followed by
  its word.
- **Where it appears.** Sessions (head column and group titles), turns (head column and waterfall
  bars), fleet (each head's title), usage (each head's row and chart series), logs (the head
  filter, the band on the stream's bar, and a head column when a tail carries several heads),
  settings (the scope picker), accounts (the heads an account serves).

## 6. Navigation and information architecture

The 13 pages were 13 flat peers. They now sit in four groups, built from splice's own objects
rather than from what other proxies put on their home pages:

| Group | Object | Pages | Answers |
|---|---|---|---|
| in flight | the session in flight | sessions, turns, teams, projects | who is working, where, how fast |
| routing | the head | fleet, models, compaction | where the work goes, and on which model |
| plans | the account window | accounts, usage | how much plan is left, and who spent it |
| daemon | the daemon under all of it | settings, mcp, logs, doctor | how it is tuned, and is it healthy |

- **Addresses stay.** `#/fleet` and the rest are unchanged, and no page is renamed. What changes
  is the order: `ADDRESSES` in `app/rows.ts` follows the nav, so the console opens on sessions,
  the first object.
- **The shell.** A 232px sidebar holds the wordmark, a "jump to" button showing ⌘K, the four
  groups, and the theme switch at the bottom. Each item is an icon and its label. The current
  page gets the active ground, full ink and weight 500, plus `aria-current="page"`. The
  sidebar's arrow-key movement is kept.
- **The status strip.** A 44px strip runs along the top of the page column. It holds the link
  (a live dot and its word), daemon health, the nearest plan limit with its reset countdown, heads
  without limits, a pending restart, and the local and UTC clocks. It never moves the layout.
- **Phone width.** Below 720px the sidebar becomes a horizontal row of items above the page, with
  no group labels, and the status strip wraps.

## 7. The four surface types

Every page is one of four types. Build new pages from these.

### A live list: the departure board (Sessions)

- One row per session, 44px tall, with fixed columns: session, head (with its mark), project,
  started, seen, last hand-off, status. Times use the mono face.
- The status column comes last, as on an airport board: a dot and a word (`live`, `stale`,
  `gone`). A stale row gets a warn tint on its leading edge, because it is the one that needs the
  operator.
- Above the board sits a summary line of large figures: live, stale and gone counts.
- Views (by head, by project, by team, timeline) are tabs under the title. A grouped view gives
  each group a title row, with the head mark when grouped by head.
- The whole row opens the session. The detail panel opens on the right and the board narrows; it
  never covers the board.

### A numbers page (Usage)

- Plan windows come first, one card per plan, using claude-code-router's shape: each window is
  a big percentage, a meter and "resets in 2h 3m". A meter turns warn at 75% and danger at 90%,
  and the word says so.
- Then a stat row: tokens, turns, cache hit and cost for the chosen window.
- Then the heads table: a head mark, figures in mono, and a share bar in the head's colour.
- Charts use head hues for per-head series and greys for kinds of token (fresh input, cache read,
  cache write, output), so a hue always means a head.

### A form (Settings)

- The scope picker sits under the title: `global`, then one chip per head with its mark.
- Groups of knobs run in a single column, with a sticky list of groups on the left for jumping.
- A knob is one row: the name, the TOML key in mono under it, one line saying what it does, the
  input with its unit, where the value comes from (`default`, `toml`, `head`, `state`, `env`) as
  a quiet badge, and `applies live` or `on restart`.

### A stream (Logs)

- A filter rail on the left: head (chips with marks), tail length, tag, level, search.
- The stream fills the rest: time in mono, then the message. `key=value` pairs are split so keys
  print in the subtle ink and values in full ink, which makes a perf line scannable.
- The stream's bar wears the tailed head's band, the wash a head's run takes on the sessions
  board. The daemon's `/api/logs/{head}` answers with that head's lines only, so a head column
  prints only for a tail that carries several heads, where it shows each line's mark.
- Scrolled, the top rows fade under the column names instead of being sliced by them, and a
  wrapped line cut by the top keeps its time in view.
- Following pins the newest line to the bottom. While paused, a bar says how many lines arrived.
- A request capture panel sits under the stream, closed until opened.

## 8. Tokens

All values live in `console/src/shared/tokens.css`. A page sheet may only read them: spacing
through `var(--space-N)` (`webui-css-tokens-only`), font size through `var(--text-N)`
(`webui-css-font-size-scale`), and colour only in the token sheet (`webui-css-no-color-literals`).

### Scale

| Token | Values |
|---|---|
| `--space-0..8` | 0, 2, 4, 8, 12, 16, 24, 32, 48 px (in rem) |
| `--text-1..7` | 12, 13, 14, 16, 20, 24, 32 px. 12 for captions and column labels, 13 for table cells and controls, 14 for body and nav, 16 for section titles, 20 and 24 for page titles, 32 for a number that is the point |
| `--leading-tight`, `--leading` | 1.25, 1.5 |
| `--radius-1..3`, `--radius-full` | 4px (marks, badges), 6px (controls), 8px (panels, cards), pill |
| `--dur-1..3` | 120, 180, 240ms; 0ms under `prefers-reduced-motion` |

### Colour, dark (default) / light

| Role | Dark | Light |
|---|---|---|
| `--bg` page | `#0b0c0e` | `#ffffff` |
| `--bg-raised` sidebar, panels, table heads | `#131418` | `#f7f7f8` |
| `--bg-hover` | `#1a1c20` | `#f1f2f4` |
| `--bg-active` current page, selected row | `#20232a` | `#eaecf0` |
| `--line`, `--line-strong` | `#23252b`, `#30333a` | `#e4e5e9`, `#d3d5db` |
| `--fg` text (16.8:1 / 18.1:1) | `#ededf0` | `#15161a` |
| `--fg-muted` (8.0:1 / 7.7:1) | `#a3a6ae` | `#50535c` |
| `--fg-subtle` (5.7:1 / 5.9:1) | `#878a93` | `#61646d` |
| `--accent` primary action, the ink itself | `#ededf0` | `#15161a` |
| `--ok`, `--warn`, `--danger` | `#4fbf87`, `#e0a44a`, `#f07171` | `#166b44`, `#855304`, `#b02f2f` |
| `--head-1..8` | `#6eadfe #ee8f58 #00c5be #e686be #a0b74d #b896f3 #1fbcea #c9a732` | `#266ec3 #b04d00 #008882 #a84482 #647a00 #7d56b8 #007eaf #8e6900` |
| `--series-1..4` kinds of token | the ink, stepped from `--fg` to `--line-strong` | the same, stepped |

Every text and ground pair clears WCAG AA in both themes, and `console/tests/contrast.test.ts`
computes them from the token sheet.

### Type

IBM Plex Sans (variable, weights 100 to 700) and IBM Plex Mono (400, 500, 600), latin subset,
SIL OFL 1.1, embedded in the single-file build (`shared/fonts/`). No network fonts. Body 14px,
tables 13px. Page titles 24px at weight 600. Figures use Plex Mono with tabular numbers.

## 9. Components

The building blocks are in `console/src/shared/ui/kit.tsx`, and pages compose them:
`PageHeader`, `Section`, `DataTable` (the board is a DataTable whose rows open), `Badge` (a dot and
a word), `Stat` and `StatRow`, `Meter`, `DetailPanel`, `KeyValue`. `HeadMark` lives with the
registry it reads (`@entities/control-status`). Controls (`Choice`, buttons, inputs) keep their
behaviour and take the new look.

## 10. Voice

- Lowercase, per PRODUCT.md: `sessions`, `by head`, `about this list`. Labels are three words or
  fewer (the label wall).
- The fact comes first: `resets in 2h 3m`, not `your plan window will reset in`.
- Absent values keep their vocabulary (`–`, `none`, `unknown`, `unavailable`, `ineligible`,
  `not built`); each is a different fact.
- Empty states may be warm, and only they may be: "no sessions in flight. start one with a head,
  like claudex, and it lands here."
- No em-dashes in UI text (the copy gate).

## 11. Accessibility

- AA contrast for all text in both themes. Marks and meters clear 3:1.
- A visible focus ring on everything focusable: 2px in the ink colour, offset 2px.
- Colour is never the only signal: head marks carry the name, and status dots carry the word.
- `prefers-reduced-motion` zeroes every duration.
- Keyboard: the palette on ⌘K or /, arrow keys in the sidebar, Tab to every row opener, Escape
  closes a panel.

## 12. What this replaces

The strip-bay world is retired: paper strips, holder edges, bays, the Archivo and JetBrains faces,
the `--root-size` viewport scalar, and the comp of record with the tools that measured against it
(`console/.impeccable/`, `console/tools/src/commands/{comp,gate,look,typography}.ts`,
`docs/design/type-spec.md`). The old token names stay mapped to the new roles in one marked block
of `tokens.css` until the last page moves, and then they are deleted.
