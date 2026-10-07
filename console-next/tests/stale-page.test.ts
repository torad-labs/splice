// The open tab against the page the daemon serves: identity by content, and no claim when either side is unknown.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { servedFingerprint } from '../src/app/StalePage';
import { fingerprint, loadedFingerprint, pageStale } from '../src/lib/stale-page';

afterEach(() => vi.unstubAllGlobals());

describe('a page left open across an upgrade', () => {
  test('is the same page by its content, and a changed byte is another', async () => {
    expect(await fingerprint('<html>a</html>')).toBe(await fingerprint('<html>a</html>'));
    expect(await fingerprint('<html>a</html>')).not.toBe(await fingerprint('<html>b</html>'));
    expect(await fingerprint('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });
  test('a document carries its own fingerprint without hashing that fingerprint into its identity', async () => {
    const original = '<html><head></head><body>A</body></html>';
    const loaded = await fingerprint(original);
    const stamped = original.replace('</head>', `<meta name="splice-page-fingerprint" content="${loaded}"></head>`);
    expect(await fingerprint(stamped)).toBe(loaded);
    expect(pageStale(loaded, await fingerprint(stamped + '<!-- B -->'))).toBe(true);
    expect(pageStale(loaded, await fingerprint(stamped))).toBe(false);
  });
  test('is stale only when both are known and differ', () => {
    expect(pageStale('a', 'b')).toBe(true);
    expect(pageStale('a', 'a')).toBe(false);
    expect(pageStale(null, 'b')).toBe(false);
    expect(pageStale('a', null)).toBe(false);
  });
  test('the baseline comes from the loaded document, not a later served answer', async () => {
    const loaded = await fingerprint('<html>A</html>');
    vi.stubGlobal('document', { querySelector: vi.fn(() => ({ content: loaded })) });
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>B</html>')));
    expect(loadedFingerprint()).toBe(loaded);
    expect(await servedFingerprint()).not.toBe(loaded);
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>A</html>')));
    expect(pageStale(loadedFingerprint(), await servedFingerprint())).toBe(false);
  });
  test('an unstamped document never claims stale, including development and fallback pages', async () => {
    for (const html of [
      '<html><body>development page</body></html>',
      '<html><body>console override</body></html>',
      '<!doctype html><title>splice</title><p>dashboard build missing</p>',
      '<!doctype html><title>splice</title><p>packaged dashboard build unreadable</p>',
    ]) {
      vi.stubGlobal('document', { querySelector: vi.fn(() => null) });
      expect(loadedFingerprint()).toBeNull();
      expect(pageStale(loadedFingerprint(), await fingerprint(html))).toBe(false);
    }
    vi.stubGlobal('document', { querySelector: vi.fn(() => ({ content: 'not a fingerprint' })) });
    expect(loadedFingerprint()).toBeNull();
    expect(pageStale(loadedFingerprint(), await fingerprint('<html>changed</html>'))).toBe(false);
  });
  test('reads the served page without the cache, and says nothing when the daemon does not answer', async () => {
    const fetchMock = vi.fn(async () => new Response('<html>served</html>', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    expect(await servedFingerprint()).toBe(await fingerprint('<html>served</html>'));
    expect(fetchMock).toHaveBeenCalledWith('/', { cache: 'no-store' });
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('refused'); }));
    expect(await servedFingerprint()).toBeNull();
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })));
    expect(await servedFingerprint()).toBeNull();
  });
});

describe('the offer to reload', () => {
  test('says the page is out of date and offers the reload', async () => {
    const { StaleBanner } = await import('../src/app/StalePage');
    const { createElement } = await import('react');
    const { renderToStaticMarkup } = await import('react-dom/server');
    const html = renderToStaticMarkup(createElement(StaleBanner));
    expect(html).toContain('role="status"');
    expect(html).toContain('The served page changed after this tab opened');
    expect(html).not.toContain('splice was upgraded');
    expect(html).toContain('>Reload the page<');
  });
});
