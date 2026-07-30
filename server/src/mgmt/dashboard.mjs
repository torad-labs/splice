// Serve the committed single-file WebUI at /dashboard. The proxy runs without
// a frontend toolchain — webui/dist/index.html is a committed build artifact.
import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sendJson } from '../http/server.mjs';

const DIST_HTML = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'webui', 'dist', 'index.html');

let cache = { mtimeMs: -1, html: null };

export function handleDashboard(req, res) {
  const path = (req.url ?? '').split('?')[0];
  // Served by the control server at the root and at /dashboard.
  if (req.method !== 'GET' || !['/', '/dashboard', '/dashboard/'].includes(path)) return false;
  try {
    if (!existsSync(DIST_HTML)) {
      sendJson(res, 503, { error: { type: 'api_error', message: 'dashboard not built: webui/dist/index.html missing' } });
      return true;
    }
    const st = statSync(DIST_HTML);
    if (cache.mtimeMs !== st.mtimeMs) {
      cache = { mtimeMs: st.mtimeMs, html: readFileSync(DIST_HTML, 'utf8') };
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(cache.html);
  } catch (err) {
    // This wraps a FILE READ, so an ENOENT message carries the install path verbatim — the one
      // concrete leak among these flows.
    // Detail to stderr, generic to the client (CodeQL js/stack-trace-exposure, 2026-07-29).
    process.stderr.write(`[dashboard] dashboard render failed: ${err?.stack || err?.message || err}\n`);
    sendJson(res, 500, { error: { type: 'api_error', message: 'dashboard render failed — see the daemon log' } });
  }
  return true;
}
