#!/usr/bin/env node
/**
 * mythosd — the control server: the centralized dashboard + cross-head management
 * plane (auth, usage soft-warn, config, full lifecycle). Loopback only. Serves:
 *   GET /            → the unified dashboard (webui/dist/index.html)
 *   *   /api/*       → aggregated control API (heads, config, usage, auth, logs)
 *   GET /health      → liveness for the launcher version handshake
 * The heads (codex/claudithos/grok) are the data plane; no Messages traffic is
 * proxied here — this plane never touches a subscription token.
 */
import { getConfig } from './config.mjs';
import { createProxyServer, listenLoopback, sendJson } from './http/server.mjs';
import { handleControlApi } from './control/api.mjs';
import { handleDashboard } from './mgmt/dashboard.mjs';
import { ensureMgmtKey } from './mgmt/api.mjs';
import { CONTROL_SERVER_VERSION as PROXY_VERSION } from './versions.mjs';

export { PROXY_VERSION };

const startedAt = Date.now();

export function createServer() {
  return createProxyServer(async (req, res) => {
    if (req.method === 'GET' && (req.url === '/health' || req.url?.startsWith('/health?'))) {
      sendJson(res, 200, {
        ok: true,
        mode: 'control-server',
        port: getConfig().controlPort,
        version: PROXY_VERSION,
        uptime_s: Math.round((Date.now() - startedAt) / 1000),
      });
      return;
    }
    if (await handleControlApi(req, res)) return;
    if (handleDashboard(req, res)) return;
    res.writeHead(404);
    res.end('Not Found');
  });
}

export function startServer() {
  const cfg = getConfig();
  // Mint the bearer key at boot so the dashboard and the launcher can read it
  // immediately (authorized() only mints it lazily on a bearer-carrying request).
  ensureMgmtKey();
  const server = createServer();
  return listenLoopback(server, cfg.controlPort, 'control-server', () => {
    process.stderr.write(`[control-server] listening on http://127.0.0.1:${cfg.controlPort} v${PROXY_VERSION} — dashboard at /\n`);
  });
}

// Re-export for tooling/tests.
export { handleControlApi } from './control/api.mjs';

if (process.env.CONTROL_SERVER_TEST !== '1') {
  startServer();
}
