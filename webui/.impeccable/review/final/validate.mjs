// THE FINISH ROUND'S CAPTURE CHECK (M3-04). The finish reviewer has no browser, so its check 0 is
// the whole gate on these frames: one malformed capture stops the review. This runs that check
// before the frames are handed over, on every file in the round:
//   DIMENSIONS  the PNG is exactly the viewport its name claims (desktop 1536x1024, mobile 390x844).
//   NO DEAD PAINT  no tile of a 24x24 grid is pure black (every channel <= 4) or pure white (>= 251):
//              neither room paints either, so such a tile is a hole in the frame, not a quiet ground.
//   NOT ONE FILL  the most common colour covers under 85% of the frame (capture.mjs's own floor).
// capture.mjs has already refused a frame that did not answer, did not render or lost its fixture;
// the names the round writes carry the address, so "content matches the filename" is capture.mjs's
// fixture marker plus the contact look this row takes of every frame.
// Usage: node webui/.impeccable/review/final/validate.mjs <dir>   exit 1 on any failure, by name.
import { readdirSync } from 'node:fs';
import path from 'node:path';

const { loadRaster } = await import('/home/marcos/.claude/skills/impeccable/scripts/lib/png.mjs');
const SIZES = { desktop: [1536, 1024], mobile: [390, 844] };
const dir = process.argv[2];
const files = readdirSync(dir).filter((f) => /^[a-z]+-(dark|light)-(desktop|mobile)\.png$/.test(f)).sort();
let bad = 0;
for (const f of files) {
  const { image } = loadRaster(path.join(dir, f));
  const { width: w, height: h, data } = image;
  const [ew, eh] = SIZES[f.match(/-(desktop|mobile)\.png$/)[1]];
  const why = [];
  if (w !== ew || h !== eh) why.push(`is ${w}x${h}, not ${ew}x${eh}`);
  const counts = new Map();
  // 24, not 12: a tile only fails when a hole covers it whole, and a 150x120 black block planted
  // on a phone frame straddled every 12-grid tile it touched and passed
  const N = 24;
  for (let ty = 0; ty < N; ty++) for (let tx = 0; tx < N; tx++) {
    let black = true; let white = true;
    for (let y = Math.floor((ty * h) / N); y < Math.floor(((ty + 1) * h) / N) && (black || white); y++) {
      for (let x = Math.floor((tx * w) / N); x < Math.floor(((tx + 1) * w) / N); x++) {
        const i = (y * w + x) * 4;
        const lo = Math.min(data[i], data[i + 1], data[i + 2]); const hi = Math.max(data[i], data[i + 1], data[i + 2]);
        if (hi > 4) black = false; if (lo < 251) white = false;
        if (!black && !white) break;
      }
    }
    if (black || white) why.push(`tile ${tx},${ty} is pure ${black ? 'black' : 'white'}`);
  }
  for (let i = 0; i < data.length; i += 4) { const k = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2]; counts.set(k, (counts.get(k) ?? 0) + 1); }
  const top = Math.max(...counts.values()) / (w * h);
  if (top >= 0.85) why.push(`one colour covers ${(top * 100).toFixed(1)}%`);
  if (why.length) bad++;
  console.log(`${why.length ? 'BAD ' : 'ok  '} ${f.padEnd(28)} ${w}x${h}  top colour ${(top * 100).toFixed(1)}%${why.length ? `  ${why.join('; ')}` : ''}`);
}
if (files.length === 0) { console.log('no captures found'); process.exit(1); }
console.log(`${files.length} captures, ${bad} bad`);
process.exit(bad ? 1 : 0);
