# V4-444 phase A — direction contract for the splice console

Status: the direction is approved ("Yes, keep going") and the operator picked variant D, the calm version ("D, all three"), as the rule for all five pages. Nothing under `console/src` changes until the replacement reaches parity. Every comp is seeded demo content
(no real session names, messages, repos or paths). Render: `node docs/design/comps/build.mjs` → `png/<screen>-<day|night>-<1440|1920>.png`. D is in `calm.css`, loaded after `comp.css` and the page's own css.

## Direction

> Reading this as: the splice console for one person running several coding agents on their own computer, seated at the film's desk,
> under the site's day wall (Day) and the film's night wall (Night) — composed, still, airy.

The console is the site's and the film's world, not a new one. Sources, all in `torad-main` (branch `feat/splice-product-page`):

| What | Where |
|---|---|
| Palette, walls, paper, model colours | `products/website/web/framework/torad-tokens.css` (`--cel-*`), the four film hours |
| Objects: ivory paper, dark outline, hard cast shadow in the model's colour | `tools/build/bodies/products-splice.html` (`.sp-win`, `.sp-plug`, `.sp-shot`) |
| The console as the film draws it: session rows on a rail, toggles, tanks, hand-offs riding the rail | `products/website/video/src/films/splice-cel/console.tsx`, `bits.tsx` |
| Type | Fraunces (names), Source Serif 4 (reading), IBM Plex Mono (commands, ids, counts). Files copied to `comps/fonts/` |
| Stills | `Splice-Animator/renders/cel-2026-09-28/r16` (console beat), `r18` (windows on the rail) |

Rules the comps follow (each one is a thing he rejected, turned around):

