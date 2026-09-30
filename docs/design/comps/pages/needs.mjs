import { icon } from '../shell.mjs';
export const nav = 'needs';

const ITEMS = [
  { c: 'gpt', kind: 'Waiting on you', tone: 'wait', title: 'Review the rate limiter diff', says: 'claudex has waited 2 minutes for your answer: <em>Run <code>npm run migrate -- --apply</code>?</em> It is a session in <b>tally</b>, handed over by claude-grok.', fix: 'Open the session', more: 'Copy resume command' },
  { c: 'kimi', kind: 'Stuck', tone: 'stuck', title: 'Migrate the billing tables', says: 'claude-kimi has shown nothing for 14 minutes; its last step, <code>psql ledger &lt; 0042.sql</code>, has no result. It is a session in <b>ledger-api</b>.', fix: 'Stop the turn', more: 'Open the session' },
  { c: 'gpt', kind: 'Out of quota', tone: 'quota', title: 'claudex is out of quota until Oct 5, 2:13 PM', says: 'The ChatGPT plan refuses new turns until then. One session, <b>Review the rate limiter diff</b>, is affected. Another account can take over now.', fix: 'Switch account', more: 'See the plan' },
  { c: 'muse', kind: 'Signed out', tone: 'stuck', title: 'claude-muse needs you to sign in again', says: 'Its login could not be refreshed, so its next turn will fail. Two sessions use it: <b>Write the tests</b> and one idle.', fix: 'Sign in again', more: '' },
];
const LABEL = { wait: 'wait', stuck: 'stuck', quota: 'quota' };

export const css = `
.stack { display: grid; gap: 34px; max-width: 1040px; }
.need { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 40px; align-items: center; padding: 22px 30px 24px 34px; }
.need .k { display: inline-flex; margin-bottom: 8px; }
.need h2 { font: 540 30px/1.12 var(--display); font-variation-settings: 'opsz' 72; letter-spacing: -.018em; color: var(--ink); }
.need p { margin-top: 10px; max-width: 62ch; font: 400 18px/1.6 var(--read); color: var(--body); }
.need p em { font-style: normal; font-weight: 600; color: var(--ink); }
.need p b { color: var(--ink); font-weight: 650; }
.need code { font: 500 .82em var(--mono); padding: 2px 6px; background: var(--obj-sunk); border: 2px solid var(--hair); border-radius: 5px; color: var(--ink); }
.need .do { display: grid; gap: 10px; justify-items: stretch; min-width: 200px; }
.need .do .btn { justify-content: center; min-height: 46px; font-size: 14px; }
.need::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 12px; background: var(--rimc); border-radius: 10px 0 0 10px; }
[data-theme='night'] .need::before { border-radius: 11px 0 0 11px; }
.calm { max-width: 1040px; margin-top: 54px; padding-top: 26px; border-top: 3px solid var(--edge); display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 30px; }
[data-theme='night'] .calm { border-top-color: var(--hair); }
.calm h3 { font: 520 22px/1.15 var(--display); color: var(--ink); }
.calm p { margin-top: 6px; font: 400 16px/1.5 var(--read); color: var(--body); }
.calm .n { font: 500 44px/1 var(--display); color: var(--ink); letter-spacing: -.03em; }
`;

export function body() {
  const rows = ITEMS.map((i) => `<article class="win need ${i.c}" style="--rimc:var(--${i.c})">
<div><span class="state ${i.tone} k"><i></i>${i.kind}</span><h2>${i.title}</h2><p>${i.says}</p></div>
<div class="do"><button class="btn go">${i.fix}</button>${i.more ? `<button class="btn quiet sm">${i.more}</button>` : ''}</div></article>`).join('');
  return `<header class="page-head"><div><h1>Needs you</h1>
<p class="lede">Four things a person has to do. Everything else is running.</p></div></header>
<div class="stack">${rows}</div>
<div class="calm"><div><div class="n">3</div><h3>Sessions working</h3><p>Nothing to do; they will surface here if that changes.</p></div>
<div><div class="n">3</div><h3>Plans serving</h3><p>Claude, Grok and Kimi answer. ChatGPT and Muse are above; your GPU is switched off.</p></div>
<div><h3>Not listed here</h3><p>An idle session and a local runtime you switched off are not problems, so they are not items.</p></div></div>`;
}
