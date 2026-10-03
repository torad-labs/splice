// V4-444: the Requests view lives in its address. A link from Usage or from a command's Failed count opens the
// same rows, and a selector the page cannot read is said, never dropped: dropping it shows the whole window under
// a link that promised a narrower one.
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { askOf, searchOf, viewOf } from '../src/lib/requests-view';

const view = (query: string) => viewOf(new URLSearchParams(query));
const HOUR = 3_600_000;

describe('the address as a view', () => {
  test('a bare address is the last hour, every status, nothing narrowed', () => {
    expect(view('')).toEqual({
      range: { kind: 'last', window: '1h' },
      status: 'all',
      head: null, model: null, account: null, session: null, unattributed: null,
      unread: null,
    });
  });

  test('the window, the status and each selector are read as written', () => {
    const read = view('window=7d&status=failed&head=claudex&model=gpt-6-sol&account=work%40x.io&session=1a2b3c4d');
    expect(read.range).toEqual({ kind: 'last', window: '7d' });
    expect(read.status).toBe('failed');
    expect([read.head, read.model, read.account, read.session]).toEqual(['claudex', 'gpt-6-sol', 'work@x.io', '1a2b3c4d']);
    expect(view('unattributed=account').unattributed).toBe('account');
  });

  test('Usage\'s since and until are a fixed span, until exclusive, and since alone runs to now', () => {
    expect(view('since=1000&until=2000').range).toEqual({ kind: 'span', since: 1000, until: 2000, day: null });
    expect(view('since=1000').range).toEqual({ kind: 'span', since: 1000, until: null, day: null });
  });

  test('a selector spelled wrong is named, with the value the link carried', () => {
    expect(view('status=broken').unread).toEqual({ param: 'status', value: 'broken' });
    expect(view('unattributed=session').unread).toEqual({ param: 'unattributed', value: 'session' });
    expect(view('since=yesterday').unread).toEqual({ param: 'since', value: 'yesterday' });
    expect(view('since=1000&until=-5').unread).toEqual({ param: 'until', value: '-5' });
    expect(view('until=2000').unread).toEqual({ param: 'since', value: '' });
    expect(view('day=2026-02-31').unread).toEqual({ param: 'day', value: '2026-02-31' });
  });

  test('the address a view writes reads back as the same view, and defaults are left out', () => {
    for (const query of ['', 'window=24h&status=compacted', 'since=1000&until=2000&head=h&account=a', 'model=m&session=s&unattributed=model']) {
      const once = view(query);
      expect(viewOf(new URLSearchParams(searchOf(once)))).toEqual(once);
    }
    expect(searchOf(view('window=1h&status=all'))).toEqual({});
  });
});

describe('a day in the viewer\'s zone', () => {
  const zone = process.env.TZ;
  beforeAll(() => { process.env.TZ = 'America/Chicago'; });
  afterAll(() => { process.env.TZ = zone; });

  test('runs from that midnight to the next, so the day the clocks go back holds 25 hours', () => {
    const range = view('day=2026-11-01').range;
    expect(range).toEqual({ kind: 'span', since: Date.UTC(2026, 10, 1, 5), until: Date.UTC(2026, 10, 2, 6), day: '2026-11-01' });
    expect(searchOf(view('day=2026-11-01'))).toEqual({ day: '2026-11-01' });
  });
});

describe('the view as the daemon\'s ask', () => {
  test('a window is a rolling read; Failed and Compacted are filters the daemon runs; local steps are left out', () => {
    expect(askOf(view('window=24h&status=failed&head=claudex'))).toEqual({
      head: 'claudex',
      last: 24 * HOUR,
      filter: { outcome: 'failed', local: false },
    });
    expect(askOf(view('status=compacted')).filter).toEqual({ compact: true, local: false });
  });

  test('a span asks its since and until, and every selector rides the filter', () => {
    expect(askOf(view('since=1000&until=2000&model=m&account=a&session=s&unattributed=model'))).toEqual({
      head: undefined,
      since: 1000,
      until: 2000,
      filter: { model: 'm', account: 'a', session: 's', unattributed: 'model', local: false },
    });
  });
});