1. **Objects, not rows.** A session, a plan and a needs-you item is one paper window with its model's colour on the back edge. No table of one-line rows anywhere.
2. **Void before border.** Only windows, inputs and the selected state carry an outline. Sections are separated by space and type.
3. **Opening a thing goes to its own page.** No side panel that narrows a list. The Sessions comp has no detail pane; the session has a page.
4. **Rich where the content is rich.** Messages are Source Serif prose with headings, lists, tables and code (highlighted); tool calls are collapsible dark-glass blocks; hand-offs from another session are a dashed, model-coloured card, visibly not a person's message.
5. **Human names.** Sessions read by their title, repos by name (`tally`), plans by their command. Ids, paths and TOML keys appear on demand (Settings row's key chip; hover for a path).
6. **Typed controls.** Toggle, segmented choice, select (drawn open), stepper, slider, folder picker, secret field. Familiar behaviour; the film's paper material.
7. **Order is the operator's.** Cards carry a grip and drag. Sessions order and Fleet order persist in the browser (no daemon field exists).
8. **Only live things need him.** Needs you lists waiting, stuck, out-of-quota and signed-out; not an idle session or a runtime he switched off.
9. Charged act = one vermilion button per item. One fix per item; the second action is quiet text.

Two themes, one geometry. **Day**: paper objects on the day wall (`#e9dcc0`), 3 px ink outline, `8px 9px` hard shadow in the model colour.
**Night**: the film's console, warm dark objects on the night wall (`#1d2140`), 3 px hairline outline, same shadow.

## D, the design law

Why it felt busy, and what he chose (variants A–D compared on Sessions and a session, Day, 1440): the outlines, too little room, too much per
card, mono everywhere, and several colours on one card. D answers all of them. Every page and every new surface is built against these five
rules; `calm.css` is their CSS and a page that needs a rule `calm.css` does not have gets it there, not in a page stylesheet.

1. **Fewer outlines.** One edge treatment: the window's 3 px outline with the model-hued shadow. Chips and pills are a quiet tint with no border;
   a state is a dot and a word; secondary buttons are underlined text (only the one charged act is a filled button); no frame inside the frame
   (the glass block and its strip sit flush with the window edge). An outline stays only where it is a functional boundary: a window, an input,
   a selected state, an open menu. Settings controls keep a 2 px input outline in a soft ink; their TOML key chips are tint, not outline.
2. **Twice the room.** Gutters, section spacing and inner padding are about double the first comps: wall padding 56 px, gap 72 px between
   windows, 84 px above a group heading, 26 px inside a card bar. Separate rows with space, never with a rule.
3. **Less per card.** A card is a title, a state, one activity line, one quiet meta line, and at most one action. No grip on the card (the
   whole card drags), no "more" menu, no duplicate facts. Everything else lives on the object's own page. A Fleet card shows only the plan's
   tightest window; every window, Models and Log are on the card's page.
4. **Mono only for code and terminal text.** Mono is for the glass block, tool blocks, code, commands, addresses and TOML keys. Meta lines,
   buttons, nav, labels, counts, times and values are Source Serif 4; names are Fraunces.
5. **One accent per card.** The head's colour is the card's identity, and it lives in the shadow only. A card that needs a person drops the
   hue (tan shadow) and keeps the one vermilion button. State never adds a second colour: a state dot is the only tint besides that. Gauge
   fills inside the glass are the glass ink, not the head's colour. Toggles and sliders are ink.

Checks a page passes before it is shown: count the outlines a card carries (window edge only); find a mono run that is not code; find two
colours on one card; look for a rule between rows. Each is a defect.

## Foundation for phase C (decision, by easy-to-replace / decoupled / clear contract)

A new frontend on the daemon's existing `/api`, in `console-next/` beside the old one until it covers its routes; the old `console/src` is then deleted.

| Concern | Choice | Why |
|---|---|---|
| Framework, build | React 19 + Vite + TypeScript | Same runtime the daemon already embeds; nothing to learn. |
| Primitives | Radix Primitives (headless: Dialog, Popover, Select, Switch, Slider, Tabs, DropdownMenu) | Accessibility and keyboard behaviour without a look; the look is ours. Replaceable per primitive. |
| Drag reorder | dnd-kit | Keyboard-accessible sortable; independent of everything else. |
| Messages | `react-markdown` + `remark-gfm` + `highlight.js` (core, few languages) | Real markdown, tables, highlighted code, far smaller than Shiki in one inlined file; one `Markdown` component is the only place it lives. |
| Data | TanStack Query over a typed API client; payload types are the daemon's own, kept beside the client | The API contract is one file; pages never touch `fetch`. |
| Routing | react-router 7 (hash routes, deep-linkable sessions; the old addresses redirect) | Already in the lockfile; no server needed. |
| Styles | CSS custom properties from `comp.css` tokens, plain CSS per component, no utility framework | The look is bespoke and small; tokens are the contract. |

## Nav

Needs you · Sessions · Fleet · Turns · Usage · Settings. Where the rest went:

| Was | Now |
|---|---|
| Accounts | Fleet: each plan's card carries its accounts, sign-in, switch. Limits in Usage. |
| Models | A Fleet card's Models drawer. |
| Teams | Sessions (group by Team) and the team rail on a session's page. Team economics: Usage. |
| Projects | Sessions, group by Repo (by name). |
| Compaction | Settings › Conversation (its instructions); a "compacted" mark on the turn in Turns. |
| Logs | A Fleet card's Log drawer. |
| MCP | Settings › Tools. |
| Doctor | Needs you (a failing check with its one fix) and Settings › Health. A deliberately stopped local runtime is a Fleet state ("Runtime off"), never a Health or Needs you item. |
| What splice keeps | Settings › Storage. |

## Every action drawn, and what backs it

| Action (where) | Backing | Status |
|---|---|---|
| Stop the turn (session, Needs you, Sessions card) | `POST /api/heads/{head}/turns/{id}/stop`; id from `GET /api/heads/{head}/turns/live` | exists |
| Copy resume command (session, cards) | `GET /api/sessions/{id}/resume` (recipe) | exists |
| Open the session | client route | exists |
| Switch account, Sign in again / Sign in | `POST /api/auth/{head}/switch`, `/api/auth/{head}/login` | exists |
| Start a stopped head (Fleet) | `POST /api/heads/{head}/start` | exists |
| Start a local runtime that is off (Fleet) | none: the runtime is `rig`'s, not splice's | **not drawn as a button**: "Copy start command" (`rig up <head>`), a copy |
| Add a plan | `POST /api/add` (+ `/api/add/{id}/…`) | exists |
| Settings controls | `GET/PATCH /api/config`; keys `GET/PUT /api/keys/{name}` | exists |
| Drag reorder (Sessions, Fleet) | browser storage | no daemon work |
| Approve / Deny a permission prompt | none: the prompt belongs to Claude Code, splice cannot answer it | **not drawn**; future = a PermissionRequest hook on launched sessions (a security decision) |
| Send a message to a session (composer) | none: `address` is Claude Code's own socket, splice never writes to it | **daemon work**: `POST /api/sessions/{id}/message`; feasibility unverified. Drawn in the comp, marked PENDING |
| Interrupt a tool call | none: the call runs in the client | **not drawn** (Stop the turn instead) |
| Stuck | derived: `status` busy and `status_updated_at` older than a threshold | console rule |

## Fields behind what is read

| Shown | From |
|---|---|
| Session title | `name` in `/api/sessions` and `/api/sessions/history` (Claude Code's registry name; 15 of 15 live rows carry it). Fallback: repo name + start time |
| State word | `status` (`busy` → Working, `waiting` → Waiting on you, `idle` → Idle, `shell` → Working in a shell) |
| Repo | `repo` on the session row |
| One-line activity | last `TranscriptMessage` (`role`, `tool`, `text`) of `GET /api/sessions/{id}/transcript`. **Small daemon work**: a `tail=1` or a `last` field on the row so the list does not read a page per card |
| Plan windows, reset, out-of-quota | `/api/usage` `quota`; `quotaResetAtEpochSeconds` on `/api/heads` (V4-429) |
| Hand-offs, team rail | `/api/sessions/{id}/edges`, `/api/teams/{id}/edges` |

## What each of his words is answered by

| He said | Answer |
|---|---|
| "it looks terrible", "I hate everything about it" | The whole direction: the site's and film's world, one geometry, two hours. |
| "the buttons look awful, the fields look terrible" | `btn`, `seg`, `switch`, `select`, `step`, `slider`, `folder`, `secret` in the film's paper (Settings, all of them; Sessions cards). |
| "everything is a fucking edit text" | Settings: a toggle, segmented choice, select (open), stepper, slider, folder picker and secret field replace text boxes. |
| "everything is fucking list", "we can't reorder" | Sessions and Fleet are windows, not rows; every card has a grip; the order carries to the other page. |
| "everything uses weird names and codes" | Titles, repo names, plan names. The setting key is a chip on demand (Settings › Summarize at). |
| "open items that are dead" | Needs you: four live items, each with one fix; a "Not listed here" line says an idle session and a stopped runtime are not items. |
| "the session list page is absolutely confusing" | Sessions groups by what a person must do: Needs you, Working, Idle; each card says what the agent is doing in one line and in whose colour. |
| "we open each detail next to it which is all super tight" | The session opens on its own page; no side panel anywhere. |
| "we dont have fucking rich UI anywhere" | Session page: prose, table, highlighted code, collapsible tool blocks, a team rail with hand-offs riding it; Fleet: gauges; Settings: typed controls. |
| "messages don't have any styling or formatting" | The session page's conversation is rendered markdown; a peer's hand-off is a distinct dashed card. |
| "the repo name has the full path" | Repos read `tally`, `ledger-api`, `harbor-web`; no path appears in any comp. |

## Settings: what backs every control

A control is drawn only if it maps to a knob (`GET`/`PATCH /api/config`; the knob table `Knob.kt`), a topology key (`GET`/`PUT /api/topology`, i.e. `splice.toml`), a route, or the browser. "Advanced" lists every knob the curated sections do not, each as a typed control from the same table, with the head scope switcher, so no knob leaves the console.

| Section › control | Control | Backing | Applies |
|---|---|---|---|
| General › Appearance | segmented: Day / Night / Match my computer | browser storage `splice-theme` | at once |
| General › Open the console at | read-only address + Copy | `location.host` | n/a |
| General › Warn me when a plan is this full | slider 50–100% | knob `usageWarnPct` | restart (says so, offers "Restart now": `POST /api/daemon/restart`) |
| General › Detailed log | switch | knob `debug` | restart |
| Conversation › How hard the model thinks | segmented | knob `effort` (`''` = model default, then low/medium/high; the rest in Advanced) | restart |
| Conversation › Turns at once, per plan | stepper | knob `maxInflight` (0 = no limit); a head's own value in its Fleet card | at once |
| Conversation › Show the model's reasoning | select: In the reply / As thinking / Hidden | knob `showReasoning` = `text` / `thinking` / `off` | restart |
| Storage › Keep message history for | select of days | knob `activityRetentionDays` | restart |
| Storage › Keep request traces for | select of days | knob `traceRetentionDays` | restart |
| Storage › Show git branches in | folder chips + add | knob `statuslineGitRoots` (colon-separated) | at once |
| Storage › OpenRouter key | secret field, Replace | `PUT /api/keys/OPENROUTER_API_KEY`; state from `GET /api/keys` | at once |
| Tools › Share tools across sessions | switch | topology `daemon.mcp_hosting` via `PUT /api/topology` | restart |
| Tools › per-server state and switch | word + switch | state `GET /api/mcp`; switch adds or removes the server in topology `daemon.mcp_hosting_exclude` | restart |
| Health › Everything splice depends on | word + Check again | `GET /api/doctor` (Check again re-reads it) | n/a |
| Health › a failing check | word + its one fix | the check's own remedy: `POST /api/doctor/fix/{id}` where the daemon runs it, else a command to copy | at once |

Cut from the first comp because no knob or route backs them: "Keep long chats going" and "Summarize at" (compaction is Claude Code's own; splice only adds instructions text, topology `compaction.instructions`, shown under Conversation), "Start splice when I sign in" (no route; `supervisorUnit` names a unit splice restarts into, it does not enable one), "Where splice keeps its files → Change…" (the state folder is fixed at start), "Keep session transcripts for 30 days" (the transcripts are Claude Code's files; the real retention is message history and traces above).
