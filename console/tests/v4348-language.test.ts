import ts from 'typescript';
import { describe, expect, test } from 'vitest';

const modules = {
  ...import.meta.glob('../src/**/strings.ts', { eager: true, query: '?raw', import: 'default' }),
  ...import.meta.glob('../src/**/copy.ts', { eager: true, query: '?raw', import: 'default' }),
} as Record<string, string>;
const forbidden = /\b(?:heads?|knobs?|daemons?|backends?|codex|registry|bound session|tiers filled)\b/i;

function findings(source: string, file: string): string[] {
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
  const found: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isStringLiteral(node) || ts.isTemplateLiteralToken(node)) {
      // Protocol keys are not copy. Keep literal config tables and file-name placeholders intact.
      const key = ts.isPropertyAssignment(node.parent) && node.parent.name === node;
      const copy = node.text.replaceAll('<head>', '<plan>').replaceAll('[daemon]', '[service]');
      if (!key && forbidden.test(copy)) {
        const line = ast.getLineAndCharacterOfPosition(node.getStart(ast)).line + 1;
        found.push(`${file}:${line}: ${node.text}`);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(ast);
  return found;
}

describe('the console speaks in product words, not implementation nouns', () => {
  test('every shipped copy module is scanned, including a new one', () => {
    expect(Object.keys(modules).length).toBeGreaterThan(40);
    const found = Object.entries(modules).flatMap(([file, source]) => findings(source, file));
    if (found.length > 0) console.log(found.join('\n'));
    expect(found.length).toBe(0);
  });

  test('a newly added copy module containing a forbidden word fails by name', () => {
    expect(findings('export const S = { title: "Daemon" };', 'src/pages/new/strings.ts'))
      .toEqual(['src/pages/new/strings.ts:1: Daemon']);
    expect(findings('export const S = { conflict: (name: string) => `head ${name} is declared` };', 'src/pages/new/strings.ts'))
      .toEqual(['src/pages/new/strings.ts:1: head ']);
  });
});
