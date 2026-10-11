// Settings, built from the finished drawing (Splice-Animator/design/console at d3338fa). A rail of the jobs a person
// comes with, and the open job beside it. Every value on this page is read from the daemon (GET /api/config through
// knobs.js, GET /api/history, GET /api/kept/*) and every change goes back to it; a figure splice does not keep is not
// drawn, and a store with no route to count or delete it says so on its own row instead of offering a Delete that
// would do nothing. Jobs whose settings the daemon reads only at start, and the jobs that need their own routes
// (the week's turns, MCP servers, the version), join as each one is true end to end.
"use strict";

const { fmt, KNOB, st: ks, val, word, rowHtml, ctlHtml, saidHtml, cmdChip, needsRestart } = KNOBS;
const SV = {
  open: G('<path d="M7 17L17 7M9 7h8v8"/>'),
  busy: G('<path d="M4 6h16M4 12h16M4 18h9"/><circle cx="18" cy="18" r="1.6"/>'),
  silent: G('<circle cx="12" cy="13" r="7"/><path d="M12 9v4l2.5 2M9 3h6"/>'),
  reasoning: G('<path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.4 1 2.5h6c0-1.1.3-1.8 1-2.5A6 6 0 0 0 12 3z"/>'),
  plan: G('<path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/>'),
  mcp: G('<path d="M9 3v5M15 3v5M7 8h10v4a5 5 0 0 1-10 0z"/><path d="M12 17v4"/>'),
  version: G('<path d="M12 19V5M6 11l6-6 6 6"/>'),
  data: G('<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>'),
};
const MiB = 1048576;
const DAY = 864e5;

// ---------- the jobs ----------
// Only the jobs people come to it with (Marlin, Oct 10): a job draws the settings the daemon takes live, in knobs.js's
// order, and a job none of whose settings is live is not on the rail until one is. The names are the
// mock's, with no description line under them (fin).
const TOPICS = [
  { id: "silent", name: "Silent models", also: "stall stuck wedged hang quiet resume timeout" },
  { id: "busy", name: "Many agents at once", keys: ["maxInflight", "maxQueued"], also: "overloaded busy queue waiting 429 failed errors retry" },
  { id: "reasoning", name: "Reasoning", also: "thinking summary show hide progress line replay" },
  { id: "plan", name: "Plan limits", also: "quota usage warning budget spend limit" },
  { id: "mcp", name: "MCP servers", also: "tools servers model context protocol" },
  { id: "data", name: "Your data", also: "privacy prompts saved keep delete disk history logs" },
  { id: "version", name: "Version", also: "update upgrade new release roll back restart" },
];
const TOPIC = Object.fromEntries(TOPICS.map((x) => [x.id, x]));
const topicOf = (key) => KNOB[key].home || TOPICS.find((x) => x.keys && x.keys.includes(key)).id;
/** The settings this page offers: those with a job here that the daemon names as taken without a restart (GET
 *  /api/config's restart_required_keys, Knob.kt), so a knob that turns live joins and one that turns boot-only leaves
 *  with the daemon's own answer, never a list kept here. */
const liveKnobs = () => Object.values(KNOB)
  .filter((k) => !needsRestart(k.key) && shownKnob(k.key) && (k.home ? TOPIC[k.home] : TOPICS.some((x) => x.keys && x.keys.includes(k.key))));
// the poll interval means nothing while reading plan limits is off, and a value that does nothing would read as working (fin)
const shownKnob = (key) => (key !== "quotaPollIntervalMs" || val("quotaPoll") !== "off") && (key !== "stallReanchorMs" || KNOB.stallReanchorMs.only.length > 0);
/** The jobs on the rail: Your data always, the others once one of their settings is live. */
const jobs = () => TOPICS.filter((x) => x.id === "data" || x.id === "version" || liveKnobs().some((k) => topicOf(k.key) === x.id));
/** A job's live settings, in the order knobs.js lists them (the first two of Many agents at once are its own section). */
const keysOf = (id, skip = []) => liveKnobs().filter((k) => topicOf(k.key) === id && !skip.includes(k.key)).map((k) => k.key);

const ui = { said: {}, capture: {}, week: null, ver: null, run: null, headRows: [], saving: null, held: {}, open: "busy", shown: false, q: "", armed: null, bad: {}, ask: null, heads: [], hist: null, kept: {}, err: null };

