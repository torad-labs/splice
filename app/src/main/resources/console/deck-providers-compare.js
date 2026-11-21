/* Providers: one prompt sent to every model in Compare models (window.PROVIDER_COMPARE, for deck-providers.js), as the
   old Compare page sent it. Each model is one POST /api/playground {head, model, prompt}: the command's own provider
   sends the one-message request a real request would, as the login a real request would use next, and splice records it
   like a request (an Activity row, its price, the command's budget; an over-budget command refuses it as it refuses a
   request). The answer comes back whole, as the provider's own stream ({raw}), read here frame by frame; its price is
   the daemon's (GET /api/perf/price), and its row opens on Activity by the id the reply names
   (activity.html#request=<id>).

   Under the comparison: the prompt (Enter sends, Shift+Enter is a new line), the round's figure (how many answered of
   how many were asked, the time to the last answer), and each answer under its model's column with its time, cost and
   tokens each way, the best of each figure marked, a failure first and saying why. The last prompt and the models
   picked are kept in this browser (localStorage splice.compare.prompt, splice.compare.models). */
window.PROVIDER_COMPARE = (() => {
  'use strict';
  const { h } = Deck, enc = encodeURIComponent;
  const KEPT = 'splice.compare.prompt', KEPT_MODELS = 'splice.compare.models', MAX = 3, TICK_MS = 250;
  const secs = ms => (ms == null ? '—' : window.REQUEST_WORDS ? REQUEST_WORDS.secs(ms) : `${(ms / 1000).toFixed(1)} s`);
  const n = v => (v == null ? '—' : Math.round(v).toLocaleString('en-US'));
  const usd = v => (v == null ? '—' : v < 0.01 && v > 0 ? '<$0.01' : `$${v.toFixed(v < 1 ? 3 : 2)}`);
  const store = {
    get: (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch { return d; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage off: nothing kept */ } },
  };
  let prompt = store.get(KEPT, '');
  /** the models picked last time in this browser, as `<command>|<model id>` */
  function kept() { try { const v = JSON.parse(store.get(KEPT_MODELS, '[]')); return Array.isArray(v) ? v.filter(x => typeof x === 'string').slice(0, MAX) : []; } catch { return []; } }
  const keep = keys => store.set(KEPT_MODELS, JSON.stringify(keys.slice(0, MAX)));

  /* ---------- reading an answer: the provider's stream, frame by frame, in whichever dialect it came ---------- */
  function framesOf(raw) {
    return String(raw == null ? '' : raw).split(/\r?\n\r?\n/).map(chunk => {
      const data = [];
      for (const line of chunk.split(/\r?\n/)) if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      if (!data.length) return null;
      try { return JSON.parse(data.join('\n')); } catch { return null; }
    }).filter(Boolean);
  }
  function readFrames(frames) {
    let text = '', tin = null, tout = null, error = null;
    for (const d of frames) {
      if (typeof d !== 'object') continue;
      if (d.type === 'content_block_delta' && d.delta && d.delta.text != null) text += d.delta.text;
      else if (d.type === 'response.output_text.delta') text += d.delta || '';
      else if (d.object === 'chat.completion.chunk' && d.choices && d.choices[0] && d.choices[0].delta && d.choices[0].delta.content != null) text += d.choices[0].delta.content;
      if (d.type === 'message_start' && d.message && d.message.usage) tin = d.message.usage.input_tokens ?? tin;
      if (d.type === 'message_delta' && d.usage) tout = d.usage.output_tokens ?? tout;
      if (d.type === 'response.completed' && d.response && d.response.usage) { tin = d.response.usage.input_tokens ?? tin; tout = d.response.usage.output_tokens ?? tout; }
      if (d.usage && d.object === 'chat.completion.chunk') { tin = d.usage.prompt_tokens ?? tin; tout = d.usage.completion_tokens ?? tout; }
      if (d.type === 'error' || d.type === 'response.failed') error = d;
    }
    return { text, tin, tout, error };
  }
  /** what went wrong, in Activity's words for its kind: the provider's status or error type, else splice's own code */
  function failWord(status, type, code) {
    if (status === 429 || /rate_limit|usage_limit/.test(type)) return 'Rate limited';
    if (status === 529 || /overloaded/.test(type)) return 'Overloaded';
    if (status === 401 || status === 403) return 'Not signed in';
    if (code === 'timed_out') return 'Timed out';
    if (code === 'no_credential' || code === 'caller_auth') return 'Not signed in';
    if (code === 'no_provider') return 'Not answering';
    if (code === 'unknown_head') return 'Command not found';
    if (code === 'call_failed') return 'Connection reset';
    return 'Failed';
  }
  const cut = s => (s.length > 400 ? `${s.slice(0, 399).trimEnd()}…` : s);

  /* ---------- sending ---------- */
  const answers = new Map();   // `<command>|<model id>` -> { st, t0, ms, text, tin, tout, cost, fail, turn, order }
  let finished = 0, ticker = 0, onChange = () => {};
  const sending = () => [...answers.values()].some(a => a.st === 'sending');
  async function ask(col) {
    const a = { st: 'sending', t0: Date.now(), ms: null, text: '', tin: null, tout: null, cost: null, fail: null, turn: null, order: null, asked: prompt };
    answers.set(col.key, a);
    // never sent, so it has no time: a dash, not 0 ms
    if (!col.p.canSend) { Object.assign(a, { st: 'failed', ms: null, fail: { word: 'Can’t send', said: col.p.cantSend } }); return; }
    const began = performance.now();
    const res = await API.post('/api/playground', { head: col.p.key, model: col.id, prompt: a.asked });
    if (answers.get(col.key) !== a) return;
    a.ms = Math.round(performance.now() - began);
    const reply = res.body;
    if (res.status === 429 && reply && reply.blocked === 'budget') {
      const spent = typeof reply.spent_usd === 'number' && reply.limit_usd > 0 ? `${usd(reply.spent_usd)} of ${usd(reply.limit_usd)} today` : '';
      Object.assign(a, { st: 'failed', fail: { word: 'Over budget', said: spent } });
      return;
    }
    if (!res.ok || !reply || !reply.response) {
      if (res.status === 0) { Object.assign(a, { st: 'failed', fail: { word: 'splice is not answering', said: '' } }); return; }
      const said = reply && typeof reply.error === 'string' ? cut(reply.error) : `splice answered ${res.status}`;
      Object.assign(a, { st: 'failed', fail: { word: failWord(0, '', reply && reply.code), said } });
      return;
    }
    a.turn = (reply.request && reply.request.turn_id) || null;
    const status = reply.response.status, body = reply.response.body;
    const frames = body && typeof body.raw === 'string' ? framesOf(body.raw) : null;
    const got = frames ? readFrames(frames) : { text: '', tin: null, tout: null, error: null };
    a.tin = got.tin; a.tout = got.tout;
    const err = got.error ? (got.error.error || (got.error.response && got.error.response.error) || got.error) : status >= 400 ? (body && body.error) || body : null;
    if (err) {
      const type = (err && (err.type || err.code)) || '', told = err && err.message;
      Object.assign(a, { st: 'failed', fail: { word: failWord(got.error ? 0 : status, String(type), null), said: typeof told === 'string' ? cut(told) : `the provider answered ${status}` } });
      return;
    }
    const plain = !frames && body ? ((body.choices && body.choices[0] && body.choices[0].message && body.choices[0].message.content) || (body.content && body.content[0] && body.content[0].text) || '') : '';
    const text = got.text || plain;
    if (!text.trim()) { Object.assign(a, { st: 'failed', fail: { word: 'Empty response', said: '' } }); return; }
    Object.assign(a, { st: 'done', text, order: ++finished });
    if (a.tin != null && a.tout != null) {
      const q = new URLSearchParams({ head: col.p.key, model: col.id, in_tokens: a.tin, out_tokens: a.tout });
      if (reply.request && reply.request.account) q.set('account', reply.request.account);
      const pr = await API.get('/api/perf/price?' + q);
      if (pr.ok && pr.body) a.cost = { usd: pr.body.cost_usd ?? null, plan: pr.body.plan_value_usd ?? null };
    }
  }
  async function send(cols) {
    if (!prompt.trim() || !cols.length || sending()) return;
    store.set(KEPT, prompt); finished = 0;
    for (const k of [...answers.keys()]) if (!cols.some(c => c.key === k)) answers.delete(k);
    const all = cols.map(c => ask(c).then(() => onChange()));
    clearInterval(ticker); ticker = setInterval(() => { if (sending()) onChange(true); else clearInterval(ticker); }, TICK_MS);
    onChange();
    await Promise.all(all);
    clearInterval(ticker); onChange();
  }

  /* ---------- drawing: the prompt row and the answers row ---------- */
  /** the prompt and the round's answers, under the comparison of [cols] ({ key, id, name, p }); [repaint] draws the
   *  comparison again (a tick passes only the answers' own figures) */
  function rows(body, cols, repaint) {
    onChange = tick => (tick ? paintAnswers() : repaint());
    const r = h('div', 'lr'); r.dataset.item = 'prompt';
    const nm = h('div', 'lr-nm', 'Prompt'); nm.append(h('small', '', 'Sent to each model through splice; Enter sends, Shift+Enter is a new line')); r.append(nm, h('span'));
    const box = h('div', 'lr-sub'), ta = h('textarea', 'lr-in'); ta.rows = 3; ta.value = prompt; ta.placeholder = 'Ask every model the same thing'; ta.setAttribute('aria-label', 'Prompt');
    const go = h('button', 'btn primary press', sending() ? 'Sending' : 'Send'); go.type = 'button'; go.disabled = sending() || !cols.length;
    ta.addEventListener('input', () => { prompt = ta.value; store.set(KEPT, prompt); });
    ta.addEventListener('keydown', e => { e.stopPropagation(); if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(cols); } });
    go.addEventListener('click', () => send(cols));
    const acts = h('div', 'lr-acts'); acts.append(go);
    box.append(ta); r.append(box, acts); body.append(r);
    const out = h('div', 'lr'); out.dataset.item = 'answers'; body.append(out);
    const asked = cols.filter(c => answers.has(c.key));
    const paintAnswers = () => {
      out.replaceChildren();
      out.hidden = !asked.length;
      if (!asked.length) return;
      const as = asked.map(c => answers.get(c.key)), done = as.filter(a => a.st === 'done').length;
      const last = Math.max(0, ...as.map(a => (a.st === 'sending' ? Date.now() - a.t0 : a.ms || 0)));
      const head = h('div', 'lr-nm', 'Answers'); head.append(h('small', '', `${done} of ${as.length} answered · ${secs(last)}${as.some(a => a.st === 'sending') ? ' so far' : ''}`));
      out.append(head, h('span'));
      // a failure reads first, the answer that needs a look, with why
      const failed = asked.filter(c => answers.get(c.key).st === 'failed');
      if (failed.length) { const fl = h('div', 'cmp-fails'); failed.forEach(c => { const x = h('span'); x.append(h('b', '', answers.get(c.key).fail.word), ` · ${c.name}`); fl.append(x); }); out.append(fl); }
      const grid = h('div', 'cmp cmp-ans'); grid.style.setProperty('--n', String(cols.length));
      const best = (f, low) => { const v = as.filter(a => a.st === 'done').map(f).filter(x => typeof x === 'number'); return v.length > 1 ? (low ? Math.min(...v) : Math.max(...v)) : null; };
      const bt = best(a => a.ms, true), bc = best(a => (a.cost ? a.cost.usd : null), true);
      grid.append(h('span'));
      cols.forEach(c => { const x = h('span', 'cmp-ans-h'), d = h('i'); d.style.setProperty('--pc', c.p.color); x.append(d, c.name); grid.append(x); });
      const line = (label, f) => { grid.append(h('span', '', label)); cols.forEach(c => { const a = answers.get(c.key); const [v, cls] = a ? f(a) : ['', '']; grid.append(h('b', cls || '', v)); }); };
      line('Time', a => [a.st === 'sending' ? secs(Date.now() - a.t0) : secs(a.ms), a.st === 'done' && a.ms === bt ? 'best' : a.st === 'sending' ? 'none' : '']);
      line('Cost', a => [a.cost ? usd(a.cost.usd ?? a.cost.plan) : '—', a.cost && a.cost.usd === bc ? 'best' : '']);
      line('Tokens in', a => [n(a.tin), '']);
      line('Tokens out', a => [n(a.tout), '']);
      out.append(grid);
      const texts = h('div', 'cmp cmp-texts'); texts.style.setProperty('--n', String(cols.length)); texts.append(h('span'));
      cols.forEach(c => {
        const a = answers.get(c.key), cell = h('div', 'cmp-text' + (a && a.st === 'failed' ? ' is-bad' : ''));
        if (!a) cell.append(h('span', 'faint', 'Not asked'));
        else if (a.st === 'sending') cell.append(h('span', 'faint', 'Waiting for the answer'));
        else if (a.st === 'failed') { cell.append(h('b', 'cmp-fail', a.fail.word)); if (a.fail.said) cell.append(h('span', 'cmp-why', a.fail.said)); }
        else { cell.append(h('p', 'cmp-said', a.text)); if (a.asked !== prompt) cell.append(h('span', 'faint small', 'Answer to the previous prompt')); }
        if (a && a.turn) { const go2 = h('a', 'btn quiet press', 'Request'); go2.href = 'activity.html#request=' + enc(a.turn); cell.append(go2); }
        texts.append(cell);
      });
      out.append(texts);
    };
    paintAnswers();
  }
  return { rows, kept, keep, MAX, busy: sending };
})();
