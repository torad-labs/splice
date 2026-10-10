// The open request: its sends on one time rail, its tokens and cost, and the bodies both ways as trees that open where
// he clicks (Sent, Attempts, Returned). Requests draws every request this way, and Compare models opens each answer's
// request in the same view (Marcos, Oct 9: "reuse the same component we use for the requests page"). One file, so the
// two never drift. The page that draws it loads first and declares esc, ICON, md, colorOf and NOW (kit.js, once it
// lands, is where those live); a row carries its own provider id, so this view keeps no table of its own.
"use strict";

const REQVIEW = (() => {
  const pad = (n) => String(n).padStart(2, "0");
  const clockS = (d) => `${d.getHours() % 12 || 12}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${d.getHours() < 12 ? "AM" : "PM"}`;
  const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const dayOf = (d) => (d.toDateString() === NOW.toDateString() ? "" : `${DAY[d.getDay()]} `);
  const n0 = (n) => n.toLocaleString("en-US");
  const secs = (ms) => (ms < 1000 ? `${ms} ms` : ms < 60000 ? `${+(ms / 1000).toFixed(ms < 10000 ? 1 : 0)} s` : `${Math.floor(ms / 60000)} min${Math.round((ms % 60000) / 1000) ? ` ${Math.round((ms % 60000) / 1000)} s` : ""}`);
  // The three glyphs this view borrows are read when a row is drawn, not when this file loads, so the page that owns
  // the icons may declare them after it.
  const GLYPH = {
    get limit() { return ICON.limit; }, get signout() { return ICON.signout; }, get retries() { return ICON.retries; },
    bang: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v9M12 18.5v.5"/></svg>',
    compact: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h14M5 20h14M12 7v4M9 9l3 3 3-3M12 17v-4M9 15l3-3 3 3"/></svg>',
  };
  // kept, cut or unavailable: the records are there. off, deleted or expired: only the perf row is (Knob.kt:519-530, TraceRoute.kt)
  const kept = (st) => ["kept", "cut", "unavailable"].includes(st);
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

  // view(state): the drawing, over the page's own state, read each time: { tab, nodes (the folds he opened or closed), q }
  function view(state) {
    const s = () => state();
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
    function bodyTree(body, path) {
      const n = body.messages ? body.messages.length - 1 : -1;
      const openBy = (p, d) => d === 0 || p === `${path}.messages` || p === `${path}.messages.${n}` || p === `${path}.messages.${n}.content` || p.startsWith(`${path}.messages.${n}.content.`) && d <= 4;
      return `<div class="json">${tree(body, path, 0, openBy)}</div>`;
    }
    function headersHtml(h) {
      return `<dl class="hdrs">${Object.entries(h).filter(([, v]) => v != null).map(([k, v]) => `<dt>${esc(k)}</dt><dd${v === "[redacted]" ? ' class="redacted"' : ""}>${v === "[redacted]" ? `${ICON.lock}Redacted` : esc(v)}</dd>`).join("")}</dl>`;
    }
    // the reply put back together from its frames: the text, then each tool call as the transcript's chip, then how it ended
    function replyHtml(fr, provider) {
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
      return `<div class="reply">${blocks.filter(Boolean).map((b) => b.type === "text" ? `<div class="msg agent"><div class="body">${md(b.text)}</div></div>`
        : `<div class="call"><span class="tool" style="--c:${colorOf(provider)}"><span class="tname">${esc(b.name)}</span><span class="tsum">${esc(toolSummary(b.partial))}</span></span></div>`).join("")}`
        + `${stop ? `<p class="stopr"><span class="chip">${esc(stop)}</span></p>` : ""}`
        // a stream that ended on an error event: its type and its sentence where the stop reason would be
        + `${err ? `<p class="stopr err"><span class="chip">${esc(err.error.type)}</span><span class="state limit">${esc(err.error.message)}</span></p>` : ""}</div>`;
    }
    // frames, the consecutive pieces of one answer gathered into one line
    function framesHtml(fr, key) {
      const groups = [];
      for (const f of fr) {
        const g = groups.at(-1), pc = pieceOf(f);
        if (g && pc && g.piece === pc.key && g.event === f.event) g.n++;
        else groups.push({ event: f.event, piece: pc?.key, n: 1, data: f.data });
      }
      const open = s().nodes.has(`${key}:frames`);
      const list = open ? `<ol class="frames">${groups.map((g) => `<li><span class="ev">${esc(g.event)}</span>${g.n > 1 ? `<span class="times">× ${g.n}</span>` : ""}${g.n === 1 ? `<span class="fd">${typeof g.data === "object" ? prev(g.data) : prim(g.data)}</span>` : ""}</li>`).join("")}</ol>` : "";
      return `<button class="framefold" data-act="frames" data-k="${key}" aria-expanded="${open}">${ICON.caret}${n0(fr.length)} ${fr.length === 1 ? "frame" : "frames"}</button>${list}`;
    }
    function blockHtml(title, inner, extra = "") { return `<section class="block"><h3>${title}${extra}</h3>${inner}</section>`; }
    // the bodies, by tab: what the caller sent splice, each send upstream, and what splice handed back.
    // tr: { sent: {headers, body}, attempts: [{url, start, ms, status, headers, body, resHeaders?, frames? | raw?, failure?, lock?}],
    // returned: {status, frames | body} }. st: the records' state (kept, cut, unavailable, off, deleted, expired, step).
    function bodiesHtml(r, tr, st) {
      if (st === "step") return `<div class="gone"><span class="state">No send</span></div>`;
      // the records are being read; nothing is drawn as missing until that read answers
      if (st === "reading") return `<div class="gone"><span class="state">Reading</span></div>`;
      if (st === "off") return `<div class="gone"><span class="state">${ICON.lock}Not traced</span></div>`;
      if (st === "deleted") return `<div class="gone"><span class="state">Deleted</span></div>`;
      if (st === "expired") return `<div class="gone"><span class="state">Not kept</span></div>`;
      // Unavailable: the request is listed and its records are not here. There is no body, no headers and no status
      // to draw, so every block says so — reading one off a trace that is not there is the one thing this must not do.
      const tab = s().tab, cut = st === "cut", gone = st === "unavailable" || !tr;
      const absent = `<p class="unavail">Not kept</p>`;
      if (tab === "sent") return blockHtml("Headers", gone ? absent : headersHtml(tr.sent.headers))
        + blockHtml("Body", gone ? absent : `${bodyTree(tr.sent.body, `${r.id}.sent`)}${cut ? `<p class="cut">Cut at 16M characters</p>` : ""}`);
      if (tab === "returned") {
        if (gone) return blockHtml("Status", absent) + blockHtml("Body", absent);
        const rt = tr.returned;
        const inner = rt.frames ? `${replyHtml(rt.frames, r.provider)}${framesHtml(rt.frames, `${r.id}:ret`)}` : `<div class="json">${tree(rt.body, `${r.id}.ret`, 0, () => true)}</div>`;
        // a 200 whose stream ends on an error event keeps its true status, out of the success colour, so the eye lands on the
        // ending (fin, after p88 read Claude's in-stream Overloaded as a success)
        const ended = ["error", "response.failed"].includes(rt.frames?.at(-1)?.event);
        return blockHtml("Status", rt.status == null ? absent : `<p class="status s${ended ? "x" : String(rt.status)[0]}">${rt.status}</p>`) + blockHtml("Body", inner);
      }
      if (gone) return `<div class="gone"><span class="state">Not kept</span></div>`;
      // a send's answer: its headers when the record has them, then its frames or its body
      const answer = (a, i) => (a.frames ? framesHtml(a.frames, `${r.id}:a${i}`) : `<div class="json">${tree(a.raw, `${r.id}.raw${i}`, 0, () => true)}</div>`);
      const resHtml = (a, i) => (a.resHeaders ? `<div>${blockHtml("Response", headersHtml(a.resHeaders))}${answer(a, i)}</div>`
        : a.frames || a.raw ? `<div>${blockHtml("Response", answer(a, i))}</div>` : "");
      // A websocket round records no address and no status: the frames went over a socket splice had already opened
      // (TurnTrace.wsAttempt). Its heading names the transport rather than spelling a POST that never happened.
      const sendTo = (a) => (a.url ? `POST ${esc(a.url)}` : a.transport === "ws" ? "WebSocket" : "");
      return tr.attempts.map((a, i) => `<section class="attempt${a.failure ? " fail" : ""}"><h3><span class="an">${i + 1}</span><span class="url">${sendTo(a)}</span>${a.status ? `<span class="status s${a.failure ? "x" : String(a.status)[0]}">${a.status}</span>` : ""}<span class="ams">${secs(a.ms)}</span></h3>`
        + `${a.failure ? `<p class="why">${a.lock ? ICON.lock : ""}${esc(a.failure)}</p>` : ""}`
        + `<div class="two${resHtml(a, i) ? "" : " one"}"><div>${i && a.headers === tr.attempts[0].headers && a.body === tr.attempts[0].body ? `<p class="same">Same as 1</p>`
          : `${blockHtml("Request", headersHtml(a.headers))}${a.body === tr.sent.body ? `<p class="same">Unchanged</p>` : blockHtml("Body", bodyTree(a.body, `${r.id}.a${i}`))}`}</div>`
        + `${resHtml(a, i)}</div></section>`).join("");
    }
    // the attempts on one time rail: each send a bar from its start to its end, the waits between them the bare rail, and
    // the first byte and the first token marked where they came. tr null: no records, so the request is one bar.
    function railHtml(r, tr) {
      const total = r.total, pct = (ms) => `${((ms / total) * 100).toFixed(2)}%`;
      const sends = (tr ? tr.attempts : [{ start: r.queued || 0, ms: total - (r.queued || 0), status: 200 }]).map((a) => {
        const bad = a.failure || a.status >= 400, pre = !bad && r.ttfb != null ? `<i class="pre" style="width:${(((r.ttfb - a.start) / a.ms) * 100).toFixed(2)}%"></i>` : "";
        return `<span class="sbar${bad ? " bad" : ""}" style="left:${pct(a.start)};width:${pct(a.ms)}">${pre}${a.status >= 400 ? `<b>${a.status}</b>` : ""}</span>`;
      }).join("");
      // a mark past the middle hangs its label to the left, so it never runs off the rail's end
      const mark = (ms, label, cls) => (ms == null ? "" : `<span class="mk ${cls}${ms / total > .6 ? " late" : ""}" style="left:${pct(ms)}"><span>${label} ${secs(ms)}</span></span>`);
      // a silence splice waited out, as the stretch it was, and a resume where the stream broke and splice re-sent: from the
      // partial answer, or the round over from scratch
      const quiet = r.silent ? `<span class="quiet${r.silentFrom / total > .6 ? " late" : ""}" style="left:${pct(r.silentFrom)};width:${pct(r.silent)}"><span>Waited out ${secs(r.silent)}</span></span>` : "";
      // the wait for a free slot, before splice sent it (admit_wait_ms)
      const line = r.queued ? `<span class="quiet" style="left:0;width:${pct(r.queued)}"><span>Waited in line ${secs(r.queued)}</span></span>` : "";
      const resumed = r.resumed ? `<span class="resume${r.resumedAt / total > .6 ? " late" : ""}" style="left:${pct(r.resumedAt)}"><span>${r.resumedFresh ? "Started over" : "Resumed"}</span></span>` : "";
      return `<div class="trail"><div class="rail-t">${sends}${line}${quiet}${resumed}${mark(r.ttfb, "First byte", "fb")}${mark(r.ftok, "First token", "ft")}</div>`
        + `<div class="ends"><span>${clockS(new Date(+r.ts - total))}</span><span class="tot">${secs(total)}</span><span>${clockS(r.ts)}</span></div></div>`;
    }
    // the figures the request has: cells names which, in order (in, write, out, cost, attempts); cost is its text
    function figuresHtml(r, cells, cost) {
      const cell = (label, value, sub = "") => `<div class="fig"><span class="label">${label}</span><span class="v">${value}</span>${sub ? `<span class="fsub">${sub}</span>` : ""}</div>`;
      const told = (v) => (v == null ? `<span class="nr">Not reported</span>` : n0(v)); // TurnBill.kt:37-40 writes a field only when reported
      const cachePct = r.in ? Math.round((r.cached / r.in) * 100) : 0;
      const of = { in: () => cell("Tokens in", told(r.in), r.cached ? `${cachePct}% cached` : ""), write: () => cell("Cache write", told(r.write)),
        out: () => cell("Tokens out", told(r.out)), cost: () => cell("Cost", cost), attempts: () => cell("Attempts", r.attempts) };
      return `<div class="figs" style="--n:${cells.length}">${cells.map((k) => of[k]()).join("")}</div>`;
    }
    // the open request. h: the head's lamp (cls, glyph), word, name and account, the page's own acts, the trace and its
    // state, and the figures with the cost's text
    function paneHtml(r, h) {
      const bodies = kept(h.st);
      const tabs = bodies ? `<div class="switch tabs" role="tablist">${[["sent", "Sent"], ["attempts", "Attempts"], ["returned", "Returned"]].map(([v, w]) => `<button role="tab" data-act="tab" data-v="${v}" aria-pressed="${s().tab === v}">${w}</button>`).join("")}</div>` : "";
      return `<section class="pane rqpane" style="--c:${colorOf(r.provider)}" aria-label="Request at ${clockS(r.ts)}"><header><span class="lamp ${h.lampCls}" aria-hidden="true">${h.lamp}</span>`
        + `<div class="who"><div class="top"><span class="name">${esc(h.name)}</span><time>${dayOf(r.ts)}${clockS(r.ts)}</time></div>`
        + `<div class="meta"><span class="chip">${esc(r.cmd)}</span>${h.word}<span>${esc(r.model)}</span>${h.acct ? `<span>${h.acct}</span>` : ""}</div></div>`
        + `<div class="acts">${h.acts}</div></header>`
        + `<div class="scroll">${h.st === "step" ? "" : railHtml(r, kept(h.st) ? h.tr() : null)}${h.st === "step" ? "" : figuresHtml(r, h.cells, h.cost)}${tabs}<div class="bodies">${bodiesHtml(r, bodies ? h.tr() : null, h.st)}</div></div></section>`;
    }
    // its controls: a tab, a fold of a body, a send's frames. True when the click was one of them; the page draws again.
    function click(el) {
      const st = s();
      switch (el.dataset.act) {
        case "tab": st.tab = el.dataset.v; return true;
        case "node": { const p = el.dataset.p, isOpen = el.getAttribute("aria-expanded") === "true"; st.nodes.delete(p); st.nodes.delete(`-${p}`); st.nodes.add(isOpen ? `-${p}` : p); return true; }
        case "frames": { const k = `${el.dataset.k}:frames`; st.nodes.has(k) ? st.nodes.delete(k) : st.nodes.add(k); return true; }
        default: return false;
      }
    }
    return { markIn, paneHtml, click };
  }
  return { view, kept, GLYPH, pad, clockS, dayOf, n0, secs };
})();
