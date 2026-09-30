import { icon } from '../shell.mjs';
export const nav = 'sessions';

export const css = `
.crumb { display: inline-flex; align-items: center; gap: 8px; margin-bottom: 18px; font: 600 13px var(--mono); color: var(--mute); text-decoration: none; }
.top { display: flex; align-items: flex-start; gap: 22px; margin-bottom: 30px; }
.top h1 { font: 500 clamp(40px, 3.2vw, 54px)/1.02 var(--display); font-variation-settings: 'opsz' 144; letter-spacing: -.025em; color: var(--ink); }
.top .facts { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 14px; }
.top .acts { margin-left: auto; display: flex; gap: 10px; padding-top: 8px; }
.cols { display: grid; grid-template-columns: minmax(0, 1fr) 330px; gap: 44px; align-items: start; }
.sheet { padding: 0; }
.sheet .bar { padding: 16px 26px 12px; border-bottom: 1px solid var(--hair); }
.sheet .bar h3 { font-size: 15px; font-family: var(--mono); font-weight: 600; color: var(--mute); letter-spacing: 0; }
.convo { padding: 8px 40px 28px; display: grid; gap: 30px; justify-items: center; }
.convo > * { width: 100%; max-width: 78ch; }
.msg { display: grid; gap: 10px; max-width: 78ch; }
.who { display: flex; align-items: center; gap: 10px; font: 600 13px var(--mono); color: var(--ink); }
.who .t { margin-left: auto; font-weight: 500; color: var(--mute); }
.who .m::before { content: ''; display: inline-block; width: 10px; height: 10px; border-radius: 50%; background: var(--c); margin-right: 8px; }
.prose { font: 400 18.5px/1.68 var(--read); color: var(--ink); font-variation-settings: 'opsz' 20; }
.prose p + p, .prose p + ul, .prose ul + p, .prose ol + p, .prose p + ol { margin-top: 14px; }
.prose h2 { font: 560 27px/1.15 var(--display); font-variation-settings: 'opsz' 72; letter-spacing: -.015em; margin: 6px 0 12px; color: var(--ink); }
.prose h3 { font: 600 19px/1.3 var(--read); margin: 20px 0 8px; color: var(--ink); }
.prose ol, .prose ul { padding-left: 1.3em; margin: 0; }
.prose li { margin: 5px 0; padding-left: 4px; }
.prose li::marker { font: 600 .85em var(--mono); color: var(--mute); }
.prose code { font: 500 .8em/1 var(--mono); padding: 2px 6px; background: var(--obj-sunk); border: 2px solid var(--hair); border-radius: 5px; color: var(--ink); }
.prose strong { font-weight: 650; }
.prose table { border-collapse: collapse; margin: 16px 0 6px; width: 100%; font: 400 16px/1.4 var(--read); }
.prose th { text-align: left; font: 600 12px var(--mono); letter-spacing: .04em; color: var(--mute); padding: 6px 14px 8px 0; border-bottom: 3px solid var(--edge); }
[data-theme='night'] .prose th { border-bottom-color: var(--hair); }
.prose td { padding: 9px 14px 9px 0; border-bottom: 1px solid var(--hair); vertical-align: top; }
.prose td:first-child { font-weight: 600; }
.pick { color: var(--ok); font-weight: 650; }
.you { background: var(--obj-sunk); border-radius: 10px; padding: 18px 22px 20px; border: 3px solid var(--edge); box-shadow: 4px 5px 0 var(--tan); }
[data-theme='night'] .you { border-color: var(--hair); box-shadow: 4px 5px 0 #0c0912; background: var(--obj-hi); }
.peer { position: relative; border: 3px dashed var(--edge); border-radius: 10px; padding: 16px 22px 18px 26px; background: color-mix(in srgb, var(--c) 12%, var(--obj)); }
[data-theme='night'] .peer { border-color: var(--c); }
.peer::before { content: ''; position: absolute; left: -3px; top: -3px; bottom: -3px; width: 12px; background: var(--c); border-radius: 10px 0 0 10px; }
.peer .stamp { display: inline-flex; align-items: center; gap: 8px; font: 600 12px var(--mono); letter-spacing: .04em; color: var(--ink); margin-bottom: 8px; }
.peer .prose { font-size: 17px; }
.files { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.tool { border-radius: 10px; background: var(--glass); color: var(--glass-ink); font: 400 13px/1.6 var(--mono); overflow: hidden; }
.tool > header { display: flex; align-items: center; gap: 12px; padding: 11px 16px; }
.tool > header .verb { font-weight: 600; color: var(--glass-ink); }
.tool > header .arg { color: var(--glass-dim); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tool > header .res { margin-left: auto; display: inline-flex; align-items: center; gap: 8px; color: #7bc47f; white-space: nowrap; }
.tool > header .res.run { color: #e7c069; }
.tool > header .res .sp { display: inline-block; width: 12px; height: 12px; border-radius: 50%; border: 2.5px solid currentColor; border-right-color: transparent; }
.tool .chev { color: var(--glass-dim); }
.tool pre { margin: 0; padding: 4px 16px 16px; border-top: 1px solid var(--glass-line); white-space: pre; overflow: hidden; }
.tool pre.code { padding-top: 14px; }
.ln { display: block; padding: 0 16px; margin: 0 -16px; }
.ln.add { background: rgba(97, 190, 163, .16); }
.ln.del { background: rgba(232, 93, 93, .16); }
.k { color: #ee8f58; } .s { color: #7bc47f; } .f { color: #6eadfe; } .c { color: #8f8571; font-style: italic; } .n { color: #e3b341; }
.pass { color: #7bc47f; }
.shell-out { padding-top: 12px !important; }
.composer { width: calc(100% - 52px); max-width: calc(78ch + 40px); margin: 0 auto 26px; display: grid; gap: 0; border: 3px solid var(--edge); border-radius: 12px; background: var(--obj); }
[data-theme='night'] .composer { border-color: var(--hair); background: var(--obj-hi); }
.composer .in { padding: 16px 18px 6px; font: 400 18px/1.5 var(--read); color: var(--mute); min-height: 68px; }
.composer .row { display: flex; align-items: center; gap: 10px; padding: 8px 12px 12px 14px; }
.composer .row .sp { margin-left: auto; }
.pend { font: 600 12px var(--mono); color: var(--wait); }
.rail { position: sticky; top: 26px; display: grid; gap: 18px; }
.rail h2 { font: 520 22px/1.1 var(--display); color: var(--ink); letter-spacing: -.01em; }
.team { position: relative; display: grid; gap: 14px; padding-left: 30px; }
.team::before { content: ''; position: absolute; left: 10px; top: 14px; bottom: 14px; width: 12px; border-radius: 6px; background: var(--tan); border: 3px solid var(--edge); }
[data-theme='night'] .team::before { border-color: var(--hair); background: #3a2f27; }
.seat { position: relative; display: flex; align-items: center; gap: 10px; padding: 10px 14px; background: var(--obj); border: 3px solid var(--edge); border-radius: 10px; }
[data-theme='night'] .seat { border-color: var(--hair); }
.seat::before { content: ''; position: absolute; left: -30px; top: 50%; width: 30px; height: 4px; margin-top: -2px; background: var(--c); }
.seat::after { content: ''; position: absolute; left: -26px; top: 50%; width: 14px; height: 14px; margin-top: -7px; border-radius: 50%; background: var(--obj-sunk); border: 3px solid var(--edge); }
.seat.here { box-shadow: 5px 6px 0 var(--c), 5px 6px 0 3px var(--edge); }
[data-theme='night'] .seat.here { box-shadow: 5px 6px 0 var(--c), 5px 6px 0 3px var(--edge); }
.seat b { font: 600 14px var(--mono); color: var(--ink); }
.seat span.s2 { margin-left: auto; font: 600 12px var(--mono); color: var(--ok); }
.seat span.s2.w { color: var(--mute); }
.ride { margin-left: 30px; padding: 8px 12px; background: var(--obj-hi); border: 3px solid var(--edge); border-radius: 8px; font: 600 12.5px var(--mono); color: var(--ink); position: relative; }
[data-theme='night'] .ride { border-color: var(--hair); background: var(--obj-hi); }
.ride::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 10px; background: var(--c); border-radius: 5px 0 0 5px; }
.ride span { display: block; padding-left: 8px; }
.ride small { display: block; padding-left: 8px; font: 500 11px var(--mono); color: var(--mute); margin-top: 2px; }
.facts-list { display: grid; gap: 10px; font: 500 13px var(--mono); color: var(--mute); }
.facts-list div { display: flex; justify-content: space-between; gap: 12px; }
.facts-list b { color: var(--ink); font-weight: 600; }
.meter { height: 12px; border-radius: 6px; background: var(--obj-sunk); border: 3px solid var(--edge); overflow: hidden; }
[data-theme='night'] .meter { border-color: var(--hair); }
.meter i { display: block; height: 100%; width: 41%; background: var(--grok); }
`;

