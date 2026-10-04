// `bun console-next/tests/support/print-notices.ts [--write]`: the console-next sections of THIRD_PARTY_NOTICES.md as the bundle says they
// should read. With --write the two marked blocks in the file are replaced in place; without it they are printed.
import { readFileSync, writeFileSync } from 'node:fs';
import { BEGIN, END, FONT_BEGIN, FONT_END, NOTICES_FILE, readBundle, renderFonts, renderPackages } from './licenses';

const bundle = await readBundle();
const blocks: [string, string, string][] = [[BEGIN, END, renderPackages(bundle.packages)], [FONT_BEGIN, FONT_END, renderFonts(bundle.fonts)]];
if (!process.argv.includes('--write')) {
  for (const [begin, end, body] of blocks) console.log(`${begin}\n${body}\n${end}\n`);
} else {
  let text = readFileSync(NOTICES_FILE, 'utf8');
  for (const [begin, end, body] of blocks) {
    const from = text.indexOf(begin);
    const to = text.indexOf(end);
    if (from === -1 || to === -1) throw new Error(`THIRD_PARTY_NOTICES.md has no ${begin} block`);
    text = `${text.slice(0, from + begin.length)}\n${body}\n${text.slice(to)}`;
  }
  writeFileSync(NOTICES_FILE, text);
  console.log(`wrote ${bundle.packages.length} packages and ${bundle.fonts.length} font families`);
}