// ---------- what the daemon keeps ----------
// One read per store; a store whose route answers with an error is drawn as unreadable, never as empty.
async function readKept() {
  const [hist, turns, edges, labels, heads, models, upgrade, upgradeRun] = await Promise.all([
    API.get("/api/history"), API.get("/api/kept/turns"), API.get("/api/kept/edges"), API.get("/api/kept/labels"), API.get("/api/heads"), API.get("/api/models"),
    API.get("/api/upgrade"), API.get("/api/upgrade/run"),
  ]);
  ui.hist = hist.ok ? hist.body : null;
  const stores = await API.get("/api/kept/stores");
  ui.stores = stores.ok ? stores.body.stores : null;
  ui.kept = { turns: turns.ok ? turns.body : null, edges: edges.ok ? edges.body : null, labels: labels.ok ? labels.body : null };
  ui.heads = (heads.body?.heads || []).map((h) => h.key);
  ui.headRows = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  ui.ver = upgrade.ok ? upgrade.body : null;
  // the commands whose provider splice resumes mid-answer report a finite resume tier on their gate
  KNOB.stallReanchorMs.only = (heads.body?.heads || []).filter((h) => typeof h.gate?.stall_reanchor_ms === "number").map((h) => h.key);
  if (!ui.run && upgradeRun.ok && upgradeRun.body?.run?.state === "running") { ui.run = { state: "running", to: ui.ver?.rollback_target, output: upgradeRun.body.run.output || [] }; pollRun(ui.run.to); }
  ks.cmds = ui.heads;
  ks.providerOf = Object.fromEntries((models.body?.heads || []).map((r) => [r.head, r.provider]));
  // the fold set is ChatGPT models, so only the commands that reach that provider offer theirs
  ks.models = [...new Map((models.body?.heads || []).filter((r) => r.provider === "codex").flatMap((r) => r.models || []).map((m) => [m.id, { id: m.id, label: m.label || m.id }])).values()];
  // the bodies each tapped command holds in memory now, counted and never read out (GET /api/heads/{head}/wire)
  const tapped = ui.heads.filter((h) => tapOf(h) > 0);
  const wires = await Promise.all(tapped.map((h) => API.get(`/api/heads/${encodeURIComponent(h)}/wire`)));
  ui.held = Object.fromEntries(tapped.map((h, i) => [h, wires[i].ok ? (wires[i].body?.records ?? []).length : null]));
  const traces = await Promise.all(ui.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h)}/trace/kept`)));
  const caps = await Promise.all(ui.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h)}/capture`)));
  ui.capture = Object.fromEntries(ui.heads.map((h, i) => [h, caps[i].ok && typeof caps[i].body?.enabled === "boolean" ? caps[i].body.enabled : null]));
  ui.kept.trace = traces.every((t) => t.ok) && traces.length ? traces.map((t) => t.body) : null;
  await readWeek();
}
const mbWord = (b) => { const mb = b / MiB; return mb >= 1000 ? `${+(mb / 1024).toFixed(1)} GB` : mb >= 1 ? `${+mb.toFixed(1)} MB` : b > 0 ? `${Math.max(1, Math.round(b / 1024))} KB` : "0 B"; };
const dateWord = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
const atWord = (ms) => { const d = new Date(ms); return d.getHours() || d.getMinutes() ? `${dateWord(d)}, ${d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })}` : dateWord(d); };

// ---------- search ----------
const norm = (s) => String(s).toLowerCase();
const words = () => ui.q.trim().split(/\s+/).filter(Boolean);
const hits = (text) => { const w = words(); return w.length > 0 && w.every((x) => norm(text).includes(norm(x))); };
const jobHay = (id) => `${TOPIC[id].name} ${TOPIC[id].also}`;
const knobHay = (k) => [k.label, k.key, k.also || "", k.home ? "" : jobHay(topicOf(k.key))].join(" ");
function hl(s) {
  const w = words().map((x) => x.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return w.length ? esc(s).replace(new RegExp(`(${w.join("|")})`, "gi"), "<mark>$1</mark>") : esc(s);
}
function found(id) {
  if (!words().length) return [];
  const f = liveKnobs().filter((k) => topicOf(k.key) === id && hits(knobHay(k))).map((k) => k.label);
  if (id === "data") f.push(...DATA_ROWS.filter((r) => hits(r.name)).map((r) => r.name));
  if (!f.length && hits(jobHay(id))) f.push(TOPIC[id].name);
  return [...new Set(f)];
}
const lit = (text) => (words().length && hits(text) ? " lit" : "");
const form = (keys) => `<div class="form">${keys.filter((key) => !needsRestart(key) && shownKnob(key)).map((key) => rowHtml(KNOB[key], { hl, lit: lit(knobHay(KNOB[key])) })).join("")}</div>`;

// the per-command settings splice reads at start and the page only shows (restart-only, set in [heads.KEY.overrides])
const tapOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.wireTap) || 0;
// whether a command is saving prompts and answers NOW: its capture switch (GET /api/heads/{head}/capture), which the daemon
// applies on the command's next request; the setting it booted with is only the fallback when that route did not answer
const traceOn = (head) => ui.capture[head] ?? ks.cfg?.layers?.perHead?.[head]?.trace !== false;

