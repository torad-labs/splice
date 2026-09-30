import { icon } from '../shell.mjs';
export const nav = 'sessions';

const HERE = [
  { m: 'grok', name: 'Write the rate limiter', plan: 'Grok' },
  { m: 'muse', name: 'Write the tests', plan: 'Muse' },
];
const FILES = [
  ['instructions', '/home/ava/code/tally/CLAUDE.md', '# tally\nKeep handlers under 40 lines.\nRun npm test before you say a change is done.'],
  ['memory', '/home/ava/.claude/projects/tally/MEMORY.md', '- The limiter reads its window from config.'],
];
const PLANS = [['claude-grok', '/home/ava', 'Home folder'], ['claude-muse', '/home/ava', 'Home folder'], ['claude-splice', '/home/ava', 'Home folder']];

export const css = `
.crumb { display: flex; gap: 12px; margin-bottom: 26px; font: 600 15px var(--meta); color: var(--mute); }
.crumb a { color: var(--ink); text-decoration: underline; text-underline-offset: 4px; text-decoration-thickness: 2px; text-decoration-color: var(--hair); }
.hero { max-width: 1080px; }
.root { margin-top: 12px; font: 500 15px var(--meta); color: var(--mute); }
.section { margin-top: 84px; max-width: 1080px; }
.section > h2 { font: 520 26px/1.15 var(--display); color: var(--ink); }
.section > .why { margin-top: 8px; font: 400 16px/1.5 var(--read); color: var(--mute); max-width: 70ch; }
.list { margin: 32px 0 0; padding: 0; display: grid; gap: 22px; }
.list li { list-style: none; display: flex; gap: 18px; align-items: baseline; font: 500 17px var(--meta); color: var(--body); }
.list li b { font: 650 17px var(--meta); color: var(--ink); }
.list li small { font: 500 14px var(--meta); color: var(--mute); }
.standing { margin-top: 32px; display: grid; gap: 30px; max-width: 760px; }
.standing label { display: grid; gap: 8px; }
.standing .box { min-height: 112px; padding: 14px 16px; background: var(--obj); border-radius: 10px; font: 400 16px/1.5 var(--read); color: var(--body); }
.files details { margin-top: 26px; }
.files summary { font: 600 17px var(--meta); color: var(--ink); }
.files summary small { margin-left: 16px; font: 500 14px var(--mono); color: var(--mute); }
.files pre { margin: 14px 0 0; padding: 18px 20px; background: var(--glass); color: var(--glass-ink); border-radius: 10px; font: 400 13px/1.55 var(--mono); }
`;

export function body() {
  const here = HERE.map((s) => `<li style="--c:var(--${s.m})"><b>${s.name}</b><small>${s.plan}</small></li>`).join('');
  const files = FILES.map(([k, p, t]) => `<details${k === 'instructions' ? ' open' : ''}><summary>${k}<small>${p}</small></summary><pre>${t}</pre></details>`).join('');
  const plans = PLANS.map(([p, r, e]) => `<li><b>${p}</b><small>${r} · ${e}</small></li>`).join('');
  return `<div class="crumb"><a href="#">Sessions</a><span>/</span><span>tally</span></div>
<header class="page-head hero"><div><h1>tally</h1><p class="lede">2 sessions are running and 1 team works here. 41 turns today. About $3.20 of API cost.</p>
<p class="root">/home/ava/code/tally · Last seen 4:31 PM</p></div></header>
<section class="section"><h2>Sessions here</h2><ul class="list">${here}</ul></section>
<section class="section"><h2>Compaction rules</h2><p class="why">The instructions a compaction here runs under; the most specific rule wins.</p><ul class="list"><li><b>Repo rule</b><small>Applies to every plan in tally</small></li></ul></section>
<section class="section"><h2>Standing prompt and rule</h2><p class="why">This repo’s prompt and compaction rule, as splice.toml holds them. They apply after splice restarts, at each session’s next turn.</p>
<div class="standing"><label><span class="eyebrow">Standing prompt</span><div class="box">Be brief. Prefer the existing helpers over new ones.</div></label>
<label><span class="eyebrow">Compaction rule</span><div class="box">Keep the plan, the open decisions and the failing tests.</div></label>
<div><span class="eyebrow">Reaches</span><div style="margin-top:8px;font:500 17px var(--meta);color:var(--body)">Write the rate limiter, Write the tests</div></div>
<div><button class="btn go">Save</button></div></div></section>
<section class="section files"><h2>Files splice read</h2><p class="why">Instructions and memory, exactly as the client would see them.</p>${files}</section>
<section class="section"><h2>Where each plan looks for a branch</h2><p class="why">The trusted folder each plan’s status line finds this repo under.</p><ul class="list">${plans}</ul></section>`;
}
