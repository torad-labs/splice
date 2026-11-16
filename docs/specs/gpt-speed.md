# Fast and Ultrafast on GPT heads signed in with ChatGPT

- Status: planned 2026-11-15. Built against frozen acceptance checks in `.campaign/checks/speed/`, feature
  `gpt-speed` in `.campaign/ledger.toml`.
- Scope: heads whose provider signs in with a ChatGPT login (`auth = { kind = "chatgpt-oauth" }`), the heads that
  run on the same backend Codex does. An OpenAI API-key head can take the same tiers later. That's a separate
  change, because its catalog, its rate limits and how it is billed differ.

## The goal

A person running a GPT head in Claude Code switches it to Fast or Ultrafast the way they would in Codex. They type
`/fast` or `/ultrafast`, find it in Claude Code's own command menu, and are told at once what it costs. Their
next turns run at that speed and their other sessions don't change. Afterwards they can see which turns ran at
which speed and what each was worth.

## What OpenAI and Codex do (read 2026-11-15)

- **The tiers.** Fast mode (renamed from Priority processing on July 30, 2026) is `service_tier: "priority"` or
  `"fast"`. Ultrafast is `service_tier: "ultrafast"`, offered on GPT-6 Astra and GPT-6.1 Sol only. Standard is no
  `service_tier` at all. Sources: developers.openai.com/api/docs/guides/fast-mode and
  developers.openai.com/api/docs/guides/ultrafast-mode.
- **The answer names the tier it was served at.** The response's `service_tier` is `priority` (or `fast`, for
  models after GPT-5.6) for a fast turn, `ultrafast`, or `default` when the turn was served at standard speed,
  even when a faster tier was asked for.
- **The catalog says which model offers which tier, in its own words.** Each model in the ChatGPT backend's
  `/models` lists `service_tiers: [{ id, name, description }]`, such as
  `{ "id": "priority", "name": "Fast", "description": "2x speed, increased usage" }`. Today GPT-6 Astra and GPT-6.1
  Sol list Fast and Ultrafast, and every other model lists Fast alone.
- **What Codex sends** (openai/codex at `rust-v0.169.0`):
  - `ModelInfo::service_tier_for_request` (`codex-rs/protocol/src/openai_models.rs`) drops `default` and any tier
    the model's catalog entry does not list.
  - The request carries the tier's id (`codex-rs/core/src/client.rs`, `build_request`).
  - On a ChatGPT login, every request also carries `x-codex-routing-hint: model=<slug>;tier=<id>`, or
    `model=<slug>` without one (`build_routing_hint_header`).
  - Codex's `/fast` and `/ultrafast` come from the catalog, and choosing the tier already on goes back to
    standard (`codex-rs/tui/src/chatwidget/service_tiers.rs`).
- **What it costs.**
  - On a ChatGPT plan, Fast uses included limits at 2.5x and Ultrafast at 8x (credits: 2x and 6x).
  - On the API each tier has its own published card per model, on developers.openai.com/api/docs/pricing ("Fast"
    and "Ultrafast" tabs). For GPT-6 Astra: standard $10 / $1 cached / $50 output per million tokens, Fast $20 /
    $2 / $100, Ultrafast $60 / $6 / $300.

## What Claude Code does

- Claude Code has its own `/fast`, Anthropic's fast mode. `CLAUDE_CODE_DISABLE_FAST_MODE=1` turns it off.
- A skill with the same name as a built-in command replaces it in a local terminal session. A file in
  `commands/` does not reliably do that: see code.claude.com/docs/en/skills, "Resolve skills that share a name".
- A skill's frontmatter `description` is what the `/` menu shows beside it, and `disable-model-invocation: true`
  keeps the model from running it on its own.
- UserPromptSubmit hooks see what the person typed and can stop it before it reaches the model, which is how
  splice's `/login` works today.

## Decisions

1. **Skills, not command files.** On a GPT head the launch lays down `/fast` and `/ultrafast` as skills in the
   head's own `CLAUDE_CONFIG_DIR/skills`, so they replace Claude Code's own `/fast` and show in its menu. The
   launch also sets `CLAUDE_CODE_DISABLE_FAST_MODE=1`, so Claude Code never sends Anthropic's speed setting to a GPT
   model.
   - A head gets a skill for each tier its model's catalog entry offers.
   - A head that is not signed in with ChatGPT gets neither skill, and Claude Code's own `/fast` stays there.
   - The person's own skills stay in `~/.claude/skills`, untouched, and stay offered in every head, as their
     commands are today.
2. **Caught before the model.** The head's UserPromptSubmit hook catches `/fast` and `/ultrafast`, whether Claude
   Code hands it the typed command or the skill's expanded text. It answers with a decision that stops the
   prompt, so nothing reaches the model and no turn is spent. A prompt that only starts like one (`/fastidious`)
   goes through.
3. **Per session, like Codex.**
   - The speed belongs to the session the command was typed in, keyed by Claude Code's session id, and it
     outlives a daemon restart.
   - A bare command turns its tier on, or goes back to standard when that tier is already on. `on` and `off` set
     it whatever it was, and `off` always means standard. Fast and Ultrafast replace each other.
   - The reply names the tier and the catalog's description of it, so the person sees what it costs.
   - A tier the head's model doesn't offer is refused, naming the model and the tiers it does offer.
4. **On the wire as Codex sends it.**
   - Every request of a session at Fast or Ultrafast carries `service_tier` `priority` or `ultrafast`, and the
     routing hint `model=<slug>;tier=<id>`. That includes a subagent's request, a compaction and a turn that is not
     streamed.
   - A standard session sends no `service_tier` and the hint `model=<slug>`.
   - The tier never varies by turn type: like reasoning effort, it is part of what the backend caches the prompt
     under.
   - A tier the turn's model doesn't list in the catalog is not sent.
5. **Recorded as served.**
   - Each turn's row on `/api/perf/turns` carries `speed_asked` and `speed`, each `standard`, `fast` or
     `ultrafast`. `speed` is read from the backend's own answer, so a turn served slower than asked shows it.
   - A plan turn's `plan_value_usd` is priced at the card for the speed it was served at. A rate card in
     splice.toml names the tier cards as flat keys beside the long-context ones, because the TOML reader takes no
     table nested in a model row: `fast_input`, `fast_cache_read`, `fast_output`, `fast_cache_write`, and the same
     with `ultrafast_`.
   - The shipped price table carries OpenAI's published Fast and Ultrafast cards.
   - A turn served at a speed whose card splice doesn't have is left unpriced, never priced at the standard card.

## The items

Each item has its check in `.campaign/checks/speed/`, run against a real daemon built from the checkout. The checks
use a stand-in ChatGPT backend (`lib/codex.ts`) that serves the catalog's tiers as the real one lists them and
echoes the tier each turn was served at.

- **speed-commands** (`commands.test.ts`), decisions 1 and 2. A real Claude Code session on a GPT head is the last
  word here. The feature review runs one on the stand-in backend and confirms three things: `/fast` shows in the
  `/` menu with its description, running it reaches no model, and the reply is shown.
- **speed-switch** (`switch.test.ts`), decision 3.
- **speed-wire** (`wire.test.ts`), decision 4.
- **speed-recorded** (`recorded.test.ts`), decision 5.

## Not in this change

- OpenAI API-key heads (see Scope).
- Showing the speed in the console's tables or in the status line. The route carries it; the pages follow in a
  change of their own, with captures.
- A tier set for a head as a whole in splice.toml. The speed is the person's choice per session, as in Codex.
