/* What every Night Deck page knows of this splice: its providers, the command each runs through, and the models splice's
   catalog lists for each. The prototype kit's common.js held a snapshot of them; here they are read live, once as the
   page loads, from GET /api/heads (the commands), /api/status (each command's family) and /api/models (each command's
   models), through api.js. SPLICE.ready settles once those reads have landed; until then SPLICE.providers is empty.
   Provider colour lives only in dots and meters: each command carries its family's (deck.css --p-<id>). */
window.SPLICE = (() => {
  'use strict';
  // A command's provider, by the family splice gives its head (/api/status registry, ProviderFamilyRule.kt), in the
  // names the console has always used. The id is the colour's (deck.css --p-<id>).
  const FAMILY = {
    anthropic: { id: 'claude', name: 'Claude' }, openai: { id: 'chatgpt', name: 'ChatGPT' }, codex: { id: 'chatgpt', name: 'ChatGPT' },
    xai: { id: 'grok', name: 'Grok' }, grok: { id: 'grok', name: 'Grok' }, moonshot: { id: 'kimi', name: 'Kimi' }, kimi: { id: 'kimi', name: 'Kimi' },
    meta: { id: 'muse', name: 'Muse' }, muse: { id: 'muse', name: 'Muse' }, openrouter: { id: 'openrouter', name: 'OpenRouter' },
    router: { id: 'openrouter', name: 'OpenRouter' }, deepseek: { id: 'deepseek', name: 'DeepSeek' },
    // a model server of his own is named for where it runs, as Providers names it; it draws in no provider's colour
    local: { id: 'local', name: 'Local' }, vast: { id: 'local', name: 'Rented GPU' },
  };
  /** [{ id, key, name, command, family, auth, color, models: [[name, model id], ...] }], one per command, in splice's order.
   *  auth is how the command signs in as /api/status names it (an OAuth kind, api-key, client), or null. */
  const providers = [];
  const rows = (res, field) => (res && res.ok && Array.isArray(res.body && res.body[field]) ? res.body[field] : []);
  function fill(heads, registry, catalog) {
    const familyOf = new Map(registry.map(r => [r.key, r]));
    const modelsOf = new Map(catalog.map(c => [c.head, Array.isArray(c.models) ? c.models : []]));
    const taken = new Set();
    providers.length = 0;
    for (const hd of heads) {
      const reg = familyOf.get(hd.key) || {};
      const command = hd.label || reg.label || hd.key;
      const fam = FAMILY[String(reg.family || '').toLowerCase()];
      // a family the console has no name for is called by its own label and draws in no colour
      const base = fam ? fam.id : 'local';
      let id = base;
      for (let n = 2; taken.has(id); n++) id = base + '-' + n;
      taken.add(id);
      providers.push({
        id, key: hd.key, name: fam ? fam.name : command, command, family: reg.family || null, auth: reg.authKind || hd.authKind || null, color: 'var(--p-' + base + ')',
        models: (modelsOf.get(hd.key) || []).filter(m => m && m.id).map(m => [m.label || m.id, m.id]),
      });
    }
  }
  const read = () => Promise.all([API.get('/api/heads'), API.get('/api/status'), API.get('/api/models')])
    .then(([heads, status, models]) => { fill(rows(heads, 'heads'), rows(status, 'registry'), rows(models, 'heads')); return providers; });
  const ready = read();
  // a page that was locked reads them again the moment this browser is signed in (api.js)
  addEventListener('splice:signed-in', () => { read(); });
  /** a model's name as splice's catalog spells it, or its id where no command lists it */
  const modelName = id => { for (const p of providers) for (const m of p.models) if (m[1] === id) return m[0]; return id; };
  /** the provider a command (its name, or its head key) runs through, or null */
  const providerOf = command => providers.find(p => p.command === command || p.key === command) || null;
  const MODES = [['ask', 'Ask first', 'default'], ['act', 'Act on its own', '']];
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);
  /* The manager, the one session that runs splice for him: its state (GET /api/manager), and a message to it, typed into
     its session (POST /api/sessions/{id}/say), started first when it is not running (POST /api/manager/start), as the
     console's composer has always reached it. say answers what the daemon said, in the composer's { state, said }. */
  const manager = {
    async read() { const r = await API.get('/api/manager'); return r.ok ? r.body : null; },
    async say(text) {
      let m = await manager.read();
      if (!m) return { state: 'stalled', said: 'Not sent: the manager did not answer' };
      if (m.state !== 'live' || !m.session_id) {
        const s = await API.post('/api/manager/start', m.head ? { head: m.head } : {});
        if (!s.ok) return { state: 'stalled', said: 'The manager did not start: ' + why(s) };
        m = s.body && s.body.session_id ? s.body : await manager.read();
        if (!m || !m.session_id) return { state: 'stalled', said: 'The manager is starting. Send again in a moment' };
      }
      const r = await API.post('/api/sessions/' + encodeURIComponent(m.session_id) + '/say', { text });
      return r.ok ? { state: 'idle', said: 'Sent to the manager' } : { state: 'stalled', said: 'Not sent: ' + why(r) };
    },
  };
  return { providers, modelName, providerOf, MODES, ready, manager };
})();
