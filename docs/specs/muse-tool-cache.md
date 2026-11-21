# A Muse session pays for its conversation once

- Status: planned 2026-11-16. Built against frozen acceptance checks in `.campaign/checks/muse/`, feature
  `muse-tool-cache` in `.campaign/ledger.toml`.
- Scope: the Muse head (`[providers.muse]`, Meta's Model API on the Responses wire). Other heads keep what they
  do today (see "Not in this change").

## The goal

A person working in Claude Code on the Muse head pays for each part of a conversation once. When the tools of the
conversation change in the middle of it, the next request still reads everything already sent from Meta's prompt
cache, at the cached price, and pays in full only for what is new. The tool that arrived is the model's to call,
under its own name.

## What happened (2026-11-15 21:58 CT to 2026-11-16 01:30 CT)

One five-hour window of a Muse subscription was used up in three and a half hours. Read from the head's own trace:

- The window took 277.0 million input tokens. 266.6 million were read from the cache and 10.44 million were paid
  in full.
- 26 requests paid a whole conversation in full because a tool had just been loaded: 20 on main threads and 6 in
  subagents, 2.69 million tokens together. The median one re-read about 100,000 tokens. The largest came at
  00:12 CT: the request before it read 252,404 of its 252,776 tokens from the cache, the model loaded `TaskStop`,
  and the next request read 10,868 of 253,236 from the cache and paid 242,368 in full.
- The tools were Claude Code's own deferred tools, loaded through its ToolSearch: `SendMessage` 12 times, the Exa
  search tools 4 times, `TaskStop`, `Monitor` and a Temple tool.
- A token paid in full costs about eight times a token read from the cache (Meta's published API card for Muse
  Spark 1.3: $1.25 against $0.15 per million). Meta doesn't publish how a subscription's window is metered.
  Fitting its own per-answer meter (`response.subscription_usage`) to that card gives about 2.3 points of the
  window per million tokens paid in full and 0.28 per million read from the cache. By that estimate one load in
  a 250,000-token conversation costs half a point of the window, and the 26 loads cost about five and a half
  points. It is an estimate: the fit is off by about five points over the window.

The number that matters is the absolute cost. The window as a whole read 96% of its input from the cache, and it
still lost millions of tokens to this.

## Why a load re-reads the conversation today

- Meta caches a request by prefix, in the order instructions, tools, input. A change anywhere in the tool list
  moves everything after it, the whole conversation, out of the cache.
- Claude Code changes a conversation's tools three ways, each built so that Anthropic's own cache keeps its
  prefix:
  - its ToolSearch answers with a `tool_reference` for each tool it loads, and the tool's definition joins the
    request flagged `defer_loading`, which Anthropic keeps out of the cached prefix;
  - a server that connects adds its tools in a system message (`tool_addition`);
  - a server that leaves takes them away in a system message (`tool_removal`).
- For the Codex backend splice writes an added tool into the history, as an `additional_tools` item. Muse refused
  that item on 2026-10-27 (400, "`input[8]` did not match any supported type"), so since then the Muse head has put
  every loaded or added tool at the end of the request's tool list and taken a removed one out of it
  (`MUSE_TAKES_INLINE_TOOL_CHANGES`, `ResponsesRequestBuilder.directTools`). Every one of those changes re-reads
  the conversation.

## What Meta's API does

From Meta's documentation (dev.meta.ai, read 2026-11-16):

- Prompt caching is automatic and by prefix, in the order instructions, tools, input. `prompt_cache_key` keeps
  requests that share a prefix together, and `prompt_cache_retention: "24h"` keeps it for a day. Both are on the
  Responses route only.
- Tool search runs on the Responses and the Messages route: "When the model loads a tool, the API appends it at the
  end of the context, preserving the cache prefix."
- A tool search can be executed by the client. The request declares
  `{"type": "tool_search", "execution": "client", ...}`, and the history carries a `tool_search_call` and a
  `tool_search_output` whose `tools` are the tools handed over, each a function tool flagged `defer_loading`.
- "Changing the loaded set on a later turn breaks the cache from that point forward", which leaves everything
  before that point cached.
- The Messages route (`POST /v1/messages`) is an adapter over the same pipeline. It has no `prompt_cache_key` and
  no retention setting, and it refuses a top-level field it doesn't know.

From Meta's own answers:

- 2026-10-27: the `additional_tools` input item is refused, 400.
- 2026-11-16 02:58 CT: a probe sent with the head's own key was answered three times before anything else was
  checked. An `additional_tools` item: 400 "`input[1]` did not match any supported type". A tool name of 77
  characters: 400 "`name` must be at most 64 characters, got 77". A tool flagged `defer_loading` with no
  `tool_search` tool: 400 "Deferred tools require tools.tool_search."
- Every other request of that probe was refused 403 `client_attestation_denied`: the subscription's key serves the
  Muse Code client only, and a script is not that client. The probe was not sent again in any other form.

So two things are documented and not yet seen on this subscription: that Meta takes the client-executed pair from
the Muse head, and that a request carrying it reads its history from the cache. Whether the pair needs a
`tool_search` tool in the tool list, and whether it rides beside the head's own hosted tool search, is not known
either. The only client that can show them is the head itself, so the build shows them first (see "The items").

splice already writes this pair. `ResponsesInputTools.appendToolDeclaration` declares a tool of the head's own tool
surface in the history as a `tool_search_call` and a `tool_search_output` with `execution: "client"`, under a call
id that depends on the tool's name alone, so the pair is the same bytes on every request.

## Decisions

1. **A conversation's tool list never changes.** Every request of a conversation sends Meta the tool list its
   first request sent: the same tools, in the same order, in the same bytes. A tool Claude Code declares
   `defer_loading` and never loads stays out of it, as today. If the pair of decision 2 needs something in the
   tool list, it is there from the first request.
2. **A tool that arrives rides in the history, at the place it arrived.** It is handed to the model as Meta's
   client-executed tool-search pair, written right after the result of the ToolSearch call that loaded it, or at
   the system message that added it.
   - The pair is the same bytes on every later request. Nothing in it depends on the turn: not Claude Code's
     `cache_control` mark, which sits on the load's result only while it is the last block, and not a position
     or a clock.
   - From that request on the tool is the model's to call, and its call reaches Claude Code under the tool's own
     name. A name past Meta's 64 characters is shortened on the way out and restored on the way back, as it is in
     the tool list today.
   - This holds for a subagent's conversation, for the request that compacts a conversation, and after a daemon
     restart.
3. **A tool that leaves is told, not unlisted.** The tool list and the history stay as they were, whether the
   conversation started with the tool or it arrived later. The removal's system message tells the model, in
   words, which tools went. A call the model still makes to one of them goes to Claude Code, which refuses a tool
   it no longer has. Taking the tool out of the list, as today, costs a whole conversation each time.
4. **A tool that comes back with a new schema** is handed over again at the place it came back, and the model
   calls it with the newest schema.
5. **Only the conversation changes the request.** After a compaction Claude Code starts a new conversation. A
   tool it still declares `defer_loading` there, with no result that references it, is not the model's until
   ToolSearch loads it again. That is today's behaviour and it stays.
6. **How Muse takes a tool change is a Muse fact.** It stays in the Muse provider module, next to
   `MUSE_TAKES_INLINE_TOOL_CHANGES`, and the shared dialect reads it as a quirk.
7. **The tests that pinned the old behaviour change with it.** `ToolChangeEveryHeadTest` holds every head's tool
   list to the conversation's current tools. For the Muse head it now holds decisions 1 to 4, and the commit says
   so.

### Why not Meta's Messages route

Meta documents Claude Code on `POST /v1/messages`, and on that route Claude Code's own shapes (`defer_loading`,
`tool_reference`) may pass through as they are. It is not the first choice here, for three reasons:

