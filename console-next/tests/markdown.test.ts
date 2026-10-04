import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { Markdown } from '../src/ui/Markdown';
import { highlight } from '../src/ui/highlight';

const html = (source: string): string => renderToStaticMarkup(createElement(Markdown, null, source));

describe('a message renders as markdown', () => {
  test('headings, ordered lists and tables', () => {
    const out = html('## Plan\n\n1. one\n2. two\n\n| a | b |\n|---|---|\n| 1 | 2 |\n');
    expect(out).toContain('<h2>Plan</h2>');
    expect(out).toContain('<ol>');
    expect(out).toContain('<table>');
    expect(out).toContain('table-wrap');
  });
  test('a fenced block with a known language is highlighted', () => {
    const out = html('```ts\nexport function f() { return 1 }\n```');
    expect(out).toContain('<pre class="code">');
    expect(out).toContain('hljs-keyword');
  });
  test('a fenced block with no or an unknown language is plain text, escaped', () => {
    const out = html('```\n<b>&\n```\n\n```nosuchlang\nx < y\n```');
    expect(out).toContain('&lt;b&gt;&amp;');
    expect(out).toContain('x &lt; y');
    expect(out).not.toContain('hljs-');
  });
  test('inline code stays inline', () => expect(html('use `db` here')).toContain('<code>db</code>'));
  test('raw html and script urls are never rendered', () => {
    expect(html('<script>alert(1)</script>ok')).not.toContain('<script');
    expect(html('[x](javascript:alert(1))')).not.toContain('javascript:');
  });
  test('a link opens in a new tab without the opener', () =>
    expect(html('[docs](https://example.com)')).toContain('target="_blank" rel="noopener noreferrer"'));
});

describe('the highlighter', () => {
  test('knows the aliases a message uses and refuses a language it lacks', () => {
    expect(highlight('ls -la', 'sh')).not.toBeNull();
    expect(highlight('x', 'brainfuck')).toBeNull();
    expect(highlight('x', null)).toBeNull();
  });
});
