# Source 3: what proxy-console users can do, as acceptance questions

Gathered 2026-09-27 from FEATURES.md §3 (2026-09-17 survey) and the current docs of LiteLLM Admin UI,
Portkey, OpenRouter (Activity, Models, Keys, Credits), Helicone and Langfuse. Each question names
where it is seen and what makes it feel easy there. Input to the console's acceptance list (owner:
Marlin); not the list. Team/org items (SSO, RBAC, invites) apply to splice only where one person's
setup has an equivalent.

## Signing in and access
- Can the user sign in with one click instead of a password form? — OpenRouter (OAuth)
- Is the console protected from repeated failed logins with nothing to configure? — LiteLLM (auto lockout)

## Connecting providers and models
- Can the user add a provider or model without restarting? — LiteLLM ("Add Model" applies live)
- Can the user reference a key by name so the raw key never appears in config? — Portkey (Virtual Keys)
- Can the user rotate a key without touching anything that uses it? — Portkey
- Can the user see their own-key spend apart from platform spend on one chart? — OpenRouter (two stacked series)

## Request log: browse, search, filter
- Can the user filter by model, key, status, provider without writing a query? — LiteLLM, Portkey, OpenRouter, Helicone (filter chips, live)
- Can the user jump to one request by its id? — OpenRouter
- Can the user filter by a tag they attached themselves? — Helicone (custom properties)
- Do filters carry across dashboard, requests and sessions, and live in the URL? — Helicone
- Can the user see request volume over a picked window ("1h", "3d")? — OpenRouter (histogram)
- Can the user tell from the row whether it hit cache, retried or fell back? — Portkey (plain-English status chips)
- Can the user link to one log entry? — Portkey
- Can the user export the filtered table, and watch new requests arrive live? — table stakes (FEATURES.md)
- Can the user record only metadata, never content, for a sensitive workload? — Portkey ("Do not track")

## One request in full
- Does clicking a row open the full request and response beside the table? — Portkey, OpenRouter (side drawer)
- Can the user see tool-call arguments and results, not only the final text? — LiteLLM (Logs v2, highlighted payloads)
- Can the user re-run the exact request? — Portkey (Replay into playground)
- Can the user leave feedback on a request? — Portkey
- Does the detail show where the time went: provider, overhead, retries? — OpenRouter (waterfall)
- Is storing full content something the user turns on knowingly? — LiteLLM, OpenRouter (off by default, said plainly)

## Routing after the fact
- Can the user see every provider a request tried, in order, before it succeeded? — OpenRouter (waterfall of attempts)
- Can the user find out why a request went where it did without asking support? — OpenRouter (a known pain point)

## Sessions and agent runs
- Can the user see every request of one conversation grouped together? — LiteLLM, Helicone, Langfuse
- Can the user read a session top to bottom like a transcript? — Langfuse
- Can the user switch between chat view, tree view and timing view of one session? — Helicone
- Can the user see a session's totals (cost, requests, latency) at a glance? — LiteLLM, Helicone
- Can the user click one step of a run and see just that step's input, output, tokens and cost? — Langfuse
- Can the user filter to one named kind of session? — Helicone

## Keeping and sharing sessions
- Can the user bookmark a session, share it by link, comment on it, score it? — Langfuse

## Usage and cost
- Can the user see spend by model, key, user and day without a spreadsheet? — LiteLLM, OpenRouter, Langfuse, Helicone
- Can the user pick the metric, the grouping and the granularity? — OpenRouter (Explore)
- Do headline numbers carry a trend and a comparison to the prior period? — OpenRouter (sparkline + delta)
- Can the user save a breakdown view? — OpenRouter
- Can the user set a price for a model the console doesn't know, including cached tokens and tiers? — Langfuse

## From a chart to the requests behind it
- Does clicking a bar open the exact requests behind it, already filtered? — OpenRouter, Helicone
- Can the user go from a cost spike to the one session that caused it? — Langfuse

## Budgets and limits
- Can the user see their balance and what's left without cross-referencing? — OpenRouter
- Can the user cap spend per key with a reset schedule, with the reset spelled out? — OpenRouter, LiteLLM
- Can the user be warned before a limit is reached, not after? — Portkey
- Does basic limit enforcement work for everyone? — avoid Portkey/LiteLLM's enterprise gating

## Models
- Can the user browse every model with price and context window in one table? — OpenRouter
- Can the user sort by price, context, speed, uptime? — OpenRouter
- Can the user expand a model to see every provider serving it? — OpenRouter
- Can the user compare two or three models side by side? — OpenRouter (/compare)

## Routing and fallbacks
- Can the user set an ordered fallback chain? — Portkey, OpenRouter, Helicone
- Can the user split traffic by weight? — Portkey
- Can the user route by conditions with a default? — Portkey
- Can the user say "cheapest" or "fastest" with one setting? — OpenRouter
- Is the routing config readable and diffable text as well as a UI? — Portkey (JSON), Helicone (YAML)
- Does each request link back to the config that routed it? — Portkey

## Prompts: edit, version, compare
- Can the user edit a prompt in the console and have it apply immediately? — Helicone, Langfuse
- Does every save keep a version, with history? — LiteLLM, Portkey, Helicone, Langfuse
- Can the user compare two prompt versions side by side on the same input? — Portkey
- Can the user roll back to an earlier version? — Helicone
- Can the user see which sessions used which prompt version? — Langfuse
- Can the user build a prompt message by message without code? — LiteLLM, Helicone

## Playground and comparison
- Can the user send one prompt to several models and read the answers side by side? — OpenRouter (Chatroom), LiteLLM (Compare)
- Can the user open a logged request in the playground to iterate on it? — Portkey

## Alerts and caching
- Is the user told at a spend threshold instead of finding out later? — Portkey
- Can the user see what caching actually saves? — table stakes; Portkey per-row cache chip

## Settings
- Are settings grouped under task-named sections? — OpenRouter, LiteLLM
- Is each setting named by its outcome, so the user knows what it does before flipping it? — LiteLLM ("Store Prompts in Spend Logs"), OpenRouter
- Is the effect explained next to the control? — LiteLLM (reset text under the budget control)

## Navigation and ease
- Can the user reach logs, usage, keys and models from a persistent nav? — LiteLLM, OpenRouter, Portkey
- Does it start working by changing one line? — Helicone
- Can the user replay a whole agent run step by step? — Requesty
- Are providers, policy and telemetry in one consistent place? — TrueFoundry

## What their users complain about (avoid)
1. A convenience view that scales with total record count and loads the backend down (LiteLLM keys autocomplete, "the UI DDoSing itself").
2. A detail pane that renders blank with no error (LiteLLM log detail).
3. Limits that exist in the UI but only enforce on the top pricing tier (Portkey, LiteLLM).
4. Failures with no explanation in the product (Cloudflare AI Gateway 500s).
5. Safe retry behavior that has to be opted into (Vercel AI Gateway 503s).
6. A product that stops being maintained (Helicone after acquisition).
7. Large payloads rendered raw so the viewer crawls (Langfuse base64 images).
8. Aggregate views with no default time bound (Langfuse self-hosted timeouts).
9. A money-costing routing decision explained only in a help article (OpenRouter provider choice).
10. No search that spans logs, models, prompts and settings, and no search inside Settings.
