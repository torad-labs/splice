// THE KIT'S CHARTS (DESIGN.md section 9, "show, then say"). What is pinned here is what a capture
// cannot show: that every shape carries its numbers to a screen reader, that an unreported point is
// a gap and never a zero, that a mark paints only the tokens the contrast wall measures, and that
// nothing moves under reduced motion. A .ts test holds no JSX, so elements are built with
// createElement and asserted on the static markup.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement as h } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { Braid, LayerChip, LifetimeBar, Ring, Sparkline, StackedBar, Waterfall } from '../src/shared/ui';

const render = (element: Parameters<typeof renderToStaticMarkup>[0]): string => renderToStaticMarkup(element);
const nameOf = (html: string): string => /role="img" aria-label="([^"]*)"/.exec(html)?.[1] ?? '';
const css = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src/shared/ui/charts.css'), 'utf8');

describe('Sparkline', () => {
  test('says its last value and its peak', () => {
    expect(nameOf(render(h(Sparkline, { values: [1, 4, 2], label: 'Tokens per hour' })))).toBe('Tokens per hour: 2 last, 4 peak');
  });

  test('an hour nothing reported breaks the line instead of drawing it to zero', () => {
    const html = render(h(Sparkline, { values: [3, null, 5, 6], label: 'Turns' }));
    expect(html.match(/<path /g)).toHaveLength(2);
  });

  test('a series with no reading says none and draws no line', () => {
    const html = render(h(Sparkline, { values: [null, null], label: 'Turns' }));
    expect(nameOf(html)).toBe('Turns: none');
    expect(html).not.toContain('<path');
  });
});

describe('StackedBar', () => {
  const parts = [
    { key: 'in', label: 'In', value: 30, mark: 'series-1' as const },
    { key: 'out', label: 'Out', value: 10, mark: 'series-3' as const },
  ];

  test('each part takes its share of the whole, and the name carries every figure', () => {
    const html = render(h(StackedBar, { parts, label: 'Tokens' }));
    expect(html).toContain('width:75%');
    expect(html).toContain('width:25%');
    expect(nameOf(html)).toBe('Tokens: In 30, Out 10');
  });

  test('a total larger than the parts leaves the rest as track', () => {
    expect(render(h(StackedBar, { parts, label: 'Budget', total: 80 }))).toContain('width:37.5%');
  });

  test('a zero part draws no segment but keeps its key in the legend', () => {
    const html = render(h(StackedBar, { parts: [...parts, { key: 'gone', label: 'Gone', value: 0, mark: 'series-2' }], label: 'Sessions', legend: true }));
    expect(html.match(/myx-stack-part/g)).toHaveLength(2);
    expect(html).toContain('myx-stack-key-none');
  });
});

describe('Ring', () => {
  test('the dash is the share, and the name says it as a percentage', () => {
    const html = render(h(Ring, { value: 0.89, label: 'Cache read' }));
    expect(html).toContain('stroke-dasharray="89 100"');
    expect(nameOf(html)).toBe('Cache read 89%');
  });

  test('a share past the ends is clamped', () => {
    expect(render(h(Ring, { value: 1.4, label: 'Used' }))).toContain('stroke-dasharray="100 100"');
  });
});

describe('LifetimeBar', () => {
  test('born, last seen and now sit on the shared axis', () => {
    const html = render(h(LifetimeBar, { start: 25, seen: 50, now: 100, from: 0, to: 100, label: 'Started 25, seen 50' }));
    expect(html).toContain('left:25%;width:25%'); // reporting
    expect(html).toContain('left:50%;width:50%'); // silent since
    expect(nameOf(html)).toBe('Started 25, seen 50');
  });

  test('a life that began before the window starts at its edge', () => {
    expect(render(h(LifetimeBar, { start: -50, seen: 50, now: 100, from: 0, to: 100, label: 'x' }))).toContain('left:0%;width:50%');
  });
});

describe('Waterfall', () => {
  test('stages sit on the shared scale and the name lists where the time went', () => {
    const html = render(h(Waterfall, {
      scale: 2000,
      label: 'Turn',
      stages: [
        { key: 'queue', label: 'Queue', start: 0, end: 200, mark: 'series-1' },
        { key: 'stream', label: 'Stream', start: 1000, end: 1500, mark: 'hue' },
      ],
    }));
    expect(html).toContain('left:0%;width:10%');
    expect(html).toContain('left:50%;width:25%');
    expect(nameOf(html)).toBe('Turn: Queue 200 ms, Stream 500 ms');
  });
});

describe('LayerChip', () => {
  test('the winner is lit, the layers under it outlined, the ones over it unset', () => {
    const html = render(h(LayerChip, { names: ['Default', 'File', 'Head', 'Env'], active: 2, label: 'Source' }));
    expect(html.match(/myx-layer-under/g)).toHaveLength(2);
    expect(html.match(/myx-layer-on/g)).toHaveLength(1);
    expect(nameOf(html)).toBe('Source: Head');
  });
});

describe('Braid', () => {
  const strands = [
    { key: 'a', name: 'claudex', hue: 'myx-hue-1', count: 2, landed: 5 },
    { key: 'b', name: 'grok', hue: 'myx-hue-2', count: 0, landed: 0 },
  ];

  test('one strand per head, each named with its count, and the total printed beside them', () => {
    const html = render(h(Braid, { strands, label: 'Turns', unit: 'in flight' }));
    expect(html).toContain('aria-label="claudex: 2 in flight"');
    expect(html).toContain('aria-label="grok: 0 in flight"');
    expect(html).toContain('myx-braid-total" aria-hidden="true">2<');
    expect(html).toContain('aria-label="Turns: 2 in flight"');
  });

  test('an idle head keeps a strand, and nothing pulses on the first paint', () => {
    const html = render(h(Braid, { strands, label: 'Turns', unit: 'in flight' }));
    expect(html).toContain('myx-strand-idle');
    expect(html).not.toContain('myx-strand-pulse');
  });
});

describe('the sheet', () => {
  test('every mark paints a token the contrast wall measures', () => {
    const marks = [...css.matchAll(/^\.myx-mark-([a-z0-9-]+) \{ --mark: var\(([^)]*)\)/gm)].map((m) => [m[1], m[2]]);
    expect(marks.map(([mark]) => mark)).toEqual(['series-1', 'series-2', 'series-3', 'ok', 'warn', 'danger', 'hue']);
    for (const [, token] of marks) expect(token).toMatch(/^--(series-[123]|ok|warn|danger|hue, var\(--head-none)$/);
  });

  test('nothing moves under reduced motion', () => {
    const reduced = css.slice(css.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(reduced).toMatch(/\.myx-strand-pulse \{ animation: none; \}/);
    // every other movement reads a --dur token, which the token sheet sets to 0ms there
    const durations = [...css.matchAll(/(?:transition|animation):[^;]*/g)].map((m) => m[0]);
    for (const rule of durations) expect(rule).toMatch(/var\(--dur-\d\)|none/);
  });
});
