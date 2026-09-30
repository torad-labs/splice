import { icon } from '../shell.mjs';
export const nav = 'usage';

const PLANS = [
  { m: 'claude', name: 'Claude', used: 62, note: 'about 3 days left at this pace', spark: [3, 5, 4, 8, 9, 7, 10, 6, 8, 12, 9, 7], cost: '$96.10', cache: 86, tokens: '412M' },
  { m: 'gpt', name: 'ChatGPT', used: 100, note: 'out until Oct 5, 2:13 PM', spark: [2, 3, 6, 5, 9, 12, 10, 8, 4, 2, 1, 0], cost: '$41.50', cache: 71, tokens: '188M' },
  { m: 'kimi', name: 'Kimi', used: 82, note: 'about 9 hours left at this pace', spark: [1, 1, 2, 3, 2, 4, 6, 8, 9, 7, 5, 6], cost: '$22.30', cache: 52, tokens: '74M' },
  { m: 'grok', name: 'Grok', used: 34, note: 'plenty left', spark: [1, 2, 2, 3, 2, 3, 4, 3, 5, 4, 3, 3], cost: '$24.10', cache: 64, tokens: '91M' },
  { m: 'muse', name: 'Muse', used: 0, note: 'idle for a week', spark: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0], cost: '$0.20', cache: 0, tokens: '1M' },
];
const spark = (v, m) => { const mx = Math.max(...v, 1); return `<svg viewBox="0 0 120 32" width="120" height="32" role="img" aria-label="turns per hour">${v.map((n, i) => `<rect x="${i * 10 + 1}" y="${32 - (n / mx) * 30 - 1}" width="8" height="${(n / mx) * 30 + 1}" rx="2" fill="var(--${m})" opacity=".85"/>`).join('')}</svg>`; };

export const css = `
.section { margin-top: 96px; }
.section:first-of-type { margin-top: 0; }
.section > h2 { font: 520 26px/1.15 var(--display); color: var(--ink); letter-spacing: -.01em; }
.section > .why { margin-top: 8px; font: 400 16px/1.5 var(--read); color: var(--mute); max-width: 70ch; }
.totals { margin-top: 40px; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 56px; max-width: 1200px; }
.totals .n { font: 500 56px/1 var(--display); color: var(--ink); letter-spacing: -.03em; }
.totals h3 { margin-top: 12px; font: 600 17px var(--meta); color: var(--ink); }
.totals p { margin-top: 4px; font: 400 15px/1.45 var(--read); color: var(--mute); max-width: 26ch; }
.plans { margin-top: 44px; display: grid; }
.plan { display: grid; grid-template-columns: 200px minmax(240px, 1fr) 150px 130px 120px; gap: 40px; align-items: center; padding: 28px 0; border-top: 2px solid var(--hair); }
.plan:first-child { border-top: 0; }
.plan b { display: flex; align-items: center; gap: 12px; font: 600 19px var(--meta); color: var(--ink); }
.plan b i { width: 14px; height: 14px; border-radius: 50%; background: var(--c); }
.use { display: grid; gap: 9px; }
.use .track { height: 12px; border-radius: 6px; background: color-mix(in srgb, var(--ink) 7%, transparent); overflow: hidden; }
.use .track i { display: block; height: 100%; background: var(--c); border-radius: 6px; }
.use .track.full i { background: repeating-linear-gradient(135deg, var(--c) 0 8px, color-mix(in srgb, var(--c) 45%, transparent) 8px 16px); }
.use span { font: 500 15px var(--meta); color: var(--body); }
.use span b { font-weight: 650; color: var(--ink); }
.plan .tok { font: 500 15px var(--meta); color: var(--mute); }
.plan .tok b { display: block; font: 500 22px var(--display); color: var(--ink); }
.plan .cost { font: 500 22px var(--display); color: var(--ink); text-align: right; }
.plan .cost small { display: block; font: 500 14px var(--meta); color: var(--mute); }
.rows { margin-top: 36px; display: grid; max-width: 900px; }
.row { display: grid; grid-template-columns: 220px minmax(0, 1fr) auto; gap: 32px; align-items: center; padding: 22px 0; border-top: 2px solid var(--hair); }
.row:first-child { border-top: 0; }
.row b { font: 600 18px var(--meta); color: var(--ink); }
.row span { font: 400 16px/1.5 var(--read); color: var(--body); }
.onoff { font: 600 15px var(--meta); color: var(--ink); display: inline-flex; align-items: center; gap: 8px; }
.onoff i { width: 34px; height: 20px; border-radius: 10px; background: var(--ok); position: relative; }
.onoff i::after { content: ''; position: absolute; right: 3px; top: 3px; width: 14px; height: 14px; border-radius: 50%; background: var(--obj); }
`;

export function body() {
  const plans = PLANS.map((p) => `<div class="plan" style="--c:var(--${p.m})"><b><i></i>${p.name}</b>
<div class="use"><div class="track${p.used >= 100 ? ' full' : ''}" role="img" aria-label="${p.used}% of its limit"><i style="width:${p.used}%"></i></div><span><b>${p.used}%</b> of its limit · ${p.note}</span></div>
<div class="tok"><b>${p.tokens}</b>tokens read in</div>${spark(p.spark, p.m)}
<div class="cost">${p.cost}<small>${p.cache ? p.cache + '% cached' : 'no cache'}</small></div></div>`).join('');
  return `<header class="page-head"><div><h1>Usage</h1>
<p class="lede">About $184 of API-equivalent in the last 7 days. ChatGPT is out of quota and Kimi will be in about nine hours.</p></div>
<div class="tools"><span class="seg" role="group" aria-label="Window"><button aria-pressed="false">24 hours</button><button aria-pressed="true">7 days</button><button aria-pressed="false">30 days</button></span></div></header>
<section class="section"><div class="totals">
<div><div class="n">3,204</div><h3>Turns</h3><p>Across five plans in the last 7 days.</p></div>
<div><div class="n">766M</div><h3>Tokens read in</h3><p>68% came from the cache. Plans still count those.</p></div>
<div><div class="n">14M</div><h3>Tokens written out</h3><p>Answers, tools and thinking.</p></div>
<div><div class="n">$184</div><h3>API cost, estimated</h3><p>At each model’s public prices. 41 turns left out: no price.</p></div></div></section>
<section class="section"><h2>Each plan against its limit</h2><p class="why">The limit is the provider’s own. The pace is the last day’s, so it moves when you do.</p><div class="plans">${plans}</div></section>
<section class="section"><h2>Budgets</h2><p class="why">A budget is your own daily ceiling in dollars. Splice tells you, or stops the turn, when a plan reaches it.</p>
<div class="rows"><div class="row"><b>Kimi</b><span>$20 a day. $7.40 spent in the last 24 hours. Splice stops turns once it is reached.</span><button class="btn sm">Change</button></div>
<div class="row"><span></span><span></span><button class="btn sm">Add a budget</button></div></div></section>
<section class="section"><h2>Alerts</h2><p class="why">Where splice sends a message when a plan nears a limit or a budget.</p>
<div class="rows"><div class="row"><b>Desktop notifications</b><span>A notification on this computer.</span><span class="onoff"><i></i>On</span></div>
<div class="row"><b>Webhook</b><span>https://hooks.example.com/…9f2a</span><button class="btn sm">Save the webhook</button></div>
<div class="row"><b>Send a test</b><span>Posts to the saved webhook and tells you what it said.</span><button class="btn sm">Send a test</button></div></div></section>`;
}