export function body(opts = {}) {
  const c = opts.compact !== false;
  return `<a class="crumb" href="sessions.html">${icon.back}Sessions</a>
<header class="top"><div><h1>Write the rate limiter</h1>
${c ? `<div class="facts quiet-meta"><span class="state work"><i></i>Working</span><span>tally</span><span>rate-limit</span><span>claude-grok</span><span>grok-4.7</span><span>31 min</span></div></div>
<div class="acts"><button class="btn go">${icon.stop}Stop the turn</button></div></header>` : `<div class="facts"><span class="tag">tally</span><span class="tag">rate-limit</span><span class="model m-grok">claude-grok · grok-4.7</span><span class="state work"><i></i>Working</span><span class="hand mono">31 min · 14 turns</span></div></div>
<div class="acts"><button class="btn go">${icon.stop}Stop the turn</button><button class="btn">Copy resume command</button></div></header>`}
<div class="cols">
<section class="win grok sheet" aria-label="Conversation">
<div class="bar"><h3>Conversation</h3><span class="seg" role="group"><button aria-pressed="true">Messages</button><button aria-pressed="false">Tools only</button><button aria-pressed="false">Hand-offs</button></span></div>
<div class="convo">

<div class="msg peer" style="--c:var(--claude)"><div class="stamp">HAND-OFF · from claude-splice · 3:04 PM</div>
<div class="prose"><p>Build the limiter in <code>src/rateLimit.js</code>. Use the existing database handle; a fixed window is enough for now. <strong>claude-muse</strong> is writing the tests, so keep the export name stable.</p></div>
<div class="files"><span class="tag">src/rateLimit.js</span><span class="tag">src/server.js</span></div></div>

<div class="msg"><div class="who"><span class="m" style="--c:var(--grok)">claude-grok</span><span class="t">3:05 PM</span></div>
<div class="prose">
<h2>Plan</h2>
<p>I’ll add one module and wire it into the server. Three decisions, in the order they matter:</p>
<ol><li>Count per <strong>API key</strong>, not per IP, so a shared office network isn’t throttled together.</li><li>Store the counters in the existing <code>db</code>, so a restart keeps them.</li><li>Answer <code>429</code> with a <code>Retry-After</code> header, never a silent drop.</li></ol>
<table><thead><tr><th>OPTION</th><th>KEEPS COUNTS ACROSS RESTARTS</th><th>EXTRA MOVING PARTS</th><th></th></tr></thead>
<tbody><tr><td>Fixed window in the db</td><td>Yes</td><td>None</td><td class="pick">Chosen</td></tr>
<tr><td>Sliding log</td><td>Yes</td><td>A cleanup job</td><td>Later</td></tr>
<tr><td>In-memory bucket</td><td>No</td><td>None</td><td>Rejected</td></tr></tbody></table>
</div></div>

<div class="msg"><div class="tool"><header><span class="chev">${icon.chev}</span><span class="verb">Read</span><span class="arg">src/server.js</span><span class="res">${icon.check}212 lines · 0.4 s</span></header></div></div>

<div class="msg"><div class="tool"><header><span class="chev">${icon.chev}</span><span class="verb">Edit</span><span class="arg">src/rateLimit.js</span><span class="res">${icon.check}+18 −0 · 0.6 s</span></header>
<pre class="code"><span class="ln add"><span class="k">export function</span> <span class="f">createRateLimiter</span>(db, { limit, windowMs }) {</span><span class="ln add">  <span class="k">const</span> hit = db.<span class="f">prepare</span>(<span class="s">\`</span></span><span class="ln add"><span class="s">    INSERT INTO rate_limits (key, window_start, count) VALUES (?, ?, 1)</span></span><span class="ln add"><span class="s">    ON CONFLICT (key, window_start) DO UPDATE SET count = count + 1</span></span><span class="ln add"><span class="s">    RETURNING count\`</span>);</span><span class="ln add">  <span class="k">return</span> (key, now = Date.<span class="f">now</span>()) =&gt; {</span><span class="ln add">    <span class="k">const</span> start = now - (now % windowMs);   <span class="c">// fixed window</span></span><span class="ln add">    <span class="k">return</span> hit.<span class="f">get</span>(key, start).count &lt;= limit;</span><span class="ln add">  };</span><span class="ln add">}</span></pre></div></div>

<div class="msg"><div class="tool"><header><span class="chev">${icon.chev}</span><span class="verb">Run</span><span class="arg">npm test</span><span class="res">${icon.check}18 passed · 3.1 s</span></header>
<pre class="shell-out"><span class="pass">✓</span> allows requests under the limit
<span class="pass">✓</span> answers 429 with Retry-After at the limit
<span class="pass">✓</span> counts each API key on its own
<span class="dim" style="color:var(--glass-dim)">… 15 more passing</span></pre></div></div>

<div class="msg"><div class="who"><span class="m" style="--c:var(--grok)">claude-grok</span><span class="t">3:33 PM</span></div>
<div class="prose"><p>The limiter is in and the server uses it. Two things I did <strong>not</strong> touch: the login route, which needs its own, lower limit, and the <code>/health</code> route, which must never be limited. Say the word and I’ll take the login route next.</p></div></div>

<div class="msg"><div class="tool"><header><span class="chev">${icon.chev}</span><span class="verb">Run</span><span class="arg">npm run lint</span><span class="res run"><span class="sp"></span>running · 6 s</span></header></div></div>
</div>
<div class="composer"><div class="in">Message claude-grok, or reply to the hand-off…</div>
<div class="row"><span class="model m-grok">grok-4.7</span><span class="pend">Pending: splice cannot send to a session yet</span><span class="sp"></span><button class="btn sm" aria-disabled="true" style="opacity:.6">Send${icon.send}</button></div></div>
</section>

<aside class="rail" aria-label="The team">
<h2>The team</h2>
<div class="team">
<div class="seat" style="--c:var(--claude)"><b>claude-splice</b><span class="s2">Working</span></div>
<div class="ride" style="--c:var(--claude)"><span>src/rateLimit.js</span><small>to this session · 3:04 PM</small></div>
<div class="seat here" style="--c:var(--grok)"><b>claude-grok</b><span class="s2">Working</span></div>
<div class="ride" style="--c:var(--grok)"><span>createRateLimiter is ready</span><small>from this session · 3:33 PM</small></div>
<div class="seat" style="--c:var(--muse)"><b>claude-muse</b><span class="s2">Running tests</span></div>
<div class="seat" style="--c:var(--gpt)"><b>claudex</b><span class="s2" style="color:var(--charge)">Waiting on you</span></div>
</div>
${c ? '' : `<h2 style="margin-top:14px">This session</h2>
<div class="facts-list"><div>Started<b>3:04 PM</b></div><div>Turns<b>14</b></div><div>Estimated cost<b>$0.42</b></div><div>Context<b>41% of 500k</b></div></div>
<div class="meter" aria-label="Context used 41%"><i></i></div>`}
</aside>
</div>`;
}