// ---------- Your data ----------
// Each store, what it holds, how long, and its Delete now. A store with no route to count or delete it says so.
const DATA_ROWS = [
  { id: "trace", g: "text", name: "Prompts and answers", unit: "requests", yours: true, door: ["requests.html", "Requests"] },
  // the stores a person's prompts sit in that no feature runs without: counted and cleared by /api/kept/stores, each naming the
  // feature that needs it in place of a switch, with the lifetime splice gives it (the history window, or its own)
  { id: "originals", g: "text", name: "Transcript copies", unit: "files", yours: true, store: "transcript_copies", needs: "Needed to resume on another model" },
  { id: "recordings", g: "text", name: "Compaction summaries", unit: "files", store: "compaction_summaries", needs: "Needed to retry a compaction", life: "2 hours" },
  { id: "journals", g: "text", name: "Code mode work", unit: "files", store: "code_mode", needs: "Needed by code mode", life: "1 day unused" },
  { id: "reasoning", g: "text", name: "Reasoning between requests", noRoute: true },
  { id: "hist", g: "records", name: "Usage and request history", unit: "requests", door: ["usage.html", "Usage"] },
  { id: "edges", g: "records", name: "Who messaged whom", unit: "messages", door: ["teams.html", "Teams"] },
  { id: "labels", g: "records", name: "What each agent is doing", unit: "lines", door: ["teams.html", "Teams"] },
];
const EP = { trace: null, hist: "/api/kept/turns", edges: "/api/kept/edges", labels: "/api/kept/labels" };
// a store behind /api/kept/stores is read from that one answer and cleared by its own name
const storeOf = (r) => r.store && ui.stores && ui.stores[r.store];
// what a store holds now: rows, bytes, since when. null when its route would not answer.
function heldOf(r) {
  if (r.id === "hist") return ui.hist && { n: ui.hist.held.turns, bytes: ui.hist.held.bytes, since: ui.hist.held.oldest_epoch_ms };
  if (r.id === "trace") {
    const t = ui.kept.trace;
    return t && { n: t.reduce((s, x) => s + x.records, 0), bytes: t.reduce((s, x) => s + x.bytes, 0), since: null };
  }
  if (r.store) { const s = storeOf(r); return s && !s.error ? { n: s.files, bytes: s.bytes, since: s.oldest_epoch_ms ?? null } : null; }
  const k = ui.kept[r.id];
  return k && { n: k.rows, bytes: k.bytes, since: k.oldest ? Date.parse(`${k.oldest}T00:00:00`) : null };
}
const delBtn = (id, n, unit) => (!n ? "" : ui.armed === id
  ? `<span class="armed"><button class="act danger small" data-act="delete" data-id="${id}">Delete ${fmt(n)} ${unit}</button><button class="act quiet small" data-act="disarm">Cancel</button></span>`
  : `<button class="act quiet small" data-act="arm" data-id="${id}">Delete now</button>`);
const ALWAYS = `<span class="fixed quiet">Always saved</span>`; // a store whose daemon has no off switch
const YOURS = `<span class="yours">Your prompts</span>`;
const drow = ({ cls = "", name, hay = name, yours, door, amount, keep = "", sw = "", del = "", more = "" }) =>
  `<div class="drow${cls}${lit(hay)}"><div class="dname"><span class="nm">${hl(name)}${yours ? YOURS : ""}</span>${door ? `<a class="link" href="${door[0]}">${SV.open}${door[1]}</a>` : ""}</div>`
  + `<div class="amt">${amount}</div><div class="keep">${keep}</div><div class="dsw">${sw}</div><div class="ddel">${del}</div>${more}</div>`;
const amountOf = (x, unit) => (x.n ? `<b>${fmt(x.n)}</b> ${unit}<span class="mb">${mbWord(x.bytes)}</span>${x.since ? `<span class="mb">Since ${atWord(x.since)}</span>` : ""}` : `<span class="none">Nothing saved</span>`);
const unreadable = `<span class="none">splice did not answer</span>`;

