import { icon } from '../shell.mjs';
export const nav = 'settings';

export const css = `
.split { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: 56px; align-items: start; max-width: 1240px; }
.sub { position: sticky; top: 30px; display: grid; gap: 2px; }
.sub a { position: relative; padding: 9px 12px; font: 500 15px var(--mono); color: var(--mute); text-decoration: none; }
.sub a[aria-current='true'] { color: var(--ink); font-weight: 600; }
.sub a[aria-current='true']::before { content: ''; position: absolute; left: -16px; top: 8px; bottom: 8px; width: 6px; border-radius: 3px; background: var(--charge); }
.sheets { display: grid; gap: 44px; }
.sheet h2 { font: 520 30px/1.1 var(--display); font-variation-settings: 'opsz' 72; letter-spacing: -.02em; color: var(--ink); margin-bottom: 20px; }
.set { padding: 6px 34px; }
.row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(220px, auto); gap: 40px; align-items: center; padding: 24px 0; border-bottom: 1px solid var(--hair); }
.row:last-child { border-bottom: 0; }
.row h3 { font: 600 19px/1.25 var(--read); color: var(--ink); }
.row p { margin-top: 4px; font: 400 16px/1.5 var(--read); color: var(--body); max-width: 56ch; }
.row .ctl { justify-self: end; display: flex; align-items: center; gap: 14px; }
.key { display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; font: 500 12px var(--mono); color: var(--mute); cursor: pointer; }
.key code { padding: 2px 8px; background: var(--obj-sunk); border: 2px solid var(--hair); border-radius: 5px; color: var(--ink); font-weight: 600; }
.select { position: relative; display: inline-flex; align-items: center; justify-content: space-between; gap: 20px; min-width: 200px; height: 42px; padding: 0 14px; background: var(--obj); border: 3px solid var(--edge); border-radius: var(--r1); font: 600 14px var(--mono); color: var(--ink); }
[data-theme='night'] .select { border-color: var(--hair); background: var(--obj-hi); }
.select.open { border-color: var(--charge); }
.menu { position: absolute; bottom: 48px; right: 0; z-index: 3; width: 290px; padding: 6px; background: var(--obj); border: 3px solid var(--edge); border-radius: 10px; box-shadow: 6px 7px 0 var(--tan); text-align: left; }
[data-theme='night'] .menu { border-color: var(--hair); box-shadow: 6px 7px 0 #0c0912; background: var(--obj-hi); }
.menu div { padding: 10px 12px; border-radius: 6px; font: 500 14px var(--mono); color: var(--ink); }
.menu div small { display: block; margin-top: 2px; font: 400 14px var(--read); color: var(--mute); }
.menu div.on { background: var(--sel); }
.menu div { position: relative; }
.menu div.on::after { content: '✓'; position: absolute; right: 12px; top: 10px; color: var(--charge); font-weight: 700; }
.step { display: inline-flex; align-items: center; border: 3px solid var(--edge); border-radius: var(--r1); background: var(--obj); }
[data-theme='night'] .step { border-color: var(--hair); background: var(--obj-hi); }
.step button { width: 40px; height: 36px; border: 0; background: transparent; font: 600 18px var(--mono); color: var(--ink); cursor: pointer; }
.step b { min-width: 56px; text-align: center; font: 600 15px var(--mono); color: var(--ink); }
.slider { width: 220px; display: grid; gap: 6px; }
.slider .track { position: relative; height: 12px; border-radius: 6px; background: var(--obj-sunk); border: 3px solid var(--edge); }
[data-theme='night'] .slider .track { border-color: var(--hair); }
.slider .track i { position: absolute; left: 0; top: 0; bottom: 0; width: 66.7%; background: var(--claude); border-radius: 4px 0 0 4px; }
.slider .track u { position: absolute; left: 66.7%; top: -8px; width: 22px; height: 22px; margin-left: -11px; border-radius: 50%; background: var(--obj); border: 3px solid var(--edge); }
[data-theme='night'] .slider .track u { background: #efe2c6; border-color: var(--edge); }
.slider small { display: flex; justify-content: space-between; font: 500 12px var(--mono); color: var(--mute); }
.folder { display: inline-flex; align-items: center; gap: 10px; padding: 8px 12px; background: var(--obj-sunk); border: 3px solid var(--edge); border-radius: var(--r1); font: 600 14px var(--mono); color: var(--ink); }
[data-theme='night'] .folder { border-color: var(--hair); }
.secret { display: inline-flex; align-items: center; gap: 12px; }
.secret .mask { padding: 8px 14px; min-width: 190px; background: var(--obj-sunk); border: 3px solid var(--edge); border-radius: var(--r1); font: 600 14px var(--mono); letter-spacing: .12em; color: var(--mute); }
[data-theme='night'] .secret .mask { border-color: var(--hair); }
.saved { display: inline-flex; align-items: center; gap: 8px; font: 600 12px var(--mono); color: var(--ok); }
.tip-restart { margin-top: 10px; display: inline-flex; align-items: center; gap: 8px; font: 500 13px var(--mono); color: var(--wait); }
`;

