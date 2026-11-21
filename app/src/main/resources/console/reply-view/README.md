# reply-view: splice's session reader

reply-view draws one Claude Code session's conversation the way a good chat app does: the person's messages, the
model's replies as Markdown with highlighted code and a copy button, each tool call as one line that opens to its input
and result, thinking behind a toggle, and a reply that is still being written growing in place by whole blocks
(a paragraph, a list item, a code block at a time, never by the character). splice's own pages use it, and any other
host can embed it.

## The files, and where splice serves them

| File | What it is | Served at |
| --- | --- | --- |
| `reply-view.js` | The core: one ES module with no imports. It draws what it is handed and fetches nothing. The Markdown parser and highlighter are pasted into it. | `/reply-view/reply-view.js` |
| `reply-view.css` | Its stylesheet. Every rule is scoped under `.rv`; its look comes only from `--rv-*` custom properties. | `/reply-view/reply-view.css` |
| `connect.js` | The connector for splice's own API: it reads a session's transcript and live feed and hands them to a view. | `/reply-view/connect.js` |
| `index.html`, `page.js`, `page.css` | The reader's own page: `/reply-view/index.html?session=<id>` shows one session; with no `session` it lists them. | `/reply-view/index.html` |

splice serves these files on its control plane, open like the console's other files (a file carries no data; the data
comes from the keyed `/api/*` routes). `/reply-view/` opens the page. They are versioned with splice: they ship inside
splice beside the routes they read, so a host that loads them from the splice whose feed it reads always gets the
version that matches that feed. A host that serves pages from its own origin can fetch these paths from splice and
serve them itself, pinning the hash of what it fetched.

## Embedding it

A host needs the two files, a container, and data. The reader draws only inside one root `<div class="rv">` it appends
to the container. It never scrolls, focuses or measures the page: the host keeps its own scroll, its pinning to the
newest line and its composer.

```html
<link rel="stylesheet" href="/reply-view/reply-view.css">
<main id="scroller"><div id="conversation"></div></main>
<script type="module" src="/host.js"></script>
```

```js
// host.js
import { mount } from "/reply-view/reply-view.js";

const scroller = document.getElementById("scroller");
const container = document.getElementById("conversation");
const view = mount(container, { history: [], live: [] });

// transcript messages, in the shape GET /api/sessions/{id}/transcript sends them (an array)
view.addHistory(messages);
// feed events from GET /api/sessions/{id}/live, one { event, data } or an array of them
view.addLive({ event: "text", data: { turn: "t1", index: 0, delta: "Hello" } });

// keep the host's own pin to the newest line: the root sends a bubbling event after every draw
let pinned = true;
scroller.addEventListener("scroll", () => {
  pinned = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 48;
});
container.addEventListener("replyview:change", () => {
  if (pinned) scroller.scrollTop = scroller.scrollHeight;
});

// later: remove what it drew, its timers and its listener
view.destroy();
```

- `mount(container, { history, live })` draws `history` (transcript messages) and `live` (feed events) and returns the
  view.
- `view.addHistory(messages)` adds transcript messages. It draws only what is new: what is drawn stays where it is, so
  the line a person reads does not move. A message whose `index` is already drawn is replaced in place (the transcript
  route reads its last message again when it grew); an older `index` is drawn above newer ones.
- `view.addLive(events)` adds feed events. Unknown event names are ignored.
- `view.destroy()` removes the root, its timers and its listener.
- After each draw the root dispatches `replyview:change` (a bubbling `CustomEvent`).

A host that relays splice's data through its own channel (its own server, its own socket) feeds the view itself with
`addHistory` and `addLive` and never needs splice's key in the browser: the core reads nothing on its own.

## What it reads

### Transcript messages: `GET /api/sessions/{id}/transcript`

