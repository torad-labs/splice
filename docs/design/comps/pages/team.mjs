import { icon } from '../shell.mjs';
export const nav = 'sessions';

const SEATS = [
  { m: 'claude', role: 'Planner', lead: true, cmd: 'claude-splice', who: 'Add a rate limiter to the API', state: 'work', word: 'Working', note: 'Splits the work and reviews what comes back.', stat: '42 turns · $4.10 · checks passing' },
  { m: 'grok', role: 'Builder', cmd: 'claude-grok', who: 'Write the rate limiter', state: 'work', word: 'Working', note: 'Writes the code in src/ and keeps to the plan the lead sends.', stat: '31 turns · $1.90 · checks passing' },
  { m: 'muse', role: 'Tester', cmd: 'claude-muse', who: 'Write the tests', state: 'work', word: 'Working', note: 'Writes and runs the tests; reports failures to the builder.', stat: '29 turns · $0.60 · checks failing' },
  { m: 'kimi', role: 'Reviewer', cmd: 'claude-kimi', who: '', state: 'idle', word: 'Open seat', note: 'Reads the diff before it merges.', stat: 'No turns yet' },
];
const TALK = [
  ['4:31 PM', 'claude-splice', 'claude-grok', 'Take createRateLimiter in src/rateLimit.js; keep the limit in config.'],
  ['4:29 PM', 'claude-muse', 'claude-grok', 'Two tests fail on the window edge: the reset is off by one tick.'],
  ['4:12 PM', 'claude-grok', 'claude-splice', 'Limiter is in and wired ahead of the routes. Ready for review.'],
];

export const css = `
.crumb { display: flex; gap: 12px; margin-bottom: 26px; font: 600 15px var(--meta); color: var(--mute); }
.crumb a { color: var(--ink); text-decoration: underline; text-underline-offset: 4px; text-decoration-thickness: 2px; text-decoration-color: var(--hair); }
.hero { max-width: 1080px; }
.chips { margin-top: 22px; display: flex; flex-wrap: wrap; gap: 10px; }
.section { margin-top: 84px; max-width: 1080px; }
.section > h2 { font: 520 26px/1.15 var(--display); color: var(--ink); }
.section > .why { margin-top: 8px; font: 400 16px/1.5 var(--read); color: var(--mute); }
.seats { margin-top: 36px; display: grid; }
.seatrow { display: grid; grid-template-columns: 210px minmax(0, 1fr) 220px; gap: 40px; align-items: start; padding: 30px 0; border-top: 2px solid var(--hair); }
.seatrow:first-child { border-top: 0; }
.seatrow h3 { display: flex; align-items: center; gap: 12px; font: 600 19px var(--meta); color: var(--ink); }
.seatrow h3 i { width: 14px; height: 14px; border-radius: 50%; background: var(--c); }
.seatrow .cmd { margin-top: 4px; font: 500 15px var(--meta); color: var(--mute); }
.seatrow .who { font: 560 19px var(--display); color: var(--ink); }
.seatrow .note { margin-top: 6px; font: 400 16px/1.5 var(--read); color: var(--body); max-width: 60ch; }
.seatrow .stat { font: 500 15px var(--meta); color: var(--mute); }
.talk { margin-top: 32px; display: grid; gap: 28px; max-width: 760px; }
.talk p { font: 400 17px/1.6 var(--read); color: var(--body); }
.talk small { display: block; margin-bottom: 4px; font: 600 14px var(--meta); color: var(--mute); }
.day { margin-top: 20px; display: inline-flex; gap: 14px; align-items: center; font: 600 15px var(--meta); color: var(--ink); }
`;

export function body() {
  const seats = SEATS.map((s) => `<div class="seatrow" style="--c:var(--${s.m})"><div><h3><i></i>${s.role}${s.lead ? ' <span class="tag">Lead</span>' : ''}</h3><div class="cmd">${s.cmd}</div></div>
<div>${s.who ? `<div class="who">${s.who}</div>` : `<div class="who" style="color:var(--mute)">Nobody is in this seat</div>`}<p class="note">${s.note}</p></div>
<div><span class="state ${s.state}"><i></i>${s.word}</span><div class="stat" style="margin-top:10px">${s.stat}</div></div></div>`).join('');
  const talk = TALK.map(([t, a, b, x]) => `<p><small>${a} to ${b} · ${t}</small>${x}</p>`).join('');
  return `<div class="crumb"><a href="#">Sessions</a><span>/</span><span>Rate limiter</span></div>
<header class="page-head hero"><div><h1>Rate limiter</h1>
<p class="lede">Add a rate limiter to the API. Three of four seats are working; the reviewer's seat is open.</p>
<div class="chips"><span class="tag">tally</span><span class="tag">per-key limits</span><span class="tag">config-driven window</span></div></div>
<div class="tools"><button class="btn">Edit the team</button><button class="btn quiet">Archive</button></div></header>
<section class="section"><h2>Seats</h2><p class="why">Each seat has a role, a plan and standing instructions; a session sits in it.</p><div class="seats">${seats}</div></section>
<section class="section"><h2>Talked today</h2><p class="why">What the seats sent each other, newest first.</p>
<div class="day"><button class="btn quiet sm">Earlier</button><span>Today</span></div><div class="talk">${talk}</div></section>
<section class="section"><h2>What they did today</h2><p class="why">One line about every half minute while a seat works.</p>
<div class="talk"><p><small>claude-grok · 4:31 PM</small>Editing src/rateLimit.js</p><p><small>claude-muse · 4:30 PM</small>Running npm test</p></div></section>`;
}
