/* Settings' live model (window.SETTINGS_LIVE), for deck-settings.js: every setting the page draws, read from splice and
   saved back to it at once. The words and the steps a value is picked from are the page's (prototype settings/m-machine,
   USER-LANGUAGE.md); every value, its source, whether it waits for a restart, and every figure are the daemon's.

   Reads: GET /api/config (effective values, layers, knobs with scope, disposition, editable), /api/heads (each command's
   gate), /api/alerts, /api/upgrade, /api/sessions, /api/mcp and /api/mcp/memory, /api/kept/stores, /api/kept/labels,
   /api/kept/edges, /api/history, /api/projects, /api/topology, and per command one GET /api/perf/turns with its week's
   questions as asks (the counts Settings has always read) and one with peak=1 for today's peak.
   Writes: PATCH /api/config (?head=<key> for one command's own value), PUT /api/alerts, PUT /api/topology (MCP sharing),
   PUT /api/history (a shorter usage history, after the yes), POST /api/upgrade/check, POST /api/upgrade ({to} or
   {rollback}), POST /api/daemon/restart (?now=1 for Restart now). */
window.SETTINGS_LIVE = (() => {
  'use strict';
  const SEC = 1000, MIN = 60000, HR = 3600000, MB = 1048576, DAY = 864e5, ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const enc = encodeURIComponent;
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);
  const dur = ms => (ms >= HR ? `${+(ms / HR).toFixed(1)} h` : ms >= 2 * MIN || ms % MIN === 0 ? `${+(ms / MIN).toFixed(1)} min` : `${Math.round(ms / SEC)} s`);
  const exact = ms => { const m = Math.floor(ms / MIN), s = Math.round((ms % MIN) / SEC); return m ? `${m} min${s ? ` ${s} s` : ''}` : `${s} s`; };
  const bytes = b => (b == null ? '—' : b >= 1e9 ? `${(b / 1e9).toFixed(1)} GB` : b >= 1e6 ? `${Math.round(b / 1e6)} MB` : b >= 1e3 ? `${Math.round(b / 1e3)} KB` : `${b} B`);
  const steps = (vs, f) => vs.map(v => ({ v, label: f(v) }));
  const days = d => (d === 'forever' ? 'Forever' : d === 0 ? 'Today' : d === 1 ? '1 day' : d === 365 ? '1 year' : `${d} days`);

  /* the settings the page places, each with splice's default, and the ones it leaves to Providers */
  const DEFAULTS = {
    effort: 'high', summary: 'detailed', showReasoning: 'text', progressLine: true, replayReasoning: true, verbosity: null,
    firstByteTimeoutMs: 90 * SEC, streamIdleMs: 90 * SEC, stallReanchorMs: 20 * SEC, upstreamTimeoutMs: 30 * MIN,
    maxInflight: 12, maxQueued: 512, maxRequestBytes: 32 * MB, requestReadTimeoutMs: 30 * SEC, ingressReadIdleMs: 30 * SEC, materializationHeapBytes: 0,
    usageWarnPct: 80, budgetDefaultAction: 'warn', quotaPoll: 'auto', quotaPollIntervalMs: 5 * MIN, usageWarnTokens5h: 0,
    mcpMaxServers: 32, mcpIdleTimeoutMs: 30 * MIN, mcpRequestTimeoutMs: 30 * MIN, mcpInitializeTimeoutMs: 60 * SEC,
    projectsFolders: '', statuslineGitRoots: '',
    transcriptView: true, historyRetentionDays: 35, traceRetentionDays: 7, keepTranscriptCopies: true, transcriptCopyDays: 30, activityRetentionDays: 90,
    messageEdges: true, keepCompactionSummaries: true, keepCodeModeWork: true, traceMaxBodyChars: 16 * MB, perfCollectionEnabled: true, perfArchiveRetentionDays: 90,
    debug: false, controlPort: 3096, supervisorUnit: 'splice.service', mcpSlice: 'app-mcp.slice', activityStoreHeads: '*',
    telemetryPrometheusEnabled: false, telemetryOtlpEndpoint: '', telemetryOtlpProtocol: 'http/protobuf', telemetryOtlpSignals: '', telemetryOtlpHeaders: '{}',
  };
  const OFF = { quotaPoll: 'off', keepTranscriptCopies: false, perfCollectionEnabled: false };
  const ON = { quotaPoll: 'auto', keepTranscriptCopies: true, perfCollectionEnabled: true };
  const PENDING_KEY = 'splice-settings-at-restart';

  const S = { configRead: false, fmt: { dur, bytes, exact }, knobs: [], commands: [], week: {}, mcp: { shared: true, bytesNow: null, bytesIfShared: null, servers: [], sessions: null, harnesses: null },
    data: { stores: [] }, projects: { list: [], folders: [] }, alerts: { command: null, desktop: false, webhook: null }, version: { installed: null, previous: null, latest: null },
    paths: {}, running: { requests: 0, cutRequests: 0, sessions: 0 }, restartState: { status: 'idle', handsOver: false, drainMs: null, compactions: 0 }, upgradeRun: null, topology: null, history: null,
    // the modes splice carries, as GET /api/modes lists them (modes/README.md); null until read, or when splice did not answer
    modes: null };
  const K = {};
  S.knob = key => K[key];
  const valueOf = (k, cmd) => (!k ? null : cmd && cmd in k.own ? k.own[cmd] : k.value);
  S.valueOf = (key, cmd) => valueOf(K[key], cmd);
  S.isDefault = (key, cmd) => (cmd ? !(cmd in K[key].own) : K[key].scope === 'default');
  /** where a value comes from, at rest: nothing for splice's default */
  S.sourceWord = (key, cmd) => {
    const k = K[key]; if (!k) return '';
    if (!S.configRead) return 'Settings did not load';
    if (cmd) return cmd in k.own ? 'Changed' : '';
    if (k.scope === 'env') return 'Your shell' + (k.envNames && k.envNames[0] ? ` · ${k.envNames[0]}` : '');
    return { file: 'Changed', runtime: 'Changed', toml: 'splice.toml', head: 'splice.toml', default: '' }[k.scope] || '';
  };

  /* ---------- changes that wait for a restart, kept for this tab until splice restarts ---------- */
  let pending = [];
  try { pending = JSON.parse(sessionStorage.getItem(PENDING_KEY) || '[]'); } catch { pending = []; }
  const keepPending = () => { try { sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending)); } catch { /* storage off */ } };
  S.pending = () => pending;
  S.changes = [];

  /** save one value, for one command when [cmd] is given (null clears that command's own). The daemon's answer:
   *  { state: 'applied' | 'restart' | 'refused', why? } */
  S.save = async (key, to, cmd = null) => {
    const k = K[key], from = valueOf(k, cmd);
    const res = await API.patch('/api/config' + (cmd ? '?head=' + enc(cmd) : ''), { [key]: to });
    if (!res.ok) return { state: 'refused', why: why(res) };
    const rejected = res.body && res.body.rejected && res.body.rejected[key];
    if (rejected) return { state: 'refused', why: typeof rejected === 'string' ? rejected : rejected.reason || 'Not saved' };
    if (cmd) { if (to === null) delete k.own[cmd]; else k.own[cmd] = to; } else { k.value = to; k.scope = 'file'; }
    const restart = (res.body && (res.body.restart_required || []).includes(key)) || k.restart;
    if (restart) { pending = pending.filter(c => !(c.key === key && c.cmd === cmd)); pending.push({ key, cmd, to }); keepPending(); }
    S.changes.unshift({ key, cmd, from, to, at: Date.now() });
    return { state: restart ? 'restart' : 'applied' };
  };
  /** a two-setting item (an on switch and its value) read as one value: 'off' or the value */
  S.comboValue = keys => (S.valueOf(keys[0]) === OFF[keys[0]] ? 'off' : S.valueOf(keys[1]));
  S.saveCombo = async (keys, v) => {
    if (v === 'off') return S.save(keys[0], OFF[keys[0]]);
    const a = S.valueOf(keys[0]) === OFF[keys[0]] ? await S.save(keys[0], ON[keys[0]]) : null;
    if (a && a.state === 'refused') return a;
    const b = await S.save(keys[1], v);
    return { state: b.state === 'refused' ? 'refused' : (a && a.state === 'restart') || b.state === 'restart' ? 'restart' : 'applied', why: b.why };
  };
  const listOf = key => String(S.valueOf(key) || '').split(':').filter(Boolean);
  S.folders = key => listOf(key);
  S.addFolder = (key, path) => (listOf(key).includes(path) ? Promise.resolve({ state: 'applied' }) : S.save(key, [...listOf(key), path].join(':')));
  S.removeFolder = (key, path) => S.save(key, listOf(key).filter(p => p !== path).join(':'));
  S.projectsUnder = path => S.projects.list.filter(p => p.root !== path && p.root.startsWith(path + '/')).length;
  /** A mode turned on or off for one command: PUT /api/modes/<name> writes [modes.<name>] in splice.toml, and the next
   *  request follows it with no restart. A press back to the mode's own value for every command clears the command's
   *  entry instead of pinning it. The row splice answers replaces the one held, so the switch shows what holds. */
  S.setMode = async (name, cmd, on) => {
    const m = (S.modes || []).find(x => x.name === name);
    if (!m) return { state: 'refused', why: 'No such mode' };
    const res = await API.put('/api/modes/' + encodeURIComponent(name), { heads: { [cmd]: m.on && on === m.on.value ? null : on } });
    if (!res.ok || !res.body || res.body.name !== name) return { state: 'refused', why: why(res) };
    S.modes = S.modes.map(x => (x.name === name ? res.body : x));
    return { state: 'applied' };
  };
  /** A mode turned on (true) or off (false) for every session, Claude Desktop's included, or back to its manifest's
   *  default (null): PUT /api/modes/<name> writes or clears [modes.<name>] on, the daemon gives Claude Desktop the
   *  mode or takes it away in its configuration, and the row splice answers replaces the one held. */
  S.setModeEvery = async (name, on) => {
    const res = await API.put('/api/modes/' + encodeURIComponent(name), { on });
    if (!res.ok || !res.body || res.body.name !== name) return { state: 'refused', why: why(res) };
    S.modes = S.modes.map(x => (x.name === name ? res.body : x));
    return { state: 'applied' };
  };
  /** The callers a mode is on for while it is off for every command, as its manifest's on_for leaves it when splice.toml
   *  says nothing (GET /api/modes' callers; none from a splice that does not say) */
  S.modeOnlyFor = m => (m.on && m.on.value ? [] : Object.entries(m.callers || {}).filter(([, st]) => st && st.on).map(([c]) => c));
  /** MCP servers shared (one instance for all sessions) or one per session: [daemon] mcp_share, mcp_hosting cleared when on */
  S.setShare = async on => {
    const t = structuredClone(S.topology || {}); t.daemon = { ...(t.daemon || {}), mcp_share: on }; if (on) delete t.daemon.mcp_hosting;
    const res = await API.put('/api/topology', { topology: t });
    if (!res.ok || (res.body && res.body.ok === false)) return { state: 'refused', why: (res.body && res.body.findings && res.body.findings.length) ? 'Config error' : why(res) };
    S.topology = t; S.mcp.shared = on;
    return { state: res.body && res.body.restart_required ? 'restart' : 'applied' };
  };
  /** Import discovered user MCP servers into Splice's opt-in registry (`POST /api/mcp/harnesses/import`). */
  S.importMcp = async (sources, servers) => {
    const body = {};
    if (Array.isArray(sources) && sources.length) body.sources = sources;
    if (Array.isArray(servers) && servers.length) body.servers = servers;
    const res = await API.post('/api/mcp/harnesses/import', body);
    if (!res.ok || !res.body || res.body.ok === false) return { state: 'refused', why: why(res) };
    if (res.body.state) S.mcp.harnesses = res.body.state;
    S.deck = deck();
    const n = res.body.imported || 0;
    return { state: 'applied', word: n ? `Imported ${n}` : 'Up to date' };
  };
  /** Sync Splice MCP servers to one or more harnesses (`POST /api/mcp/harnesses/sync`). */
  S.syncMcp = async (harnesses, importFirst = false) => {
    const body = { import: !!importFirst };
    if (Array.isArray(harnesses) && harnesses.length) body.harnesses = harnesses;
    const res = await API.post('/api/mcp/harnesses/sync', body);
    if (res.body && res.body.state) { S.mcp.harnesses = res.body.state; S.deck = deck(); }
    if (!res.ok || !res.body || res.body.ok === false) {
      const bad = res.body && Array.isArray(res.body.outcomes) ? res.body.outcomes.find(o => !o.ok) : null;
      return { state: 'refused', why: (bad && bad.detail) || why(res) };
    }
    const anyChanged = Array.isArray(res.body.outcomes) && res.body.outcomes.some(o => o.changed);
    return { state: 'applied', word: anyChanged ? 'Synced' : 'Up to date' };
  };
  /** alert outlets: desktop and a webhook address (PUT /api/alerts, the whole settings at once) */
  S.setAlert = async (outlet, value) => {
    const next = { desktop: outlet === 'desktop' ? !!value : !!S.alerts.desktop, webhook_url: outlet === 'webhook' ? value || null : S.alerts.webhook || null };
    const res = await API.put('/api/alerts', next);
    if (!res.ok) return { state: 'refused', why: why(res) };
    S.alerts.desktop = !!res.body.desktop; S.alerts.webhook = res.body.webhook_url || null;
    return { state: 'applied' };
  };
  S.testAlert = async () => { const res = await API.post('/api/alerts/test', {}); return res.ok ? { state: 'applied', word: 'Sent' } : { state: 'refused', why: why(res) }; };
  /** what a shorter keep deletes: usage history asks splice (GET /api/history?days=), the others are read from what the store holds */
  S.wouldDelete = async (key, v) => {
    if (key === 'historyRetentionDays') {
      const res = await API.get('/api/history?days=' + (v === 'forever' ? 'forever' : v));
      if (!res.ok || !res.body || !res.body.cut) return null;
      return { bytes: res.body.cut.bytes, items: res.body.cut.turns, word: 'requests', cutoff: res.body.cut.cutoff_epoch_ms };
    }
    const st = S.data.stores.find(s => s.knob === key);
    if (!st || v === 'forever' || !st.oldest || !st.bytes) return null;
    const held = (Date.now() - st.oldest) / DAY;
    if (!(v < held)) return { bytes: 0, items: 0 };
    const share = v === 0 ? 1 : 1 - v / held;
    return { bytes: Math.round(st.bytes * share), items: Math.round((st.items || 0) * share), word: st.itemWord, estimated: true };
  };
  /** usage history's window; a shorter one sends the cutoff splice named with it, after the yes */
  S.shortenHistory = async (v, cutoff) => {
    const res = await API.put('/api/history', cutoff == null ? { days: v } : { days: v, delete_before_epoch_ms: cutoff });
    if (!res.ok) return { state: 'refused', why: why(res) };
    K.historyRetentionDays.value = v; K.historyRetentionDays.scope = 'file';
    return { state: 'applied' };
  };
  S.checkVersion = async () => { const res = await API.post('/api/upgrade/check', {}); if (res.ok) readVersion(res.body); return res.ok ? { state: 'applied' } : { state: 'refused', why: why(res) }; };
  S.upgrade = async () => { const res = await API.post('/api/upgrade', { to: S.version.latest }); return res.ok ? { state: 'applied', word: 'Upgrading' } : { state: 'refused', why: why(res) }; };
  S.goBack = async () => { const res = await API.post('/api/upgrade', { rollback: true }); return res.ok ? { state: 'applied', word: 'Rolling back' } : { state: 'refused', why: why(res) }; };
  /** restart splice: default hands over at once under systemd (?wait=1 waits for all compactions; ?now=1 skips every wait). */
  S.restart = async (mode = 'default') => {
    const query = mode === 'wait' ? '?wait=1' : mode === true || mode === 'now' ? '?now=1' : '';
    const res = await API.post('/api/daemon/restart' + query, {});
    if (!res.ok) return { state: 'refused', why: why(res) };
    if (res.body && typeof res.body.hands_over === 'boolean') S.restartState.handsOver = res.body.hands_over;
    for (let i = 0; i < 90; i++) {
      await new Promise(r => setTimeout(r, 2000));
      const st = await API.get('/api/daemon/restart');
      if (st.ok && st.body && st.body.status === 'idle') { pending = []; keepPending(); await S.read(); return { state: 'applied', word: 'Back' }; }
    }
    return { state: 'refused', why: 'Not back yet' };
  };

  /* ---------- the page's deck: every word it shows, and how settings group (prototype settings/deck.ts) ---------- */
  function deck() {
    const wk = Object.values(S.week), sum = f => (wk.length ? wk.reduce((n, w) => n + (f(w) || 0), 0) : null);
    const longest = wk.length ? Math.max(0, ...wk.map(w => w.longestSilenceMs || 0)) : null;
    const busy = Object.entries(S.week).filter(([, w]) => w.peakToday > 0).sort((a, b) => b[1].peakToday - a[1].peakToday);
    const store = id => S.data.stores.find(s => s.id === id) || {};
    return [
      { id: 'models', title: 'Reasoning', items: [
        { id: 'effort', kind: 'pick', key: 'effort', label: 'Default effort', hint: 'Applies to new sessions', perCommand: true,
          options: [{ v: 'low', label: 'Low' }, { v: 'medium', label: 'Medium' }, { v: 'high', label: 'High' }, { v: 'xhigh', label: 'Extra high' }, { v: 'max', label: 'Max' }] },
        { id: 'reasoning', kind: 'pair', label: 'Reasoning display', perCommand: true, parts: [
          { key: 'summary', label: 'Detail', options: [{ v: 'detailed', label: 'Full' }, { v: 'concise', label: 'Short' }, { v: 'auto', label: 'Auto' }, { v: 'none', label: 'None' }] },
          { key: 'showReasoning', label: 'Show in', options: [{ v: 'thinking', label: 'Thinking block' }, { v: 'text', label: 'Reply' }, { v: 'off', label: 'Hidden' }] },
        ] },
        { id: 'progressLine', kind: 'toggle', key: 'progressLine', label: 'Thinking status line' },
        { id: 'replayReasoning', kind: 'toggle', key: 'replayReasoning', label: 'Reuse previous reasoning', hint: 'Uses more tokens' },
        { id: 'verbosity', kind: 'pick', key: 'verbosity', label: 'ChatGPT response size', adv: true, perCommand: true,
          options: [{ v: null, label: 'Default' }, { v: 'low', label: 'Short' }, { v: 'medium', label: 'Medium' }, { v: 'high', label: 'Long' }] },
      ] },
      { id: 'stalls', title: 'Stalled responses', items: [
        { id: 'stallLine', kind: 'timeline', label: 'Stalled responses', longest, silent: sum(w => w.silentOverLimit), gaveUp: sum(w => w.gaveUp), rows: [
          { key: 'firstByteTimeoutMs', label: 'First response timeout', hint: "Checks the provider, doesn't cancel", stops: steps([30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], dur) },
          { key: 'streamIdleMs', label: 'Stalled stream timeout', hint: "Checks the provider, doesn't cancel", stops: steps([30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], dur) },
          { key: 'stallReanchorMs', label: 'Retry after', hint: 'Resumes the stalled response, on providers that support it', adv: true, stops: steps([10 * SEC, 20 * SEC, 30 * SEC, 60 * SEC], dur) },
          { key: 'upstreamTimeoutMs', label: 'Request timeout', hint: 'Cancels after no progress', stops: steps([10 * MIN, 15 * MIN, 20 * MIN, 30 * MIN, HR, 2 * HR], dur) },
        ] },
      ] },
      { id: 'capacity', title: 'Agent settings', items: [
        { id: 'maxInflight', kind: 'perCommandLimit', key: 'maxInflight', label: 'Concurrent requests',
          stops: steps([4, 8, 12, 16, 24, 32, 64, 100, 0], v => (v === 0 ? 'No limit' : String(v))),
          rows: S.commands.map(c => ({ cmd: c.key, peak: (S.week[c.key] || {}).peakToday || 0 })).sort((a, b) => b.peak - a.peak), busy },
        { id: 'maxQueued', kind: 'steps', key: 'maxQueued', label: 'Queue size', hint: 'Requests waiting for a free slot; extra are rejected', stops: steps([128, 256, 512, 1024, 0], v => (v === 0 ? 'No limit' : String(v))) },
        { id: 'maxRequestBytes', kind: 'steps', key: 'maxRequestBytes', label: 'Max request size', stops: steps([8 * MB, 16 * MB, 32 * MB, 64 * MB], v => `${v / MB} MB`) },
        { id: 'requestReadTimeoutMs', kind: 'steps', key: 'requestReadTimeoutMs', label: 'Upload timeout', adv: true, stops: steps([10 * SEC, 30 * SEC, 60 * SEC, 2 * MIN, 5 * MIN], dur) },
        { id: 'ingressReadIdleMs', kind: 'steps', key: 'ingressReadIdleMs', label: 'Idle connection timeout', adv: true, stops: steps([10 * SEC, 30 * SEC, 60 * SEC, 2 * MIN], dur) },
        { id: 'materializationHeapBytes', kind: 'steps', key: 'materializationHeapBytes', label: 'Request memory', adv: true, stops: steps([0, 256 * MB, 512 * MB, 1024 * MB, 2048 * MB], v => (v === 0 ? 'Auto' : `${v / MB} MB`)) },
      ] },
      { id: 'limits', title: 'Usage alerts', items: [
        { id: 'planPoll', kind: 'combo', label: 'Plan usage refresh', keys: ['quotaPoll', 'quotaPollIntervalMs'], stops: [{ v: 'off', label: 'Off' }, ...steps([MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN], v => `Every ${dur(v)}`)] },
        { id: 'usageWarnPct', kind: 'steps', key: 'usageWarnPct', label: 'Alert threshold', stops: steps([50, 70, 80, 90, 95], v => `${v}%`) },
        { id: 'budgetDefaultAction', kind: 'pick', key: 'budgetDefaultAction', label: 'When a budget runs out', options: [{ v: 'warn', label: 'Alert only' }, { v: 'block', label: 'Block requests' }] },
        { id: 'alerts', kind: 'outlets', label: 'Send alerts to', outlets: [
          { id: 'command', label: 'Command', value: S.alerts.command ? 'On' : 'Not set', source: 'splice.toml', readOnly: true },
          { id: 'desktop', label: 'Desktop', kind: 'toggle' },
          { id: 'webhook', label: 'Webhook', kind: 'url' },
        ], events: ['Last available login over the threshold', 'No login available'], action: { id: 'testAlert', label: 'Send test' } },
        { id: 'usageWarnTokens5h', kind: 'steps', key: 'usageWarnTokens5h', label: '5-hour token alert', hint: 'For providers without plan limits', adv: true,
          stops: steps([0, 1e6, 5e6, 10e6, 25e6], v => (v === 0 ? 'Off' : `${v / 1e6}M tokens`)) },
      ] },
      { id: 'mcp', title: 'MCP servers', items: [
        { id: 'mcpShare', kind: 'share', label: 'Server instances', options: [{ v: true, label: 'Shared' }, { v: false, label: 'Per session' }] },
        { id: 'mcpHarnesses', kind: 'mcpHarnesses', label: 'Connected harnesses' },
        { id: 'mcpImport', kind: 'mcpImport', label: 'User MCP servers' },
        { id: 'mcpMaxServers', kind: 'steps', key: 'mcpMaxServers', label: 'Max running servers', adv: true, stops: steps([8, 16, 32, 64], String) },
        { id: 'mcpIdleTimeoutMs', kind: 'steps', key: 'mcpIdleTimeoutMs', label: 'Stop idle servers after', stops: steps([5 * MIN, 15 * MIN, 30 * MIN, HR], dur) },
        { id: 'mcpRequestTimeoutMs', kind: 'steps', key: 'mcpRequestTimeoutMs', label: 'Request timeout', adv: true, stops: steps([5 * MIN, 15 * MIN, 30 * MIN, HR], dur) },
        { id: 'mcpInitializeTimeoutMs', kind: 'steps', key: 'mcpInitializeTimeoutMs', label: 'Startup timeout', adv: true, stops: steps([30 * SEC, 60 * SEC, 2 * MIN], dur) },
        { id: 'mcpUnshared', kind: 'list', label: 'Not shareable', rows: S.mcp.servers.filter(s => !s.shared).map(s => ({ name: s.name, value: s.why })) },
      ] },
      { id: 'projects', title: 'Projects', items: [
        { id: 'projectsFolders', kind: 'folders', key: 'projectsFolders', label: 'Project folders', hint: 'Each subfolder is a project', empty: 'None', candidates: S.projects.candidates || [] },
        { id: 'statuslineGitRoots', kind: 'folders', key: 'statuslineGitRoots', label: 'Show git branch in status line', empty: 'None', candidates: S.projects.candidates || [] },
      ] },
      { id: 'storage', title: 'Storage', items: [
        { id: 'transcriptView', kind: 'toggle', key: 'transcriptView', label: 'Show conversations in Sessions' },
        { id: 'keep', kind: 'stores', label: 'Retention', rows: [
          { id: 'history', label: 'Usage history', size: store('history').bytes, key: 'historyRetentionDays', asks: true, stops: steps([0, 7, 14, 35, 90, 365, 'forever'], days) },
          { id: 'copies', label: 'Conversation backups', size: store('transcript_copies').bytes, combo: { keys: ['keepTranscriptCopies', 'transcriptCopyDays'] }, asks: true, stops: [{ v: 'off', label: 'Off' }, ...steps([7, 14, 30, 90, 365], days)] },
          { id: 'traces', label: 'Full prompts', size: null, key: 'traceRetentionDays', stops: steps([1, 3, 7, 14, 30], days) },
          { id: 'labels', label: 'Activity history', size: store('labels').bytes, key: 'activityRetentionDays', asks: true, stops: steps([7, 30, 90, 180, 365], days) },
          { id: 'edges', label: 'Agent messages', size: store('edges').bytes, toggle: 'messageEdges' },
          { id: 'codemode', label: 'Code mode journal', size: store('code_mode').bytes, toggle: 'keepCodeModeWork' },
          { id: 'compaction', label: 'Compaction summaries', size: store('compaction_summaries').bytes, toggle: 'keepCompactionSummaries' },
          { id: 'timings', label: 'Request timings', size: null, combo: { keys: ['perfCollectionEnabled', 'perfArchiveRetentionDays'] }, stops: [{ v: 'off', label: 'Off' }, ...steps([30, 90, 180, 365], days)] },
        ] },
        { id: 'traceMaxBodyChars', kind: 'steps', key: 'traceMaxBodyChars', label: 'Max saved prompt size', stops: steps([4 * MB, 8 * MB, 16 * MB, 32 * MB], v => `${v / MB}M chars`) },
      ] },
      { id: 'splice', title: 'Splice', items: [
        { id: 'version', kind: 'version', label: 'Version' },
        { id: 'restart', kind: 'restart', label: 'Restart' },
        { id: 'debug', kind: 'toggle', key: 'debug', label: 'Debug logs' },
        { id: 'files', kind: 'info', label: 'Files', rows: Object.entries(S.paths).map(([k, v]) => ({ label: k[0].toUpperCase() + k.slice(1), value: v })) },
        { id: 'fixed', kind: 'info', label: 'Service', rows: [['controlPort', 'Console port'], ['supervisorUnit', 'Unit'], ['mcpSlice', 'MCP slice'], ['activityStoreHeads', 'Activity kept for']]
          .filter(([k]) => K[k]).map(([k, label]) => ({ label, value: k === 'activityStoreHeads' && S.valueOf(k) === '*' ? 'All commands' : String(S.valueOf(k)) })) },
      ] },
      { id: 'metrics', title: 'Monitoring', items: [
        { id: 'telemetryPrometheusEnabled', kind: 'toggle', key: 'telemetryPrometheusEnabled', label: 'Prometheus metrics', adv: true },
        { id: 'otlp', kind: 'otlp', label: 'OpenTelemetry export', adv: true, keys: { endpoint: 'telemetryOtlpEndpoint', protocol: 'telemetryOtlpProtocol', signals: 'telemetryOtlpSignals', headers: 'telemetryOtlpHeaders' },
          placeholder: 'https://collector:4318', protocols: [{ v: 'http/protobuf', label: 'HTTP' }, { v: 'grpc', label: 'gRPC' }], signals: [{ v: 'metrics', label: 'Metrics' }, { v: 'traces', label: 'Traces' }, { v: 'logs', label: 'Logs' }] },
      ] },
    ];
  }
  S.deck = [];
  S.item = id => { for (const s of S.deck) { const i = s.items.find(x => x.id === id); if (i) return i; } return null; };

  /* ---------- the reads ---------- */
  const get = p => API.get(p).then(r => (r.ok ? r.body : null));
  function readVersion(u) { if (u) S.version = { installed: u.installed || null, previous: u.rollback_target || null, latest: u.latest || null, checkedAt: u.checked_at_epoch_millis || null }; }
  /** the week's questions of one command, asked in one read (as Settings has always read them), and today's peak */
  async function readWeek(head, cfgOf) {
    const week = Date.now() - 7 * DAY, day = (() => { const d = new Date(); d.setHours(0, 0, 0, 0); return +d; })();
    const tier = cfgOf('streamIdleMs', head.key);
    const asks = [['allweek', {}], ['restarted', { outcome: 'error:restarted' }], ['gaveup', { outcome: 'failed' }], ['queued', { queued_ms: '0' }]];
    if (Number.isFinite(tier)) asks.push(['waited', { silence_ms: String(Math.round(tier)) }]);
    const q = new URLSearchParams({ head: head.key, since: String(week), time_zone: ZONE, n: '1', local: '0', usage: '0' });
    asks.forEach(([, a]) => q.append('ask', new URLSearchParams({ n: '1', from: String(week), local: '0', usage: '0', ...a }).toString()));
    const peakQ = new URLSearchParams({ head: head.key, since: String(day), time_zone: ZONE, n: '1', local: '0', usage: '0', peak: '1' });
    const [w, p] = await Promise.all([
      API.perfRequest('perf:settings:week:' + head.key, 'GET', '/api/perf/turns?' + q).then(r => r || { ok: false }),
      API.perfRequest('perf:settings:peak:' + head.key, 'GET', '/api/perf/turns?' + peakQ).then(r => r || { ok: false }),
    ]);
    const hw = w.ok && w.body && (w.body.heads || []).find(x => x.key === head.key), got = hw && Array.isArray(hw.asks) ? hw.asks : [];
    const at = name => { const i = asks.findIndex(([n]) => n === name); return i >= 0 ? got[i] : null; };
    const n = name => { const a = at(name); return a && typeof a.count === 'number' ? a.count : null; };
    const hp = p.ok && p.body && (p.body.heads || []).find(x => x.key === head.key), held = hp && hp.at_once;
    return { requests: n('allweek'), cutByRestart: n('restarted'), gaveUp: n('gaveup'), waitedInLine: n('queued'), silentOverLimit: n('waited'),
      longestSilenceMs: (at('waited') || {}).longest_silence_ms ?? null, peakToday: held ? held.peak : null, peakAt: held ? held.at : null };
  }
  S.read = async () => {
    const [cfg, hd, al, up, ss, mcp, mem, stores, labels, edges, hist, pr, topo, modes, rst, upr, hrRes] = await Promise.all([
      ...['/api/config', '/api/heads', '/api/alerts', '/api/upgrade', '/api/sessions',
        '/api/mcp', '/api/mcp/memory', '/api/kept/stores', '/api/kept/labels', '/api/kept/edges', '/api/history', '/api/projects', '/api/topology', '/api/modes', '/api/daemon/restart', '/api/upgrade/run'].map(get),
      API.get('/api/mcp/harnesses'),
    ]);
    if (!cfg) return false;
    // a splice that answers without its settings is drawn at splice's defaults, every row saying they were not read
    S.configRead = !!(cfg.effective && typeof cfg.effective === 'object');
    if (!S.configRead) cfg.effective = { ...DEFAULTS };
    const per = (cfg.layers && cfg.layers.perHead) || {};
    for (const [key, def] of Object.entries(DEFAULTS)) {
      const d = (cfg.knobs && cfg.knobs[key]) || null;
      if (!d && !(cfg.effective && key in cfg.effective)) continue;   // a setting this splice does not have is not drawn
      const own = {};
      for (const [cmd, o] of Object.entries(per)) if (o && key in o) own[cmd] = o[key];
      const k = K[key] || (K[key] = { key, def, own });
      Object.assign(k, { value: d && d.value !== undefined ? d.value : cfg.effective && key in cfg.effective ? cfg.effective[key] : def, scope: (d && d.scope) || 'default',
        restart: !!d && d.disposition === 'restart', editable: !d || d.editable !== false, readOnlyWhy: d && (d.read_only_reason || null), envNames: (d && d.env_names) || null, own });
    }
    // usage history's window is history's own (GET /api/history window, PUT /api/history), not a /api/config value
    if (hist && hist.window && typeof hist.window === 'object') {
      const k = K.historyRetentionDays || (K.historyRetentionDays = { key: 'historyRetentionDays', def: DEFAULTS.historyRetentionDays, own: {}, scope: 'default' });
      Object.assign(k, { value: hist.window.forever ? 'forever' : hist.window.days, restart: false, editable: true, readOnlyWhy: null, envNames: null });
    }
    S.knobs = Object.values(K);
    const cfgOf = (key, cmd) => S.valueOf(key, cmd);
    S.restartState = { status: (rst && rst.status) || 'idle', handsOver: !!(rst && rst.hands_over), drainMs: (rst && rst.drain_ms) ?? null, compactions: (rst && rst.compactions) || 0 };
    S.upgradeRun = (upr && upr.run) || null;
    S.commands = ((hd && hd.heads) || []).map(x => ({ key: x.key, label: x.label || x.key, gate: x.gate || {}, handsOver: x.hands_over !== false }));
    S.running.requests = S.commands.reduce((n, c) => n + (c.gate.inflight || 0), 0);
    S.running.cutRequests = S.restartState.handsOver ? S.commands.filter(c => !c.handsOver).reduce((n, c) => n + (c.gate.inflight || 0), 0) : S.running.requests;
    S.running.sessions = ((ss && ss.sessions) || []).filter(s => s.availability === 'live').length;
    if (al) S.alerts = { command: S.alerts.command, desktop: !!al.desktop, webhook: al.webhook_url || null };
    S.topology = topo && topo.topology ? topo.topology : S.topology;
    const daemon = (S.topology && S.topology.daemon) || {};
    S.alerts.command = daemon.alert_command ? true : null;
    readVersion(up);
    S.modes = modes && Array.isArray(modes.modes) ? modes.modes : null;
    S.paths = (cfg.paths && typeof cfg.paths === 'object') ? cfg.paths : {};
    const mcpShared = daemon.mcp_share !== false && daemon.mcp_hosting !== false && !(mcp && mcp.hosting === false);
    const nextHarnesses = hrRes && hrRes.ok && hrRes.body ? hrRes.body : hrRes && hrRes.status === 404 ? null : S.mcp.harnesses;
    if (mem) S.mcp = { shared: mcpShared, sessions: mem.sessions ?? null, bytesNow: mem.bytes_now ?? null, bytesIfShared: mem.bytes_if_shared ?? null,
      servers: (mem.servers || []).map(s => ({ name: s.name, shared: s.class === 'shared', why: s.why ? ({ variable: 'A different value in each session', relative_path: 'A different folder in each project', stateful: "Keeps one session's work", cwd: 'Starts in its own folder' })[s.why] || "Can't be shared" : null })),
      harnesses: nextHarnesses };
    else S.mcp = { ...S.mcp, shared: mcpShared, harnesses: nextHarnesses };
    const st = (stores && stores.stores) || {};
    S.history = hist;
    S.data.stores = [
      hist && hist.held ? { id: 'history', bytes: hist.held.bytes, items: hist.held.turns, itemWord: 'requests', oldest: hist.held.oldest_epoch_ms, knob: 'historyRetentionDays' } : null,
      st.transcript_copies ? { id: 'transcript_copies', bytes: st.transcript_copies.bytes, items: st.transcript_copies.files, itemWord: 'backups', oldest: st.transcript_copies.oldest_epoch_ms, knob: 'transcriptCopyDays' } : null,
      edges ? { id: 'edges', bytes: edges.bytes, items: edges.rows, itemWord: 'messages', oldest: edges.oldest ? Date.parse(edges.oldest) : null, knob: 'messageEdges' } : null,
      st.code_mode ? { id: 'code_mode', bytes: st.code_mode.bytes, items: st.code_mode.files, itemWord: 'files', oldest: st.code_mode.oldest_epoch_ms, knob: 'keepCodeModeWork' } : null,
      labels ? { id: 'labels', bytes: labels.bytes, items: labels.rows, itemWord: 'labels', oldest: labels.oldest ? Date.parse(labels.oldest) : null, knob: 'activityRetentionDays' } : null,
      st.compaction_summaries ? { id: 'compaction_summaries', bytes: st.compaction_summaries.bytes, items: st.compaction_summaries.files, itemWord: 'summaries', knob: 'keepCompactionSummaries' } : null,
    ].filter(Boolean);
    const list = (pr && pr.projects) || [];
    // the folders a person would pick from: the parents of the projects splice found, never a path the page makes up
    const parents = [...new Set(list.map(p => String(p.root || '').replace(/\/[^/]+\/?$/, '')).filter(Boolean))];
    S.projects = { list: list.map(p => ({ name: p.name, root: p.root })), folders: (pr && pr.folders) || [], candidates: parents };
    S.deck = deck();
    readWeekAll(cfgOf);
    return true;
  };
  let onWeek = () => {};
  S.onWeek = fn => { onWeek = fn; };
  async function readWeekAll(cfgOf) {
    const got = await Promise.all(S.commands.map(c => readWeek(c, cfgOf)));
    S.week = Object.fromEntries(S.commands.map((c, i) => [c.key, got[i]]));
    S.deck = deck();
    onWeek();
  }
  return S;
})();
