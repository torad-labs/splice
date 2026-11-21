/* The full request, in the kit's glass: the document splice kept of what one request sent and what came back, in the
   words and order the old console's request view used: Sent, Attempts and Returned; a body (Body, Response) closed
   until pressed; each attempt on the request's one timeline; headers folded to their count; the tokens and timing above.
   Read lazily: GET /api/heads/{head}/trace?turn=<id>&view=outline when the request is
   opened (headers and timing, every body deferred), GET /api/heads/{head}/trace/document?turn&record&section&path&offset
   for one branch or text page only when it is opened, and GET /api/heads/{head}/capture to say why a request has no
   records. Nothing that is not opened is read.

   DeckRequest.fill(body, row) draws into a glass's body; row: { head, turn, ts, model, cmd, total, firstByte, up, in,
   out, cached, cacheWrite, value, paid, retries }. */
window.DeckRequest = (() => {
  'use strict';
  const { h } = Deck;
  const enc = encodeURIComponent;
  const n0 = n => Math.round(n).toLocaleString('en-US');
  // the words a request reads in are request-words.js's, the same on Compare and Models
  const { STOP_WORD, failureWords } = REQUEST_WORDS;
  const secs = ms => (ms == null ? '—' : REQUEST_WORDS.secs(ms));
  const jsonOf = t => { if (t == null) return null; try { return JSON.parse(t); } catch { return t; } };

  /* ---------- a body as a tree: a kept value, or a document read a page at a time as it is opened ---------- */
  const prim = v => (typeof v === 'string' ? h('span', 'rq-str', `"${v}"`) : h('span', typeof v === 'number' ? 'rq-num' : 'rq-bool', String(v)));
  const keyOf = (isArray, k) => (isArray ? h('span', 'rq-idx', String(k)) : h('span', 'rq-key', `${k}: `));
  /** a kept value: objects and arrays fold to one line and open where pressed */
  function valueTree(v) {
    if (v == null || typeof v !== 'object') return prim(v);
    const ul = h('ul', 'rq-tree');
    (Array.isArray(v) ? v.map((x, i) => [i, x]) : Object.entries(v)).forEach(([k, x]) => {
      const li = h('li'); li.append(keyOf(Array.isArray(v), k));
      if (x != null && typeof x === 'object') li.append(fold(Array.isArray(x) ? `[${x.length}]` : '{…}', () => valueTree(x)));
      else li.append(prim(x));
      ul.append(li);
    });
    return ul;
  }
  /** a node that opens in place: its words, and what it holds made only when first opened */
  function fold(word, make, cls = 'rq-node') {
    const box = h('span', cls), b = h('button', 'rq-caret press'); b.type = 'button'; b.setAttribute('aria-expanded', 'false');
    b.append(h('span', 'rq-caret-i'), h('span', 'rq-fold-w', word));
    let made = null;
    b.addEventListener('click', () => {
      const open = b.getAttribute('aria-expanded') !== 'true';
      b.setAttribute('aria-expanded', String(open));
      if (open && !made) { made = make(); box.append(made); }
      if (made) made.hidden = !open;
    });
    box.append(b);
    return box;
  }
  /** one branch of a deferred document: its first page read when it is drawn, more on Load more */
  function docBranch(url, path) {
    const box = h('div', 'rq-doc');
    const more = (offset, into) => {
      const wait = h('span', 'faint small', 'Reading');
      into.append(wait);
      const u = new URL(url, location.href); u.searchParams.set('path', path); u.searchParams.set('offset', String(offset));
      API.get(u.pathname + u.search).then(res => {
        wait.remove();
        if (!res.ok) {
          console.warn('request document:', (res.body && res.body.error) || res.status);
          const r = h('button', 'btn quiet press', 'Could not load · Retry'); r.type = 'button'; r.addEventListener('click', () => { r.remove(); more(offset, into); }); into.append(r); return;
        }
        const page = res.body || {};
        if (page.type === 'object' || page.type === 'array') {
          const ul = into.querySelector(':scope > ul') || into.appendChild(h('ul', 'rq-tree'));
          for (const c of page.children || []) {
            const li = h('li'), part = String(c.key).replace(/~/g, '~0').replace(/\//g, '~1');
            li.append(keyOf(page.type === 'array', c.key));
            if (c.complete || (c.type !== 'object' && c.type !== 'array' && c.type !== 'string')) li.append(prim(c.value));
            else li.append(fold(c.type === 'array' ? '[…]' : c.type === 'object' ? '{…}' : `"${String(c.value ?? '').slice(0, 46)}…"`, () => docBranch(url, `${path}/${part}`)));
            ul.append(li);
          }
        } else if (page.type === 'string' || page.type === 'text') {
          const pre = into.querySelector(':scope > pre') || into.appendChild(h('pre', 'rq-text'));
          pre.append(String(page.value ?? ''));
        } else into.append(prim(page.value));
        if (page.next != null) { const b = h('button', 'btn quiet press', 'Load more'); b.type = 'button'; b.addEventListener('click', () => { b.remove(); more(page.next, into); }); into.append(b); }
      });
    };
    more(0, box);
    return box;
  }
  /** a body, by how the record holds it: deferred (read when opened), kept text, or nothing */
  const bodyOf = (row, part, record, section, key = 'body') => (part && part.body_deferred
    ? { url: `/api/heads/${enc(row.head)}/trace/document?${new URLSearchParams({ turn: row.turn, record, section })}` }
    : part && part[key] != null ? { value: jsonOf(part[key]) } : null);
  const bodyFold = (word, body) => (body ? fold(word, () => (body.url ? docBranch(body.url, '') : h('div', 'rq-doc', valueTree(body.value)))) : null);
  function headersFold(headers, url, lead = '') {
    const list = Object.entries(headers || {}).filter(([, v]) => v != null);
    if (!list.length && !url) return null;
    return fold(`${lead}${list.length ? `${n0(list.length)} ${list.length === 1 ? 'header' : 'headers'}` : 'Address'}`, () => {
      const box = h('div', 'rq-hdrs');
      if (url) { const p = h('div', 'rq-addr'); p.append(h('span', 'faint', 'Sent to '), url); box.append(p); }
      const dl = h('dl');
      list.forEach(([k, v]) => { dl.append(h('dt', '', k), h('dd', v === '[redacted]' ? 'faint' : '', v === '[redacted]' ? 'Redacted' : String(v))); });
      box.append(dl);
      return box;
    });
  }

  /* ---------- the request ---------- */
  function fill(body, row) {
    // tokens and timing, as the request row gave them
    const top = h('div', 'rq-figs');
    [[secs(row.firstByte), 'Time to first token'], [secs(row.total), 'Duration'], [row.in != null ? n0(row.in) : '—', 'Input tokens'],
      [row.out != null ? n0(row.out) : '—', 'Output tokens'], [row.retries ? String(row.retries) : '0', 'Retries']]
      .forEach(([v, w]) => { const d = h('div'); d.append(h('b', '', v), h('span', '', w)); top.append(d); });
    body.append(top);
    const rec = h('div', 'rq-rec'), state = h('div', 'faint small', 'Reading the record');
    body.append(rec);
    rec.append(state);
    if (!row.head || !row.turn) { state.textContent = 'No records'; return; }
    Promise.all([
      API.get(`/api/heads/${enc(row.head)}/trace?turn=${enc(row.turn)}&view=outline`),
      API.get(`/api/heads/${enc(row.head)}/capture`),
    ]).then(([res, cap]) => {
      const records = (res.ok && res.body && res.body.records) || [];
      if (!records.length) {
        const kept = cap.ok && cap.body ? cap.body : null;
        const days = kept && kept.retention_days;
        state.textContent = res.body && res.body.code === 'trace_deleted' ? 'Deleted'
          : kept && kept.enabled === false ? 'Not saved: prompts and answers are not kept for this command'
            : days != null && row.ts < Date.now() - days * 864e5 ? `Older than ${n0(days)} ${days === 1 ? 'day' : 'days'}` : 'No records';
        return;
      }
      state.remove();
      draw(rec, row, records);
    });
  }
  function draw(rec, row, records) {
    const turn = records.find(x => x.kind === 'turn') || {};
    const started = (row.ts || 0) - (row.total || 0);
    const attempts = records.filter(x => x.kind === 'attempt').map((a, i) => ({
      url: a.url ?? null, start: Math.max(0, (a.ts ?? started) - (a.durationMs ?? 0) - started), ms: a.durationMs ?? 0,
      status: a.response?.status ?? null, failure: a.failure ?? null, headers: a.request?.headers ?? {}, resHeaders: a.response?.headers ?? null,
      body: bodyOf(row, a.request, `attempt:${i}`, 'request'), response: bodyOf(row, a.response, `attempt:${i}`, 'response', a.response && a.response.body_deferred ? 'body' : 'text'),
    }));
    const cut = Boolean(turn.client?.truncated || turn.answer?.truncated);
    const panes = {
      sent: () => {
        const box = h('div', 'rq-pane');
        const line = h('div', 'rq-line'); if (row.model) line.append(h('b', '', row.model));
        const hf = headersFold(turn.client?.headers); if (hf) line.append(hf);
        box.append(line);
        const b = bodyFold('Body', bodyOf(row, turn.client, 'turn', 'client'));
        box.append(b || h('div', 'faint small', 'Not reported'));
        if (cut) box.append(h('div', 'faint small', 'Cut at 16M characters'));
        return box;
      },
      attempts: () => {
        const box = h('div', 'rq-pane');
        if (!attempts.length) { box.append(h('div', 'faint small', 'Attempts 0')); return box; }
        const reach = row.total > 0 ? row.total : Math.max(1, ...attempts.map(a => a.start + a.ms));
        attempts.forEach((a, i) => {
          const sec = h('section', 'rq-att' + (a.failure || a.status >= 400 ? ' is-bad' : ''));
          const hd = h('div', 'rq-att-h'); hd.append(h('span', 'rq-att-n', String(i + 1)), h('span', '', a.url ? new URL(a.url, location.href).host : 'Over the open connection'));
          if (a.status) hd.append(h('span', 'rq-status', String(a.status)));
          hd.append(h('span', 'faint', secs(a.ms)));
          const track = h('div', 'rq-track'), bar = h('i');
          bar.style.setProperty('--from', a.start / reach); bar.style.setProperty('--len', Math.max(0.004, a.ms / reach)); track.append(bar);
          sec.append(hd, track);
          if (a.failure) sec.append(h('div', 'rq-why', failureWords(a.failure)));
          [headersFold(a.headers, a.url, 'Request · '), bodyFold('Body', a.body), headersFold(a.resHeaders, null, 'Response · '), bodyFold('Response', a.response)].filter(Boolean).forEach(x => sec.append(x));
          box.append(sec);
        });
        return box;
      },
      returned: () => {
        const box = h('div', 'rq-pane'), an = turn.answer || {};
        const st = h('div', 'rq-line'); st.append(h('span', 'rq-status', an.status != null ? String(an.status) : '—'));
        const back = an.body_deferred ? { url: `/api/heads/${enc(row.head)}/trace/document?${new URLSearchParams({ turn: row.turn, record: 'turn', section: 'answer' })}` } : an.body != null ? { value: jsonOf(an.body) } : null;
        const stop = back && back.value && typeof back.value === 'object' ? back.value.stop_reason || back.value.status : null;
        if (stop) st.append(h('span', 'faint', STOP_WORD[stop] || stop));
        box.append(st);
        box.append(bodyFold('Response', back) || h('div', 'faint small', 'Not reported'));
        return box;
      },
    };
    const holder = h('div');
    const tabs = Deck.choice([{ v: 'sent', label: 'Sent' }, { v: 'attempts', label: 'Attempts' }, { v: 'returned', label: 'Returned' }], 'sent', v => holder.replaceChildren(panes[v]()), { label: 'Record' });
    const hd = h('div', 'rq-rec-h'); hd.append(h('span', 'eyebrow', 'Record'), tabs);
    rec.append(hd, holder);
    holder.append(panes.sent());
  }
  return { fill };
})();
