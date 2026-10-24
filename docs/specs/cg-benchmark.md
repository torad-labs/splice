# Splice side of the cg benchmark: design

From splice-lead, Oct 19 2026, for Simon's spec ~/.torad/simon/health/2026-10-19-splice-cg-benchmark.md.
Design only. Nothing here is built, installed or switched on.

## What Splice can and cannot do, from the code

| Need | Today | Verdict |
|---|---|---|
| Append text to the system prompt per head and per project folder | System prompt layers: append, replace and strip, resolved from the session's working folder (SystemPromptLayers.kt, TurnPrompts.kt). Appends ride as trailing blocks, so the client's cache breakpoints do not move. | Works now, config only. Arms B and C text can use it. |
| Remove paragraphs of Claude Code's own prompt that tell the model to use Grep and Glob | The strip layer deletes matching paragraphs. | Works now. Needs the exact paragraphs from the Claude Code version in use (2.1.304 now). |
| Drop named tools from the tool list sent upstream | Nothing. The tool sanitizer only rewrites schema keys. The Anthropic request tree is already parsed in TurnPreparation before any dialect runs, so one filter there covers every head. | New code, small. |
| Pick an arm per session | Nothing. The session id is known per request (metadata.user_id, else the x-claude-code-session-id header). | New code, small. |
| Know the seat's name | The session registry holds the name Claude Code registered, keyed by session id. | An accessor to add. |
| Log per request: arm, seat, model, tools offered | The perf row has the model, an 8-character session tag, the full session id, the response message id and the messages hash. It has no arm, no seat and no tool names. | New fields. |
| Log tools called | Not in the perf row. The Claude Code transcript already has every call, including each Bash command. | Do not duplicate. Join on session id. |

Every Splice head serves Claude Code, so one filter and one set of tool names (Grep, Glob, Bash, Read) cover all heads. Splice does not serve a Codex CLI directly, so a harness other than Claude Code is out of reach.

## Limits Simon should know before choosing the arms

- **Bash grep cannot be removed at the proxy.** Arm B can drop the Grep and Glob tools and tell the model to use cg. A grep, rg or find typed inside a Bash call is only text in a tool input, and Splice does not read it on the way out. Arm A's hook can block it, so the arms differ here. Measure the leak: log the first word of each Bash command from the transcript and count grep, rg and find in arm B.
- **cg is a command line tool, not a Claude Code tool.** In arm B the model reaches it through Bash. That keeps A and B identical on how cg is called, and only the enforcement differs. Hosting cg as a real tool through Splice's MCP hub would change what is measured, so I would not do it in this round.
- **Subagents.** An Explore or other Task subagent has its own prompt and its own tool list and would use Grep and Glob unless it is filtered too. The filter should apply to every request of an arm's session, subagents included. I have not confirmed that subagent requests carry the parent's session id. That is the first thing to check on a real session before the start.
- **A removed tool the model still calls** comes back from Claude Code as an error. Those failed calls show in the transcript and are themselves a measure of how hard the model leans on the old habit.

## Design

**Arm assignment.** Hash the session id with a fixed salt and take it modulo the number of arms. It is deterministic, so a daemon restart, which happens several times a day, never reshuffles anyone. It is sticky for the session, which matters because the tool list and the system prompt are part of the prompt cache prefix and changing them mid session would bust the cache and contaminate the numbers.

**Which sessions.** Only sessions whose first request arrives after the start. A session already running gets arm `off` and is excluded. Long-lived seats will mostly sit out, but the short lived seats (lint, bugwatch, review, one-off fix seats) start in the dozens each day, so the sample fills from them. If the seat mix is lopsided, Simon can reweight, because seat and model are logged.

**Arms.**
- A: no Splice change. Logged as `arm=A`. The hook is the treatment.
- B: tool filter drops Grep and Glob. Appended cg usage guide. Strip layer removes the Grep and Glob paragraphs from Claude Code's prompt.
- C: B plus the appended "talk less, point more" text as a second append layer.

**Switch.** A new `[experiments.cg_policy]` table in splice.toml with `enabled = false` as the default, the arm list, the salt, the guide and norms text files, and the start time. Turning it on is Marcos's one-line change plus `splice restart`, the way the websocket transport is gated. Until then the code is dormant, no request changes by one byte, and the perf row has no new fields.

**Logging.** Add `arm`, `seat` and `tools_offered` to the perf row when the experiment is enabled. `tools_offered` is the sorted list of names Splice sent upstream after filtering, plus a count. Tools called come from the transcript, joined to the row by session id and response message id. This keeps Splice from re-implementing a tool call parser, and the transcript has the Bash command words Splice would otherwise have to accumulate from streamed deltas.

**Model confound.** Seats run on different heads and models. The analysis must compare within a model. The model is already on every row.

## Build and timing

- One seat, about a day of work: arm assignment, the tool filter in TurnPreparation, the registry accessor, three perf fields, the config table, the doc and the changelog line. It goes through the normal pre-push check. Roughly 18 minutes of gradle on a loaded machine, once more if a failure comes back.
- I install it while off, so it can sit on the machine during the hook's two days with no effect.
- **Proposed start:** the third morning after the hook lands, once Marcos okays it. If the hook lands Oct 20, that is Oct 22 or later. Splice would be installed and dormant by then.
- Before the start, one dry run on a single throwaway session for each arm, reading the captured request to confirm the tool list, the appended text and the logged fields. That uses Splice's request trace, so nothing reaches a real seat.

## Questions for Simon and Marcos

1. Simon writes the cg guide text for B and the norms text for C. Splice only places them.
2. Should subagent requests follow their parent's arm? I assume yes.
3. Is a mid-session switch ever wanted? I assume no. New sessions only.
