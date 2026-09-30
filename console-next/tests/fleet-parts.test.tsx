// A Fleet card rendered to markup: what it says in each state.
import { DndContext } from '@dnd-kit/core';
import { SortableContext } from '@dnd-kit/sortable';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import type { FleetCard } from '../src/lib/fleet';
import { FleetCardView } from '../src/pages/fleet/FleetCard';

const card = (over: Partial<FleetCard> = {}): FleetCard => ({
  key: 'claude-grok', title: 'claude-grok', colour: 'grok', tone: 'work', state: 'Ready', attention: false,
  line: { kind: 'gauge', name: '5 hours', pct: 41, note: 'resets Oct 5, 4:40 PM', full: false }, meta: ['grok', 'Ava’s Grok', '2 sessions'], fix: null, ...over,
});
const render = (facts: FleetCard, fix: string | null = null) =>
  renderToStaticMarkup(
    <MemoryRouter>
      <DndContext>
        <SortableContext items={[facts.key]}>
          <ul>
            <FleetCardView facts={facts} fix={fix === null ? null : <button type="button">{fix}</button>} />
          </ul>
        </SortableContext>
      </DndContext>
    </MemoryRouter>,
  );

describe('a fleet card', () => {
  test('a ready card is a title that opens its page, a state, one window and one quiet line', () => {
    const html = render(card());
    expect(html).toContain('href="/fleet/claude-grok"');
    expect(html).toContain('Ready');
    expect(html).toContain('5 hours');
    expect(html).toContain('41%');
    expect(html).toContain('width:41%');
    expect(html).toContain('Ava’s Grok');
    expect(html).toContain('win grok');
    expect(html).not.toContain('class="acts"');
  });
  test('a window that has refused turns is drawn full, and a card that needs a person drops its hue and shows its one act', () => {
    const html = render(card({ attention: true, tone: 'quota', state: 'Out of quota until Oct 5, 2:13 PM', line: { kind: 'gauge', name: 'Week', pct: 100, note: 'out until Oct 5, 2:13 PM', full: true }, fix: 'switch' }), 'Switch account');
    expect(html).toContain('track full');
    expect(html).toContain('win grok attn');
    expect(html).toContain('Switch account');
    expect(html).toContain('Out of quota until Oct 5, 2:13 PM');
  });
  test('a healthy head with nothing to draw has no glass block at all', () => {
    expect(render(card({ line: null }))).not.toContain('glass');
  });
  test('a note stands where there is no window', () => {
    expect(render(card({ line: { kind: 'note', text: 'The runtime is not answering on :8099.' }, tone: 'idle', state: 'Runtime off' }))).toContain('The runtime is not answering on :8099.');
  });
});
