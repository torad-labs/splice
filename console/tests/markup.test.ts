// The markup reader every page test reads tables and figures through (tests/lib/markup.ts). It is
// pinned here on the shapes that broke the one-pass tag strip it replaced: nested tags, a `>` inside
// a quoted attribute, React's entities, and an empty cell.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { cellText, escapeHtml, statOf, tableOf } from './lib/markup';

const h = React.createElement;

describe('cell text', () => {
  test('nested tags leave only their text', () => {
    expect(cellText('<td class="a"><span><b>64</b>%</span> used</td>')).toBe('64% used');
  });

  test('a > inside a quoted attribute does not end the tag', () => {
    expect(cellText('<td title="a > b" data-x=\'c > d\'>kept</td>')).toBe('kept');
  });

  test('the entities React escapes are decoded, once', () => {
    expect(cellText('<p>Tom &amp; Jerry&#x27;s &lt;tag&gt; &quot;q&quot;</p>')).toBe('Tom & Jerry\'s <tag> "q"');
    expect(cellText('&amp;lt;')).toBe('&lt;');
  });

  test('an empty cell is empty, and an unclosed tag shows nothing', () => {
    expect(cellText('<td></td>')).toBe('');
    expect(cellText('<td')).toBe('');
  });

  test('reads what React renders, whatever the text holds', () => {
    const text = `a > b & "c" <d> it's`;
    const html = renderToStaticMarkup(h('p', { title: text }, text));
    expect(html).toContain(escapeHtml(text));
    expect(cellText(html)).toBe(text);
  });
});

describe('a labelled table', () => {
  const html = renderToStaticMarkup(h('table', { 'aria-label': 'Heads & pools' },
    h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Head'), h('th', { scope: 'col' }, 'Share'))),
    h('tbody', null,
      h('tr', { className: 'myx-dt-group' }, h('td', { colSpan: 2 }, 'chatgpt')),
      h('tr', null, h('td', null, h('b', null, 'claudex')), h('td', null, '64%')),
      h('tr', null, h('td', null, 'grok'), h('td', null)),
    )));

  test('names its columns, keeps group rows apart and reads each cell as text', () => {
    const table = tableOf(html, 'Heads & pools');
    expect(table.names).toEqual(['Head', 'Share']);
    expect(table.groups).toHaveLength(1);
    expect(table.rows).toHaveLength(2);
    expect(table.cells).toEqual([['claudex', '64%'], ['grok', '']]);
  });

  test('a table that is not there reads as empty', () => {
    expect(tableOf(html, 'Accounts')).toEqual({ names: [], rows: [], groups: [], cells: [] });
  });
});

describe('a stat', () => {
  test('is found by its label, with its figure and caption as text', () => {
    const html = renderToStaticMarkup(h('div', null,
      h('p', { className: 'myx-stat-label' }, 'Nearest limit'),
      h('p', { className: 'myx-stat-value' }, '64', h('span', { className: 'myx-stat-unit' }, '%')),
      h('p', { className: 'myx-stat-sub' }, 'work 7d'),
      h('p', { className: 'myx-stat-label' }, 'Next reset'),
      h('p', { className: 'myx-stat-value' }, '21m'),
    ));
    expect(statOf(html, 'Nearest limit')).toEqual({ value: '64%', sub: 'work 7d' });
    expect(statOf(html, 'Next reset')).toEqual({ value: '21m', sub: null });
    expect(statOf(html, 'Excluded')).toBeNull();
  });
});