// The usage and request history. A shorter window waits for the yes in a band under the row: the held days as one bar,
// the ones that go hatched, the first day kept as a tick. The yes sends the moment it showed (PUT /api/history).
function histBar(h) {
  const total = h.held.bytes || 1, at = (h.cut.bytes / total) * 100, w = h.cut.cutoff_epoch_ms;
  const days = h.days.map((d) => `<i style="--mb:${d.bytes}"></i>`).join("");
  const tick = `<span class="hcut" style="--at:${at}%">${h.window.nothing ? "" : `<em>${atWord(w)}</em>`}</span>`;
  return `<div class="hbar"><span class="hend">${atWord(h.held.oldest_epoch_ms)}</span><div class="hdays" role="img" aria-label="${mbWord(h.cut.bytes)} goes; kept from ${h.window.nothing ? "today" : atWord(w)}">`
    + `${days}<span class="hgone" style="--w:${at}%"></span><b class="hfreed" style="--w:${at}%"><span>${mbWord(h.cut.bytes)}</span></b>${tick}</div><span class="hend">Today</span></div>`;
}
function histRow(r) {
  if (!ui.hist) return drow({ name: r.name, door: r.door, amount: unreadable });
  const a = ui.ask, h = a && a.h ? a.h : ui.hist, x = heldOf(r), id = "del:hist";
  const month = ui.hist.rate_bytes_per_month;
  const rate = a || h.window.nothing || !month ? "" : `<span class="hrate">About ${mbWord(month)} a month</span>`;
  return drow({
    name: r.name, hay: `${r.name} ${KNOB.historyRetentionDays.also}`, door: r.door,
    sw: ALWAYS, // the daemon has no switch for turn statistics: only how far back they go
    amount: amountOf(x, r.unit),
    keep: `${ctlHtml(KNOB.historyRetentionDays, a ? a.v : val("historyRetentionDays"))}${saidHtml("historyRetentionDays")}${rate}`,
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
    more: a && a.h ? `<div class="dmore hask">${histBar(a.h)}<div class="hacts"><button class="act danger small" data-act="shorten">Delete ${fmt(a.h.cut.turns)} requests</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>`
      : a ? `<div class="dmore hask"><div class="hbar reading"><span class="hend"></span><div class="hdays"></div><span class="hend">Today</span></div><div class="hacts"><button class="act danger small" disabled>Delete</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>` : "",
  });
}
function dataRow(r) {
  if (r.id === "hist") return histRow(r);
  if (r.noRoute) { // no route counts or deletes it: say so on the row, never an empty Delete
    return drow({ name: r.name, yours: r.yours, amount: `<span class="none">Not counted</span>`, keep: "", sw: r.always ? ALWAYS : "" }); // a store the daemon has no off switch for
  }
  const x = heldOf(r), id = `del:${r.id}`;
  if (!x) return drow({ name: r.name, yours: r.yours, door: r.door, amount: unreadable });
  const on = r.id === "trace" ? ui.heads.filter(traceOn) : null;
  const keep = (r.id === "edges" || r.id === "trace" || r.id === "originals") && ui.hist ? `<span class="fixed quiet">${esc(ui.hist.window.forever ? "Forever" : ui.hist.window.nothing ? "Today only" : `${ui.hist.window.days} days`)}</span>`
    : r.id === "labels" ? `<span class="fixed quiet">Today and yesterday</span>`
    : r.life ? `<span class="fixed quiet">${esc(r.life)}</span>` : "";
  return drow({
    name: r.name, yours: r.yours, door: r.door, amount: amountOf(x, r.unit), keep,
    sw: r.needs ? `<span class="fixed quiet need">${esc(r.needs)}</span>` : on ? `<button class="count" data-act="saving" aria-expanded="${ui.saving === r.id}">${on.length} of ${ui.heads.length} commands${ICON.caret}</button>` : "",
    more: on && ui.saving === r.id ? `<div class="dmore"><div class="cmdsw">${ui.heads.map((h) => `<div class="cs${traceOn(h) ? "" : " off"}">${cmdChip(h)}<span class="switch"><button data-act="capture" data-head="${esc(h)}" data-v="true" aria-pressed="${traceOn(h)}">On</button><button data-act="capture" data-head="${esc(h)}" data-v="false" aria-pressed="${!traceOn(h)}">Off</button></span>${ui.said[`capture@${h}`] ? `<span class="said ${ui.bad[`capture@${h}`] ? "limit" : "ok"}">${esc(ui.said[`capture@${h}`])}</span>` : ""}</div>`).join("")}</div></div>` : "",
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
  });
}
function dataHtml() {
  const read = drow({ name: "Read your Claude Code conversations", door: ["sessions.html", "Sessions"], amount: `<span class="none">Nothing saved</span>`, keep: saidHtml("transcriptView"), sw: ctlHtml(KNOB.transcriptView, val("transcriptView")) });
  // what each tapped command holds in memory right now: counted, never shown, and no Delete since the daemon has none
  const taps = ui.heads.filter((h) => tapOf(h) > 0), heldN = taps.reduce((n, h) => n + (ui.held[h] ?? 0), 0);
  const memory = drow({
    cls: taps.length ? "" : " off", name: "Last requests, in memory", yours: true,
    amount: taps.some((h) => ui.held[h] === null) ? unreadable : heldN ? `<b>${fmt(heldN)}</b> ${heldN === 1 ? "request" : "requests"}` : `<span class="none">Nothing held</span>`,
    keep: `<span class="taps">${taps.map((h) => `<span class="tap">${cmdChip(h)}<span class="fixed">Last ${tapOf(h)}</span></span>`).join("")}</span>`,
    sw: `<span class="count">${taps.length} of ${ui.heads.length} commands</span>`,
  });
  const bytes = (g) => DATA_ROWS.filter((r) => r.g === g).reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
  const head = (name, g) => `<div class="dgroup"><h3>${name}</h3><span class="hn">${mbWord(bytes(g))}${DATA_ROWS.some((r) => r.g === g && r.noRoute) ? " counted" : ""}</span></div>`;
  const dhead = `<div class="drow dhead"><span></span><span></span><span>Keep for</span><span>Save</span><span></span></div>`;
  const rows = (g) => DATA_ROWS.filter((r) => r.g === g).map(dataRow).join("");
  return `<div class="dlist">${read}${head("Conversation text", "text")}${dhead}${rows("text")}${memory}${head("Records", "records")}${rows("records")}</div>`;
}

