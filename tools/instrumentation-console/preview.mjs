import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { response, settings } from './fixtures.mjs';
const root = resolve('app/src/main/resources/console');
let profile = null;
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1'), state = url.searchParams.get('state') || 'partial';
  if (url.pathname.startsWith('/api/')) {
    const body = req.method === 'GET' ? null : await new Promise((done) => { let text = ''; req.on('data', (x) => { text += x; }); req.on('end', () => done(JSON.parse(text || '{}'))); });
    let data = response(req.url, state);
    if (url.pathname === '/api/perf/objectives' && req.method === 'PUT') { Object.assign(settings, body); data = { status: 'ok', revision: 'canonical-fixture-r2' }; }
    if (url.pathname === '/api/perf/profiles' && req.method === 'POST') { profile = { ...data, status: 'running', until_ms: null, artifact_available: false, settings: body }; data = profile; }
    if (url.pathname.endsWith('/stop')) { profile = { ...profile, status: 'aborted', until_ms: Date.now(), artifact_available: false }; data = profile; }
    if (req.method === 'GET' && profile && /\/profiles(?:\/fixture-profile)?$/.test(url.pathname)) data = profile;
    if (url.pathname.endsWith('/artifact')) { res.writeHead(200, { 'content-type': 'application/octet-stream' }); res.end('fixture-artifact'); return; }
    res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(data)); return;
  }
  if (url.pathname === '/' || url.pathname === '/preview.html') {
    const theme = url.searchParams.get('theme') === 'day' ? 'day' : 'night';
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(`<!doctype html><html data-theme="${theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Splice performance fixture</title><link rel="stylesheet" href="/tokens.css"><link rel="stylesheet" href="/console.css"><link rel="stylesheet" href="/perf-heads.css"></head><body><div class="app"><main class="page"><header class="head"><h1>Usage</h1></header><div id="fixture"></div></main></div><script src="/api.js"></script><script src="/perf-heads.js"></script><script>
let query=PERF_CONSOLE.fromAddress({window:'5h',filters:{project:'splice',local:'0'},objective_id:'interactive'});
const view=PERF_CONSOLE.create({document,API,setTimeout,clearTimeout,setInterval},{id:'fixture',query:()=>query,apply:q=>{query=q}});document.querySelector('#fixture').innerHTML=view.html();window.fixture={view,API};
</script></body></html>`); return;
  }
  const file = resolve(root, '.' + url.pathname);
  if (!file.startsWith(root + '/')) { res.writeHead(403); res.end(); return; }
  try { const bytes = await readFile(file); const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' }; res.writeHead(200, { 'content-type': types[extname(file)] || 'application/octet-stream' }); res.end(bytes); }
  catch { res.writeHead(404); res.end(); }
});
server.listen(Number(process.env.SPLICE_CONSOLE_FIXTURE_PORT || 4318), '127.0.0.1', () => console.log('Performance fixture http://127.0.0.1:4318/preview.html'));
