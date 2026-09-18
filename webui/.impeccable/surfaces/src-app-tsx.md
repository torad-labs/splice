---
version: 1
slug: "src-app-tsx"
primary_target: "src/App.tsx"
related_targets: ["src/pages","src/widgets","src/features","src/entities","src/shared"]
---

# splice console: shape brief

Status: shape output after one correction round, an adversarial review (Eli) and the operator's rulings on the three open decisions, 2026-09-17. Awaiting final confirmation. No direction contract yet; new-work writes that after confirmation.
Feature inventory and daemon surface: .dev/web-console/FEATURES.md. Product truth: PRODUCT.md.

## 1. Job and audience

One developer running splice on their own machine, at a second monitor, while one or several coding agents work through the daemon. Visitor mode is Operate. They open the console for one answer (which head needs me, why is this turn slow, how much plan is left, where does this knob's value come from, which account is this session on, what did my lead tell my builder) and to change how splice is configured. Sessions are long; the console stays open for hours and is glanced at more than read.

The operator's setups are teams of model sessions: a frontier model orchestrating or reviewing, cheaper or faster models building, sometimes two leads, changing per project and per feature. The sessions talk to each other through the client's own messaging; splice put them on different heads and sees the SendMessage call go by. The console surfaces the composition and never enforces one.

## 2. Outcome and proof

Primary task: everything configurable in splice is configurable here, everything the daemon reports is visible here, and every feature a competing proxy console shows is here too, minus a short exclusion list the operator confirmed on 2026-09-17: user accounts, roles and SSO (one operator, not for now); guardrails, PII redaction and injection scanning (not yet); semantic cache configuration (the cache is the provider's; its hit rate is shown); prompt library, versioning and evals; alerts to chat channels (desktop notification and one webhook are in).

Success looks like: no reason to open splice.toml by hand, no reason to run a CLI verb to learn something, no reason to open a competitor to miss a feature, no more hunting through browser logins for an account with room, and the console never claims more than the daemon reports. Every empty names its source. Daemon failures appear as the daemon renders them (SafeFailureText), never as raw exceptions and never as a clean stop.

Product truth no competitor console can claim: a team of sessions across heads with the hand-offs between them; per turn, the head, model and slot as resolved, the account used and why it switched, the compaction flag and the cache-cold tag; the live in-flight stream; per-session cache and cost; compaction insight; config layer diff; subscription lifecycle with each provider's own plan windows.

Cut line for landing: surfaces on routes that exist today ship first at full fidelity. A surface whose route is still being built shows an honest empty naming the route and the exact CLI command that answers today. The routes in FEATURES.md section 6 are built in the same campaign, and the coverage gate (section 7) is judged at the campaign's final landing, with interim landings listing every pending route by name.

## 3. Selected direction

World: The Strip Bay. Air traffic control flight progress strips racked in bays, the sector hand-off, the UTC clock. Roll: direction seed 7d8e221a, assigned index 6, chosen by the operator on the decision page, no steer, build path comp. Decision comp: .impeccable/mocks/decision/assigned.png (light theme, Fleet page, critique reference). Comp round, 2026-09-17: three team-board compositions, .impeccable/mocks/team-board-a.png (board by head), team-board-b.png (board by role), team-board-c.png (timeline); the operator approved all three as views of one page rather than choosing one; the spec phase measures the default view, board by head, and the other two build to the same grammar.

Thesis: every head, session, account and turn is a printed strip in a bay. State is what happens to the strip: it prints, it cocks, it hands off, it gets struck.

The operator chose the world for its clarity and called the decision comp underwhelming. The comp round is judged against this rejection test, and a comp failing any line is regenerated before it is shown: (1) blurred until the text is unreadable and placed beside screenshots of the Portkey, LiteLLM and Helicone consoles (stored under .impeccable/reference/competitors/ before the round), it must be tellable apart from all three; (2) the first viewport holds one gesture mid-flight, a hand-off or a print; (3) the comp round's surface is the team board, not the fleet table; (4) at least three bays holding strips at data density, populated from a real read of the live daemon's sessions arranged as one team (a fixed strip count was dropped on 2026-09-17 because a real three-member team cannot honestly fill six strips per bay); (5) dark theme; (6) finish and commitment at the level of the quality bar boards stored under .impeccable/reference/quality-bar/ (the Ikeda and teletext boards the round showed).

Two themes, both designed, dark by default. Dark is the electronic flight strip display: a deep graphite room, opaque strips in pale saturated colors with black ink on them, bays as darker racks, no glow, no blue-black, no neon. Light is the paper strip bay: matte light panels, white printed strips, colored holder edges. Both keep the same strip module, field grid, figures and gestures. The console opens dark regardless of the system preference and remembers a manual switch.

Signature surface: the team board. A team's sessions sit as strips in their heads' bays with the role printed; the lead driving now is the strip most recently seen messaging; a message edge (from session, to address, time, direction, read on the wire from the SendMessage tool call's input, kept by default as metadata; new wire observation, since today's label only says a message went by) is a strip hand-off between bays. Beside the board, the team chat: every message the members sent or received, in time order, as one group chat the operator can read back over the day, with sender role and head; the text is read from the members' transcript files on demand and never stored by splice. Beside the chat, the activity feed: what each member did today from the gateway's activity labels (editing a file, running a command, messaging a peer), a sample taken about every 30 seconds from the client's own status query, labeled as such, with an empty that can tell "nothing sampled" from "this client version no longer matches". Cost per role sits beside what the role landed. The lead driving now is the slot flagged lead whose session most recently sent a message. Each slot can carry standing role instructions that splice appends to the system prompt of any session bound to it, never replacing the head's own, through a new per-session resolver at the existing per-turn seam; editing them mid-session costs that session one cold-cache turn, shown on the strip. Teams archive, never delete.

Structural thesis: a strip is a strict horizontal field grid with fixed field widths, a colored holder edge on its left, and tabular figures right aligned. One strip module serves every page. Bays are labeled racks of strips.

Sequence: rail of bays on the left; a fixed top rule that never scrolls; the page's bay in the middle; the opened strip swells into its detail on the right in one continuous motion while the bay recedes. A transcript opens as the strip's detail grown to a conversation column with the participants named, not a log tail.

The fixed top rule holds the local and UTC clock, daemon health, and the plan window nearest to exhaustion with its head and account named, plus the count of heads reporting no window. There is no daemon-wide 5h or 7d number and the rule never pretends there is.

Holder edge color means attention state, always with a text label on the strip: ok, warn with percent, unhealthy, version mismatch, account excluded, queue at max, topology stale. Cocked and struck stay as the gestures for needs-me and disabled. Provider family is a printed field and a monochrome mark, never a color. (Changed after review: the product's stated job is attention, and the operator's chosen comp already used the edge that way.)

Type: a compact workhorse grotesque for labels and a tabular monospace for figures, one density everywhere. No serif, no cream paper, no handwriting face.

Raises carried from the hand, each a rule for the build: one strike grammar for disabled and excluded items; one fixed frame that never moves; tabular numerals at one density with focus pulling a strip forward and the bay dropping faint; every page, head, session, team and project has a short typed address, and secrets and system prompts stay hidden until an explicit reveal; opening a strip swells it in place, never a modal over the room; one strip module at every width.

Copy rule, tested: every label lives in one string table and a webui test fails any label longer than three words; no paragraph on any page except an honest empty and a Doctor fix; explanation is on demand behind a reveal, never inline; the novice path is Doctor plus the empties.

What must not be literalized: no aircraft, radar sweeps, runways or airport iconography. The world enters only through the strip, the bay, the holder edge, the clock and the four gestures.

Implementation consequence: Strip and Bay are the two components everything composes; the token set is rebuilt for this world in both themes (the old plate tokens are anti-reference); motion is physical and short, fired by daemon events, with a reduced-motion path that holds stills.

## 4. Scope and boundaries

Fidelity: production, shipped inside the daemon jar as the single-file webui.
Breadth: the whole console, every page in FEATURES.md section 4 (Shell, Fleet, Turns, Sessions, Teams, Projects, Accounts, Usage, Settings, Models, Logs, Compaction, MCP, Doctor), plus the daemon routes in section 6, including events, transcripts, message edges, teams, projects, opt-in body capture, budgets, alerts and the playground.
Interactivity: full read and write against the daemon, including topology edits, account login and switch, restart, upgrade, team composition, role instructions, session binding and archiving.
Named target: webui/src (App shell and pages); daemon routes in gateway/control.
Untouched: daemon semantics and knob names; mgmt-key auth; the single-file build; the Feature-Sliced Design walls and the ast-grep walls (spacing and font sizes on var(--space-N) and var(--text-N), no em-dashes in UI text, fetch only in entity api); AA contrast in both themes; the per-head config dir isolation ruling (a head's transcripts are read from that head's own projects dir, never merged).
Anti-goals: the KPI-tile dashboard of the category; a global progress-bar pair wearing a strip; near-black with neon glow; cream and serif; verbose copy, tooltips as documentation, marketing tone; a companion-panel feel; enforcing a team shape; teams as a cost center.

## 5. States and ranges

Heads: 1 to about 12, typically 7. Sessions: tens per day, live, stale or gone; a gone session keeps its perf rows and loses its live head, so history joins on the perf session tag, which is an 8-character prefix of the session id per head file; turns with no client session id are counted as unattributed and never dropped from a total. Headless runs never register and the page says so. Teams: 0 to a handful, 2 to 8 slots each, bindings changing daily. Projects: a few. Turns in flight: 0 up to the maxInflight knob, typically 0 to 5. Perf history: hundreds to thousands of turns per day, 37 fields each. Transcripts: thousands of lines, tool results of many kilobytes, read in pages, never loaded whole. Accounts per OAuth head: 1 to as many as the operator logs in, typically 2 to 6; windows known for every pooled account, idle ones included, refreshed every five minutes; heads that share a credential file share the login. Perf history reaches back as far as the perf files' rotation keeps, and every lifetime figure names the oldest turn it saw. Knobs: 33, 3 hot. MCP servers: 0 to a handful. Models: the declared models per provider, a few to tens (remote catalogs are later daemon work).

Material states: locked (no mgmt key); daemon unreachable; event stream down with polling fallback showing observation age; topology stale; restart draining; expired or missing token; account excluded or window exhausted; a window not reported by provider; no perf yet; a session whose head keeps no transcript or whose capture is off, explained on the strip; transcript history left in the vanilla tree by the per-head un-link, read from there with the path shown; client memory switched off, named as such; a team slot with no bound session; a message edge whose far side is not a splice session; body capture off by default and saying so; empty bays that name their source; a route not yet built, named with the CLI command that answers today.

## 6. Interaction and layout

Hierarchy: the fixed top rule first, then the page's bay, then the opened strip's detail. The rail is quiet and always visible.
Topology: one rail, one fixed rule, one bay area, one detail column. Every list page is one data source under saved views, Notion-style: a view owns its layout, filter, sort, group, visible fields and name; views are tabs beside the page title with a default first; settings never leak between views. Fleet: one bay of head strips. Turns: two bays, in flight and landed. Sessions: session strips grouped by head, by project or by team, switchable, each group opening its page. Teams: the team list, the composer (role slots), and the board as three saved views switched by tabs: board by head, board by role, timeline of the day (the comp round's three compositions, all approved as views on 2026-09-17), with chat and the activity feed as panels in every view. Projects: the repo's sessions, teams, turns, cost, compaction scope and effective instructions, plus its instruction files (CLAUDE.md, AGENTS.md) and, separately, the client's per-project memory files read per head, read-only; the memory empty names the autoMemoryEnabled setting and the directory it looked in. Repo resolution is a cached, bounded git-root lookup, and worktrees fold into their shared repo with the worktree as a sub-label. Accounts: one bay of every account of every provider, sorted by headroom with unknown shown as unknown, each strip carrying plan when reported, every window at its reported length (5h, weekly, or Grok's 30 days) with its reset, per-model windows for Claude from the client's own payload, exclusion, selection and the heads riding it; adding an account is a strip that prints with the device code and link on it, fills in when the credential lands, and stays cocked until the head restarts to take it; the account the selector takes next is marked by the selector's real order (primary if available, else sticky, else lowest weekly used) and the earliest reset named. Claude logins on the splice-owned head are chosen at launch, with no mid-session switch and no next target, and the strip says so. Settings: a numbered form of field boxes, one per knob with its provenance and whether it is hot or restart-only, the Claude head mode (separate or wrap) with what claude on PATH resolves to, and a TOML editor with a diff as the escape hatch. Logs: a virtualized tail with the request drawer when capture is on. Usage and Compaction: scope insets for charts with click-through to the turns behind any point.
Responsiveness: strips keep fixed field widths in ch units and scroll inside their bay; below a wide desktop the detail column drops beneath the bay; the rail collapses to abbreviations. Second monitor first.
Affordances: click or key a strip to open it; edits happen in the strip's own field boxes; destructive or restart actions confirm inline on the strip, never in a dialog; a team is composed by picking sessions into role slots.
Feedback: a saved knob shows its new provenance immediately; a hot knob applies live; a restart-only knob cocks the daemon strip until the restart runs; every write echoes the daemon's response.
Transitions: print, hand off, swell, strike, cock. Each under half a second, none looping, fired by the event stream so they happen when the thing happened; on polling fallback they render as diffs with the observation age printed; all disabled under reduced motion. Theme switch is a cut, not a fade.

## 7. Constraints and open decisions

Platform: React 19, TypeScript strict, Vite single file, zustand, hash router today. Additions expected: a table library with virtualization for Turns, Logs and transcripts, a code editor with merge view for the TOML escape hatch, a command palette, an EventSource client. No Tailwind or shadcn (token wall). Keyboard first; AA contrast in both themes; English only.
Delivery: same branch feat/v0.4.0 in the shared worktree, no commits from this seat, ledger rows only after the impeccable pipeline is done.
Reusable components: Strip, Bay, HolderEdge, Rule (fixed frame), ScopeInset (chart frame), FieldBox (form field with provenance), Reveal (hidden secret or prompt), Conversation (transcript column), Edge (hand-off between bays), Chat (the team's group chat), Feed (the team's activity feed), FileView (instruction and memory files, read-only).
Operator rulings, 2026-09-17, recorded in PRODUCT.md: the exclusion list in section 2 stands; message edges are metadata and are kept by default, message text never leaves the transcript files; request and response bodies are never recorded unless capture is switched on per head, because the operator wants users to trust that nothing happens by default that they did not ask for.
The activity store, ruled under the operator's delegation: on by default, its content named in PRODUCT.md (a file name, the first words of a shell command, the first characters of a search pattern, never file contents), switchable off per head, bounded by a retention knob. Storage for activity, edges and teams is plain per-day JSONL through the daemon's existing sink plus a teams file, not SQLite (reasons in FEATURES.md section 6).
Claude, ruled 2026-09-17: the operator runs the splice-owned Claude head (claude-splice) from now on, and its Claude logins live there, one chosen at session launch by materializing its credential file into that head's own config dir, traffic still through Claude Code's own login. This is launch-time selection, not the account pool: no poller, no exclusion, no per-turn switch, one login per Claude head at a time, all live sessions on the head sharing it. Claude's windows, per-model ones included, come from the client's own statusline payload, gated on its rate_limits_available flag, window fields only. Users choose between two modes: Separate, a splice-owned Claude head beside their untouched vanilla claude (default), or Wrap, their default claude command run through splice over the vanilla config dir with history, login and memory left in place and no pool. Wrap is honest about its two side effects: it rewrites settings.json and .claude.json in the vanilla dir (backed up on wrap, restored on unwrap), and it shadows the claude command with a shim that must exec the real binary by absolute path. Doctor and Settings show the mode, what claude on PATH resolves to, and wrap or unwrap as one action each.
Open decisions a builder must not invent (new-work decides):
- Exact type faces (a workhorse grotesque plus a tabular monospace, chosen outside the default list).
- The typed address scheme for pages, heads, sessions, teams and projects.
- The account login flow inside the console (OAuth device flow surfaces) and which daemon route carries it.

## Direction contract

THESIS: Every head, session, account and turn is a printed strip in a bay; state is what happens to it: print, cock, hand off, strike. Refuses the sidebar-and-KPI-tile dashboard.

OWN-WORLD: Dark graphite room; opaque pale strips, black ink; a colored holder edge carrying attention; tabular mono figures, compact grotesque labels; charts as dark scope insets. Strip and Bay compose every page.

STORY: The operator sees which head needs them, opens a strip, edits a field in place, and watches work hand off between their sessions.

FIRST VIEWPORT: Rail of bays left; fixed rule top (clocks, health, windows); fleet bay of head strips center, two cocked, one struck; opened strip swollen into its detail right. Primary action: open a strip. Signature: the team board's hand-off.

FORM: The Strip Bay, position 6 of 7 on my list, seed 7d8e221a.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