// ---------- what happened this week ----------
// Counted from the requests Usage and Requests read (GET /api/perf/turns, one read per command and word), never kept
// here: a count is the daemon's `count` for the filter a door opens on Requests, so the figure and the page it opens
// agree. Three words need the rows themselves, because the daemon filters a superset of what they mean (kit.js WHY).
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
const SEC = 1000, MIN = 60000;
const MAX_ROWS = 2000; // PerfRoutes.kt MAX_TURNS
const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return +d; };
const reqUrl = (f) => `requests.html?${new URLSearchParams(f)}`;
const rowId = (head, row) => `${head}:${row.turn || row.turn_id || row.ts}`;
/** The silence a request held mid-answer at or past its command's tier, or 0 (requests.js mapRow, same rule). */
const silenceOf = (row, tier) => {
  const gap = row.up_gap_max_ms ?? 0, at = row.up_gap_max_start_epoch_ms ?? null;
  const firstByteAt = row.first_byte != null ? row.ts - (row.total ?? 0) + row.first_byte : null;
  return at && gap >= tier && (firstByteAt == null || at >= firstByteAt) ? gap : 0;
};
/** The words the daemon cannot filter exactly: what else a row must say, given its command's silence tier. */
const ROW_CHECK = { waited: (row, tier) => silenceOf(row, tier) > 0, silentresume: (row) => !!row.stall_ms, silentover: (row) => !!row.stall_ms };
async function countWeek(head, why, from) {
  const ask = why === "failed" ? { outcome: "failed" } : { ...WHY_ASKS[why] }, tier = idleTierOf(ks.cfg, head.key);
  if (why === "waited") { if (!Number.isFinite(tier)) return { head, err: "no silence setting answered" }; ask.silence_ms = String(Math.round(tier)); }
  const exact = !ROW_CHECK[why];
  const res = await API.get(`/api/perf/turns?${new URLSearchParams({ head: head.key, since: String(from), time_zone: ZONE, n: String(exact ? 1 : MAX_ROWS), local: "0", ...ask })}`);
  const answer = (res.body?.heads || []).find((x) => x.key === head.key);
  if (!res.ok || !answer || answer.error || answer.read_error) return { head, err: answer?.error || answer?.read_error || `splice answered ${res.status}` };
  const rows = exact ? (answer.rows || []) : (answer.rows || []).filter((r) => ROW_CHECK[why](r, tier));
  const newest = rows.reduce((a, b) => (!a || b.ts > a.ts ? b : a), null);
  const longest = why === "waited" ? rows.reduce((a, b) => (!a || silenceOf(b, tier) > silenceOf(a, tier) ? b : a), null) : null;
  return { head, n: exact ? answer.count ?? 0 : rows.length, newest, longest, silence: longest ? silenceOf(longest, tier) : 0 };
}
/** The most requests a command held at once today, and when: each request held a slot from the start of its sending (its
 *  end minus its time, plus its wait in line) to its end. A read at the cap is not a read of the whole day, so it
 *  claims no peak. */
