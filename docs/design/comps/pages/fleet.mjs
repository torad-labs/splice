import { icon } from '../shell.mjs';
export const nav = 'fleet';

const H = [
  { m: 'claude', cmd: 'claude-splice', model: 'opus-5.5', plan: 'Claude', acct: 'Ava’s Claude Max', state: 'ok', sessions: 1, w: [['5 hours', 41, 'resets 4:40 PM'], ['Week', 22, 'resets Fri']] },
  { m: 'grok', cmd: 'claude-grok', model: 'grok-4.7', plan: 'Grok', acct: 'Ava’s SuperGrok', state: 'ok', sessions: 1, w: [['30 days', 34, 'resets Oct 19']] },
  { m: 'gpt', cmd: 'claudex', model: 'gpt-6-sol', plan: 'ChatGPT', acct: 'Team pool · 2 accounts', state: 'quota', sessions: 2, w: [['5 hours', 100, 'out until Oct 5, 2:13 PM'], ['Week', 71, 'resets Sun']] },
  { m: 'kimi', cmd: 'claude-kimi', model: 'kimi-k3', plan: 'Kimi', acct: 'Ava’s Kimi', state: 'near', sessions: 1, w: [['5 hours', 82, 'resets 5:10 PM']] },
  { m: 'muse', cmd: 'claude-muse', model: 'muse-spark-1.3', plan: 'Muse', acct: 'Signed out', state: 'out', sessions: 1, w: [] },
  { m: 'local', cmd: 'claude-bonsai', model: 'bonsai-27b', plan: 'Your GPU', acct: 'Runs on this computer', state: 'off', sessions: 0, w: [] },
];
const STATE = {
  ok: ['work', 'Ready'], near: ['quota', 'Near its limit'], quota: ['stuck', 'Out of quota until Oct 5, 2:13 PM'],
  out: ['stuck', 'Signed out'], off: ['idle', 'Runtime off'],
};
const FIX = { ok: '', near: '', quota: '<button class="btn go sm">Switch account</button>', out: '<button class="btn go sm">Sign in</button>', off: '<button class="btn go sm">Start the runtime</button>' };

const gauge = ([label, pct, note], m, danger) => `<div class="g"><div class="gl"><span>${label}</span><b>${pct}%</b><small>${note}</small></div>
<div class="track ${danger ? 'full' : ''}" role="img" aria-label="${label} ${pct}% used"><i style="width:${pct}%;background:var(--${m})"></i></div></div>`;

const card = (h) => {
  const [tone, word] = STATE[h.state];
  const attn = h.state === 'quota' || h.state === 'out';
  return `<article class="win ${h.m}${attn ? ' attn' : ''}">
<div class="bar"><span class="grip" aria-hidden="true">${icon.grip}</span><h3 class="mono">${h.cmd}</h3><span class="more" aria-label="More">•••</span></div>
<div class="meta"><span class="tag">${h.plan}</span><span class="acct">${h.acct}</span></div>
<div class="glass gauges">${h.w.length ? h.w.map((w) => gauge(w, h.m, h.state === 'quota' && w[1] === 100)).join('') : `<p class="dim none">${h.state === 'off' ? 'No plan window: it runs on your own machine.' : 'No plan window until you sign in.'}</p>`}</div>
<div class="strip" style="--m:var(--${h.m})"><span class="model m-${h.m}">${h.model}</span><span class="sp">${h.sessions ? h.sessions + (h.sessions === 1 ? ' session' : ' sessions') : 'no sessions'}</span></div>
<div class="foot2"><span class="state ${tone}"><i></i>${word}</span><span class="fx">${FIX[h.state]}</span></div></article>`;
};

export const css = `
.fleet { display: grid; grid-template-columns: repeat(auto-fill, minmax(440px, 1fr)); gap: 38px 40px; padding-right: 10px; }
.meta { display: flex; align-items: center; gap: 12px; padding: 0 16px 12px; }
.meta .acct { font: 500 13px var(--mono); color: var(--mute); }
.more { margin-left: auto; font: 700 15px var(--mono); letter-spacing: 2px; color: var(--mute); padding: 2px 8px; }
.gauges { flex: 1; gap: 16px; padding: 18px 18px 20px; min-height: 112px; align-content: center; }
.g { display: grid; gap: 8px; }
.gl { display: flex; align-items: baseline; gap: 10px; font: 500 13px var(--mono); }
.gl span { color: var(--glass-ink); font-weight: 600; }
.gl b { color: var(--glass-ink); font-weight: 600; }
.gl small { margin-left: auto; font: 400 12.5px var(--mono); color: var(--glass-dim); }
.track { height: 14px; border-radius: 7px; background: #26201a; overflow: hidden; }
.track i { display: block; height: 100%; border-radius: 7px 0 0 7px; }
.track.full i { background: repeating-linear-gradient(135deg, var(--gpt) 0 8px, #0b8b86 8px 16px) !important; border-radius: 7px; }
.none { white-space: normal !important; }
.foot2 { display: flex; align-items: center; gap: 12px; padding: 2px 16px 16px; }
.foot2 .fx { margin-left: auto; }
.add { display: grid; place-items: center; min-height: 250px; border: 4px dashed var(--edge); border-radius: var(--r2); color: var(--mute); text-align: center; padding: 30px; gap: 8px; align-content: center; }
[data-theme='night'] .add { border-color: var(--hair); }
.add b { font: 520 26px/1.15 var(--display); color: var(--ink); }
.add span { font: 400 16px/1.5 var(--read); max-width: 30ch; }
`;

export function body() {
  return `<header class="page-head"><div><h1>Fleet</h1>
<p class="lede">Six plans: two ready, one near its limit, one out of quota, one signed out, one switched off. Drag a card to put it where you want it; Sessions follows.</p></div>
<div class="tools"><button class="btn go">${icon.plus}Add a plan</button></div></header>
<div class="fleet">${H.map(card).join('')}<div class="add"><b>Bring another plan</b><span>Sign in with a subscription, paste an API key, or point at your own GPU.</span></div></div>`;
}
