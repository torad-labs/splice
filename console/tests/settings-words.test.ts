// V4-403: Settings names neither the file's shape (topology) nor the process (daemon). Marlin's walk
// of V4-348 on bb54736ea read 'Topology', 'Write topology', 'Raw topology' and a 'daemon' section.
// The words the page prints are split by what they are: our own words (the labels, the buttons) and
// the operator's file (its path, its values, the raw text), which stays exactly as they wrote it.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';

import { fixtureTopology } from '../src/pages/settings/fixtures/settings';
import { TopologySection } from '../src/pages/settings/sections';
import { H, S } from '../src/pages/settings/strings';

const BANNED = /\b(topology|daemon)\b/i;

/** The words a person reads in [html]: text nodes and accessible names, less the operator's file. */
function readIn(html: string): string[] {
  const own = html.replace(/<(pre|textarea|code)\b[\s\S]*?<\/\1>/g, '').replace(/<span class="myx-settings-path">[\s\S]*?<\/span>/g, '');
  const names = [...own.matchAll(/aria-label="([^"]*)"/g)].map((match) => match[1]);
  const text = [...own.matchAll(/>([^<]+)</g)].map((match) => match[1]);
  return [...names, ...text].map((line) => line.trim()).filter((line) => line !== '');
}

describe('the words Settings prints', () => {
  test('a label, a button and a help sentence say neither topology nor daemon', () => {
    const strings = { ...S, ...H } as Record<string, unknown>;
    const offending = Object.entries(strings)
      .filter(([, value]) => typeof value === 'string' && BANNED.test(value))
      .map(([key]) => key);
    expect(offending).toEqual([]);
  });

  test('the file section prints its bays and buttons in our words, and the operator\'s tables under their own names', () => {
    const html = renderToStaticMarkup(createElement(TopologySection, {
      state: { path: '~/.config/splice/splice.toml', topology: fixtureTopology, stale: false },
      loaded: fixtureTopology, draft: fixtureTopology, onDraft: () => undefined, onWrite: () => undefined,
      busy: false, result: null, scope: 'other',
    }));
    const read = readIn(html);
    expect(read.filter((line) => BANNED.test(line))).toEqual([]);
    expect(read).toContain(S.groupName.daemon);
    expect(read).toContain(S.write);
    expect(read).toContain(S.rawToml);
  });
});
