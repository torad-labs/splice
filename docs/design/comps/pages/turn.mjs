import { icon } from '../shell.mjs';
export const nav = 'turns';

const STAGES = [
  ['Prepare', 0.3, 'ingest', 'Splice reading and shaping the request'],
  ['Waiting in line', 2.1, 'queue', 'The plan’s limit on turns at once'],
  ['Provider thinking', 3.4, 'upstream', 'From sending to the first word back'],
  ['Streaming', 8.4, 'stream', 'The answer arriving'],
];
const TOTAL = STAGES.reduce((a, s) => a + s[1], 0);

export const css = `
.crumb { display: flex; gap: 12px; margin-bottom: 26px; font: 600 15px var(--meta); color: var(--mute); }
.crumb a { color: var(--ink); text-decoration: underline; text-underline-offset: 4px; text-decoration-thickness: 2px; text-decoration-color: var(--hair); }
.hero { max-width: 1080px; }
.hero .facts { margin-top: 22px; display: flex; flex-wrap: wrap; gap: 8px 0; font: 500 15px var(--meta); color: var(--mute); }
.hero .facts span + span::before { content: '·'; margin: 0 10px; color: var(--faint); }
.hero .facts .tag { margin-left: 10px; } .hero .facts .tag::before { content: none !important; }
.section { margin-top: 84px; max-width: 1080px; }
.section > h2 { font: 520 26px/1.15 var(--display); color: var(--ink); }
.section > .why { margin-top: 8px; font: 400 16px/1.5 var(--read); color: var(--mute); }
.water { margin-top: 36px; display: flex; height: 44px; gap: 3px; }
.water i { display: block; height: 100%; border-radius: 6px; background: color-mix(in srgb, var(--claude) var(--s), transparent); }
.water i:first-child { border-radius: 22px 6px 6px 22px; } .water i:last-child { border-radius: 6px 22px 22px 6px; }
.legend { margin-top: 26px; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 40px; }
.legend div b { display: flex; align-items: center; gap: 10px; font: 600 16px var(--meta); color: var(--ink); }
.legend div b i { width: 14px; height: 14px; border-radius: 4px; background: color-mix(in srgb, var(--claude) var(--s), transparent); }
.legend .v { margin-top: 6px; font: 500 30px var(--display); color: var(--ink); letter-spacing: -.02em; }
.legend p { margin-top: 4px; font: 400 15px/1.45 var(--read); color: var(--mute); }
.figs { margin-top: 32px; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 40px; }
.figs .n { font: 500 40px/1 var(--display); color: var(--ink); letter-spacing: -.025em; }
.figs h3 { margin-top: 8px; font: 600 16px var(--meta); color: var(--ink); }
.figs p { margin-top: 4px; font: 400 15px/1.45 var(--read); color: var(--mute); }
.tabs { margin-top: 84px; display: flex; gap: 30px; border-bottom: 2px solid var(--hair); max-width: 1080px; }
.tabs a { padding: 0 0 14px; font: 600 17px var(--meta); color: var(--mute); }
.tabs a[aria-current] { color: var(--ink); box-shadow: 0 3px 0 var(--ink); }
.qa { margin-top: 40px; max-width: 760px; display: grid; gap: 40px; }
.qa .who { font: 600 14px var(--meta); color: var(--mute); margin-bottom: 10px; }
.qa .you { background: color-mix(in srgb, var(--ink) 6%, transparent); border-radius: 12px; padding: 20px 24px; font: 400 18px/1.6 var(--read); color: var(--ink); }
.qa .prose { font: 400 18px/1.75 var(--read); color: var(--body); }
`;

export function body() {
  const w = STAGES.map(([, ms], i) => `<i style="flex:${ms};--s:${95 - i * 15}%" title="${ms} s"></i>`).join('');
  const legend = STAGES.map(([n, ms, , d], i) => `<div><b><i style="--s:${95 - i * 15}%"></i>${n}</b><div class="v">${ms} s</div><p>${d}</p></div>`).join('');
  return `<div class="crumb"><a href="#">Turns</a><span>/</span><span>4:31 PM</span></div>
<header class="page-head hero"><div><h1>Add a rate limiter to the API</h1>
<p class="lede">Took ${TOTAL.toFixed(1)} seconds, and 2.1 of them were spent waiting in line behind another turn on Claude.</p>
<div class="facts"><span>claude-splice</span><span>opus-5.5</span><span>Ava’s Claude Max</span><span>Sep 29, 4:31 PM CT</span><span class="tag">Compacted</span></div></div>
<div class="tools"><button class="btn go">Open the session</button></div></header>
<section class="section" style="margin-top:0"><h2>Where the time went</h2><p class="why">Splice cannot say why the provider was slow, only where the time sat.</p>
<div class="water" role="img" aria-label="Time per stage">${w}</div><div class="legend">${legend}</div></section>
<section class="section"><h2>What it moved</h2><div class="figs">
<div><div class="n">48,210</div><h3>Read in</h3><p>46,000 of them came from the cache; the plan counts all of them.</p></div>
<div><div class="n">1,240</div><h3>Written out</h3><p>The answer, tools and thinking.</p></div>
<div><div class="n">$0.42</div><h3>API cost, estimated</h3><p>What this would cost at the model’s public prices.</p></div>
<div><div class="n">1</div><h3>Retry</h3><p>Splice tried again once before the first word came back.</p></div></div></section>
<nav class="tabs" aria-label="Turn"><a aria-current="page" href="#">Conversation</a><a href="#">Request</a><a href="#">Trace</a><a href="#">Wire</a></nav>
<div class="qa"><div><div class="who">You asked</div><div class="you">Add a rate limiter to the API. Use the existing db handle and keep the limit in config.</div></div>
<div><div class="who">Claude answered</div><div class="prose"><p>I added <code>createRateLimiter(db, { limit, windowMs })</code> in <code>src/rateLimit.js</code> and wired it into the server ahead of the routes.</p></div></div></div>`;
}