async function readPeak(head) {
  const res = await API.get(`/api/perf/turns?${new URLSearchParams({ head: head.key, since: String(startOfToday()), time_zone: ZONE, n: String(MAX_ROWS), local: "0" })}`);
  const answer = (res.body?.heads || []).find((x) => x.key === head.key);
  if (!res.ok || !answer || answer.error || answer.read_error) return { head, err: answer?.error || answer?.read_error || `splice answered ${res.status}` };
  const rows = answer.rows || [];
  if (answer.truncated || (answer.count ?? 0) > rows.length) return { head, err: `more than ${fmt(MAX_ROWS)} requests today`, n: answer.count };
  const events = rows.flatMap((r) => [[r.ts - (r.total ?? 0) + (r.admit_wait_ms || 0), 1], [r.ts, -1]]).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let n = 0, peak = 0, at = 0;
  for (const [t, d] of events) { n += d; if (n > peak) { peak = n; at = t; } }
  return { head, peak, at, n: rows.length, queued: rows.filter((r) => r.admit_wait_ms).length };
}
async function readWeek() {
  ui.peaks = await Promise.all(ui.headRows.map(readPeak));
  const day = startOfToday(), week = Date.now() - 7 * DAY;
  const plan = [["restarted", week], ["gaveup", week], ["waited", week], ["silentresume", week], ["silentover", week], ["overloaded", day], ["queued", day], ["failed", week]];
  ui.week = {};
  const results = await Promise.all(plan.map(([why, from]) => Promise.all(ui.headRows.map((h) => countWeek(h, why, from)))));
  plan.forEach(([why], i) => { ui.week[why] = results[i]; });
}
/** A word's figure across every command: the total, or null when any command's read did not answer. */
const weekTotal = (why) => { const per = ui.week?.[why]; return per && per.every((p) => !p.err) ? per.reduce((s, p) => s + p.n, 0) : null; };
const doorTo = (url, what, tail, chip) => `<a class="door" href="${url}">${SV.open}<span>${what}</span>${tail}${chip ? cmdChip(chip) : ""}<em>Open it</em></a>`;
const clockOf = (ts) => clock(new Date(ts));
const msLong = (ms) => (ms >= MIN ? `${Math.floor(ms / MIN)} min${ms % MIN ? ` ${Math.round((ms % MIN) / SEC)} s` : ""}` : `${Math.round(ms / SEC)} s`);
/** One figure: how many, opening them all on Requests, then each command's share; a count splice could not read says so. */
function figHtml({ why, what, door, cmds = true }) {
  const per = ui.week?.[why] ?? [], total = weekTotal(why), from = why === "overloaded" || why === "queued" ? { from: String(startOfToday()) } : { win: "7d" };
  if (total === null) return `<div class="fig3 none"><span class="big">?</span><span class="what">${esc(what)}</span><span class="state limit">${esc(per.find((p) => p.err)?.err ?? "splice did not answer")}</span></div>`;
  if (!total) return `<div class="fig3 none"><span class="big">0</span><span class="what">${esc(what)}</span></div>`;
  const ask = why === "failed" ? { outcome: "fail" } : { why };
  const shares = per.filter((p) => p.n).sort((a, b) => b.n - a.n);
  return `<div class="fig3"><a class="lead" href="${reqUrl({ ...from, ...ask })}"><span class="big">${fmt(total)}</span><span class="what">${esc(what)}</span><em>${SV.open}Open on Requests</em></a>`
    + (cmds ? `<div class="cdoors">${shares.map((p) => `<a class="cdoor" href="${reqUrl({ ...from, ...ask, cmd: p.head.command })}">${cmdChip(p.head.key)}<b>${fmt(p.n)}</b></a>`).join("")}</div>` : "")
    + (door ? door(shares) : "") + `</div>`;
}
const latestDoor = (shares) => {
  const best = shares.filter((p) => p.newest).sort((a, b) => b.newest.ts - a.newest.ts)[0];
  return best ? doorTo(`requests.html?win=7d#${encodeURIComponent(rowId(best.head.key, best.newest))}`, "Latest", `<b>${clockOf(best.newest.ts)}</b>`, best.head.key) : "";
};
const longestDoor = (shares) => {
  const best = shares.filter((p) => p.longest).sort((a, b) => b.silence - a.silence)[0];
  return best ? doorTo(`requests.html?win=7d#${encodeURIComponent(rowId(best.head.key, best.longest))}`, "Longest silence", `<b>${msLong(best.silence)}</b>`, best.head.key) : "";
};
const groupHtml = (label, figs) => `<div class="fgroup"><span class="glabel">${label}</span><div class="figs3">${figs.map(figHtml).join("")}</div></div>`;
const tallyHtml = (title, groups) => `<section class="tally"><h3>${title}</h3><div class="fgroups">${groups.join("")}</div></section>`;
const silentHtml = () => tallyHtml("This week", [
  groupHtml("Ended early", [
    { why: "restarted", what: WHY.restarted.word, door: latestDoor },
    { why: "gaveup", what: WHY.gaveup.word, door: latestDoor },
  ].sort((a, b) => (weekTotal(b.why) ?? 0) - (weekTotal(a.why) ?? 0))),
  groupHtml("Kept going", [
    { why: "waited", what: WHY.waited.word, door: longestDoor },
    { why: "silentresume", what: WHY.silentresume.word.replace(" after a silence", "") },
    { why: "silentover", what: WHY.silentover.word.replace(" after a silence", "") },
  ]),
]) + `<section class="sub">${form(keysOf("silent"))}</section>`;
const limitOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.maxInflight ?? val("maxInflight")) || 0;
function peaksHtml() {
  if (!ui.peaks?.length) return "";
  const rows = [...ui.peaks].sort((a, b) => (b.peak ?? -1) - (a.peak ?? -1));
  const row = (p) => {
    const key = p.head.key, lim = limitOf(key);
    if (p.err) return `<div class="peak">${cmdChip(key)}<span></span><span class="pnum"><span class="state limit">${esc(p.err)}</span></span><span></span></div>`;
    return `<div class="peak${lim && p.peak >= lim ? " full" : ""}">${cmdChip(key)}`
      + `<span class="pbar"><b style="width:${lim ? Math.min(100, (100 * p.peak) / lim) : 4}%"></b></span>`
      + `<span class="pnum"><b>${p.peak}</b> of ${lim || "Unlimited"} requests at once${p.peak ? `<i>${clockOf(p.at)}</i>` : ""}</span>`
      + (p.queued ? `<a class="door" href="${reqUrl({ from: String(startOfToday()), why: "queued", cmd: p.head.command })}">${SV.open}<span>Waited in line</span><b>${p.queued}</b><em>Open on Requests</em></a>` : "<span></span>") + `</div>`;
  };
  const waited = rows.reduce((a, p) => a + (p.queued || 0), 0);
  // the header door adds something only when it spans commands; with one command waiting, that command's own row door is the same list
  const doors = rows.filter((p) => p.queued).length;
  return `<section class="tally"><h3>Today</h3><div class="card peaks">${doors > 1 ? `<header><a class="door" href="${reqUrl({ from: String(startOfToday()), why: "queued" })}">${SV.open}<span>Waited in line</span><b>${fmt(waited)}</b><em>Open on Requests</em></a></header>` : ""}${rows.map(row).join("")}</div></section>`;
}
const busyHtml = () => `<section class="tally"><h3>Who refused</h3>${figHtml({ why: "overloaded", what: "Overloaded today" })}`
  + (keysOf("busy", ["maxInflight", "maxQueued", "maxRequestBytes"]).length ? `<div class="fix">${form(keysOf("busy", ["maxInflight", "maxQueued", "maxRequestBytes"]))}</div>` : "") + `</section>`
  + peaksHtml()
  + `<section class="sub"><h3>splice's limit</h3>${form(["maxInflight", "maxQueued"])}</section>`
  + (keysOf("busy").includes("maxRequestBytes") ? `<section class="sub"><h3>Too large</h3>${form(["maxRequestBytes"])}</section>` : "")
  + `<div class="doors">${figHtml({ why: "failed", what: "Failed this week", cmds: false })}</div>`;

