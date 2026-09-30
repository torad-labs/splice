import { icon, models } from '../shell.mjs';

const S = [
  { m: 'gpt', title: 'Review the rate limiter diff', repo: 'tally', branch: 'rate-limit', state: 'wait', say: ['Waiting for your answer','Run npm run migrate -- --apply?'], age: 'asked 2 min ago', hand: 'from claude-splice' },
  { m: 'kimi', title: 'Migrate the billing tables', repo: 'ledger-api', branch: 'billing-v2', state: 'stuck', say: ['Quiet for 14 min','Run psql ledger < 0042.sql — no result yet'], age: 'since 3:12 PM', hand: '' },
  { m: 'claude', title: 'Add a rate limiter to the API', repo: 'tally', branch: 'rate-limit', state: 'work', say: ['Reading src/server.js','Splitting the work between three sessions'], age: '42 min', hand: 'lead of 3' },
  { m: 'grok', title: 'Write the rate limiter', repo: 'tally', branch: 'rate-limit', state: 'work', say: ['Editing src/rateLimit.js','createRateLimiter(db, { limit, windowMs })'], age: '31 min', hand: 'from claude-splice' },
  { m: 'muse', title: 'Write the tests', repo: 'tally', branch: 'rate-limit', state: 'work', say: ['Running npm test','18 passing, 0 failing so far'], age: '29 min', hand: 'from claude-splice' },
  { m: 'claude', title: 'Tidy the changelog', repo: 'harbor-web', branch: 'main', state: 'idle', say: ['Finished — 6 files changed','Waiting for your next message'], age: 'idle 3 h', hand: '' },
  { m: 'gpt', title: 'Explain the auth flow', repo: 'harbor-web', branch: 'main', state: 'idle', say: ['Finished — answered in 2 messages','Waiting for your next message'], age: 'idle 1 d', hand: '' },
];
const WORD = { work: 'Working', wait: 'Waiting on you', stuck: 'Stuck', idle: 'Idle' };

const card = (s, compact = false) => {
  const { cmd, model } = models[s.m];
  const attn = s.state === 'wait' || s.state === 'stuck';
  const actions = s.state === 'wait'
    ? `<div class="acts"><button class="btn go sm">Open the session</button><button class="btn quiet sm">Copy resume command</button></div>`
    : s.state === 'stuck'
      ? `<div class="acts"><button class="btn go sm">Stop the turn</button><button class="btn quiet sm">Copy resume command</button></div>` : '';
  if (compact) {
    return `<article class="win ${s.m}${attn ? ' attn' : ''}">
  <div class="bar"><h3>${s.title}</h3><span class="state ${s.state}"><i></i>${WORD[s.state]}</span></div>
  <div class="glass one"><p${s.state === 'work' ? ' class="cur"' : ''}>${s.say[1]}</p></div>
  <div class="quiet-meta"><span>${s.repo}</span><span>${s.branch}</span><span>${cmd}</span><span>${model}</span></div>
  ${s.state === 'wait' ? `<div class="acts"><button class="btn go sm">Open the session</button></div>` : s.state === 'stuck' ? `<div class="acts"><button class="btn go sm">Stop the turn</button></div>` : ''}</article>`;
  }
  return `<article class="win ${s.m}${attn ? ' attn' : ''}">
  <div class="bar"><span class="grip" aria-hidden="true">${icon.grip}</span><h3>${s.title}</h3><span class="state ${s.state}"><i></i>${WORD[s.state]}</span></div>
  <div class="meta"><span class="tag">${s.repo}</span><span class="tag">${s.branch}</span>${s.hand ? `<span class="hand">${icon.arrow}<b>${s.hand}</b></span>` : ''}<span class="age">${s.age}</span></div>
  <div class="glass"><p class="dim">${s.say[0]}</p><p${s.state === 'work' ? ' class="cur"' : ''}>${s.say[1]}</p></div>
  <div class="strip" style="--m:var(--${s.m})"><span class="model m-${s.m}">${cmd}</span><span class="sp">${model}</span></div>
  ${actions}</article>`;
};

export const css = `
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(460px, 1fr)); gap: 34px 38px; padding-right: 10px; }
.meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 0 16px 10px; }
.meta .age { margin-left: auto; font: 500 12px var(--mono); color: var(--mute); }
.acts { display: flex; gap: 10px; padding: 0 16px 16px; margin-top: -2px; }
.grid.first { grid-template-columns: repeat(auto-fill, minmax(520px, 1fr)); }
.idle-row { display: grid; grid-template-columns: repeat(auto-fill, minmax(460px, 1fr)); gap: 34px 38px; padding-right: 10px; }
.idle-row .win { opacity: .92; }
.pinned { display: inline-flex; align-items: center; gap: 8px; font: 500 12px var(--mono); color: var(--mute); }
`;

export function body(opts = {}) {
  const c = opts.compact === true;
  const need = S.filter((s) => s.state === 'wait' || s.state === 'stuck');
  const work = S.filter((s) => s.state === 'work');
  const idle = S.filter((s) => s.state === 'idle');
  return `<header class="page-head"><div><h1>Sessions</h1>
<p class="lede">Three are working, one is waiting on you, one is stuck. Two finished earlier.</p></div>
<div class="tools"><span class="seg" role="group" aria-label="Group by"><button aria-pressed="true">State</button><button aria-pressed="false">Repo</button><button aria-pressed="false">Model</button></span>
<span class="search">${icon.search}Find a session<span class="kbd">⌘K</span></span></div></header>
<div class="group-head"><h2>Needs you</h2><span class="n">2</span><span class="why">A person has to act; they stay on top until they do.</span></div>
<div class="grid first">${need.map((s) => card(s, c)).join('')}</div>
<div class="group-head"><h2>Working</h2><span class="n">3</span><span class="why">Drag to reorder; the order is yours.</span></div>
<div class="grid">${work.map((s) => card(s, c)).join('')}</div>
<div class="group-head"><h2>Idle</h2><span class="n">2</span><span class="why">Finished; a message wakes them.</span></div>
<div class="idle-row">${idle.map((s) => card(s, c)).join('')}</div>`;
}