const row = (title, text, ctl, extra = '') => `<div class="row"><div><h3>${title}</h3><p>${text}</p>${extra}</div><div class="ctl">${ctl}</div></div>`;
const seg = (opts, on) => `<span class="seg" role="group">${opts.map((o, i) => `<button aria-pressed="${i === on}">${o}</button>`).join('')}</span>`;
const sw = (on, c) => `<span class="switch" role="switch" aria-checked="${on}" style="--sw:var(--${c ?? 'ok'})"></span>`;

export function body() {
  return `<header class="page-head"><div><h1>Settings</h1><p class="lede">How splice looks, what it remembers, and how it behaves. Changes apply as you make them.</p></div></header>
<div class="split"><nav class="sub" aria-label="Settings sections"><a href="#" aria-current="true">General</a><a href="#">Conversation</a><a href="#">Tools</a><a href="#">Storage</a><a href="#">Health</a></nav>
<div class="sheets">
<section class="sheet"><h2>General</h2><div class="win flat set">
${row('Appearance', 'Follows your computer unless you choose.', seg(['Day', 'Night', 'Match my computer'], 2))}
${row('Start splice when I sign in', 'The daemon comes up on its own, so your plans are ready before you open a terminal.', sw(true))}
${row('Open the console at', 'This address only opens on this computer.', '<span class="folder">127.0.0.1:3096</span><button class="btn sm">Copy</button>')}
</div></section>

<section class="sheet"><h2>Conversation</h2><div class="win flat set">
${row('Keep long chats going', 'When a chat outgrows what the model can hold, splice summarizes its oldest part and carries on.', sw(true, 'claude'))}
${row('Summarize at', 'How full the model’s memory gets before the oldest part is summarized.', '<div class="slider"><div class="track"><i></i><u></u></div><small style="position:relative;height:16px"><span style="position:absolute;left:0">50%</span><b class="mono" style="color:var(--ink);position:absolute;left:66.7%;transform:translateX(-50%)">80%</b><span style="position:absolute;right:0">95%</span></small></div>',
  '<span class="key">' + icon.key + '<code>compaction.threshold</code></span>')}
${row('Show the model’s reasoning', 'Some models think before they answer. Choose how much of that you see in a session.', '<span class="select open">Summary ' + icon.chev + '<div class="menu"><div>Hidden<small>Only the answer</small></div><div class="on">Summary<small>A short account of the thinking</small></div><div>Full<small>Everything the model wrote, as it wrote it</small></div></div></span>')}
${row('Turns at once, per plan', 'More turns at once finish sooner but use the plan’s limit faster.', '<span class="step"><button aria-label="Fewer">−</button><b>4</b><button aria-label="More">+</button></span>')}
</div></section>

<section class="sheet"><h2>Storage</h2><div class="win flat set">
${row('Keep session transcripts for', 'Older ones are removed from this computer; nothing is sent anywhere.', '<span class="select">30 days ' + icon.chev + '</span>')}
${row('Where splice keeps its files', 'Sign-ins, summaries and the turn record live in one folder you own.', '<span class="folder">' + icon.folder + 'Splice data</span><button class="btn sm">Change…</button>')}
${row('OpenRouter key', 'Used by the OpenRouter plan. It is stored in your system keychain, never shown again.', '<span class="secret"><span class="mask">••••••••••••</span><button class="btn sm">Replace</button></span><span class="saved">' + icon.check + 'Saved</span>')}
</div></section>

<section class="sheet"><h2>Tools</h2><div class="win flat set">
${row('Connect tools to every session', 'Servers splice hands to each session so any model can call them.', sw(true, 'claude'))}
${row('filesystem', 'Reads and writes files in the folders you allow. Started when a session first calls it.', '<span class="state work"><i></i>Running</span>' + sw(true, 'claude'))}
${row('browser', 'Drives a web page for a session. Off until you turn it on.', '<span class="state idle"><i></i>Off</span>' + sw(false, 'claude'))}
</div></section>

<section class="sheet"><h2>Health</h2><div class="win flat set">
${row('Everything splice depends on', 'Sign-ins, ports, folders and the daemon itself, checked a minute ago.', '<span class="state work"><i></i>All good</span><button class="btn sm">Check again</button>')}
${row('One thing to fix', 'The local runtime for claude-bonsai is not answering on port 8099.', '<span class="state stuck"><i></i>Needs you</span><button class="btn go sm">Start the runtime</button>')}
</div></section>
</div></div>`;
}