// ---------- Version ----------
// Running, Newest and Go back, from GET /api/upgrade. The route never fetches, so Newest is a version only when a check
// has succeeded on this daemon; otherwise it is the releases page, one tap away. Go back is POST /api/upgrade {rollback}.
const RELEASES = "https://github.com/torad-labs/splice/releases";
const runWord = { running: "Going back to", succeeded: "Back on", failed: "Not back on", lost: "Not back on" };
function versionHtml() {
  const v = ui.ver;
  if (!v) return `<span class="state limit">splice did not answer</span>`;
  const back = v.rollback_target, run = ui.run, going = run && run.state === "running";
  const rows = `<div class="form vform"><div class="lbl">Running</div><div class="ctl"><b class="v">${esc(v.installed)}</b></div>`
    + `<div class="lbl">Newest</div><div class="ctl">${v.latest ? `<b class="v${v.latest !== v.installed ? " new" : ""}">${esc(v.latest)}</b>` : ""}`
    + `<a class="act quiet small" href="${RELEASES}" target="_blank" rel="noopener">${SV.open}See releases</a></div>`
    + (back ? `<div class="lbl">Previous</div><div class="ctl"><b class="v">${esc(back)}</b>${ui.armed === "rollback"
      ? `<button class="act primary small" data-act="rollback">Go back to ${esc(back)}</button><button class="act quiet small" data-act="disarm">Stay on ${esc(v.installed)}</button>`
      : `<button class="act quiet small" data-act="arm" data-id="rollback"${going ? " disabled" : ""}>Go back to ${esc(back)}</button>`}</div>` : "")
    + `</div>`;
  const ran = run ? `<section class="run"><header><h3>${runWord[run.state] ?? "Going back to"} ${esc(run.to ?? back ?? "")}</h3></header>${run.output?.length ? `<ol class="rlog">${run.output.map((l) => `<li>${esc(l)}</li>`).join("")}</ol>` : ""}</section>` : "";
  return rows + ran;
}
async function goBack() {
  const to = ui.ver?.rollback_target;
  const res = await API.post("/api/upgrade", { rollback: true });
  ui.run = res.ok ? { state: "running", to, output: [] } : { state: "failed", to, output: [res.body?.error || `splice answered ${res.status}`] };
  render();
  if (res.ok) pollRun(to);
}
function pollRun(to) {
  setTimeout(async () => {
    const r = await API.get("/api/upgrade/run"), run = r.body?.run;
    if (run) ui.run = { state: run.state, to, output: run.output || [] };
    if (!run || run.state === "running") { render(); pollRun(to); return; }
    ui.ver = (await API.get("/api/upgrade")).body ?? ui.ver;
    render();
  }, 2000);
}

const PANES = {
  busy: busyHtml,
  silent: silentHtml,
  reasoning: () => `<section class="sub">${form(keysOf("reasoning"))}</section>`,
  plan: () => `<section class="sub">${form(keysOf("plan"))}</section>`,
  mcp: () => `<section class="sub">${form(keysOf("mcp"))}</section>`,
  data: dataHtml,
  version: versionHtml,
};

// ---------- the rail ----------
function summary(id) {
  if (id === "silent") {
    const early = ["restarted", "gaveup"].map(weekTotal), waited = weekTotal("waited");
    return early.includes(null) || waited === null ? [`Asks after ${word(KNOB.firstByteTimeoutMs, val("firstByteTimeoutMs"))}`]
      : [`${early[0] + early[1]} ended early this week`, `${waited} waited out`];
  }
  if (id === "version") return [ui.ver ? ui.ver.installed : "Not read"];
  if (id === "reasoning") return [KNOB.showReasoning && !needsRestart("showReasoning") ? `Shows it ${word(KNOB.showReasoning, val("showReasoning")).toLowerCase()}` : `Progress line ${val("progressLine") ? "on" : "off"}`];
  if (id === "plan") return [`Warns at ${word(KNOB.usageWarnPct, val("usageWarnPct"))}`];
  if (id === "mcp") return [`${word(KNOB.mcpMaxServers, val("mcpMaxServers"))} at most`];
  if (id === "busy" && weekTotal("overloaded") !== null && weekTotal("queued") !== null) return [`${weekTotal("overloaded")} overloaded today`, `${weekTotal("queued")} waited in line`];
  if (id === "busy") return [`${word(KNOB.maxInflight, val("maxInflight"))} at once`, `${word(KNOB.maxQueued, val("maxQueued"))} waiting`];
  const h = DATA_ROWS.filter((r) => !r.noRoute).map(heldOf), tag = DATA_ROWS.some((r) => r.noRoute) ? "counted" : "kept"; // a total that leaves a store out says counted
  return h.some((x) => x === null) ? ["Some stores did not answer"] : [`${mbWord(h.reduce((s, x) => s + x.bytes, 0))} ${tag}`];
}
function cardHtml(x) {
  const f = found(x.id), s = summary(x.id);
  return `<article class="card job" data-open="${x.id}" aria-current="${ui.open === x.id}">`
    + `<span class="lamp idle sock" aria-hidden="true">${SV[x.id]}</span>`
    + `<div class="top"><span class="jname">${hl(x.name)}</span></div>`
    + `<div class="meta">${s.map((w) => `<span>${esc(w)}</span>`).join("")}</div>`
    + (f.length ? `<div class="hits">${f.slice(0, 4).map((w) => `<span>${hl(w)}</span>`).join("")}${f.length > 4 ? `<span class="more">${f.length - 4} more</span>` : ""}</div>` : "")
    + `</article>`;
}

