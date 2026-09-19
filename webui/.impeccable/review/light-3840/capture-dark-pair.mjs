const R = '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome } = await import(`${R}/dev/web-console/lib/cdp.mjs`);
const { capturePage } = await import(`${R}/dev/web-console/capture.mjs`);
const { urlFor } = await import(`${R}/dev/web-console/lib/fixtures.mjs`);
const OUT = `${R}/webui/.impeccable/review/light-3840`;
for (const addr of ['settings','logs','fleet']) {
  const out = `${OUT}/pair-${addr}-dark-3840x2160.png`;
  const r = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': 'dark' }, async (send) =>
    capturePage(send, urlFor(addr), 3840, 2160, out, 7000));
  console.log(`ok ${addr} ${r.state} paper ${(r.paper*100).toFixed(2)}% top ${(r.frame.share*100).toFixed(1)}% ${r.frame.colour}`);
}
