import { icon } from '../shell.mjs';
export const nav = 'turns';

const LIVE = [
  { m: 'kimi', title: 'Migrate the billing tables', cmd: 'claude-kimi', say: 'Nothing from the plan for 14 minutes', tone: 'stuck', word: 'Stuck', age: '16 min' },
  { m: 'claude', title: 'Add a rate limiter to the API', cmd: 'claude-splice', say: 'Streaming its answer', tone: 'work', word: 'Working', age: '48 s' },
];
const PLANS = [
  { m: 'claude', name: 'Claude', turns: 118, failed: 0, p50: 1.4, p95: 3.9, cache: 86 },
  { m: 'gpt', name: 'ChatGPT', turns: 44, failed: 1, p50: 2.6, p95: 7.2, cache: 71 },
  { m: 'grok', name: 'Grok', turns: 31, failed: 0, p50: 1.1, p95: 2.8, cache: 64 },
  { m: 'kimi', name: 'Kimi', turns: 17, failed: 1, p50: 3.9, p95: 21.4, cache: 52 },
  { m: 'muse', name: 'Muse', turns: 2, failed: 0, p50: 2.2, p95: 2.9, cache: 0 },
];
const MAX = 22;
const TURNS = [
  { m: 'claude', t: '4:31 PM', title: 'Add a rate limiter to the API', st: 'done', word: 'Done', plan: 'Claude', model: 'opus-5.5', took: '14.2 s', inn: '48k', out: '1.2k', cost: '$0.42', tag: '' },
  { m: 'gpt', t: '4:29 PM', title: 'Review the rate limiter diff', st: 'done', word: 'Done', plan: 'ChatGPT', model: 'gpt-6-sol', took: '6.8 s', inn: '31k', out: '0.9k', cost: '$0.19', tag: '' },
  { m: 'kimi', t: '4:24 PM', title: 'Migrate the billing tables', st: 'fail', word: 'Failed', plan: 'Kimi', model: 'kimi-k3', took: '5 min', inn: '22k', out: '–', cost: '–', tag: '' },
  { m: 'claude', t: '4:18 PM', title: 'Add a rate limiter to the API', st: 'done', word: 'Done', plan: 'Claude', model: 'opus-5.5', took: '31.5 s', inn: '96k', out: '3.1k', cost: '$0.88', tag: 'Compacted' },
  { m: 'grok', t: '4:12 PM', title: 'Write the rate limiter', st: 'done', word: 'Done', plan: 'Grok', model: 'grok-4.7', took: '9.0 s', inn: '27k', out: '2.4k', cost: '$0.11', tag: '' },
  { m: 'gpt', t: '4:06 PM', title: 'Explain the auth flow', st: 'fail', word: 'Refused', plan: 'ChatGPT', model: 'gpt-6-sol', took: '0.4 s', inn: '–', out: '–', cost: '–', tag: 'Out of quota' },
];

export const css = `
.window { margin-left: auto; }
.section { margin-top: 96px; }
.section:first-of-type { margin-top: 0; }
.section > h2 { font: 520 26px/1.15 var(--display); color: var(--ink); letter-spacing: -.01em; }
.section > .why { margin-top: 8px; font: 400 16px/1.5 var(--read); color: var(--mute); }
.live { align-items: start; display: grid; grid-template-columns: repeat(auto-fill, minmax(460px, 1fr)); gap: 56px 72px; margin-top: 36px; padding-right: 24px; }
.live .win .bar { padding-bottom: 8px; }
.live .say { padding: 0 26px 22px; font: 400 17px/1.5 var(--read); color: var(--body); }
.live .say b { color: var(--ink); font-weight: 650; }
.plans { margin-top: 40px; display: grid; gap: 0; }
.plan { display: grid; grid-template-columns: 220px 90px 90px minmax(260px, 1fr) 110px; align-items: center; gap: 36px; padding: 26px 0; border-top: 2px solid var(--hair); }
.plan:first-child { border-top: 0; }
.plan.head { padding: 0 0 14px; font: 600 14px var(--meta); color: var(--mute); border-top: 0; }
.plan b { display: flex; align-items: center; gap: 12px; font: 600 19px var(--meta); color: var(--ink); }
.plan b i { width: 14px; height: 14px; border-radius: 50%; background: var(--c); }
.plan .n { font: 500 24px var(--display); color: var(--ink); }
.plan .n.bad { color: var(--stuck); }
.plan small { font: 500 15px var(--meta); color: var(--mute); }
.first { display: grid; gap: 8px; }
.first .bar2 { position: relative; height: 12px; border-radius: 6px; background: color-mix(in srgb, var(--ink) 7%, transparent); }
.first .bar2 i { position: absolute; inset: 0 auto 0 0; border-radius: 6px; background: color-mix(in srgb, var(--c) 30%, transparent); }
.first .bar2 u { position: absolute; inset: 0 auto 0 0; border-radius: 6px; background: var(--c); }
.first span { font: 500 15px var(--meta); color: var(--body); }
.first span b { font-weight: 650; color: var(--ink); }
.filters { display: flex; align-items: center; gap: 14px; margin-top: 36px; }
.list { margin-top: 28px; display: grid; }
.turn { display: grid; grid-template-columns: 90px minmax(0, 1fr) 210px 90px 120px 80px; gap: 28px; align-items: center; padding: 24px 0; border-top: 2px solid var(--hair); cursor: pointer; }
.turn:first-child { border-top: 0; }
.turn h3 { font: 560 19px/1.25 var(--display); color: var(--ink); }
.turn .sub { margin-top: 5px; font: 400 15px var(--meta); color: var(--mute); display: flex; gap: 12px; align-items: center; }
.turn .t, .turn .took, .turn .tok, .turn .cost { font: 500 16px var(--meta); color: var(--body); }
.turn .cost { text-align: right; color: var(--ink); }
.turn .tok small { display: block; color: var(--mute); font: 500 14px var(--meta); }
.turn.fail .took { color: var(--stuck); }
.foot { margin-top: 40px; font: 400 16px var(--read); color: var(--mute); }
`;

