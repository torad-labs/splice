import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { ResumeRecipe, SessionRow } from '../src/entities/session';
import { fetchResumeRecipe, sessionKey } from '../src/entities/session';
import type { UsagePayload } from '../src/shared/api';
import { firstOtherHead, resumeChoices, resumeTarget, ResumeRecipeView, SessionsBoard } from '../src/pages/sessions';

const usage: UsagePayload = {
  window_hours: 5, warn_pct: 80, warn_tokens_5h: 0,
  heads: [
    { key: 'claudex', label: 'claudex', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { level: 'critical', pct: 100, source: 'quota_7d', reset: null },
      quota: { five_hour: { used_pct: 2, resets_at: null }, seven_day: { used_pct: 100, resets_at: null } },
    } },
    { key: 'grok', label: 'claude-grok', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { level: 'ok', pct: 1, source: 'quota_7d', reset: null },
      quota: { five_hour: { used_pct: 0, resets_at: null }, seven_day: { used_pct: 1, resets_at: null } },
    } },
    { key: 'muse', label: 'claude-muse', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null, warn: { level: 'ok', pct: 9, source: 'quota_5h', reset: null },
      quota: { five_hour: { used_pct: 9, resets_at: null }, seven_day: { used_pct: 3, resets_at: null } },
    } },
  ],
};
const keys = ['claudex', 'grok', 'muse', 'splice'];

const gone: SessionRow = {
  pid: 7, session_id: 'finished-id', name: 'finished', kind: 'interactive', version: '2', cwd: '/repo',
  status: 'idle', status_updated_at: 1_790_000_000_000, started_at: 1_790_000_000_000,
  updated_at: 1_790_000_000_000, address: null, head: 'splice', availability: 'gone',
};

function detail(row: SessionRow): string {
  return renderToStaticMarkup(createElement(SessionsBoard, {
    payload: { note: '', sessions: [row] }, linked: sessionKey(row),
  }));
}

describe('resume on a plan with room', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('the binding window picks Grok over spent ChatGPT and busier Muse', () => {
    expect(firstOtherHead(keys, 'splice', usage)).toBe('grok');
    expect(firstOtherHead(keys, 'splice')).toBe('claudex');
    expect(firstOtherHead(['splice'], 'splice', usage)).toBe('splice');
    const unknown = { ...usage, heads: usage.heads.filter((row) => row.key === 'claudex') };
    expect(firstOtherHead(['claudex', 'grok', 'splice'], 'splice', unknown)).toBe('grok');
    const tied = { ...usage, heads: usage.heads.map((row) => row.key === 'muse' && row.usage !== null
      ? { ...row, usage: { ...row.usage, quota: { seven_day: { used_pct: 1, resets_at: null } } } } : row) };
    expect(firstOtherHead(['muse', 'grok', 'splice'], 'splice', tied)).toBe('muse');
  });

  test('spent heads remain selectable, and a requested head still wins', () => {
    const heads = keys.map((key) => ({ key, label: key === 'grok' ? 'claude-grok' : key }));
    const options = resumeChoices(heads, usage);
    expect(options.map((option) => option.value)).toEqual(keys);
    expect(options.find((option) => option.value === 'claudex')?.label).toBe('claudex (Spent)');
    expect(options.find((option) => option.value === 'grok')?.label).toBe('claude-grok');
    expect(resumeTarget(keys, 'splice', usage, 'claudex', null)).toBe('claudex');
    expect(resumeTarget(keys, 'splice', usage, 'claudex', 'muse')).toBe('muse');
    expect(resumeTarget(keys, 'splice', null, null, null)).toBe('claudex');
  });

  test('the default requests the chosen head and displays its copyable resume command', async () => {
    const chosen = resumeTarget(keys, 'splice', usage, null, null);
    const recipe: ResumeRecipe = {
      session_id: 'finished-id', head: 'grok', argv: ['claude-grok', '-r', 'finished-id'],
      from: '/repo/session.jsonl', to_tree: '/heads/grok/projects/repo', copies: true,
      model: 'grok-5', live: false,
    };
    const asked: string[] = [];
    vi.stubGlobal('fetch', async (url: string) => {
      asked.push(url);
      return new Response(JSON.stringify(recipe), { status: 200 });
    });
    const answer = await fetchResumeRecipe('finished-id', chosen ?? '');
    expect(asked).toEqual(['/api/sessions/finished-id/resume?head=grok']);
    expect(renderToStaticMarkup(createElement(ResumeRecipeView, { recipe: answer }))).toContain('claude-grok -r finished-id');
  });

  test('a gone session puts Resume elsewhere before files, hand-offs and Send to', () => {
    const html = detail(gone);
    const resume = html.indexOf('>Resume elsewhere<');
    expect(resume).toBeGreaterThan(0);
    for (const title of ['Files', 'Hand-offs', 'Send a message']) {
      expect(html.indexOf(`>${title}<`), title).toBeGreaterThan(resume);
    }
    const live = detail({ ...gone, availability: 'live' });
    expect(live.indexOf('>Resume elsewhere<')).toBeGreaterThan(live.indexOf('>Send a message<'));
  });
});
