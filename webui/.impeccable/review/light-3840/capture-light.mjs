// M1-40: capture all 13 addresses in the LIGHT room at 3840x2160.
// Reuses capture.mjs's capturePage so the four-claim guard (ANSWERED / RENDERED / NOT FLAT /
// FIXTURE) applies exactly as it does in the dark sweep. One Chrome per address: a single
// process driving 13 pages at 3840 timed out during M1-29.
const R = '/home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0';
const { mgmtKey, withChrome } = await import(`${R}/.dev/web-console/lib/cdp.mjs`);
const { capturePage } = await import(`${R}/.dev/web-console/capture.mjs`);
const { addresses, urlFor } = await import(`${R}/.dev/web-console/lib/fixtures.mjs`);

const OUT = `${R}/webui/.impeccable/review/light-3840`;
const rows = [];
for (const addr of addresses()) {
  const url = urlFor(addr);
  const out = `${OUT}/${addr}-light-3840x2160.png`;
  try {
    const r = await withChrome({ 'myx-mgmt-key': mgmtKey(), 'splice.theme': 'light' }, async (send) =>
      capturePage(send, url, 3840, 2160, out, 7000));
    rows.push({ addr, state: r.state, paper: +(r.paper * 100).toFixed(2), top: r.frame.colour,
                topShare: +(r.frame.share * 100).toFixed(1), sample: r.claim.sample });
    console.log(`ok   ${addr.padEnd(11)} ${r.state.padEnd(5)} paper ${(r.paper*100).toFixed(2).padStart(6)}%  top ${(r.frame.share*100).toFixed(1).padStart(5)}% ${r.frame.colour}  sample=${r.claim.sample}`);
  } catch (e) {
    rows.push({ addr, state: 'FAILED', error: e.message });
    console.log(`FAIL ${addr}: ${e.message.split('\n')[0]}`);
  }
}
console.log('\n' + JSON.stringify(rows));
