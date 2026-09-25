# What each console page is for

<!-- Rendered from each page's `job` in console/src/pages/*/coverage.ts. tests/jobs.test.ts fails when
     this file differs; edit the declarations, then `vitest run -u tests/jobs.test.ts` in console/. -->

Each page answers one question. An action with a row in brackets is not built yet; the row builds it.

## accounts

**Question.** Which login will each head use, and how close is each to its limit?

**Leaves knowing.** Every pooled account's windows, resets and the next target, and whether each head's login works.

**Actions.**

- Sign in an account
- Pin or release the next account
- Relabel or remove an account
- Refresh a login
- Store or remove an API key (V4-220)

## compaction

**Question.** Are compactions producing summaries, and under which rules?

**Leaves knowing.** Each compaction's outcome and time, the share that failed, and the instruction rules in effect.

**Actions.**

- Open a compaction

## doctor

**Question.** Is anything wrong with this install, and what fixes it?

**Leaves knowing.** Every check's verdict with its evidence and fix, the versions in play, and a prompt sent through a head end to end.

**Actions.**

- Copy a check's fix
- Send a test prompt through a head
- Open a head's log
- Run a check's fix
- Upgrade or roll back splice

## fleet

**Question.** Is every head up, and which account will each use next?

**Leaves knowing.** Each head's health, pinned model, turns in flight and account pool, with the next target marked.

**Actions.**

- Start, stop or restart a head
- Restart the daemon
- Add a backend (V4-220)

## logs

**Question.** What did a head just write to its log?

**Leaves knowing.** The tail of a head's log, filtered by tag, level and text, and whether the log rotated.

**Actions.**

- Pick a head and how many lines
- Filter by tag, level or text
- Turn a head's request capture on or off

## mcp

**Question.** Which MCP servers are running, and are they healthy and within limits?

**Leaves knowing.** Each server's state, sessions, restarts and last error, and the host limits in effect.

**Actions.**

- Open a server
- Edit the host limits in Settings

## models

**Question.** Which models can each head run, with what windows and prices?

**Leaves knowing.** Each head's declared models, their context windows and where each came from, and their rates.

**Actions.**

- Open a model
- Add a model (V4-220)
- Compare the declared models with what each provider publishes (V4-239)

## needs-you

**Question.** What needs me right now, and what do I do about it?

**Leaves knowing.** Every head, plan, account, turn, session, team seat and doctor check that needs them, worst first, each with its fix, and which inputs could not be read.

**Actions.**

- Start or restart a head
- Restart the daemon
- Copy a fix command
- Open the page that holds an item
- Open the item itself on its page (V4-219)
- Apply a doctor check's fix

## projects

**Question.** Which repositories have sessions run in, and what is running there now?

**Leaves knowing.** Every repo the daemon has seen, with its sessions, teams, today's turns and cost.

**Actions.**

- Open a project

## sessions

**Question.** What is running now, and who is handing work to whom?

**Leaves knowing.** Every live session on its head's lane, with its project and state, and each hand-off between sessions.

**Actions.**

- Change the view: lanes, by head, by project, by team or timeline
- Open a session for its detail
- Read a hand-off's text (V4-219)

## settings

**Question.** How is the daemon set up, and what does a change do?

**Leaves knowing.** Every runtime knob, each head's splice.toml table and the Claude head's mode, and which changes need a restart.

**Actions.**

- Edit a knob
- Edit a head's topology
- Wrap or unwrap the Claude head

## teams

**Question.** Who on the team is working, and who is waiting on whom?

**Leaves knowing.** Each member's head, state and running turns, the hand-offs between them, and what the team has spent.

**Actions.**

- Create or edit a team
- Bind or unbind a session to a slot
- Archive a team

## turns

**Question.** How fast are the heads answering, and where does a turn's time go?

**Leaves knowing.** Every landed turn with its timing split, cache hit and tokens, and the turns in flight now.

**Actions.**

- Open a turn's waterfall
- Turn a head's request capture on or off
- Start a stopped head
- Read a head's captured request bodies (V4-239)
- Read a head's request and response trace (V4-239)

## usage

**Question.** How many tokens and dollars is each head spending, against what limits?

**Leaves knowing.** Hourly tokens, estimated cost, request size and rate-limited turns per head, with budgets and alerts.

**Actions.**

- Set a daily budget and what passing it does
- Set the alert webhook
- Send a test alert
