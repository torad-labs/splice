import { icon } from '../shell.mjs';
export const nav = 'settings';

export const css = `
.split { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: 88px; align-items: start; max-width: 1240px; }
.sub { position: sticky; top: 30px; display: grid; gap: 2px; }
.sub a { position: relative; padding: 11px 12px; font: 600 16px var(--meta); color: var(--mute); text-decoration: none; }
.sub a[aria-current='true'] { color: var(--ink); font-weight: 600; }
.sub a[aria-current='true']::before { content: ''; position: absolute; left: -16px; top: 8px; bottom: 8px; width: 6px; border-radius: 3px; background: var(--charge); }
.sheets { display: grid; gap: 96px; }
.sheet h2 { font: 520 30px/1.1 var(--display); font-variation-settings: 'opsz' 72; letter-spacing: -.02em; color: var(--ink); margin-bottom: 30px; }
.set, .win.flat.set { padding: 14px 44px; overflow: visible; }
.row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(220px, auto); gap: 40px; align-items: center; padding: 34px 0; }
.row h3 { font: 600 19px/1.25 var(--read); color: var(--ink); }
.row p { margin-top: 4px; font: 400 16px/1.5 var(--read); color: var(--body); max-width: 56ch; }
.row .ctl { justify-self: end; display: flex; align-items: center; gap: 18px; }
.key { display: inline-flex; align-items: center; gap: 6px; margin-top: 14px; font: 500 13px var(--meta); color: var(--mute); cursor: pointer; }
.key code { font-family: var(--mono); padding: 2px 8px; background: color-mix(in srgb, var(--ink) 8%, transparent); border: 0; border-radius: 5px; color: var(--ink); font-weight: 600; }
.select { position: relative; display: inline-flex; align-items: center; justify-content: space-between; gap: 20px; min-width: 200px; height: 42px; padding: 0 14px; background: var(--obj); border: 2px solid color-mix(in srgb, var(--ink) 38%, transparent); border-radius: var(--r1); font: 600 15px var(--meta); color: var(--ink); }
[data-theme='night'] .select { border-color: var(--hair); background: var(--obj-hi); }
.select.open { border-color: var(--charge); }
.menu { position: absolute; top: calc(100% + 8px); right: 0; z-index: 3; width: 290px; padding: 8px; background: var(--obj); border: 2px solid var(--hair); border-radius: 10px; box-shadow: 6px 7px 0 var(--tan); text-align: left; }
[data-theme='night'] .menu { border-color: var(--hair); box-shadow: 6px 7px 0 #0c0912; background: var(--obj-hi); }
.menu div { padding: 12px 14px; border-radius: 6px; font: 600 15px var(--meta); color: var(--ink); }
.menu div small { display: block; margin-top: 2px; font: 400 14px var(--read); color: var(--mute); }
.menu div.on { background: var(--sel); }
.menu div { position: relative; }
.menu div.on::after { content: '✓'; position: absolute; right: 12px; top: 10px; color: var(--charge); font-weight: 700; }
.step { display: inline-flex; align-items: center; border: 2px solid color-mix(in srgb, var(--ink) 38%, transparent); border-radius: var(--r1); background: var(--obj); }
[data-theme='night'] .step { border-color: var(--hair); background: var(--obj-hi); }
.step button { width: 40px; height: 36px; border: 0; background: transparent; font: 600 18px var(--meta); color: var(--ink); cursor: pointer; }
.step b { min-width: 56px; text-align: center; font: 600 16px var(--meta); color: var(--ink); }
.slider { width: 220px; display: grid; gap: 6px; }
.slider .track { position: relative; height: 12px; border-radius: 6px; background: var(--obj-sunk); border: 2px solid var(--hair); }
.slider .track i { position: absolute; left: 0; top: 0; bottom: 0; width: 66.7%; background: var(--ink); border-radius: 4px 0 0 4px; }
.slider .track u { position: absolute; left: 66.7%; top: -8px; width: 22px; height: 22px; margin-left: -11px; border-radius: 50%; background: var(--obj); border: 3px solid var(--edge); box-shadow: 0 2px 0 var(--tan); }
[data-theme='night'] .slider .track u { background: #efe2c6; border-color: var(--edge); }
.slider small { display: flex; justify-content: space-between; font: 500 13px var(--meta); color: var(--mute); }
.folder { display: inline-flex; align-items: center; gap: 10px; padding: 8px 12px; background: color-mix(in srgb, var(--ink) 8%, transparent); border: 0; border-radius: var(--r1); font: 600 15px var(--meta); color: var(--ink); }
.folder.code { font-family: var(--mono); font-size: 14px; }
.secret { display: inline-flex; align-items: center; gap: 12px; }
.secret .mask { padding: 8px 14px; min-width: 190px; background: var(--obj); border: 2px solid color-mix(in srgb, var(--ink) 38%, transparent); border-radius: var(--r1); font: 600 14px var(--mono); letter-spacing: .12em; color: var(--mute); }
.saved { display: inline-flex; align-items: center; gap: 8px; font: 600 14px var(--meta); color: var(--ok); }
.tip-restart { margin-top: 14px; margin-left: 14px; display: inline-flex; align-items: center; gap: 8px; font: 500 14px var(--meta); color: var(--wait); }
`;

const row = (title, text, ctl, extra = '') => `<div class="row"><div><h3>${title}</h3><p>${text}</p>${extra}</div><div class="ctl">${ctl}</div></div>`;
const seg = (opts, on) => `<span class="seg" role="group">${opts.map((o, i) => `<button aria-pressed="${i === on}">${o}</button>`).join('')}</span>`;
const sw = (on, c) => `<span class="switch" role="switch" aria-checked="${on}" style="--sw:var(--${c ?? 'ok'})"></span>`;

