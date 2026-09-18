// Capture one page of the console from the pinned dev server (CONTRACTS.md section 4).
//
// WHY NOT `--screenshot=`: the console sits behind the management-key gate and a headless
// Chrome profile has an empty localStorage, so a plain screenshot captures the gate on every
// page. This seeds the key into localStorage before the app boots, then captures the frame the
// recipe asks for. No source file is touched and the key is never printed. Vendored 2026-09-18
// from design-builder4's M2-02 capture script; the CDP plumbing moved to lib/cdp.mjs the same day
// so gate.mjs could render its contact sheets through the same path, with this command line and
// this behaviour unchanged.
//
// Usage: node .dev/web-console/capture.mjs '<url>' <absolute out.png> [<width> <height>]
import { mgmtKey, shoot, show, withChrome } from './lib/cdp.mjs';

const [, , url, out, w = '1536', h = '1024'] = process.argv;
if (!url || !out) {
  console.error("usage: node .dev/web-console/capture.mjs '<url>' <absolute out.png> [<width> <height>]");
  process.exit(2);
}
const width = Number(w);
const height = Number(h);

await withChrome({ 'myx-mgmt-key': mgmtKey() }, async (send) => {
  await show(send, url, width, height);
  await shoot(send, out);
  console.log(`wrote ${out} (${width}x${height})`);
});
