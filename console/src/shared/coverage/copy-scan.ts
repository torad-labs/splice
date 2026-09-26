// The bare-copy wall's checker (half 2 of the copy gate). CONTRACTS.md / the 2026-09-25 voice
// ruling: no explanatory prose on the page — a label or a sentence belongs in a `strings.ts` or a
// `copy.ts`, never typed inline in a component or its model. This walks a file's AST (the
// TypeScript compiler API) rather than grepping text, so a string built from a template, buried in
// a ternary, or split across JSX children is still found.
//
// tests/copy.test.ts runs this over every real file under `src` (minus the copy modules
// themselves) for the denominator, and over `tests/fixtures/walls/bad-copy.tsx` for the mutation
// proof. Both call this one function so there is only one scanner to keep correct.
import ts from 'typescript';

export type BareCopyReason = 'jsx text' | 'sentence literal';

export type BareCopyFinding = {
  readonly file: string;
  readonly line: number;
  readonly reason: BareCopyReason;
  readonly text: string;
};

const TRUNCATE_AT = 80;
const MIN_WORDS = 4;
const HAS_LETTER = /\p{L}/u;

function truncate(text: string): string {
  const flat = text.trim().replace(/\s+/g, ' ');
  return flat.length > TRUNCATE_AT ? `${flat.slice(0, TRUNCATE_AT)}…` : flat;
}

function words(text: string): string[] {
  return text.trim().split(/\s+/).filter((word) => word !== '');
}

/** JSX attributes whose value is markup plumbing (a class list, an id, a wired href, an SVG
 *  coordinate string), never prose, so a literal inside one is never "bare copy". */
const NON_COPY_ATTRS = new Set([
  'className',
  'class',
  'key',
  'id',
  'href',
  'src',
  'type',
  'role',
  'htmlFor',
  'd',
  'viewBox',
  'points',
  'transform',
]);

function isNonCopyAttrName(name: string): boolean {
  return NON_COPY_ATTRS.has(name) || name.startsWith('data-');
}

/** A module specifier (`import`/`export ... from '...'`, or a dynamic `import('...')`) is a path,
 *  never copy. */
function isModuleSpecifier(node: ts.Node): boolean {
  const parent = node.parent;
  if (!parent) return false;
  if (ts.isImportDeclaration(parent) && parent.moduleSpecifier === node) return true;
  if (ts.isExportDeclaration(parent) && parent.moduleSpecifier === node) return true;
  if (ts.isCallExpression(parent) && parent.expression.kind === ts.SyntaxKind.ImportKeyword) {
    return parent.arguments[0] === node;
  }
  return false;
}

/** Climbs from a literal through the small set of expression shapes a JSX attribute value can be
 *  built from (a ternary, a concatenation, a parenthesized group, the `{...}` wrapper itself) to
 *  find the attribute it belongs to. Stops at anything else so an attribute deep inside unrelated
 *  JSX children is never mistaken for the value of one further up the tree. */
function isInsideNonCopyAttribute(node: ts.Node): boolean {
  const climbable = new Set<ts.SyntaxKind>([
    ts.SyntaxKind.JsxExpression,
    ts.SyntaxKind.ConditionalExpression,
    ts.SyntaxKind.BinaryExpression,
    ts.SyntaxKind.ParenthesizedExpression,
  ]);
  let current: ts.Node | undefined = node.parent;
  while (current) {
    if (ts.isJsxAttribute(current)) return isNonCopyAttrName(current.name.getText());
    if (!climbable.has(current.kind)) return false;
    current = current.parent;
  }
  return false;
}

/** A `console.*(...)` call argument is a developer message, not product copy. */
function isConsoleArgument(node: ts.Node): boolean {
  const parent = node.parent;
  if (!parent || !ts.isCallExpression(parent) || !parent.arguments.includes(node as ts.Expression)) return false;
  const callee = parent.expression;
  return ts.isPropertyAccessExpression(callee) && ts.isIdentifier(callee.expression) && callee.expression.text === 'console';
}