const key = (name) => `<span class="key">${icon.key}<code>${name}</code></span>`;
const chip = (t, x = true) => `<span class="folder">${icon.folder}${t}${x ? ' <b style="color:var(--mute)">×</b>' : ''}</span>`;
const slider = (min, max, val, unit) => {
  const at = ((val - min) / (max - min)) * 100;
  return `<div class="slider"><div class="track"><i style="width:${at}%"></i><u style="left:${at}%"></u></div><small style="position:relative;height:16px"><span style="position:absolute;left:0">${min}${unit}</span><b style="font-family:var(--meta);color:var(--ink);position:absolute;left:${at}%;transform:translateX(-50%)">${val}${unit}</b><span style="position:absolute;right:0">${max}${unit}</span></small></div>`;
};

// Every control below maps to a real knob (GET/PATCH /api/config, `[daemon]`/`[defaults]` in splice.toml), a
// route, or the browser; docs/design/comps/DIRECTION.md lists each with its backing.
export function body() {
  return `<header class="page-head"><div><h1>Settings</h1><p class="lede">How splice looks, what it remembers, and how it behaves. Changes apply as you make them; a few wait for a restart and say so.</p></div></header>
<div class="split"><nav class="sub" aria-label="Settings sections"><a href="#" aria-current="true">General</a><a href="#">Conversation</a><a href="#">Tools</a><a href="#">Storage</a><a href="#">Health</a><a href="#">Advanced</a></nav>
<div class="sheets">
<section class="sheet"><h2>General</h2><div class="win flat set">
${row('Appearance', 'Follows your computer unless you choose.', seg(['Day', 'Night', 'Match my computer'], 2))}
${row('Open the console at', 'This address only opens on this computer.', '<span class="folder code">127.0.0.1:3096</span><button class="btn sm">Copy</button>')}
${row('Warn me when a plan is this full', 'Splice flags a plan window once it passes this share of its limit.', slider(50, 100, 80, '%'), key('usageWarnPct') + '<span class="tip-restart">' + icon.clock + 'Applies after a restart · <b>Restart now</b></span>')}
${row('Detailed log', 'Writes extra lines to the splice log. Turn it on while chasing a problem.', sw(false, 'ink'), key('debug'))}
</div></section>

<section class="sheet"><h2>Conversation</h2><div class="win flat set">
${row('How hard the model thinks', 'Used when Claude Code sets nothing of its own; each model keeps its default until you pick.', seg(['Model default', 'Low', 'Medium', 'High'], 0), key('effort'))}
${row('Turns at once, per plan', 'More turns at once finish sooner but use the plan’s limit faster. Zero means no limit.', '<span class="step"><button aria-label="Fewer">−</button><b>12</b><button aria-label="More">+</button></span>', key('maxInflight'))}
${row('Show the model’s reasoning', 'Some models think before they answer. Choose how Claude Code shows that.', '<span class="select open">In the reply ' + icon.chev + '<div class="menu"><div class="on">In the reply<small>As plain text above the answer</small></div><div>As thinking<small>In Claude Code’s own thinking blocks</small></div><div>Hidden<small>Only the answer</small></div></div></span>', key('showReasoning'))}
</div></section>

<section class="sheet"><h2>Storage</h2><div class="win flat set">
${row('Keep message history for', 'Splice’s record of which session messaged which. Older days are removed from this computer; nothing is sent anywhere.', '<span class="select">90 days ' + icon.chev + '</span>', key('activityRetentionDays'))}
${row('Keep request traces for', 'The turn-by-turn record behind Turns. Older days are deleted.', '<span class="select">7 days ' + icon.chev + '</span>', key('traceRetentionDays'))}
${row('Show git branches in', 'Folders beyond your home folder and /tmp where a session’s branch is read.', '<span style="display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end">' + chip('work') + chip('clients') + '<button class="btn sm">' + icon.plus + 'Add a folder</button></span>', key('statuslineGitRoots'))}
${row('OpenRouter key', 'Used by the OpenRouter plan. Splice keeps it in its key store and never shows it again.', '<span class="secret"><span class="mask">••••••••••••</span><button class="btn sm">Replace</button></span><span class="saved">' + icon.check + 'Saved</span>', key('OPENROUTER_API_KEY'))}
</div></section>

<section class="sheet"><h2>Tools</h2><div class="win flat set">
${row('Share tools across sessions', 'One process per tool server, handed to every session so any model can call it.', sw(true, 'ink'), key('daemon.mcp_hosting'))}
${row('filesystem', 'Reads and writes files in the folders you allow. Started when a session first calls it.', '<span class="state work"><i></i>Running</span>' + sw(true, 'ink'))}
${row('browser', 'Drives a web page for a session. Left out of sharing until you turn it on.', '<span class="state idle"><i></i>Not shared</span>' + sw(false, 'ink'), key('daemon.mcp_hosting_exclude'))}
</div></section>

<section class="sheet"><h2>Health</h2><div class="win flat set">
${row('Everything splice depends on', 'Sign-ins, ports, folders and the daemon itself, checked a minute ago.', '<span class="state work"><i></i>Mostly good</span><button class="btn sm">Check again</button>')}
${row('Four commands are not linked', 'claude-grok, claude-kimi, claude-muse and claudex are not on your PATH yet, so a terminal cannot start them.', '<span class="state stuck"><i></i>Needs you</span><button class="btn go sm">Link them</button>')}
</div></section>
</div></div>`;
}