| Field | Meaning |
| --- | --- |
| `index` | The message's place in the conversation. Messages are drawn in `index` order, and an `index` sent again replaces the one drawn. |
| `role` | `user`, `assistant`, `system`, `tool` (a tool's result) or `peer` (a teammate's message). |
| `text` | The message's text. For a tool call (an assistant message with `tool`) it is the call's input as JSON text, read as fields. |
| `ts` | When it was written, in epoch milliseconds (not drawn). |
| `tool` | The tool's name, on a call and on its result. |
| `tool_use_id` | The call's id, on a call and on its result: a result is drawn inside its call. |
| `kind` | `interrupted` (drawn as "Interrupted") or `taken_back` (drawn dimmed, marked "Taken back"). |
| `from` | A teammate's name, on a `peer` message. |
| `message` | The id of the reply an assistant message belongs to (Claude Code's `message.id`): the id the live feed's `turn` names, so a live reply settles into it. |
| `model` | The model that wrote the reply; a quiet model line marks each reply whose model differs from the one before. |
| `thinking` | The reply's thinking, on its first message: drawn behind a closed "Thinking" toggle. |
| `compact` | `true` on a compaction summary: drawn as a "Conversation compacted" divider that opens to the summary. |

None of what the harness wraps around a message for the model is shown: system reminders, command and hook tags and
tool-result envelopes are removed, a slash command reads as the person's line (`/rename tide-fix`), a command's output
as a quiet note, and a message that is only wrapping is not drawn. The person's words are drawn as typed; only the
model's text is Markdown. Raw HTML in a reply is shown as text, never run, and only `http`, `https` and `mailto` links
are links.

### Feed events: `GET /api/sessions/{id}/live`

Each event is handed to `addLive` as `{ event, data }`: the SSE event name and its parsed `data` line.

| Event | Data | What the reader draws |
| --- | --- | --- |
| `hello` | `{session, live}` | Sent first on every connection. A live turn it does not name in `live` has ended; the first `text` or `thinking` of each block of a turn it names is a snapshot, drawn whole at once. |
| `turn` | `{turn, message, agent, model, head, compact, at}` | A reply starts, below every transcript message. `agent` set: a subagent's turn, a closed "Subagent · <model>" line. `compact` true: a closed "Compacting…" line. A `turn` for a turn already drawn starts it over. |
| `text` | `{turn, index, delta}` | Text for block `index`. Held until the block is complete (a line at its line break, a code block at its closing fence) or about a second has passed, then drawn whole as Markdown. |
| `thinking` | `{turn, index, delta}` | While it lasts, one quiet "Thinking…" line and none of its text; when the block closes, the closed "Thinking" toggle. |
| `tool` | `{turn, index, id, name}` | A tool call's one closed line, at once. Its result is drawn inside it when the transcript brings it. |
| `end` | `{turn, stop_reason, error}` | Everything held is drawn and the turn is marked ended; with an `error`, "The reply stopped: <error>". |

When a main-thread turn has ended and the transcript message whose `message` equals the turn's `message` arrives, the
live turn gives way to it: the reply is drawn once, in the transcript's form (its calls naming what they ran, its
thinking folded). A message of that id that arrives while the turn is still live waits for its end. A compaction gives
way to the transcript's summary; a subagent's turn, which the main transcript never holds, stays as its line.

The DOM carries stable hooks a host may read: each drawn transcript message's outer element has `data-index`, and each
live turn is `.rv-turn` with `data-turn` and `data-state` (`live` or `ended`).

## The connector: `connect.js`

On a page served by splice itself, `connect` reads the session from the same splice with the console key this browser
holds (`localStorage["splice-console-access-key"]`, sent as `Authorization: Bearer <key>`):

```js
import { mount } from "/reply-view/reply-view.js";
import { connect } from "/reply-view/connect.js";

const view = mount(document.getElementById("conversation"), {});
const conn = connect(view, {
  session: "<session id>",
  onLocked: () => { /* a 401: this browser is not signed in; nothing more is read until the page reloads */ },
  onEarlier: (more) => { /* whether older messages remain */ },
});
await conn.earlier(); // reads the page before the oldest one handed on, hands it to the view, then resolves
conn.close();         // aborts every read and timer
```

- It first reads the newest page (`before=end`), then what follows the newest message it has (`cursor=after:<index>`)
  300 ms after each `turn`, `end` and `hello`, and every 5 s while the feed is not connected. Reads never overlap.
- It reads the feed with `fetch` and a stream reader (an EventSource cannot send the key in a header, and the key never
  goes in an address), hands each event on in order, and drops `: ping` comments. When the stream ends it joins again
  after 1 s, doubling to at most 30 s while joins keep failing.
- A 401 from either route calls `onLocked()` once and stops reading. A feed that answers 404 or 5xx is not a lock:
  history is polled every 5 s and the feed is asked again after 30 s.

