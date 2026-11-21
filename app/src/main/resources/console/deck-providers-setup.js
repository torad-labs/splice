/* Providers: the rest of what the old Models page set for one command, as rows of its glass (window.PROVIDER_SETUP,
   drawn by deck-providers-command.js after Where it goes):

   - Sign-in: how the command signs in to its provider (the kinds splice.toml takes, as GET /api/topology's vocab
     names them), the key's environment variable, and the headers sent with every request. These are the provider's
     own table, which every command on that provider shares.
   - Shares with Claude Code: which of your own Claude Code's settings, MCP servers, skills and the rest a session on
     this command uses ([heads.KEY.claude] isolate holds the ones it keeps to itself).
   - Behaviours: the provider quirks the command's wire reads (vocab quirks for its dialect), each at the provider's own
     value until it is set here; a list or a table is set in splice.toml and shown as it stands.
   - Runs on this computer (OpenAI Chat commands): whether the provider is a local server.
   - The local runtime that answers it, and whether it says each model calls tools (GET /api/models/local).
   - Test request: one short request through the command, only when pressed (POST /api/playground), since it spends.
   Every splice.toml change is one PUT /api/topology through deck-providers-command.js's change(). */
window.PROVIDER_SETUP = (() => {
  'use strict';
  const { h } = Deck;
  const SHARE_NAME = { settings: 'Settings', mcps: 'MCP servers', skills: 'Skills', hooks: 'Hooks', agents: 'Agents', commands: 'Commands',
    plugins: 'Plugins', claude_md: 'CLAUDE.md', sessions: 'Sessions', projects: 'Transcripts' };
  const QUIRK_NAME = {
    store: 'Provider keeps responses', account_id_header: 'Send account ID', cache_key: 'Prompt cache key',
    summary_field: 'Ask for reasoning summaries', tool_choice: 'Send tool choice', reasoning_cache: 'Keep reasoning between tool calls',
    parallel_tool_calls: 'Parallel tool calls', websocket: 'Use WebSocket', summary_delivery: 'How summaries stream',
    tool_surface: 'Load tools on demand', code_mode: 'Code mode', code_mode_workers: 'Code mode workers',
    code_mode_timeout_ms: 'Code mode step timeout, ms', code_mode_heap: 'Memory per worker, MB', code_mode_memory_mb: 'Code mode memory in all, MB',
    code_mode_models: 'Code mode models', reasoning_effort: 'Send reasoning effort', chat_effort_vocabulary: 'Provider’s effort words',
    stream_usage: 'Ask for token usage', slot_affinity: 'One slot per conversation', mfjs: 'Kimi tool schemas',
    block_allowlist: 'Block types allowed', strip_cache_control: 'Remove cache markers', synthesize_signatures: 'Sign thinking blocks',
    map_thinking_adaptive: 'Map thinking to Kimi’s effort', strip_sampling_params: 'Remove sampling settings',
    reanchor_prefill: 'Resume a broken stream', tool_name_cap: 'Longest tool name', zstd_request_body: 'Compress requests',
  };
  const word = w => String(w).replace(/[_-]+/g, ' ').replace(/^./, x => x.toUpperCase());
  const kindWords = label => String(label == null ? '' : label).replace(/client's own login/i, 'Claude Code’s own login').replace(/^Codex \((.+)\)$/, '$1');
  const HEADER = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
  const ENV = /^[A-Za-z_][A-Za-z0-9_]*$/;

  /* ---------- sign-in: kind, key variable, headers ---------- */
  function signinRow(el, p, kit, T) {
    const t = kit.topology(), prov = T.provOf(t, p), kinds = (kit.vocab() || {}).auth_kinds || [];
    const share = T.others(t, p);
    const { r, sv, ctl } = kit.row('signin', 'Sign-in', share.length ? `Shared with ${share.join(', ')}` : 'How the command signs in to its provider');
    if (!prov) { T.notRead(ctl); el.append(r); return; }
    const auth = prov.auth || {}, kind = auth.kind || null;
    if (kinds.length) {
      let c; c = Deck.choice(kinds.map(k => ({ v: k.kind, label: kindWords(k.label) })), kind, v => kit.commit(sv, () => T.change(kit, p, 'Sign-in', (n, nh, np) => {
        np.auth = { ...(np.auth || {}), kind: v };
        if (v !== 'api-key') delete np.auth.env;
      }), () => c.set(kind)), { max: 1, label: 'Signs in with' });
      ctl.append(c);
    } else ctl.append(h('span', 'faint', kindWords(kind) || 'No sign-in set'));
    const box = h('div', 'lr-sub');
    if (kind === 'api-key') {
      const line = h('div', 'lr-ctl'), env = T.typed('in', auth.env || '', 'Key variable, e.g. ACME_API_KEY'); env.setAttribute('aria-label', 'Key variable');
      const go = T.saveBtn();
      go.addEventListener('click', () => kit.commit(sv, async () => {
        const v = env.value.trim();
        if (v && !ENV.test(v)) return { ok: false, why: 'Not a variable name' };
        return T.change(kit, p, 'Key variable', (n, nh, np) => { np.auth = { ...(np.auth || {}), kind: 'api-key' }; if (v) np.auth.env = v; else delete np.auth.env; });
      }));
      line.append(env, go); box.append(line);
    }
    // the headers sent with every request: each name and value, one more to add, saved together
    const rows = Object.entries(prov.extra_headers || {}).map(([k, v]) => ({ k, v }));
    const list = h('div', 'ps-heads');
    const draw = () => {
      list.replaceChildren();
      rows.forEach((x, i) => {
        const line = h('div', 'lr-ctl'), nm = T.typed('in', x.k, 'Header'), val = T.typed('in', x.v, 'Value');
        nm.setAttribute('aria-label', 'Header name'); val.setAttribute('aria-label', 'Header value');
        nm.addEventListener('input', () => { x.k = nm.value; }); val.addEventListener('input', () => { x.v = val.value; });
        const rm = Deck.iconBtn('close', 'Remove header'); rm.addEventListener('click', () => { rows.splice(i, 1); draw(); });
        line.append(nm, val, rm); list.append(line);
      });
    };
    draw();
    const acts = h('div', 'lr-acts'), add = T.saveBtn('Add header'), go = T.saveBtn('Save headers');
    add.classList.add('quiet'); add.addEventListener('click', () => { rows.push({ k: '', v: '' }); draw(); });
    go.addEventListener('click', () => kit.commit(sv, async () => {
      const kept = rows.filter(x => x.k.trim());
      if (kept.some(x => !HEADER.test(x.k.trim()))) return { ok: false, why: 'Not a header name' };
      return T.change(kit, p, 'Headers', (n, nh, np) => { if (kept.length) np.extra_headers = Object.fromEntries(kept.map(x => [x.k.trim(), x.v])); else delete np.extra_headers; });
    }));
    acts.append(add, go);
    box.append(h('span', 'eyebrow', 'Headers'), list);
    r.append(box, acts);
    el.append(r);
  }

  /* ---------- what a session on this command shares with your own Claude Code ---------- */
  function shareRow(el, p, kit, T) {
    const t = kit.topology(), hd = T.headOf(t, p), items = (kit.vocab() || {}).share || [];
    if (!items.length) return;
    const { r, sv, ctl } = kit.row('share', 'Shares with Claude Code', 'Your own settings, MCP servers, skills and the rest, used by sessions on this command');
    if (!hd) { T.notRead(ctl); el.append(r); return; }
    const own = (hd.claude && hd.claude.isolate) || [];
    const b = h('button', 'pickbtn press'); b.type = 'button'; b.setAttribute('aria-haspopup', 'menu');
    b.append(h('span', 'pickbtn-v', `${items.length - own.filter(x => items.includes(x)).length} of ${items.length} shared`));
    b.addEventListener('click', () => Deck.openMenu(b, items.map(x => ({ label: SHARE_NAME[x] || word(x), sub: own.includes(x) ? 'Its own' : 'Shared', checked: !own.includes(x),
      run: () => kit.commit(sv, () => T.change(kit, p, `Shares ${SHARE_NAME[x] || word(x)}`, (n, nh) => {
        const iso = new Set((nh.claude && nh.claude.isolate) || []);
        if (iso.has(x)) iso.delete(x); else iso.add(x);
        nh.claude = nh.claude || {};
        if (iso.size) nh.claude.isolate = [...iso]; else delete nh.claude.isolate;
        if (!Object.keys(nh.claude).length) delete nh.claude;
      })) })), 'down'));
    ctl.append(b);
    el.append(r);
  }

  /* ---------- behaviours: the provider quirks this command's wire reads ---------- */
  function quirksRow(el, p, kit, T) {
    const t = kit.topology(), prov = T.provOf(t, p), qs = ((kit.vocab() || {}).quirks || []).filter(q => (q.dialects || []).includes(p.dialect));
    if (!qs.length) return;
    const { r, sv, ctl } = kit.row('quirks', 'Behaviours', 'How splice speaks this provider’s wire; each follows the provider until set here');
    if (!prov) { T.notRead(ctl); el.append(r); return; }
    ctl.remove();
    const set = prov.quirks || {}, grid = h('div', 'tiers ps-quirks');
    const save = (q, v) => T.change(kit, p, `Behaviour: ${QUIRK_NAME[q.name] || word(q.name)}`, (n, nh, np) => {
      const next = { ...(np.quirks || {}) };
      if (v === undefined) delete next[q.name]; else next[q.name] = v;
      if (Object.keys(next).length) np.quirks = next; else delete np.quirks;
    });
    qs.forEach(q => {
      const cell = h('div'), now = set[q.name];
      cell.append(h('div', 'eyebrow', QUIRK_NAME[q.name] || word(q.name)));
      if (q.kind === 'bool' || q.words) {
        const opts = [{ v: undefined, label: 'Provider’s own' }, ...(q.words ? q.words.map(w => ({ v: w, label: word(w) })) : [{ v: true, label: 'On' }, { v: false, label: 'Off' }])];
        let c; c = Deck.choice(opts, now, v => kit.commit(sv, () => save(q, v), () => c.set(now)), { max: 1, label: QUIRK_NAME[q.name] || q.name });
        cell.append(c);
      } else if (q.kind === 'int' || q.kind === 'string') {
        const i = T.typed('in', now == null ? '' : String(now), 'Provider’s own'); i.setAttribute('aria-label', QUIRK_NAME[q.name] || q.name);
        if (q.kind === 'int') i.inputMode = 'numeric';
        i.addEventListener('keydown', e => {
          if (e.key !== 'Enter') return;
          const raw = i.value.trim();
          if (q.kind === 'int' && raw && !(Number.isInteger(Number(raw)) && Number(raw) >= 0)) { sv.set('refused', 'Not a whole number'); return; }
          kit.commit(sv, () => save(q, raw === '' ? undefined : q.kind === 'int' ? Number(raw) : raw));
        });
        cell.append(i);
      } else cell.append(h('span', 'faint small', now == null ? 'Provider’s own · set in splice.toml' : JSON.stringify(now)));
      grid.append(cell);
    });
    r.append(grid);
    el.append(r);
  }

  /* ---------- runs on this computer, and the runtime that answers it ---------- */
  function localRow(el, p, kit, T) {
    const t = kit.topology(), prov = T.provOf(t, p), srv = p.server;
    if (p.dialect !== 'openai-chat' && !srv) return;
    const { r, sv, ctl } = kit.row('local', 'Runs on this computer', srv && srv.runtime ? `${srv.runtime}${srv.version ? ' ' + srv.version : ''}` : '');
    if (p.dialect === 'openai-chat' && prov) {
      const now = prov.local == null ? null : !!prov.local;
      let c; c = Deck.choice([{ v: null, label: 'As splice finds' }, { v: true, label: 'Yes' }, { v: false, label: 'No' }], now,
        v => kit.commit(sv, () => T.change(kit, p, 'Runs on this computer', (n, nh, np) => { if (v === null) delete np.local; else np.local = v; }), () => c.set(now)), { label: 'Runs on this computer' });
      ctl.append(c);
    } else ctl.remove();
    const tools = srv ? Object.entries(srv.tools || {}) : [];
    if (tools.length) {
      const k = h('div', 'kvs');
      tools.forEach(([id, yes]) => { const d = h('div'); d.append(h('span', '', (p.models.find(m => m.id === id) || {}).name || id), h('b', '', yes ? 'Calls tools' : 'No tool calls')); k.append(d); });
      r.append(k);
    }
    el.append(r);
  }

  /* ---------- one test request, only when pressed ---------- */
  function testRow(el, p, kit) {
    const { r, sv, ctl } = kit.row('test', 'Test request', 'One short request through this command; it is billed like any request');
    const go = h('button', 'btn quiet press', 'Send one'); go.type = 'button';
    go.addEventListener('click', () => kit.commit(sv, async () => {
      const t0 = performance.now();
      const res = await API.post('/api/playground', { head: p.key, model: p.defaultModel || (p.models[0] || {}).id || null, prompt: 'Say hello in one word.' });
      const status = res.ok && res.body && res.body.response ? res.body.response.status : null;
      if (!res.ok || status == null || status >= 400) return { ok: false, why: (res.body && typeof res.body.error === 'string' && res.body.error) || (status ? `the provider answered ${status}` : `splice answered ${res.status}`) };
      return { ok: true, word: `Answered in ${(performance.now() - t0) / 1000 >= 10 ? Math.round((performance.now() - t0) / 1000) : ((performance.now() - t0) / 1000).toFixed(1)} s` };
    }));
    ctl.append(go);
    el.append(r);
  }

  function rows(el, p, kit, T) {
    signinRow(el, p, kit, T);
    shareRow(el, p, kit, T);
    quirksRow(el, p, kit, T);
    localRow(el, p, kit, T);
    testRow(el, p, kit);
  }
  return { rows };
})();