/** A pattern source string, not prose. Covers both `new RegExp(...)` and the no-`new` call form. */
function isRegExpArgument(node: ts.Node): boolean {
  const parent = node.parent;
  if (!parent) return false;
  const isRegExpCallee = (expr: ts.Expression): boolean => ts.isIdentifier(expr) && expr.text === 'RegExp';
  if (ts.isNewExpression(parent) && isRegExpCallee(parent.expression)) {
    const args: readonly ts.Expression[] = parent.arguments ?? [];
    return args.includes(node as ts.Expression);
  }
  if (ts.isCallExpression(parent) && isRegExpCallee(parent.expression)) return parent.arguments.includes(node as ts.Expression);
  return false;
}

/** An object/interface member NAME (`{ 'some-key': 1 }`), not its value. */
function isPropertyKey(node: ts.Node): boolean {
  const parent = node.parent;
  if (!parent) return false;
  if (ts.isPropertyAssignment(parent) && parent.name === node) return true;
  if (ts.isPropertySignature(parent) && parent.name === node) return true;
  if (ts.isEnumMember(parent) && parent.name === node) return true;
  return false;
}

/** A string-literal TYPE (`type X = 'a' | 'b and c'`), not a runtime value. */
function isTypePosition(node: ts.Node): boolean {
  return !!node.parent && ts.isLiteralTypeNode(node.parent);
}

/** A class-name or design-token list: every "word" is really a `myx-` class or a `--` custom
 *  property, never a sentence built of them. */
function looksLikeTokenList(text: string): boolean {
  const parts = words(text);
  return parts.length > 0 && parts.every((word) => word.startsWith('myx-') || word.startsWith('--'));
}

/** A path, URL or CSS value: starts with one of the markers real prose never starts with, or has
 *  no space-separated run of 2+ letters at all (so it cannot read as a sentence). */
function looksLikePathUrlOrCss(text: string): boolean {
  const trimmed = text.trim();
  if (/^(\/|\.|http|#|var\(|calc\(|M )/.test(trimmed)) return true;
  return !words(trimmed).some((word) => /\p{L}{2,}/u.test(word));
}

function isExcludedLiteral(node: ts.StringLiteralLike): boolean {
  return (
    isModuleSpecifier(node) ||
    isInsideNonCopyAttribute(node) ||
    isConsoleArgument(node) ||
    isRegExpArgument(node) ||
    isPropertyKey(node) ||
    isTypePosition(node) ||
    looksLikeTokenList(node.text) ||
    looksLikePathUrlOrCss(node.text)
  );
}

/** Every JsxText node and every bare sentence-shaped literal in one file, by line. A template
 *  literal WITH substitutions (`` `Hi ${name}` ``) is never visited as a whole — its cooked pieces
 *  (TemplateHead/Middle/Tail) are not string-literal nodes, so it is excluded by construction; its
 *  substituted expressions are still walked, since they may contain their own bare copy. */
export function scanFileForBareCopy(sourceText: string, filePath: string): BareCopyFinding[] {
  const scriptKind = filePath.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const sourceFile = ts.createSourceFile(filePath, sourceText, ts.ScriptTarget.Latest, true, scriptKind);
  const findings: BareCopyFinding[] = [];

  const lineOf = (node: ts.Node): number => sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile)).line + 1;

  const visit = (node: ts.Node): void => {
    if (ts.isJsxText(node)) {
      const text = node.getText();
      if (HAS_LETTER.test(text.trim())) {
        findings.push({ file: filePath, line: lineOf(node), reason: 'jsx text', text: truncate(text) });
      }
    } else if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) && !isExcludedLiteral(node)) {
      if (words(node.text).length >= MIN_WORDS && HAS_LETTER.test(node.text)) {
        findings.push({ file: filePath, line: lineOf(node), reason: 'sentence literal', text: truncate(node.text) });
      }
    }
    ts.forEachChild(node, visit);
  };

  visit(sourceFile);
  return findings;
}