// ---------- the page ----------
const root = document.getElementById("st");
function render() {
  tick(); // times read in the person's present
  if (ui.err) { root.innerHTML = `<div class="list rail empty"><div class="nomatch"><span class="state limit">${esc(ui.err)}</span><button class="act quiet" data-act="again">Read again</button></div></div>`; return; }
  const shown = words().length ? jobs().filter((x) => found(x.id).length) : jobs();
  if (shown.length && !shown.some((x) => x.id === ui.open)) ui.open = shown[0].id;
  const x = TOPIC[ui.open], s = summary(x.id);
  const list = shown.length ? `<div class="list rail">${shown.map(cardHtml).join("")}</div>`
    : `<div class="list rail empty"><div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clear">Clear</button></div></div>`;
  const pane = shown.length ? `<div class="pane"><header><span class="lamp idle sock" aria-hidden="true">${SV[x.id]}</span><div class="who"><div class="top"><span class="name">${esc(x.name)}</span></div>`
    + `<div class="meta">${s.map((w) => `<span>${esc(w)}</span>`).join("")}</div></div>`
    + `<div class="acts"><button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button></div></header><div class="body kn">${PANES[x.id]()}</div></div>` : "";
  const was = root.querySelector(".pane .body")?.scrollTop ?? 0;
  root.innerHTML = `<div class="jobs${ui.shown ? " open" : ""}">${list}${pane}</div>`;
  if (was) { const b = root.querySelector(".pane .body"); if (b) b.scrollTop = was; }
  KNOBS.focusCustom(root);
  root.querySelector(".pane .menu")?.scrollIntoView({ block: "nearest" });
}

// A shorter history window that would delete turns waits for the yes; the daemon says what the cut would take.
ks.onAsk = (id, days) => {
  ui.ask = { v: days, h: null };
  API.get(`/api/history?days=${days === null ? "forever" : days}`).then(async (r) => {
    if (!ui.ask || ui.ask.v !== days) return;
    if (!r.ok || !r.body) { ui.ask = null; ks.bad[id] = "Not saved"; render(); return; }
    if (!r.body.cut || !r.body.cut.turns) { ui.ask = null; await KNOBS.patch(id, days, true); await refresh(); return; } // nothing held goes: it applies at once
    ui.ask.h = r.body; render();
  });
  delete ks.said[id];
  return true;
};
async function shorten() {
  const a = ui.ask; if (!a || !a.h) return;
  ui.ask = null;
  const r = await API.put("/api/history", { days: a.v === null ? "forever" : a.v, delete_before_epoch_ms: a.h.cut.cutoff_epoch_ms });
  if (r.ok) { ks.said.historyRetentionDays = "Applied"; delete ks.bad.historyRetentionDays; } else { ks.bad.historyRetentionDays = "Not saved"; delete ks.said.historyRetentionDays; }
  await refresh();
}
/** One command's prompt saving, on or off: the daemon takes it on that command's next request, and the row says so. */
async function setCapture(head, on) {
  const id = `capture@${head}`;
  delete ui.bad[id];
  const res = await API.put(`/api/heads/${encodeURIComponent(head)}/capture`, { enabled: on });
  if (!res.ok) { ui.bad[id] = true; ui.said[id] = "Not saved"; render(); return; }
  ui.capture[head] = on;
  ui.said[id] = res.body?.restart_required ? "Saves after splice restarts" : "Applied";
  render();
}
async function remove(id) {
  const key = id.slice(4), bad = () => { ui.bad[id] = "Not deleted"; };
  const store = DATA_ROWS.find((r) => r.id === key)?.store;
  const paths = key === "trace" ? ui.heads.map((h) => `/api/heads/${encodeURIComponent(h)}/trace/kept`) : [store ? `/api/kept/stores/${store}` : EP[key]];
  const answers = await Promise.all(paths.map((p) => API.del(p)));
  if (answers.some((a) => !a.ok)) bad(); else delete ui.bad[id];
  await refresh();
}
async function refresh() {
  const err = await KNOBS.load();
  if (err) ui.err = err; else { ui.err = null; await readKept(); }
  render();
}

KNOBS.wire(root, render);
document.getElementById("q").addEventListener("input", (e) => { ui.q = e.target.value; ks.menu = null; ks.custom = null; render(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && (ui.armed || ui.ask)) { ui.armed = null; ui.ask = null; render(); } });
document.addEventListener("click", (e) => {
  if (KNOBS.click(e, render)) { render(); return; }
  const t = e.target.closest("[data-act], [data-open]");
  if (ui.armed && !e.target.closest("[data-act=delete], [data-act=arm]")) { ui.armed = null; if (!t) { render(); return; } }
  if (!t) return;
  const d = t.dataset;
  if (d.open && !d.act) { ui.open = d.open; ui.shown = true; ui.armed = null; ks.custom = null; render(); return; }
  switch (d.act) {
    case "theme": document.documentElement.dataset.theme = document.documentElement.dataset.theme === "day" ? "night" : "day"; return;
    case "close": ui.shown = false; break;
    case "clear": ui.q = ""; document.getElementById("q").value = ""; break;
    case "again": refresh(); return;
    case "capture": setCapture(d.head, d.v === "true"); return;
    case "saving": ui.saving = ui.saving === "trace" ? null : "trace"; break;
    case "arm": ui.armed = d.id; delete ui.bad[d.id]; break;
    case "disarm": ui.armed = null; break;
    case "rollback": ui.armed = null; goBack(); return;
    case "delete": ui.armed = null; render(); remove(d.id); return;
    case "shorten": shorten(); return;
    case "unask": ui.ask = null; break;
    default: return;
  }
  render();
});
if (TOPIC[location.hash.slice(1)]) { ui.open = location.hash.slice(1); ui.shown = true; }
refresh();
