// Add models: offered only to a plan the daemon offers models to.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { AddModels } from '../src/pages/fleet/AddModels';

const render = (head: string): string => {
  const client = new QueryClient();
  client.setQueryData(['add-model', '/api/add-model'], { path: '/x/splice.toml', heads: [{ head: 'claudeor', provider: 'openrouter', models: [{ id: 'a/b', label: 'B', context_window: 200000, slots: [] }] }] });
  return renderToStaticMarkup(<QueryClientProvider client={client}><AddModels head={head} /></QueryClientProvider>);
};

describe('add models', () => {
  test('a plan the daemon offers models to gets the button', () => {
    expect(render('claudeor')).toContain('Add models');
  });
  test('any other plan gets nothing', () => {
    expect(render('claude-grok')).toBe('');
  });
});
