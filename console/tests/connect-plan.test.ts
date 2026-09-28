import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { PlanPicker } from '../src/widgets/connect-plan';
import { planChoices } from '../src/widgets/connect-plan/model';
import type { AddProfile } from '../src/entities/add';

const ids = ['codex', 'grok', 'kimi', 'muse', 'openrouter', 'local'] as const;
const profiles: AddProfile[] = ids.map((id) => ({
  name: id, summary: `${id} plan`, auth_kind: 'chatgpt-oauth', requires_key: false,
  base_url: null, head_key: id, command: `claude-${id}`, models: [], asks: [],
}));

describe('the first-hour plan chooser', () => {
  test('each plan description belongs inside its named button, with Other providers the same', () => {
    const html = renderToStaticMarkup(createElement(PlanPicker, {
      choices: planChoices(profiles), onSelect: () => undefined, onOther: () => undefined,
    }));
    const cards = [...html.matchAll(/<button[^>]*aria-label="([^"]+)"[^>]*>(.*?)<\/button>/gs)]
      .filter((match) => match[0].includes('myx-connect-card'));
    expect(cards).toHaveLength(7);
    expect(cards.map((card) => card[1])).toEqual(['ChatGPT', 'Grok', 'Kimi', 'Muse', 'OpenRouter key', 'Local model', 'Other providers']);
    for (const card of cards) expect(card[2]).toContain('myx-connect-description');
    expect(cards[0]?.[2]).toContain('Sign in with your ChatGPT plan.');
  });

  test('an unavailable plan announces why while keeping its exact button name', () => {
    const html = renderToStaticMarkup(createElement(PlanPicker, {
      choices: planChoices(profiles.filter((profile) => profile.name !== 'kimi')),
      onSelect: () => undefined, onOther: () => undefined,
    }));
    const kimi = /<button[^>]*aria-label="Kimi"[^>]*>.*?<\/button>/s.exec(html)?.[0];
    expect(kimi).toBeDefined();
    expect(kimi).toContain('disabled=""');
    const reasonId = /aria-describedby="([^"]+)"/.exec(kimi ?? '')?.[1];
    expect(reasonId).toBeDefined();
    expect(kimi).toContain(`id="${reasonId}"`);
    expect(kimi).toContain('Unavailable in this splice build.');
  });
});
