// Capture one page of the console from the pinned dev server (CONTRACTS.md section 4).
//
// WHY NOT `--screenshot=`: the console sits behind the management-key gate and a headless
// Chrome profile has an empty localStorage, so a plain screenshot captures the gate on every
// page. This drives the same Chrome over CDP only to seed the key into localStorage before
// the app boots, then captures the frame the recipe asks for. No source file is touched and
// the key is never printed. Vendored 2026-09-18 from design-builder4's M2-02 capture script.
//
// Usage: node .dev/web-console/capture.mjs '<url>' <absolute out.png> [<width> <height>]
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [, , url, out, w = '1536', h = '1024'] = process.argv;
if (!url || !out) {
  console.error("usage: node .dev/web-console/capture.mjs '<url>' <absolute out.png> [<width> <height>]");
  process.exit(2);
}
const width = Number(w);
const height = Number(h);
const key = readFileSync(join(process.env.HOME, '.claude-codex/state/mgmt-key'), 'utf8').trim();
const port = 9333 + Math.floor(Math.random() * 400);
const profile = mkdtempSync(join(tmpdir(), 'console-capture-')); // throwaway browser profile

const chrome = spawn('/usr/bin/google-chrome', [
  '--headless=new',
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profile}`,
  '--no-first-run',
  '--hide-scrollbars',
  `--window-size=${width},${height}`,
  'about:blank',
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForChrome() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await sleep(200);
  }
  throw new Error('chrome never answered on the debugging port');
}

function client(ws) {
  let next = 1;
  const pending = new Map();
  ws.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data);
    const slot = pending.get(msg.id);
    if (slot) {
      pending.delete(msg.id);
      msg.error ? slot.reject(new Error(JSON.stringify(msg.error))) : slot.resolve(msg.result);
    }
  });
  return (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = next++;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
}

try {
  await waitForChrome();
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve) => ws.addEventListener('open', resolve, { once: true }));
  const send = client(ws);

  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try { localStorage.setItem('myx-mgmt-key', ${JSON.stringify(key)}); } catch (e) {}`,
  });
  await send('Page.navigate', { url });
  await sleep(6000); // boot, fonts, first poll, and the fixture import

  const shot = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(out, Buffer.from(shot.data, 'base64'));
  console.log(`wrote ${out} (${width}x${height})`);
} finally {
  chrome.kill('SIGKILL');
  rmSync(profile, { recursive: true, force: true });
}
