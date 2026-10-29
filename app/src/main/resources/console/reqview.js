// The open request, one card of sections: its login and what the provider said, Timing with its sends on one rail, where
// this model's requests land, Tokens and cost, and the Record whose bodies open both ways as trees (Sent, Attempts,
// Returned). Requests draws every request this way, and Compare models opens each answer's request in the same view
// (Marcos, Oct 9: "reuse the same component we use for the requests page"). One file, so the two never drift. The page
// that draws it loads first and declares esc, ICON, md, colorOf and NOW (kit.js, once it lands, is where those live); a
// row carries its own provider id, so this view keeps no table of its own.
"use strict";

const REQVIEW = (() => {
  const pad = (n) => String(n).padStart(2, "0");
  const clockS = (d) => `${d.getHours() % 12 || 12}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${d.getHours() < 12 ? "AM" : "PM"}`;
  const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const dayOf = (d) => (d.toDateString() === NOW.toDateString() ? "" : `${DAY[d.getDay()]} `);
  const n0 = (n) => n.toLocaleString("en-US");
  // a time as a person says it: tenths of a second under 10 s, whole seconds to a minute, then minutes and seconds. The
  // seconds are rounded before they are split, so 119.6 s reads "2 min" and 59.6 s "1 min", never "1 min 60 s" or "60 s"
  const secs = (ms) => {
    if (ms < 1000) return `${ms} ms`;
    if (ms < 9950) return `${+(ms / 1000).toFixed(1)} s`;
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min${s % 60 ? ` ${s % 60} s` : ""}`;
  };
  // The three glyphs this view borrows are read when a row is drawn, not when this file loads, so the page that owns
  // the icons may declare them after it.
  const GLYPH = {
    get limit() { return ICON.limit; }, get signout() { return ICON.signout; }, get retries() { return ICON.retries; },
    bang: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v9M12 18.5v.5"/></svg>',
    compact: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h14M5 20h14M12 7v4M9 9l3 3 3-3M12 17v-4M9 15l3-3 3 3"/></svg>',
    // the two sizes a request that did not end cleanly counts: how much of it left splice, and how much came back
    sent: '<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M2 8h10M8.5 4.5L12 8l-3.5 3.5M14 2.5v11"/></svg>',
    received: '<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M14 8H4M7.5 4.5L4 8l3.5 3.5M2 2.5v11"/></svg>',
  };
  // kept or cut: the records are there, and only then are there tabs. Every other state is said ONCE for the request, in
  // the page's words with the page's control (h.gone), never "Not kept" on Sent, Attempts and Returned in turn: three
  // empty tabs are what sent both p157 and p158 hunting through Settings (fin, Oct 10).
  const kept = (st) => ["kept", "cut"].includes(st);
  // one frame's piece of the answer, in whichever dialect it came: Anthropic's block delta, a Responses text delta, or a
  // chat completion chunk's content. Consecutive pieces of one answer fold to one line in the frames list.
  const pieceOf = (f) => {
    const d = f.data;
    if (!d || typeof d !== "object") return null;
    if (d.type === "content_block_delta") return { key: `b${d.index}`, text: d.delta.text, partial: d.delta.partial_json, index: d.index };
    if (d.type === "response.output_text.delta") return { key: "r", text: d.delta };
    if (d.object === "chat.completion.chunk" && d.choices?.[0]?.delta?.content != null) return { key: "c", text: d.choices[0].delta.content };
    return null;
  };
  // HOW A REPLY ENDED, IN WORDS: each dialect names it in its own field (Anthropic's stop_reason, a chat completion's
  // finish_reason, a Responses status), and "end_turn" is a value, not a sentence. The value stays under the pointer.
  const STOP_WORD = { end_turn: "Ended its turn", tool_use: "Called a tool", max_tokens: "Reached its output limit", stop_sequence: "Hit a stop sequence",
    pause_turn: "Paused its turn", refusal: "Refused", model_context_window_exceeded: "Ran out of context", stop: "Ended its turn", length: "Reached its output limit",
    tool_calls: "Called a tool", function_call: "Called a tool", content_filter: "Filtered by the provider", completed: "Completed", incomplete: "Incomplete",
    failed: "Failed", cancelled: "Cancelled", in_progress: "Still running" };
  // A SEND THAT FAILED INSIDE splice NAMES A JAVA EXCEPTION, which nobody reading the request should have to translate.
  // The common network failures read as what happened, any other exception keeps its own message without its class
  // name, and the raw text stays under the pointer (the attempt's title).
  const EXCEPTION_WORD = { ConnectException: "Could not connect", SocketTimeoutException: "Timed out", HttpTimeoutException: "Timed out", TimeoutException: "Timed out",
    HttpConnectTimeoutException: "Timed out connecting", UnknownHostException: "Unknown host", SSLException: "Secure connection failed",
    SSLHandshakeException: "Secure connection failed", EOFException: "The connection closed early", ClosedChannelException: "The connection closed",
    SocketException: "The connection broke", NoRouteToHostException: "No route to the host" };
  const failureWords = (text) => {
    const m = /(?:^|\s)(?:[a-z_$][\w$]*\.)*([A-Z][\w$]*(?:Exception|Error))(?::\s*([\s\S]*))?$/.exec(String(text ?? ""));
    if (!m) return text;
    const said = m[2]?.trim(), word = EXCEPTION_WORD[m[1]], told = said && !/^null$/i.test(said) ? said : "";
    if (word) return told ? `${word} · ${told}` : word;
    return told || m[1].replace(/(Exception|Error)$/, "").replace(/([a-z])([A-Z])/g, (_, a, b) => `${a} ${b.toLowerCase()}`);
  };

  // view(state): the drawing, over the page's own state, read each time: { tab, nodes (the folds he opened or closed), q }
  const documentJobs = [];
  let documentReads = 0;
  function readDocuments() {
    while (documentReads < 2 && documentJobs.length) {
      const job = documentJobs.shift();
      documentReads++;
      job().finally(() => { documentReads--; readDocuments(); });
    }
  }
  class DeferredBody {
    constructor(id, url, api, notify) {
      Object.assign(this, { id, url, api, notify, active: true, generation: 0, nodes: new Map(), abort: new AbortController() });
    }
    activate() {
      this.active = true;
      if (this.abort.signal.aborted) this.abort = new AbortController();
    }
    deactivate() {
      if (!this.active) return;
      this.active = false; this.generation++; this.abort.abort();
      for (const node of this.nodes.values()) node.loading = false;
    }
    load(path, offset = 0, before = false) {
      if (!this.active) return;
      let node = this.nodes.get(path);
      if (!node) {
        node = { pages: [], history: new Map(), loading: false, error: null };
        this.nodes.set(path, node);
      }
      if (node.loading) return;
      node.loading = true; node.error = null;
      const generation = this.generation, signal = this.abort.signal;
      documentJobs.push(async () => {
        if (!this.active || generation !== this.generation) return;
        const url = new URL(this.url, location.href);
        url.searchParams.set("path", path); url.searchParams.set("offset", offset);
        const answer = await this.api.scoped(signal).get(url.pathname + url.search);
        if (signal.aborted || generation !== this.generation) return;
        node.loading = false;
        if (!answer.ok) {
          node.error = { offset, before };
          console.warn("request document:", answer.body?.error || answer.status);
        } else {
          const page = { offset, body: answer.body };
          if (before) node.pages.unshift(page);
          else {
            const previous = node.pages.at(-1)?.offset;
            if (previous != null) node.history.set(offset, previous);
            node.pages.push(page);
          }
          // Both the rendered document and retained page data stay bounded during a long scroll.
          if (node.pages.length > 4) before ? node.pages.pop() : node.pages.shift();
        }
        this.notify();
      });
      readDocuments();
    }
  }

  function view(state, options = {}) {
    const s = () => state();
    const documents = new Map();
    let documentSerial = 0, documentObserver;
    function documentBody(url) {
      const id = String(++documentSerial), body = new DeferredBody(id, url, options.API, options.render);
      documents.set(id, body);
      return body;
    }
    function moreButton(body, path, offset, label, { auto = false, before = false } = {}) {
      return `<button class="act quiet small doc-more" data-act="document" data-doc="${body.id}" data-path="${esc(path)}" data-offset="${offset}"${before ? ' data-before="1"' : ""}${auto ? ' data-doc-load="1"' : ""}>${label}</button>`;
    }
    function documentHtml(body, path = "", depth = 0, preview = null) {
      const key = `doc:${body.id}:${path}`, open = depth === 0 || s().nodes.has(key);
      if (preview?.complete) return prim(preview.value);
      const toggle = `<button class="node" data-act="node" data-p="${esc(key)}" aria-expanded="${open}">${ICON.caret}</button>`;
      if (!open) return toggle + (preview?.type === "string" ? `${prim(preview.value)}<span class="pv"> …</span>` : `<span class="pv">${preview?.type === "array" ? "[…]" : "{…}"}</span>`);
      const node = body.nodes.get(path);
      let content;
      if (!node?.pages.length) {
        // a body's first page is read as it comes into view: at the top it is a body's own shape, its first keys hollow
        // (a request's model, limits, messages, system and tools), so the request's rows under it stay where they are
        // when it lands; a branch he opened is one line until its children come
        content = node?.error ? moreButton(body, path, 0, "Could not load · Retry")
          : `<span class="doc-reading${depth ? "" : " top"}" data-doc-load="1" data-doc="${body.id}" data-path="${esc(path)}">${depth ? GHOST.bar(12) : GHOST.lines([14, 9, 11, 10, 8, 7])}</span>`;
      } else {
        const first = node.pages[0], last = node.pages.at(-1), kind = first.body.type;
        const earlier = first.offset > 0 ? moreButton(body, path, node.history.get(first.offset) ?? 0, "Load earlier", { before: true }) : "";
        if (kind === "object" || kind === "array") {
          const children = node.pages.flatMap((page) => page.body.children || []);
          content = `<ul class="tree${depth === 0 ? " root" : ""}">${children.map((child) => {
            const part = String(child.key).replace(/~/g, "~0").replace(/\//g, "~1");
            return `<li>${kind === "array" ? `<span class="idx">${esc(child.key)}</span>` : `<span class="key">${esc(child.key)}</span>: `}${documentHtml(body, `${path}/${part}`, depth + 1, child)}</li>`;
          }).join("")}</ul>`;
        } else if (kind === "string" || kind === "text") {
          content = `<pre class="doc-text">${node.pages.map((page) => markIn(page.body.value ?? "")).join("")}</pre>`;
        } else content = prim(first.body.value);
        const later = last.body.next != null ? moreButton(body, path, last.body.next, "Load more", { auto: node.pages.length < 4 }) : "";
        const retry = node.error ? moreButton(body, path, node.error.offset, "Could not load · Retry", node.error) : "";
        content = earlier + content + (node.loading ? GHOST.bar(8) : retry || later);
      }
      return `<span class="doc-node" data-doc="${body.id}" aria-busy="${Boolean(node?.loading)}">${depth ? toggle : ""}${content}</span>`;
    }
    function observeDocuments() {
      if (!options.document || !options.IntersectionObserver) return;
      documentObserver?.disconnect();
      const shown = new Set([...options.document.querySelectorAll(".rqpane [data-doc]")].map((el) => el.dataset.doc));
      for (const [id, body] of documents) shown.has(id) ? body.activate() : body.deactivate();
      documentObserver = new options.IntersectionObserver((entries) => {
        for (const entry of entries) if (entry.isIntersecting) {
          documentObserver.unobserve(entry.target);
          const { doc, path, offset } = entry.target.dataset;
          documents.get(doc)?.load(path, Number(offset) || 0);
        }
      }, { rootMargin: "160px" });
      options.document.querySelectorAll(".rqpane [data-doc-load]").forEach((el) => documentObserver.observe(el));
    }
    function resetDocuments() {
      documentObserver?.disconnect();
      for (const body of documents.values()) body.deactivate();
      documents.clear();
      for (const key of s().nodes) if (key.startsWith("doc:") || key.startsWith("-doc:")) s().nodes.delete(key);
    }
    // ---------- a body as a tree: an object or an array folds to a one-line preview, and opens where he clicks ----------
    function markIn(t) {
      const e = esc(t);
      if (!s().q) return e;
      const q = esc(s().q).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      return e.replace(new RegExp(q, "ig"), (m) => `<mark>${m}</mark>`);
    }
    function prev(v) {
      if (Array.isArray(v)) return `<span class="pv">[${v.length} ${v.length === 1 ? "item" : "items"}]</span>`;
      const ks = Object.keys(v), bits = [];
      for (const k of ks) {
        const x = v[k];
        if (x == null || typeof x === "object") continue;
        bits.push(`<span class="key">${esc(k)}</span>: ${prim(x, 46)}`);
        if (bits.length === 2) break;
      }
      return `<span class="pv">{ ${bits.join(", ")}${ks.length > bits.length ? (bits.length ? ", …" : "…") : ""} }</span>`;
    }
    function prim(x, cut = 0) {
      if (typeof x === "string") { const t = cut && x.length > cut ? `${x.slice(0, cut)}…` : x; return `<span class="str">"${markIn(t)}"</span>`; }
      if (typeof x === "number") return `<span class="num">${x}</span>`;
      if (typeof x === "boolean" || x == null) return `<span class="bool">${x}</span>`;
      return "";
    }
    const contains = (v) => s().q && JSON.stringify(v).toLowerCase().includes(s().q.toLowerCase());
    function tree(v, path, depth, openBy) {
      if (v instanceof DeferredBody) return documentHtml(v);
      const isObj = v && typeof v === "object";
      if (!isObj) return prim(v);
      const entries = Array.isArray(v) ? v.map((x, i) => [i, x]) : Object.entries(v);
      const nodes = s().nodes;
      const open = nodes.has(path) ? true : nodes.has(`-${path}`) ? false : (openBy(path, depth, v) || contains(v));
      const toggle = `<button class="node" data-act="node" data-p="${esc(path)}" aria-expanded="${open}">${ICON.caret}</button>`;
      if (!open) return `${toggle}${prev(v)}`;
      const kids = entries.map(([k, x]) => `<li>${Array.isArray(v) ? `<span class="idx">${k}</span>` : `<span class="key">${esc(k)}</span>: `}${tree(x, `${path}.${k}`, depth + 1, openBy)}</li>`).join("");
      if (depth === 0) return `<ul class="tree root">${kids}</ul>`;  // the body itself is always open: no caret on its own
      return `${toggle}<span class="pv">${Array.isArray(v) ? `[${v.length}]` : ""}</span><ul class="tree">${kids}</ul>`;
    }
    // A body splice could not keep says so in the words the page uses when keeping is off, with what it measured when
    // that was the disk, and the door where old records are deleted to free the space (fin). Its raw reason goes to the log.
    function unsavedHtml(body) {
      if (!body || typeof body !== "object" || body.unavailable !== true) return null;
      if (body.reason) console.info("request body not kept:", body.reason);
      const disk = /free-space floor/.test(body.reason || "");
      return `<div class="gone"><span class="state">Not saved${disk ? " · Disk nearly full" : ""}</span>${disk ? `<a class="act quiet" href="settings.html#data">Your data</a>` : ""}</div>`;
    }
    function bodyTree(body, path) {
      const unsaved = unsavedHtml(body);
      if (unsaved) return unsaved;
      const n = Array.isArray(body?.messages) ? body.messages.length - 1 : -1;
      const openBy = (p, d) => d === 0 || p === `${path}.messages` || p === `${path}.messages.${n}` || p === `${path}.messages.${n}.content` || p.startsWith(`${path}.messages.${n}.content.`) && d <= 4;
      return `<div class="json">${tree(body, path, 0, openBy)}</div>`;
    }
    function headersHtml(h) {
      return `<dl class="hdrs">${Object.entries(h).filter(([, v]) => v != null).map(([k, v]) => `<dt>${esc(k)}</dt><dd${v === "[redacted]" ? ' class="redacted"' : ""}>${v === "[redacted]" ? `${ICON.lock}Redacted` : esc(v)}</dd>`).join("")}</dl>`;
    }
    // the reply put back together from its frames: the text, then each tool call as the transcript's chip, then how it ended
    // [text] false leaves the reply's words to the host that already sets them (Compare's answer, under its record)
    function replyHtml(fr, provider, text = true) {
      const blocks = [];
      for (const f of fr) {
        const d = f.data;
        if (d?.type === "content_block_start") blocks[d.index] = { ...d.content_block, text: d.content_block.text ?? "", partial: "" };
        const pc = pieceOf(f);
        if (!pc) continue;
        const i = pc.index ?? 0;
        blocks[i] ??= { type: "text", text: "", partial: "" };
        if (pc.text) blocks[i].text += pc.text;
        if (pc.partial) blocks[i].partial += pc.partial;
      }
      // What a tool call says beside its name. The input arrives as JSON a piece at a time, so a stream that was cut
      // mid-call leaves it half-written; a call whose input cannot be read yet says nothing rather than taking the
      // answer down with it, and a tool with no command names its first value instead (every tool but Bash).
      const toolSummary = (partial) => {
        let input = null;
        try { input = JSON.parse(partial); } catch { return ""; }
        if (!input || typeof input !== "object") return "";
        const first = input.command ?? input.file_path ?? input.pattern ?? input.path ?? input.url ?? input.description;
        return typeof first === "string" ? first : "";
      };
      // how it ended, in the dialect's own field: a stop reason, a finish reason, or a response's status
      const stop = fr.find((f) => f.data?.type === "message_delta")?.data.delta.stop_reason
        ?? fr.find((f) => f.data?.choices?.[0]?.finish_reason)?.data.choices[0].finish_reason
        ?? fr.find((f) => f.data?.type === "response.completed")?.data.response.status;
      // a Responses stream ends on response.failed with a code where Anthropic's has an error event (ResponsesRoundEnd.kt:14)
      const failed = fr.find((f) => f.event === "response.failed")?.data.response.error;
      const err = fr.find((f) => f.event === "error")?.data ?? (failed && { error: { type: failed.code, message: failed.message } });
      // how it ended and the error's type in words, the dialect's own value under the pointer (a provider's type through
      // the console's own outcome words, so an overload reads "Overloaded" here as on its row)
      const errWord = (type) => OUTCOME_WORD[`failure:${type}`] || tagWord(`failure:${type}`) || type;
      return `<div class="reply">${blocks.filter(Boolean).filter((b) => text || b.type !== "text").map((b) => b.type === "text" ? `<div class="msg agent"><div class="body">${md(b.text)}</div></div>`
        : `<div class="call"><span class="tool" style="--c:${colorOf(provider)}"><span class="tname">${esc(b.name)}</span><span class="tsum">${esc(toolSummary(b.partial))}</span></span></div>`).join("")}`
        + `${stop ? `<p class="stopr"><span class="chip" title="${esc(stop)}">${esc(STOP_WORD[stop] ?? stop)}</span></p>` : ""}`
        // a stream that ended on an error event: its type and its sentence where the stop reason would be
        + `${err ? `<p class="stopr err"><span class="chip" title="${esc(err.error.type)}">${esc(errWord(err.error.type))}</span><span class="state limit">${esc(err.error.message)}</span></p>` : ""}</div>`;
    }
    // events (what the provider calls what it streams), the consecutive pieces of one answer gathered into one line
    function framesHtml(fr, key, lead = "", wide = false) {
      const groups = [];
      for (const f of fr) {
        const g = groups.at(-1), pc = pieceOf(f);
        if (g && pc && g.piece === pc.key && g.event === f.event) g.n++;
        else groups.push({ event: f.event, piece: pc?.key, n: 1, data: f.data });
      }
      const open = s().nodes.has(`${key}:frames`);
      const list = open ? `<ol class="frames">${groups.map((g) => `<li><span class="ev">${esc(g.event)}</span>${g.n > 1 ? `<span class="times">${g.n} times</span>` : ""}${g.n === 1 ? `<span class="fd">${typeof g.data === "object" ? prev(g.data) : prim(g.data)}</span>` : ""}</li>`).join("")}</ol>` : "";
      return `<div class="rvw-fd${open ? " open" : ""}${wide ? " wide" : ""}"><button class="framefold" data-act="frames" data-k="${key}" aria-expanded="${open}">${ICON.caret}${wide ? "Response" : `${lead}${n0(fr.length)} ${fr.length === 1 ? "event" : "events"}`}</button>${list}</div>`;
    }
    // A STATUS IN THE CONSOLE'S WORDS, ITS CODE BESIDE IT (Oct 27): "Status 200" was the wire's word for what the pane
    // above already says in his. An answer is a tick and "Answered"; a refusal is its outcome word in the limit red, as
    // its row says it ("Rate limited", "Overloaded"); a spent plan window is "Limit reached"; a 200 whose stream ended on
    // an error event is that error's word, out of the answer's ink. The code stays on the record, small, as its own fact.
    const CODE_TAG = { 400: "invalid_request_error", 401: "authentication_error", 403: "permission_error", 404: "not_found_error",
      413: "invalid_request_error", 422: "invalid_request_error", 429: "rate_limit_error", 500: "api_error", 502: "api_error", 503: "overloaded_error", 529: "overloaded_error" };
    const TICK = '<svg class="stick" viewBox="0 0 16 16" width="1em" height="1em" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8.5l3.2 3L13 4.5"/></svg>';
    function statusHtml(code, { ended = null, lock = false } = {}) {
      if (code == null) return DRAW.dash;
      const bad = code >= 400, cls = ended ? "x" : String(code)[0];
      const word = lock ? "Limit reached" : ended ? errWord(ended) : !bad ? "Answered"
        : OUTCOME_WORD[`failure:${CODE_TAG[code] ?? (code >= 500 ? "api_error" : "invalid_request_error")}`];
      return `<span class="status s${cls}">${!bad && !ended ? TICK : lock ? ICON.lock : ""}<span class="sword">${esc(word)}</span><small class="scode">${code}</small></span>`;
    }
    // a provider's error type through the console's own outcome words, so an overload reads "Overloaded" here as on its row
    const errWord = (type) => OUTCOME_WORD[`failure:${type}`] || tagWord(`failure:${type}`) || type;
    // A SEND FROM COMPARE IN THE CONSOLE'S NAMES: the body is { head, prompt, model }, and the pane's chip names the
    // command by its label while the tree named it by its key. It reads as three parts side by side: the command as its
    // chip, the model by its name and the prompt as he wrote it, its lines kept. The wire's own values stay under the pointer.
    const isPlaySend = (b) => b && typeof b === "object" && !Array.isArray(b) && typeof b.head === "string" && typeof b.prompt === "string"
      && Object.keys(b).every((k) => ["head", "prompt", "model"].includes(k));
    function partsHtml(r, b, names = {}) {
      const label = names.head || r.cmd || b.head, model = b.model ? names.model || modelName({}, b.model) : null;
      const part = (word, v, wide = false) => `<div class="rvw-pt${wide ? " wide" : ""}"><span class="rvw-ptn">${word}</span>${v}</div>`;
      return `<p class="kicker">What was sent</p><div class="rvw-parts">`
        + part("Command", `<span class="chip" style="--c:${colorOf(r.provider)}" title="${esc(b.head)}">${esc(label)}</span>`)
        + (model ? part("Model", `<span class="mname" title="${esc(b.model)}">${esc(model)}</span>`) : "")
        + part("Prompt", `<span class="sprompt">${markIn(b.prompt)}</span>`, true) + `</div>`;
    }
    // a part of the record kept to one closed line until he opens it; a search that is running opens it, so the marks it
    // found inside are on the screen, and pressing it closes it again whatever the search says
    function folded(key, word, inner, wide = false) {
      const nodes = s().nodes, open = nodes.has(key) ? true : nodes.has(`-${key}`) ? false : Boolean(s().q);
      return `<div class="rvw-fd${open ? " open" : ""}${wide ? " wide" : ""}"><button class="framefold" data-act="node" data-p="${esc(key)}" aria-expanded="${open}">${ICON.caret}${word}</button>${open ? inner : ""}</div>`;
    }
    const bodyOf = (key, body, path, wide = false) => unsavedHtml(body) ?? folded(key, "Body", bodyTree(body, path), wide);

    // ---------- the input drawn by its parts ----------
    // The tokens in as one stacked bar of what the provider served from its cache, what it wrote there and what was new,
    // each part's words and count inside it where they fit and in the row under it where they do not, with the share served
    // from cache as a ring at its head. The three are the split the row holds: a request reports its input in those parts
    // and no others. A write the row did not report is no part; a write it reported as none is said, with its zero.
    const PART_INK = { cached: "var(--splice-chart-2)", write: "var(--splice-chart-1)", fresh: "var(--splice-chart-3)" };
    function inHtml(r) {
      if (r.in == null) return `<div class="rvw-part"><p class="kicker">Tokens in</p><div class="rvw-unrep">Not reported</div></div>`;
      const cached = Math.min(r.cached || 0, r.in), write = Math.min(r.write || 0, r.in - cached), fresh = r.in - cached - write;
      const segs = [{ n: cached, word: "Cached", ink: PART_INK.cached }, { n: write, word: "Cache write", ink: PART_INK.write }, { n: fresh, word: "New", ink: PART_INK.fresh }];
      // a write the row reported as none stands in the row as a part of no length, so it is said where it would have been
      const none = r.write === 0 ? `<span class="pd-lb"><i class="pd-sw ink" style="--c:${PART_INK.write}"></i>Cache write <b>0</b></span>` : "";
      const share = r.in > 0 ? cached / r.in : null;
      const ring = share == null ? "" : DRAW.statTile(DRAW.ring(share, { size: "focal", label: `${DRAW.pct(share)} of tokens in came from the cache` }), DRAW.pct(share), "Cached", { focal: true });
      const said = `Tokens in ${n0(r.in)}: cached ${n0(cached)}, cache write ${n0(write)}, new ${n0(fresh)}`;
      return `<div class="rvw-part"><p class="kicker">Tokens in · ${n0(r.in)}</p><div class="rvw-inrow">${ring}${DRAW.stack(segs, r.in, { label: said, tail: none, bw: 90 })}</div></div>`;
    }
    // what the request was made of, as counts the row holds: the system prompt's layers and the tools it carried, sent
    // with it or deferred to a search; a count the row does not hold is not drawn
    function countedHtml(r) {
      const sock = (word, n) => `<span class="rvw-sock-c"><span>${word}</span><b>${n}</b></span>`;
      const items = [r.layers != null ? sock("System prompt", `${n0(r.layers)} ${r.layers === 1 ? "layer" : "layers"}`) : "",
        r.eager != null ? sock("Tools sent", n0(r.eager)) : "", r.deferredTools != null ? sock("Tools deferred", n0(r.deferredTools)) : ""].filter(Boolean);
      return items.length ? `<div class="rvw-counted">${items.join("")}</div>` : "";
    }
    // the reply's size against what it answered, on one scale: tokens in as the whole, tokens out as its part of it
    function outVsIn(r) {
      if (r.out == null || r.in == null) return "";
      const most = Math.max(r.in, r.out) || 1;
      const row = (word, v, cls) => `<div class="rvw-scale"><span class="rvw-sl"><em>${word}</em><b>${n0(v)}</b></span><span class="rvw-trk ${cls}" role="img" aria-label="${word} ${n0(v)}"><i style="width:${Math.max(0.6, (v / most) * 100).toFixed(2)}%"></i></span></div>`;
      return `<div class="rvw-part"><p class="kicker">Tokens out · ${n0(r.out)} against tokens in · ${n0(r.in)}</p><div class="rvw-scales">${row("Tokens in", r.in, "in")}${row("Tokens out", r.out, "out")}</div></div>`;
    }

    // A fold that keeps a list of headers to one line until he opens it: the count says how many it holds.
    // [url] is where the send went: the fold's first line, so the heading above can name the provider instead of an address
    function headFold(h, key, url = null, lead = "") {
      const n = Object.values(h || {}).filter((v) => v != null).length, open = s().nodes.has(`${key}:frames`);
      if (!n && !url) return "";
      const word = n ? `${n0(n)} ${n === 1 ? "header" : "headers"}` : "Address";
      const to = url ? `<p class="addr"><span>Sent to</span>${esc(url)}</p>` : "";
      return `<div class="rvw-fd${open ? " open" : ""}"><button class="framefold" data-act="frames" data-k="${key}" aria-expanded="${open}">${ICON.caret}${lead}${word}</button>${open ? to + (n ? headersHtml(h) : "") : ""}</div>`;
    }
    // the bodies, by tab: what the caller sent splice, each send upstream, and what splice handed back.
    // tr: { sent: {headers, body}, attempts: [{url, start, ms, status, headers, body, resHeaders?, frames? | raw?, failure?, lock?}],
    // returned: {status, frames | body} }. st: the records' state, kept or cut; every other state never reaches here.
    // h: the pane's options, for { reply } (false: the reply's words are the host's), { names } (the Sent tab's names) and
    // { cells } (the figures the page draws: none on Compare, whose answers carry theirs)
    function bodiesHtml(r, tr, st, h = {}) {
      // a kept trace that holds no record for a piece: that piece was not reported, which is not the request missing
      const tab = s().tab, cut = st === "cut", gone = !tr;
      const absent = `<p class="unavail">${DRAW.unread.object("Not reported")}</p>`;
      // what Claude Code sent: its input by part, what it carried, the model by its name and its headers folded to their
      // count (credentials redacted inside), then the body folded to one line
      if (tab === "sent") {
        if (gone) return absent;
        const note = cut ? `<p class="cut">Cut at 16M characters</p>` : "";
        if (isPlaySend(tr.sent.body)) return `${partsHtml(r, tr.sent.body, h.names)}${bodyOf(`${r.id}:body`, tr.sent.body, `${r.id}.sent`, true)}${note}`;
        const model = r.model ? `<b class="rvw-mname">${esc(r.model)}</b>` : "";
        const line = `<div class="rvw-wordline">${model}${headFold(tr.sent.headers, `${r.id}:sh`)}</div>`, tin = (h.cells || []).length ? inHtml(r) : "";
        return (r.in != null ? line + tin + countedHtml(r) : tin + countedHtml(r) + line)
          + `${bodyOf(`${r.id}:body`, tr.sent.body, `${r.id}.sent`, true)}${note}`;
      }
      if (tab === "returned") {
        if (gone) return absent;
        const rt = tr.returned;
        const reply = rt.frames ? replyHtml(rt.frames, r.provider, h.reply !== false) : "";
        const tail = rt.frames ? framesHtml(rt.frames, `${r.id}:ret`, "", true) : (unsavedHtml(rt.body) ?? folded(`${r.id}:retbody`, "Response", `<div class="json">${tree(rt.body, `${r.id}.ret`, 0, () => true)}</div>`, true));
        // a 200 whose stream ends on an error event keeps its true status, out of the success colour, so the eye lands on the
        // ending (fin, after p88 read Claude's in-stream Overloaded as a success)
        const last = rt.frames?.at(-1), ended = last?.event === "error" ? last.data?.error?.type ?? "api_error"
          : last?.event === "response.failed" ? last.data?.response?.error?.code ?? "api_error" : null;
        const status = `<p class="rstat">${statusHtml(rt.status, { ended, lock: Boolean(tr.attempts?.at(-1)?.lock) && rt.status >= 400 })}</p>`;
        const events = rt.frames ? `<span class="rvw-tag">${n0(rt.frames.length)} ${rt.frames.length === 1 ? "event" : "events"}</span>` : "";
        return `<div class="rvw-tags">${status}${reply}${events}</div>` + outVsIn(r) + tail;
      }
      if (gone) return absent;
      // a send's answer: its headers when the record has them, then its frames or its body
      const res = (a, i) => (a.resHeaders ? headFold(a.resHeaders, `${r.id}:a${i}rh`, null, "Response · ") : "")
        + (a.frames ? framesHtml(a.frames, `${r.id}:a${i}`, "Response · ") : a.raw ? folded(`${r.id}:a${i}:raw`, "Response", `<div class="json">${tree(a.raw, `${r.id}.raw${i}`, 0, () => true)}</div>`) : "");
      // A send is headed by where it went. A websocket round records no address and no status: the frames went over a
      // connection splice had already opened (TurnTrace.wsAttempt), and its heading says so in those words.
      const toWord = PROVIDER_NAME[r.provider] ?? r.cmd ?? "";
      // the provider and the host it answered from; the whole address is under the pointer and behind the headers' disclosure
      const hostOf = (url) => { try { return new URL(url).host; } catch { return ""; } };
      const sendTo = (a) => `<span class="to" title="${esc(a.url ?? (a.transport === "ws" ? "Over the open connection" : ""))}"><i class="pdot" style="--c:${colorOf(r.provider)}" aria-hidden="true"></i>${esc(toWord)}${a.url && hostOf(a.url) ? `<span class="host">${esc(hostOf(a.url))}</span>` : ""}</span>`;
      // WHEN EACH SEND RAN, AS A LENGTH: every attempt is a bar on the one axis the whole request spans, from where it started
      // to where it ended, hatched to the request's first byte, in the provider's colour, a failed one in the limit red, the
      // axis's two ends named under it
      const reach = r.total > 0 ? r.total : Math.max(0, ...tr.attempts.map((a) => (a.start || 0) + (a.ms || 0)));
      const track = (a) => {
        if (!(reach > 0) || !(a.ms >= 0)) return "";
        const from = Math.max(0, Math.min(100, ((a.start || 0) / reach) * 100)), long = Math.max(0, Math.min(100 - from, (a.ms / reach) * 100));
        const bad = a.failure || a.status >= 400, into = r.ttfb != null ? r.ttfb - (a.start || 0) : null;
        const pre = !bad && into > 0 && a.ms > 0 ? `<b class="pre" style="width:${Math.min(100, (into / a.ms) * 100).toFixed(2)}%"></b>` : "";
        return `<div class="atl" role="img" aria-label="${esc(`From ${secs(a.start || 0)} to ${secs((a.start || 0) + a.ms)} of ${secs(reach)}`)}"><span class="atk"><i${bad ? ` class="bad"` : ""} style="left:${from.toFixed(2)}%;width:max(${long.toFixed(2)}%, .3rem)">${pre}</i></span><span class="ate" aria-hidden="true"><small>0</small><small>${secs(reach)}</small></span></div>`;
      };
      // the answer's first byte, as the time into this send it came
      const firstByte = (a) => { const into = r.ttfb != null ? r.ttfb - (a.start || 0) : null; return !(a.failure || a.status >= 400) && into > 0 && a.ms > 0 ? `<p class="rvw-tags"><span class="rvw-tag">First byte <b>${secs(into)}</b></span></p>` : ""; };
      // why it failed, in words; a send that failed as an earlier one did says which, rather than the same line again
      const why = (a, i) => {
        if (!a.failure) return "";
        const same = tr.attempts.slice(0, i).findIndex((b) => b.failure === a.failure);
        return `<p class="why" title="${esc(a.failure)}">${a.lock ? ICON.lock : ""}${same >= 0 ? `Same failure as ${same + 1}` : esc(failureWords(a.failure))}</p>`;
      };
      const first = tr.attempts[0];
      const parts = (a, i) => {
        const same = i && a.headers === first.headers && a.body === first.body;
        const request = same ? `<span class="rvw-same">Same as 1</span>${a.url && a.url !== first.url ? headFold({}, `${r.id}:a${i}h`, a.url, "Request · ") : ""}`
          : `${headFold(a.headers, `${r.id}:a${i}h`, a.url, "Request · ")}${a.body === tr.sent.body ? `<span class="rvw-same">Unchanged</span>` : bodyOf(`${r.id}:a${i}:body`, a.body, `${r.id}.a${i}`)}`;
        return `<div class="rvw-folds">${request}${res(a, i)}</div>`;
      };
      return tr.attempts.map((a, i) => `<section class="attempt${a.failure ? " fail" : ""}"><h3><span class="an">${i + 1}</span>${sendTo(a)}${a.status ? statusHtml(a.status, { lock: Boolean(a.lock) }) : ""}<span class="ams">${secs(a.ms)}</span></h3>`
        + `${track(a)}${firstByte(a)}${why(a, i)}${parts(a, i)}</section>`).join("");
    }
    // THE TIME RAIL (the redesign, Oct 26): each send a bar from its start to its end, hatched to its first byte, a failed
    // one in the limit red; the waits between them the dashed rail; the first byte and the first token marked where they
    // came, under one label when they fall together. Where the page knows this model's own typical and slowest 5% on this
    // command ([ref]), the rail reaches at least that far and marks both, so a request reads as quick or slow against its
    // own kind. An axis of elapsed time runs under it. tr null: no records, so the request is one bar. With more than one
    // send, each is a numbered socket over its bar, the waits between them are drawn, and the count of sends and the time
    // waited stand as the words over the rail's ends.
    const STEPS = [100, 200, 500, 1e3, 2e3, 5e3, 1e4, 2e4, 3e4, 6e4, 12e4, 3e5, 6e5, 12e5, 18e5, 36e5];
    const codeWord = (code) => (code >= 400 ? OUTCOME_WORD[`failure:${CODE_TAG[code] ?? (code >= 500 ? "api_error" : "invalid_request_error")}`] : "Answered");
    // [failed]: the request ended in a failure; with no records its one bar is the whole request, red as its row's is
    function railHtml(r, tr, ref, failed) {
      const total = r.total, span = Math.max(total, ref?.slow ?? 0); // Requests passes no [ref]: its rail is the request's own length
      const at = (ms) => Math.max(0, Math.min(100, (ms / span) * 100)), pct = (ms) => `${at(ms).toFixed(2)}%`;
      const list = tr ? tr.attempts : [{ start: r.queued || 0, ms: total - (r.queued || 0), status: null, failure: failed }];
      const many = Boolean(tr) && list.length > 1;
      const isBad = (a) => a.failure || a.status >= 400;
      const sends = list.map((a) => {
        const bad = isBad(a), into = r.ttfb != null ? r.ttfb - a.start : null;
        const pre = !bad && into > 0 && a.ms > 0 ? `<i class="pre" style="width:${Math.min(100, (into / a.ms) * 100).toFixed(2)}%"></i>` : "";
        return `<span class="sbar${bad ? " bad" : ""}" style="left:${pct(a.start)};width:${pct(a.ms)}">${pre}${!many && a.status >= 400 ? `<b>${a.status}</b>` : ""}</span>`;
      }).join("");
      const gaps = many ? list.slice(1).map((a, i) => { const from = list[i].start + list[i].ms; return a.start > from ? `<span class="rvw-gap" style="left:${pct(from)};width:${pct(a.start - from)}"></span>` : ""; }).join("") : "";
      const socks = many ? `<div class="rvw-socks">${list.map((a, i) => `<span class="rvw-sock${isBad(a) ? " bad" : ""}" style="left:${pct(a.start)}" title="${esc(`Send ${i + 1}${a.status ? ` · ${codeWord(a.status)} ${a.status}` : ""}`)}">${i + 1}</span>`).join("")}</div>` : "";
      const waited = (r.counts || []).find((c) => c.label === "Waited between sends");
      const labels = many ? `<div class="rvw-rlbls"><span></span><span>${waited ? `${waited.n} waited between sends` : ""}</span><span${list.some(isBad) ? ` class="bad"` : ""}>${list.length} attempts</span></div>` : "";
      // a mark past the middle hangs its label to the left, so it never runs off the rail's end
      const mark = (ms, label, cls) => (ms == null ? "" : `<span class="mk ${cls}${at(ms) > 60 ? " late" : ""}" style="left:${pct(ms)}"><span>${label}</span></span>`);
      const together = r.ttfb != null && r.ftok != null && Math.abs(r.ftok - r.ttfb) <= 0.08 * span;
      const below = r.ttfb != null && r.ftok != null && !together;
      const marks = together ? mark(r.ttfb, `First byte ${secs(r.ttfb)} · first token ${secs(r.ftok)}`, "fb up")
        : mark(r.ttfb, `First byte ${secs(r.ttfb)}`, below ? "fb" : "fb up") + mark(r.ftok, `First token ${secs(r.ftok)}`, r.ttfb != null ? "ft" : "fb up");
      // a stretch nothing came through, neutral and dashed, named over the rail: the time the gate held it behind a full
      // limit before splice sent it (admit_held_ms), and a silence splice waited out mid-answer
      const band = (from, ms, label) => `<span class="quiet${at(from) > 60 ? " late" : ""}" style="left:${pct(from)};width:${pct(ms)}"><span>${label}</span></span>`;
      const line = r.queued ? band(0, r.queued, `Waited in line ${secs(r.queued)}`) : "";
      const quiet = r.silent ? band(r.silentFrom, r.silent, `No output for ${secs(r.silent)}`) : "";
      // a resume ends the silence before it, so it stands at that silence's end and its label goes a line higher; the
      // silence's own label already says how long it lasted
      const word = `${r.resumedFresh ? "Started over" : "Resumed"}${r.resumed > 1 ? ` ${r.resumed} times` : ""}`;
      const resumed = r.resumed && r.resumedAt ? `<span class="resume${at(r.resumedAt) > 60 ? " late" : ""}${r.silent ? " stack" : ""}" style="left:${pct(r.resumedAt)}"><span>${word}</span></span>` : "";
      const refs = ref ? `<i class="ref" style="left:${pct(ref.typ)}"></i><i class="ref slow" style="left:${pct(ref.slow)}"></i>` : "";
      const step = STEPS.find((x) => span / x <= 6) ?? STEPS.at(-1), ticks = [];
      for (let t = 0; t <= span * 1.0001; t += step) ticks.push(t);
      const axis = `<span class="rax" aria-hidden="true">${ticks.map((t, i) => `<span class="${i === 0 ? "first" : at(t) > 96 ? "tail" : ""}" style="left:${pct(t)}">${t ? tickWord(t) : "0"}</span>`).join("")}</span>`;
      const tall = Boolean(r.resumed && r.resumedAt && r.silent);
      return `<div class="trail${tall ? " tall" : ""}${below ? "" : " bare"}${below ? " two" : ""}${many ? " socked" : ""}">${labels}${socks}<div class="rail-t">${refs}${gaps}${sends}${line}${quiet}${resumed}${marks}${axis}</div></div>`;
    }
    // an axis label: a round figure, minutes and seconds past the first minute ("1 min 20 s", never "1.33 min")
    const tickWord = (ms) => (ms < 1000 ? `${ms} ms` : ms < 60000 ? `${ms / 1000} s` : ms % 60000 ? `${Math.floor(ms / 60000)} min ${(ms % 60000) / 1000} s` : `${ms / 60000} min`);
    // the waterfall's counters, each a tile drawn only when it happened, so a clean request draws none (the page's
    // countsOf: { glyph, n, label, title, share }). First the load it entered: how many requests its command was already
    // running when splice let this one in (the row's inflight), then its sends when there was more than one and the rail
    // has not drawn them. A tile is its glyph or ring, its figure and its name.
    const tile = ({ icon, n, label, title }) => `<span class="rvw-cnt"${title ? ` title="${esc(title)}"` : ""}><span class="rvw-gl">${icon}</span><b>${n}</b><small>${esc(label)}</small></span>`;
    const tilesHtml = (r, drawn) => {
      const items = [
        r.inflight > 0 ? { icon: DRAW.glyph.peak, n: n0(r.inflight), label: "Already in flight", title: "Requests its command was already running when splice let this one in" } : null,
        r.attempts > 1 && !drawn ? { icon: DRAW.glyph.retry, n: n0(r.attempts), label: "Attempts" } : null,
        ...(r.counts || []).filter((c) => !(drawn && c.label === "Waited between sends")).map((c) => ({ icon: c.share != null ? DRAW.ring(c.share, { size: "focal", label: c.title }) : DRAW.glyph[c.glyph] ?? GLYPH[c.glyph] ?? "", n: c.n, label: c.label, title: c.title }))].filter(Boolean);
      return items.length ? `<div class="rvw-cnts">${items.map(tile).join("")}</div>` : "";
    };
    /** Where the request stands among its own kind: this model's typical and slowest 5% on this command as one range on
     *  a log axis, with this request marked on it, in the key light when it ran past the slowest 5%. */
    function kindHtml(r, ref) {
      const ax = DRAW.axis([Math.min(ref.typ, r.total)], [Math.max(ref.slow, r.total)]), slow = r.total > ref.slow;
      const me = `<i class="me${slow ? " slow" : ""}" style="left:${ax.at(r.total).toFixed(2)}%" title="This request, ${secs(r.total)}"></i>`;
      const axis = `<span class="rvw-axis" aria-hidden="true">${ax.ticks.map((v, i) => `<span class="${i === 0 ? "first" : i === ax.ticks.length - 1 ? "last" : ""}" style="left:${ax.at(v).toFixed(2)}%">${tickWord(v)}</span>`).join("")}</span>`;
      const fig = (word, ms, mark) => `<span>${mark}${word}<b>${secs(ms)}</b></span>`;
      const legend = `<p class="rvw-legend">${fig("Typical", ref.typ, `<i class="pd-lt"></i>`)}${fig("Slowest 5%", ref.slow, `<i class="pd-ls"></i>`)}${fig("This request", r.total, `<i class="me"></i>`)}</p>`;
      return DRAW.section(r.model ? `This model · ${r.model}` : "This model", `<div class="rvw-kind">${DRAW.range(ax, ref.typ, ref.slow).replace(/<\/span>$/, `${me}</span>`)}${axis}</div>${legend}`, { cls: "rqkindsec" });
    }
    /** How long it took: the whole time as the figure it leads with, its two clocks beside it and its counters at the
     *  end of the line, then the rail of its own length. */
    function timingHtml(r, tr, failed) {
      const drawn = Boolean(tr && tr.attempts.length > 1);
      const counts = tilesHtml(r, drawn);
      if (!(r.total > 0) && !counts) return "";
      const clocks = `${dayOf(new Date(+r.ts - r.total))}${clockS(new Date(+r.ts - r.total))} to ${clockS(r.ts)}`;
      const lead = r.total > 0 ? `<div class="rvw-fig${failed ? " bad" : ""}"><b data-tn="">${esc(secs(r.total))}</b><span>${esc(clocks)}</span></div>` : "";
      return DRAW.section("Timing", `<div class="rvw-top">${lead}${counts}</div>${r.total > 0 ? railHtml(r, tr, null, failed) : ""}`, { cls: "rqtime" });
    }
    /** What it used: the tokens out against the whole of the tokens that went through, and the cost beside it. Tokens in is
     *  drawn in the Record's Sent tab, by its parts, and here only where the Record has no tabs to hold it (its records are
     *  not kept). [cells] names what the page draws (in, write, out, cost, attempts). Each count is drawn only where it says
     *  something: the attempts only at 0, the request that never left splice. */
    function usageHtml(r, h, { inRecord = false } = {}) {
      const cells = h.cells || [];
      if (!cells.length) return "";
      const wants = (...ks) => ks.some((k) => cells.includes(k));
      const none = r.in == null && r.out == null && r.write == null;
      // a request splice ended before any attempt (over budget, no quota, too many at once) asked no provider, so it has
      // no token figures at all, not unreported ones and not zeros: Attempts 0 stands alone
      const sent = r.attempts !== 0;
      const colour = { reason: "var(--splice-chart-2)", other: "var(--splice-chart-4)" };
      let tokens = "";
      if (sent && wants("in", "write", "out")) {
        if (none) tokens = inRecord ? "" : `<div class="rvw-part"><p class="kicker">Tokens</p><div class="rvw-dashed">Not reported</div></div>`;
        else {
          const head = `<div class="rvw-ch"><span>Tokens out</span><b>${r.out == null ? DRAW.dash : n0(r.out)}</b></div>`;
          const whole = (r.in ?? 0) + (r.out ?? 0);
          const parts = r.reasoning > 0 && r.out > 0 && r.reasoning <= r.out
            ? DRAW.stack([{ n: r.reasoning, word: "Reasoning", ink: colour.reason }, { n: r.out - r.reasoning, word: "Other", cls: "ok" }], r.out, { label: `Tokens out ${n0(r.out)}: reasoning ${n0(r.reasoning)}, other ${n0(r.out - r.reasoning)}`, bw: 60 })
            : r.out == null ? `<span class="rvw-trk unread" aria-hidden="true"></span>`
              : `<span class="rvw-trk out" role="img" aria-label="Tokens out ${n0(r.out)} of ${n0(whole)} tokens"><i style="width:${Math.max(0.6, whole > 0 ? (r.out / whole) * 100 : 0).toFixed(2)}%"></i></span>`;
          tokens = `<div class="rvw-col">${head}${parts}</div>`;
        }
      }
      let cost = "";
      if (wants("cost")) {
        const plan = h.costPlan, bar = h.costAt != null ? `<span class="rvw-costrow"><span class="rvw-trk cost${plan ? " plan" : ""}" aria-hidden="true"><i style="width:${Math.max(1.5, h.costAt).toFixed(1)}%"></i></span>${plan ? `<i class="rvw-planmark" aria-hidden="true"></i>` : ""}</span>` : "";
        // a request nothing answered was not charged: its cost is said as the fact it is, on the empty slot the figure would fill
        cost = h.unbilled ? `<div class="rvw-col">${tokens ? `<div class="rvw-ch"><span>Cost</span></div>` : ""}<div class="rvw-dashed nc" title="${esc(h.cost ?? "")}">Not charged</div>${h.costSub ? `<small>${h.costSub}</small>` : ""}</div>`
          : `<div class="rvw-col"><div class="rvw-ch"><span>Cost</span><b>${h.cost || DRAW.dash}</b></div>${bar || (h.cost ? "" : `<span class="rvw-trk unread" aria-hidden="true"></span>`)}${h.costSub ? `<small>${h.costSub}</small>` : ""}</div>`;
      }
      const attempts = !sent && wants("attempts") ? `<div class="rvw-col"><div class="rvw-ch"><span>Attempts</span><b>0</b></div></div>` : "";
      const body = `${tokens}${cost}${attempts}`;
      if (!body) return "";
      // the tokens in, by part, under the two figures where the Record has no tabs to hold them
      const under = tokens && !inRecord && !none ? inHtml(r) : "";
      return DRAW.section(tokens && cost ? "Tokens and cost" : tokens ? "Tokens" : "Cost", `<div class="rvw-money${tokens && cost ? " two" : ""}">${body}</div>${under}`, { cls: "rqfigs" });
    }
    // the open request's top line: the login that made it, with the one act that names it where nobody did, and the page's
    // acts at the line's end, in the same place whatever the request did
    const headHtml = (h) => `<header class="rvw-head"><span class="lamp ${h.lampCls}" aria-hidden="true">${h.lamp}</span><div class="rvw-who">${h.who ?? ""}${h.nameIt ?? ""}</div><div class="acts">${h.acts}</div></header>`;
    // the open request, as sections on one ground: the top line, what the provider said (h.said), then Timing, where this
    // model's requests land, Tokens and cost and the Record with its tabs on the kicker's line. h: the head's lamp (cls,
    // glyph), the login's name (who) and its naming act (nameIt), the page's own acts, the trace and its state, the
    // figures, and h.gone: the one line the page says when the records are not here. h.head false leaves the top line
    // to the page; h.recActs ride the Record's kicker line after its tabs and h.lead stands before its kicker; h.folded keeps that line alone; h.rail false
    // leaves out Timing; cells [] leaves out the figures; h.reply false keeps the Returned tab's status, its ending, its
    // tool calls and its frames and leaves the reply's words to the page that sets them beside the record; h.names
    // { head, model } are the console's names for a Compare send's command and model.
    function paneHtml(r, h) {
      const bodies = kept(h.st), reading = h.st === "reading", tr = bodies ? h.tr() : null, tabbed = bodies || reading;
      // while the records are read the tabs already stand and the body is its own shape, hollow
      const record = bodies ? bodiesHtml(r, tr, h.st, h) : reading ? `<div class="json reading" aria-busy="true">${GHOST.lines([18, 27, 23, 14, 25, 19])}</div>` : h.gone ?? "";
      const tabs = tabbed && !h.folded ? `<div class="switch tabs" role="tablist">${[["sent", "Sent"], ["attempts", "Attempts"], ["returned", "Returned"]].map(([v, w]) => `<button role="tab" data-act="tab" data-v="${v}" aria-pressed="${s().tab === v}">${w}</button>`).join("")}</div>` : "";
      const failed = /\bfail\b/.test(h.lampCls ?? "");
      const timing = h.rail === false ? "" : timingHtml(r, tr, failed);
      const kind = h.rail === false || !h.ref || !(r.total > 0) ? "" : kindHtml(r, h.ref);
      const usage = usageHtml(r, h, { inRecord: tabbed });
      const rec = record ? `<section class="psec rqrec${h.folded ? " folded" : ""}"><div class="sechead">${h.lead ?? ""}<h3 class="kicker">Record</h3>${tabs}${h.recActs ? `<span class="racts">${h.recActs}</span>` : ""}</div>${h.folded ? "" : `<div class="bodies">${record}</div>`}</section>` : "";
      const top = h.head === false ? "" : headHtml(h);
      const said = h.said ? `<div class="upmsgs">${h.said}</div>` : "";
      return `<section class="pane rqpane rvw${h.head === false ? " rvw-bare" : ""}" style="--c:${colorOf(r.provider)}" aria-label="Request at ${clockS(r.ts)}">${top}${said}`
        + `<div class="scroll pdetail">${timing}${kind}${usage}${rec}</div></section>`;
    }
    // its controls: a tab, a fold of a body, a send's frames. True when the click was one of them; the page draws again.
    function click(el) {
      const st = s();
      switch (el.dataset.act) {
        case "tab": st.tab = el.dataset.v; return true;
        case "node": { const p = el.dataset.p, isOpen = el.getAttribute("aria-expanded") === "true"; st.nodes.delete(p); st.nodes.delete(`-${p}`); st.nodes.add(isOpen ? `-${p}` : p); return true; }
        case "frames": { const k = `${el.dataset.k}:frames`; st.nodes.has(k) ? st.nodes.delete(k) : st.nodes.add(k); return true; }
        case "document": {
          const { doc, path, offset, before } = el.dataset;
          documents.get(doc)?.load(path, Number(offset) || 0, before === "1");
          return true;
        }
        default: return false;
      }
    }
    return { markIn, paneHtml, click, documentBody, observeDocuments, resetDocuments };
  }
  return { view, kept, GLYPH, pad, clockS, dayOf, n0, secs };
})();
