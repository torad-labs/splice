// The CDP plumbing both console capture scripts share (CONTRACTS.md section 4).
//
// WHY CHROME OVER CDP AND NOT `--screenshot=`: the console sits behind the management-key gate and
// a headless Chrome profile has an empty localStorage, so a plain screenshot captures the gate on
// every page. Driving the same Chrome over the protocol is the only way to put values into
// localStorage BEFORE the app boots, which is also how a fixture address and a theme are set. No
// source file is touched and the key is never printed.
//
// Factored out of capture.mjs on 2026-09-18 so gate.mjs renders its contact sheets through the
// same path: one browser launch, one protocol client, one place the key is read.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** The management bearer key. Read here, passed into the page, and never logged. */
export function mgmtKey(home = process.env.HOME) {
  return readFileSync(join(home, '.claude-codex/state/mgmt-key'), 'utf8').trim();
}

/**
 * A chrome instance with one page open, and the protocol client for it.
 *
 * `values` are seeded into localStorage on EVERY new document, which is what makes them land
 * before the app boots: the theme feature reads `splice.theme` at module scope, and the HTTP
 * client reads the management key the same way. A value seeded after navigation would be read one
 * render too late.
 */
export async function withChrome(values, fn) {
  const port = 9333 + Math.floor(Math.random() * 400);
  const profile = mkdtempSync(join(tmpdir(), 'console-capture-'));
  const chrome = spawn('/usr/bin/google-chrome', [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--hide-scrollbars',
    '--window-size=1536,1024',
    'about:blank',
  ], { stdio: 'ignore' });

  try {
    for (let i = 0; i < 60; i++) {
      try {
        if ((await fetch(`http://127.0.0.1:${port}/json/version`)).ok) break;
      } catch { /* not up yet */ }
      await sleep(200);
      if (i === 59) throw new Error('chrome never answered on the debugging port');
    }

    const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })).json();
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve) => ws.addEventListener('open', resolve, { once: true }));

    let next = 1;
    const pending = new Map();
    ws.addEventListener('message', (event) => {
      const msg = JSON.parse(event.data);
      const slot = pending.get(msg.id);
      if (slot) {
        pending.delete(msg.id);
        if (msg.error) slot.reject(new Error(JSON.stringify(msg.error)));
        else slot.resolve(msg.result);
      }
    });
    const send = (method, params = {}) =>
      new Promise((resolve, reject) => {
        const id = next++;
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params }));
      });

    await send('Page.enable');
    const seed = Object.entries(values)
      .map(([key, value]) => `try { localStorage.setItem(${JSON.stringify(key)}, ${JSON.stringify(value)}); } catch (e) {}`)
      .join('\n');
    await send('Page.addScriptToEvaluateOnNewDocument', { source: seed });

    return await fn(send);
  } finally {
    await killChrome(chrome);
    // retries because the profile is a directory Chrome's children were still writing into: a
    // recursive rm can land between their exit and their last file and report ENOTEMPTY. Both the
    // wait above and the retries here are needed, and the failure they prevent is not cosmetic —
    // it threw out of `finally`, turning a SUCCESSFUL capture into exit 1.
    rmSync(profile, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 });
  }
}

/** SIGKILL and WAIT: kill() only delivers the signal, and Chrome's children outlive it briefly. */
function killChrome(chrome) {
  return new Promise((resolve) => {
    const done = () => resolve();
    chrome.once('exit', done);
    chrome.kill('SIGKILL');
    setTimeout(done, 5000);
  });
}

/** Point the viewport at one frame size and load one address.
 *
 *  `reload` exists because navigating to the URL the page is ALREADY on is a same-document
 *  navigation: the frame is not re-created, `addScriptToEvaluateOnNewDocument` never re-runs, and a
 *  value seeded since the last load is silently ignored. The gate hits this every time it captures
 *  the same address in the other theme — same address, same fixture, identical URL — so it asks for
 *  a reload there. Measured 2026-09-18: without it the first captures after a theme change came
 *  back in the previous room. */
export async function show(send, url, width, height, settleMs = 6000, { reload = false } = {}) {
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  if (reload) await send('Page.reload');
  else await send('Page.navigate', { url });
  await sleep(settleMs); // boot, fonts, first poll, and the fixture import
}

/** Capture the current viewport to a file, and return its bytes. */
export async function shoot(send, out) {
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  const bytes = Buffer.from(shot.data, 'base64');
  writeFileSync(out, bytes);
  return bytes;
}

/**
 * Render an HTML string to a PNG. Used for the contact sheets, so a sheet goes through exactly the
 * path a page capture does rather than a second renderer nobody tested.
 *
 * The file is written into the output's own directory so the sheet's relative `<img src>` resolve
 * (Chrome blocks a file:// page from reading file:// paths outside its own tree), and removed
 * afterwards so the gate directory holds only captures and the manifest.
 */
export async function renderHtml(send, html, htmlPath, out, width, height) {
  writeFileSync(htmlPath, html);
  try {
    await show(send, `file://${htmlPath}`, width, height, 1500);
    const metrics = await send('Page.getLayoutMetrics');
    const full = Math.ceil(metrics.cssContentSize?.height ?? height);
    await send('Emulation.setDeviceMetricsOverride', {
      width, height: Math.max(height, full), deviceScaleFactor: 1, mobile: false,
    });
    await sleep(500);
    return await shoot(send, out);
  } finally {
    rmSync(htmlPath, { force: true });
  }
}