## Dressing it: custom properties

reply-view.css reads every colour, the code font and the bubble's radius from these properties, each with a default,
and never sets them itself. A host sets any of them on the container (or any ancestor) and wins. The defaults are made
of system colours (`CanvasText`, `Canvas`, `LinkText`, `VisitedText`, `ActiveText`, `Mark`) mixed with `color-mix()`
and `light-dark()`, so they follow the host's `color-scheme`: set `color-scheme: light dark` (or `dark`, or `light`) on
the page and the reader matches it with no styling of its own. Text keeps the host's font.

| Property | What it dresses | Default |
| --- | --- | --- |
| `--rv-text` | The conversation's text | `CanvasText` |
| `--rv-muted` | Quiet text: notes, labels, thinking, a tool's argument | `CanvasText` at 64% over `Canvas` |
| `--rv-border` | Rules and rims: tool lines, code blocks, tables, notes | `CanvasText` at 15% over `Canvas` |
| `--rv-bubble` | The person's message bubble | `CanvasText` at 5% (light) or 11% (dark) over `Canvas` |
| `--rv-hover` | A fold's line and the copy button under the pointer | `CanvasText` at 6% |
| `--rv-link` | Links in a reply | `LinkText` |
| `--rv-focus` | The keyboard focus ring | `LinkText` |
| `--rv-error` | Errors: an API error, a failed tool, a stopped reply, removed diff lines' sign | `ActiveText` mixed toward `CanvasText` |
| `--rv-code-bg` | Code blocks, inline code, results, table headers | `CanvasText` at 3.5% (light) or 7% (dark) over `Canvas` |
| `--rv-code-text` | Code and results | `CanvasText` |
| `--rv-code-keyword` | Highlighted keywords | `VisitedText` mixed toward `CanvasText` |
| `--rv-code-title` | Function and section names | `LinkText` mixed toward `CanvasText` |
| `--rv-code-string` | Strings, and added diff lines' sign | `LinkText` and `Mark` mixed, toward `CanvasText` |
| `--rv-code-number` | Numbers and literals | `ActiveText` and `Mark` mixed, toward `CanvasText` |
| `--rv-code-type` | Types and class names | `Mark` mixed toward `CanvasText` |
| `--rv-code-attr` | Attributes, properties, variables | `LinkText` and `Mark` mixed, toward `CanvasText` |
| `--rv-code-comment` | Comments | `CanvasText` at 55% over `Canvas` |
| `--rv-diff-del` | A removed line's ground in an edit | `ActiveText` at 13% over `Canvas` |
| `--rv-diff-add` | An added line's ground in an edit | `LinkText` and `Mark` mixed, at 18% over `Canvas` |
| `--rv-mono` | The code font | `ui-monospace, SFMono-Regular, Menlo, monospace` |
| `--rv-radius` | The person's bubble's corner radius | `18px` |

For night and day, set the properties per theme on the container:

```css
#conversation { color-scheme: dark; --rv-text: #ece8df; --rv-bubble: #2a2a2e; --rv-code-bg: #18181b; }
[data-theme="day"] #conversation { color-scheme: light; --rv-text: #1f1e1c; --rv-bubble: #f0eee9; --rv-code-bg: #f6f5f2; }
```

splice's own page (`page.css`) sets every colour property from splice's brand tokens this way.

## Content-Security-Policy

reply-view keeps a strict policy: `reply-view.js` has no `eval`, `new Function`, dynamic `import()`, `fetch`,
XMLHttpRequest, EventSource or WebSocket; the markup it writes carries no `style` attribute and no inline handler (its
one listener is added on its root); it loads nothing at run time, fonts and images included (an image in a reply is
drawn as a link to it). Its checks serve every page under:

```text
default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
```

A host's own Content-Security-Policy needs only `script-src` and `style-src` to allow wherever it serves
`reply-view.js` and `reply-view.css` (and `connect-src` to allow splice, if it uses `connect.js`). Nothing inline, and
no `unsafe-eval`.

## Vendored libraries

`reply-view.js` pastes two libraries whole, each inside a function scope of its own so neither sets a global: marked
18.2.1 (MIT), the Markdown parser, and highlight.js 11.12.0 (BSD-3-Clause, the common-languages build), the highlighter.
Their notices and license texts are in splice's `THIRD_PARTY_NOTICES.md`.
