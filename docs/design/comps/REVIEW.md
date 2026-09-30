# Review: each built page beside its comp

Day theme, 1440 and 1920. Left is the comp (`png/`), right is the built page (`png/built/`), captured live against the running daemon on Sep 29, 2026, so the data is real and the comp's is drawn. Night is not repeated: no page sheet has night-specific rules, only the shared ones (`styles/base.css`, `styles/tokens.css`) that every page uses.

## Needs you

| Comp | Built |
|---|---|
| ![](png/needs-day-1440.png) | ![](png/built/needs-day-1440.png) |
| ![](png/needs-day-1920.png) | ![](png/built/needs-day-1920.png) |

Left: the cards are the daemon's real items (doctor findings, two waiting sessions), not the comp's four. A waiting session quotes its question and offers Copy resume command; other kinds carry only the acts the daemon backs.

## Sessions

| Comp | Built |
|---|---|
| ![](png/sessions-day-1440.png) | ![](png/built/sessions-day-1440.png) |
| ![](png/sessions-day-1920.png) | ![](png/built/sessions-day-1920.png) |

Left: a session carries a name, not a task title, so a card shows the name and its dark line is the newest message; the switch has a Team segment the comp lacks, because Team grouping exists; the sidebar carries the Day/Night switch, which the comp leaves out.

## A session

| Comp | Built |
|---|---|
| ![](png/session-day-1440.png) | ![](png/built/session-day-1440.png) |
| ![](png/session-day-1920.png) | ![](png/built/session-day-1920.png) |

Left: this is a real two-month session, so its rail is long with hand-offs and it has no Stop the turn (nothing is running). Its layout is the comp's.

## Fleet

| Comp | Built |
|---|---|
| ![](png/fleet-day-1440.png) | ![](png/built/fleet-day-1440.png) |
| ![](png/fleet-day-1920.png) | ![](png/built/fleet-day-1920.png) |

Left: eleven real plans instead of six, and a plan with no usage window is a short card that says so, since the daemon reports none for it.

## A plan's page

There is no comp for this page: the plan page was never drawn, so only the built page is shown.

| Built |
|---|
| ![](png/built/plan-day-1440.png) |
| ![](png/built/plan-day-1920.png) |

## Turns

| Comp | Built |
|---|---|
| ![](png/turns-day-1440.png) | ![](png/built/turns-day-1440.png) |
| ![](png/turns-day-1920.png) | ![](png/built/turns-day-1920.png) |

Left: the finished list is two hundred real turns (the page caps it and says so), so the built image shows its top; its heading says Finished on every page, so the comp was changed from Landed. A running turn has no title on the wire, so its card shows the turn id and model.

## A turn

| Comp | Built |
|---|---|
| ![](png/turn-day-1440.png) | ![](png/built/turn-day-1440.png) |
| ![](png/turn-day-1920.png) | ![](png/built/turn-day-1920.png) |

Left: the tab labels are Conversation, Request and answer, Sent to the plan, where the comp says Request, Trace, Wire; a turn with no retry or price shows no Retry figure and a dash for cost.

## Usage

| Comp | Built |
|---|---|
| ![](png/usage-day-1440.png) | ![](png/built/usage-day-1440.png) |
| ![](png/usage-day-1920.png) | ![](png/built/usage-day-1920.png) |

Left: the window switch offers 24 hours and 7 days because the daemon keeps 8 days of economics (the comp now says the same), and no turn is priced yet, so cost reads as a dash.

## Settings

| Comp | Built |
|---|---|
| ![](png/settings-day-1440.png) | ![](png/built/settings-day-1440.png) |
| ![](png/settings-day-1920.png) | ![](png/built/settings-day-1920.png) |

Left: Storage, Tools and Health list the real configuration (every capture toggle, every tool server), so the built page is longer than the comp; the section list on the left follows the address, not the scroll.

## A project

| Comp | Built |
|---|---|
| ![](png/project-day-1440.png) | ![](png/built/project-day-1440.png) |
| ![](png/project-day-1920.png) | ![](png/built/project-day-1920.png) |

Left: the project is `eli`, which has no turns, no compaction rule and no standing prompt, so its sections are emptier than the comp's `tally`.

## A team (by render)

The daemon has no team, so this is the real page rendered over fixture data shaped like the comp's team.

| Comp | Built |
|---|---|
| ![](png/team-day-1440.png) | ![](png/built/team-day-1440.png) |
| ![](png/team-day-1920.png) | ![](png/built/team-day-1920.png) |

Left: messages name the seats by role ("Planner to Builder") where the comp names the plans, and a cost under a dollar prints to a tenth of a cent ("$0.900") as it does on Usage and Turn.