const bar = (p) => `<div class="first" style="--c:var(--${p.m})"><div class="bar2" role="img" aria-label="median ${p.p50} s, slowest twentieth ${p.p95} s"><i style="width:${(p.p95 / MAX) * 100}%"></i><u style="width:${(p.p50 / MAX) * 100}%"></u></div><span><b>${p.p50} s</b> typical · ${p.p95} s slowest</span></div>`;

export function body() {
  const live = LIVE.map((l) => `<article class="win ${l.m}${l.tone === 'stuck' ? ' attn' : ''}"><div class="bar"><h3>${l.title}</h3><span class="state ${l.tone}"><i></i>${l.word}</span></div>
<p class="say"><b>${l.cmd}</b> · ${l.say} · running ${l.age}</p></article>`).join('');
  const plans = PLANS.map((p) => `<div class="plan" style="--c:var(--${p.m})"><b><i></i>${p.name}</b><span class="n">${p.turns}</span><span class="n${p.failed ? ' bad' : ''}">${p.failed}</span>${bar(p)}<small>${p.cache ? p.cache + '% cached' : '–'}</small></div>`).join('');
  const list = TURNS.map((t) => `<div class="turn ${t.st}"><span class="t">${t.t}</span>
<div><h3>${t.title}</h3><div class="sub"><span>${t.plan} · ${t.model}</span>${t.tag ? `<span class="tag">${t.tag}</span>` : ''}</div></div>
<span class="state ${t.st === 'fail' ? 'stuck' : 'work'}"><i></i>${t.word}</span><span class="took">${t.took}</span><span class="tok">${t.inn} in<small>${t.out} out</small></span><span class="cost">${t.cost}</span></div>`).join('');
  return `<header class="page-head"><div><h1>Turns</h1>
<p class="lede">212 turns in the last hour. Two failed, and the typical first word came back in under two seconds.</p></div>
<div class="tools"><span class="seg" role="group" aria-label="Window"><button aria-pressed="true">1 hour</button><button aria-pressed="false">24 hours</button><button aria-pressed="false">7 days</button></span></div></header>
<section class="section"><h2>Running now</h2><p class="why">A turn stays here until its answer lands. One has been quiet for 14 minutes.</p><div class="live">${live}</div></section>
<section class="section"><h2>How each plan is answering</h2><p class="why">The dark end of each bar is the typical first word; the pale end is the slowest twentieth.</p>
<div class="plans"><div class="plan head"><span>Plan</span><span>Turns</span><span>Failed</span><span>First word</span><span></span></div>${plans}</div></section>
<section class="section"><h2>Landed</h2><p class="why">Open a turn to see where its time went, what was asked and what came back.</p>
<div class="filters"><span class="seg" role="group" aria-label="Show"><button aria-pressed="true">All</button><button aria-pressed="false">Failed</button><button aria-pressed="false">Compacted</button></span><span class="search">${icon.search}Find a turn</span></div>
<div class="list">${list}</div><p class="foot">Showing the newest 200 of 212. Narrow the window or filter to see the rest.</p></section>`;
}
