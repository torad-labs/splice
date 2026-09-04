#!/usr/bin/env node
// checks/e2e/docker/mock_codex.mjs — run the migration oracle's VENDORED ChatGPT-backend mock as a
// standalone upstream for the fresh-machine e2e.
//
// The mock is not copied here: it is sliced out of dev/campaigns/proxy-hardening/oracle/
// mock-upstream.vendored.mjs between the same two markers replay.mjs slices on, so the e2e speaks
// to the exact bytes the oracle's fixtures were captured against. A drift in the vendored file is
// the oracle wall's business (sha-pinned in fixtures/_manifest.json), not this harness's.
//
// Usage: mock_codex.mjs <repo-root> [port]
//   Prints {"port": N, "auth_path": "..."} once listening, then serves until killed.
//   The mock writes its own throwaway auth.json (tokens tok-old/refresh-1) and answers
//   /oauth/token, so the daemon's refresh path is exercised against it too.
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';

const [root, portArg] = process.argv.slice(2);
if (!root) {
  console.error('usage: mock_codex.mjs <repo-root> [port]');
  process.exit(2);
}
const port = Number(portArg || 0);
const vendored = readFileSync(join(root, 'dev/campaigns/proxy-hardening/oracle/mock-upstream.vendored.mjs'), 'utf8');
const START = "import http from 'node:http';";
const END = "mock.listen(0, '127.0.0.1');";
const s = vendored.indexOf(START);
const e = vendored.indexOf(END);
if (s < 0 || e < 0) {
  console.error('mock_codex: vendored mock markers not found');
  process.exit(2);
}
const region = vendored.slice(s, e);
const tmp = mkdtempSync(join(tmpdir(), 'splice-e2e-mock-codex-'));
const modulePath = join(tmp, 'mock.mjs');
writeFileSync(
  modulePath,
  region + `\nmock.listen(${port}, '127.0.0.1');\nexport { mock, AUTH_PATH };\n`,
);
const m = await import(pathToFileURL(modulePath).href);
<<<<<<< HEAD
// The e2e's one route on top of the vendored mock: the ChatGPT usage endpoint splice polls for the
// plan's 5h/7d windows (GET <origin>/backend-api/wham/usage). Fixed numbers, so the response
// headers and the status-line bars are asserted exactly.
const USAGE = JSON.stringify({
  plan_type: 'plus',
  rate_limit: {
    allowed: true,
    limit_reached: false,
    primary_window: { used_percent: 14, limit_window_seconds: 18000, reset_after_seconds: 5880 },
    secondary_window: { used_percent: 42, limit_window_seconds: 604800, reset_after_seconds: 400000 },
  },
});
const vendoredHandler = m.mock.listeners('request')[0];
m.mock.removeAllListeners('request');
m.mock.on('request', (req, res) => {
  if (req.url === '/backend-api/wham/usage') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(USAGE);
    return;
  }
  vendoredHandler(req, res);
});
=======
>>>>>>> origin/main
if (!m.mock.listening) await once(m.mock, 'listening');
console.log(JSON.stringify({ port: m.mock.address().port, auth_path: m.AUTH_PATH }));
