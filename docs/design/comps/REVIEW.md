# Review: each built page beside its comp

Day theme, 1440 and 1920. Left is the comp (`png/`), right is the built page (`png/built/`), captured live against the running daemon (Needs you, Sessions, a session, Turns and a turn on Sep 30, 2026 against daemon `a652183da`; the other pages on Sep 29), so the data is real and the comp's is drawn. Night is not repeated: no page sheet has night-specific rules, only the shared ones (`styles/base.css`, `styles/tokens.css`) that every page uses.

## Needs you

| Comp | Built |
|---|---|
| ![](png/needs-day-1440.png) | ![](png/built/needs-day-1440.png) |
| ![](png/needs-day-1920.png) | ![](png/built/needs-day-1920.png) |

Left: the cards are the daemon's real items (doctor findings, two waiting sessions), not the comp's four. A waiting session quotes its question and offers Copy resume command; other kinds carry only the acts the daemon backs. A session with no name reads as its repo and the day it began, never a hex id.

## Sessions

| Comp | Built |
|---|---|
| ![](png/sessions-day-1440.png) | ![](png/built/sessions-day-1440.png) |
| ![](png/sessions-day-1920.png) | ![](png/built/sessions-day-1920.png) |

Left: a session carries a name, not a task title, so a card shows the name and its dark line is the newest message; the switch has a Team segment the comp lacks, because Team grouping exists; the sidebar carries the Day/Night switch, which the comp leaves out. The dark line is what the session last said (its first sentence) or did (a tool call as "Bash · List the design comps", or "Edit · sessions.ts" when the call has no description; never a command or a path). A tool's result, a slash command, a background notice and a system note are not shown; a session whose newest message is one of those says its state ("Working for 1 h"), a compaction reads "Compacted its context", and a waiting session quotes the question it asked, else says "Waiting for your answer". Sessions and Needs you read the same function. The repo on each card is the name of its git remote, which the daemon now sends, so this checkout reads "splice"; a folder with no remote shows its folder name.

## A session

| Comp | Built |
|---|---|
| ![](png/session-day-1440.png) | ![](png/built/session-day-1440.png) |
| ![](png/session-day-1920.png) | ![](png/built/session-day-1920.png) |

Left: this is a real two-month session, so its rail is long with hand-offs and it has no Stop the turn (nothing is running). Its layout is the comp's. The local-command caveat is gone, the slash command folds to one quiet line ("Ran /clear", its output inside), a Bash row reads its description with the command in the opened body, and a hand-off from a session the registry no longer holds says "an ended session" where a hex id stood.

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

Left: the finished list is two hundred real turns (the page caps it and says so), so the built image shows its top; its heading says Finished on every page, so the comp was changed from Landed. A running turn has no title on the wire, so its card shows the turn id and model. Steps splice answered itself in Codex code mode (no plan asked) are not turns the model took. The daemon marks them at the source (`local_step`) and counts them apart (`local_steps`), so the sentence above and the plan table count only real turns, and the list leaves the steps out and says how many. The daemon started marking at 1:28 AM CT on Sep 30, so the rows before that are unmarked and read as turns; no step has been marked since, so this capture shows no such line.

## A turn

| Comp | Built |
|---|---|
| ![](png/turn-day-1440.png) | ![](png/built/turn-day-1440.png) |
| ![](png/turn-day-1920.png) | ![](png/built/turn-day-1920.png) |

Left: the tab labels are Conversation, Request and answer, Sent to the plan, where the comp says Request, Trace, Wire; a turn with no retry or price shows no Retry figure and a dash for cost. A tool call in the conversation reads as its tool and what it did ("Bash · Show files changed in the console fix commit"), and a background-task notice reads as a named event, never its tags.

### A step splice answered itself

There is no comp for this: the comp has no such turn. This is what a turn page says when the plan was not asked. The image is from Sep 29, before the daemon marked these steps; the page is the same, it now keys off the daemon's mark, and a fresh capture waits for the first marked step.

| Built |
|---|
| ![](png/built/turn-local-day-1440.png) |

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

## The operator's frame: 3840 x 2060, day

His panels are 3840 wide at scale 1 (ruling 4, 2026-09-25). Built pages only, captured on Sep 30, 2026 against the running daemon with the viewport at exactly 3840 x 2060; the comps are drawn at 1440 and 1920, where the page is unchanged (the root size holds 16px through 1920).

| Needs you | Sessions |
|---|---|
| ![](png/built/needs-day-3840.png) | ![](png/built/sessions-day-3840.png) |
| **A session** | **Turns** |
| ![](png/built/session-day-3840.png) | ![](png/built/turns-day-3840.png) |

What scales: the root size grows from 16px at 1920 to 20px at 3840, and every size (type, space, radii, widths, the sidebar) is in rem, so the page is the same page 1.25 times larger; the 1560px cap on the wall is gone, so a page draws in 87 to 90% of the width. Measured on glyph boxes: Sessions 0.901, Usage and Turns 0.878, Needs you 0.874 at 3840 (floor 0.8), body 21.25px (floor 20); at 1600 body is 17px (floor 17) and the drawn share 0.77 to 0.81 (floor 0.7). Needs you goes to two columns of cards from 2400px, where one column of cards beside the summary would leave the right half bare. Settings is still a column of 1550px at this frame (39% drawn), because a form of rows stretched to the window would be harder to read; it is outside the ruling's three pages.