- The route has no `prompt_cache_key` and no retention setting. The head uses the first to keep each session's
  cache apart and to move a conversation to a new gateway route, and the second to keep a cache for a day.
- The head's reasoning replay, its gateway routing and its retries are built on the Responses wire, and
  `museCompatible` turns a Muse passthrough config into Responses at boot. Changing the wire is a far larger
  change than writing a pair splice already writes.
- Meta documents the same tool search on both routes.

The probe was meant to compare the two and was refused before it could. If the real head shows that Meta doesn't
take the pair, or that the pair doesn't hold the cache, the item is disputed back to the lead with Meta's answer,
and the Messages route is the next thing to try.

## The items

Each item has its check in `.campaign/checks/muse/`, run against a real daemon built from the checkout with a
Muse head on a stand-in for Meta's API (`lib/meta.ts`). The stand-in caches by prefix the way Meta documents it and
says how many tokens of each request it read from the cache, lets its model call the tools a request gives it, and
refuses the three requests Meta refused, in Meta's words. `lib/claude.ts` sends a conversation the way Claude Code
sent them on 2026-11-16: the tool list in name order, a placeholder tool declared `defer_loading` and never
loaded, and the `cache_control` mark on the last block of each request.

- **muse-tool-load** (`load.test.ts`), decisions 1, 2 and 5: a tool Claude Code's ToolSearch loads.
- **muse-tool-change** (`change.test.ts`), decisions 1 to 4: a tool a server adds or takes away.

The stand-in follows Meta's documentation where Meta's own answer is missing, so the real head is the last word.
Before anything else, the builder of muse-tool-load sends one conversation through a build of the head to Meta,
with a deferred tool loaded in the middle, and reads the head's trace: the request that carried the load was
answered 200, its cached count covers the conversation before it, and the model called the loaded tool. The
feature review does the same on the finished build, in a real Claude Code session.

## Showing it on the real head

The head is the only client Meta lets this subscription's key serve, so the proof is one conversation sent by a
build of the head itself. It touches a person's live subscription, so it is done this way and no other:

- A test daemon in a home of its own, as the checks boot one, with its Muse provider pointed at Meta and a copy
  of the Muse login file inside that home, mode 600. The installed daemon, its `splice.toml` and its login file
  are never edited, pointed at or shared, and the copy is deleted when the run ends. What the file holds is never
  printed, logged or sent anywhere but to Meta by the daemon.
- A handful of small requests: a short conversation, a deferred tool loaded in the middle, one call of it. The
  test session is never a real working session.
- If Meta answers 401 or 403, the run stops there and Meta's answer goes to the lead. Nobody sends the request
  again in another form, changes what the head says about itself to get past the answer, or lets the test daemon
  sign in again or mint a key.
- What it shows is read from the test daemon's trace: the status of the request that carried the load, its cached
  count against the size of the request before it, and the call of the loaded tool.

## Not in this change

- The other heads whose backend refuses the `additional_tools` item (an OpenAI API key, xAI). They grow the tool
  list as the Muse head does today. Each backend's own way to hold its cache is a change of its own.
- The other requests of that window that paid a conversation in full: the first request after a compaction and the
  first of a new or resumed session (expected), two changes of reasoning effort (the person's choice, and effort is
  part of what Meta caches under), three requests splice moved to a new gateway route after a 504 or a slow
  answer, and two whose cause isn't known yet.
- Which model a subagent runs on. Every model slot of the head runs Wonder Full today; that is the person's
  `splice.toml`.
- Showing what a missed cache cost in the console.
