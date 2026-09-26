// A link to one item on its page (V4-219): `#/<page>?open=<id>`, built by itemHref and read by
// linkedId, so the id a list writes is the id the page reads, whatever characters the id carries (a
// check id has a `/`, an account key a `:`, a pool label may have a space).
//
// A .ts file holds no JSX (CONTRACTS.md section 4), so the elements are built with createElement.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { OPEN_PARAM, itemHref, linkedId, useLinkedId, useOpen } from '../src/shared/lib';

/** The page and the query an href names, as the hash router splits them. */
function split(href: string): { page: string; search: string } {
  const [path = '', query = ''] = href.replace(/^#\//, '').split('?');
  return { page: path, search: `?${query}` };
}

describe('a link names one item on one page', () => {
  test('the id comes back as it went in, whatever it carries', () => {
    for (const id of ['claudex', 'installation/wrapper', 'chatgpt-oauth:work pool', 'pid:42', 'a?b&c#d%e']) {
      const { page, search } = split(itemHref('doctor', id));
      expect(page).toBe('doctor');
      expect(linkedId(search)).toBe(id);
    }
  });

  test('the parameter is one name for every page', () => {
    expect(itemHref('fleet', 'claudex')).toBe(`#/fleet?${OPEN_PARAM}=claudex`);
    expect(linkedId('?group=head')).toBeNull();
    expect(linkedId('')).toBeNull();
  });
});

/** A board: opens what it is handed. */
function Board({ linked }: { linked: string | null }) {
  const [open] = useOpen(linked);
  return createElement('output', null, open ?? 'nothing');
}

/** A page: hands its board what the route's link names. */
function Page() {
  return createElement(Board, { linked: useLinkedId() });
}

describe('a page opens the item its link names, and nothing when there is none', () => {
  test('the route\'s link, read inside the router', () => {
    const at = (entry: string) => renderToStaticMarkup(createElement(MemoryRouter, { initialEntries: [entry] }, createElement(Page)));
    expect(at(`/doctor?open=${encodeURIComponent('installation/wrapper')}`)).toBe('<output>installation/wrapper</output>');
    expect(at('/doctor')).toBe('<output>nothing</output>');
  });

  test('a board handed the id opens it on its first render, with no router', () => {
    expect(renderToStaticMarkup(createElement(Board, { linked: 'pid:42' }))).toBe('<output>pid:42</output>');
    expect(renderToStaticMarkup(createElement(Board, { linked: null }))).toBe('<output>nothing</output>');
  });
});
