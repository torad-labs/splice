// The comps' shared frame: the sidebar (places only), the wall, and the small icon set. Seeded demo content only:
// no real session names, messages or paths appear in anything under docs/design/comps.
export const icon = {
  grip: '<svg width="14" height="18" viewBox="0 0 14 18" fill="currentColor"><circle cx="4" cy="3" r="1.6"/><circle cx="10" cy="3" r="1.6"/><circle cx="4" cy="9" r="1.6"/><circle cx="10" cy="9" r="1.6"/><circle cx="4" cy="15" r="1.6"/><circle cx="10" cy="15" r="1.6"/></svg>',
  search: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="7" cy="7" r="5"/><path d="M11 11l3.5 3.5"/></svg>',
  back: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 3L5 8l5 5"/></svg>',
  chev: '<svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5l3 3 3-3"/></svg>',
  arrow: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 8h10M9 4l4 4-4 4"/></svg>',
  check: '<svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 7.5l3 3 6-7"/></svg>',
  stop: '<svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor"><rect x="1" y="1" width="10" height="10" rx="2"/></svg>',
  send: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 13V3M3.5 7.5L8 3l4.5 4.5"/></svg>',
  plus: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M8 3v10M3 8h10"/></svg>',
  key: '<svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4L2 7l3 3M9 4l3 3-3 3"/></svg>',
  folder: '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M1.5 4.5a1 1 0 011-1h3.2l1.6 1.8h6.2a1 1 0 011 1V12a1 1 0 01-1 1h-11a1 1 0 01-1-1z"/></svg>',
  clock: '<svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="7" cy="7" r="5.5"/><path d="M7 4v3.2l2 1.3"/></svg>',
};

export const NAV = [
  ['needs', 'Needs you', 4],
  ['sessions', 'Sessions', 0],
  ['fleet', 'Fleet', 0],
  ['turns', 'Turns', 0],
  ['usage', 'Usage', 0],
  ['settings', 'Settings', 0],
];

export function shell({ current, theme, body, css = '' }) {
  const nav = NAV.map(([id, label, n]) =>
    `<a href="${id}.html"${id === current ? ' aria-current="page"' : ''}>${label}${n ? `<span class="count">${n}</span>` : ''}</a>`).join('');
  return `<!doctype html><html lang="en" data-theme="${theme}"><head><meta charset="utf-8"><title>splice — ${current}</title>
<link rel="stylesheet" href="comp.css"><style>${css}</style><link rel="stylesheet" href="calm.css"></head><body>
<div class="app"><aside class="side"><div class="mark">splice</div><nav class="nav" aria-label="Pages">${nav}</nav>
<div class="foot"><span class="live"><i class="dot"></i><b>Daemon running</b></span><span>0.4.0 · this computer</span></div></aside>
<main class="wall"><div class="wrap">${body}</div></main></div></body></html>`;
}

export const models = {
  claude: { cmd: 'claude-splice', model: 'opus-5.5' },
  grok: { cmd: 'claude-grok', model: 'grok-4.7' },
  gpt: { cmd: 'claudex', model: 'gpt-6-sol' },
  kimi: { cmd: 'claude-kimi', model: 'kimi-k3' },
  muse: { cmd: 'claude-muse', model: 'muse-spark-1.3' },
};
