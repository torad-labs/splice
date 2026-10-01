// NEW: V4-444 — browser failure observations and page census derived from the router's source.
import { expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { STACK } from './stack';
import type { PerfTurnsWire } from '../src/types/perf';

const source = ts.createSourceFile('routes.tsx',
  readFileSync(new URL('../src/app/routes.tsx', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const place = source.statements.filter(ts.isVariableStatement)
  .flatMap((statement) => [...statement.declarationList.declarations])
  .find((declaration) => ts.isIdentifier(declaration.name) && declaration.name.text === 'PLACES');
const initializer = place?.initializer;
const array = initializer !== undefined && ts.isAsExpression(initializer) ? initializer.expression : initializer;
if (array === undefined || !ts.isArrayLiteralExpression(array) || array.elements.length === 0) {
  throw new Error('router PLACES must declare a non-empty page census');
}
const PAGES = array.elements.map((element) => {
  if (!ts.isStringLiteral(element)) throw new Error('router PLACES contains a non-literal page');
  return element.text;
});

// Own detail pages count too. Redirects and the shell are not independent page implementations.
export const ROUTES: string[] = [];
function collectRoutes(node: ts.Node): void {
  if (ts.isObjectLiteralExpression(node)) {
    const properties = node.properties.filter(ts.isPropertyAssignment);
    const path = properties.find((property) => property.name.getText(source) === 'path')?.initializer;
    const element = properties.find((property) => property.name.getText(source) === 'element')?.initializer;
    if (path !== undefined && ts.isStringLiteral(path) && element !== undefined &&
        ts.isJsxSelfClosingElement(element) && element.tagName.getText(source) !== 'Navigate' &&
        path.text !== '/') ROUTES.push(path.text);
  }
  ts.forEachChild(node, collectRoutes);
}
collectRoutes(source);
for (const page of PAGES) {
  if (!ROUTES.some((route) => route === page || route === page + '/:section?')) {
    throw new Error('canonical page has no rendered route: ' + page);
  }
}

export async function routePath(page: Page, route: string): Promise<string> {
  switch (route) {
    case 'sessions/:id': return 'sessions/' + STACK.sender.id;
    case 'fleet/:head': return 'fleet/' + STACK.oauthHead;
    case 'teams/:id': return 'teams/' + env('CONSOLE_NEXT_E2E_TEAM');
    case 'projects/:id': return 'projects/' + encodeURIComponent(env('CONSOLE_E2E_REPO'));
    case 'settings/:section?': return 'settings';
    case 'turns/:head/:ts': {
      const payload = await read<PerfTurnsWire>(page, '/api/perf/turns?head=' + STACK.oauthHead);
      const row = payload.heads.flatMap((head) => head.rows ?? [])[0];
      if (row === undefined) throw new Error('isolated daemon has no real turn for its detail route');
      return 'turns/' + STACK.oauthHead + '/' + row.ts;
    }
    default:
      if (route.includes(':')) throw new Error('render census has no real-data resolver for ' + route);
      return route;
  }
}

// The isolated daemon's first read under a loaded host can outlast the normal assertion bound.
// Traces kept heads/instruction and initial chat reads pending; later values and remount timing retain their tighter bounds.
export const FIRST_READ_MS = 20_000;

export function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(name + ' is unset: shared stack did not start');
  return value;
}

export function watch(page: Page) {
  const faults = { pageErrors: [] as string[], consoleErrors: [] as string[], failedReads: [] as string[] };
  page.on('pageerror', (error) => faults.pageErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') faults.consoleErrors.push(message.text());
  });
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/') && response.status() >= 400) faults.failedReads.push(response.status() + ' ' + path);
  });
  return faults;
}

export async function open(page: Page, path: string): Promise<ReturnType<typeof watch>> {
  const faults = watch(page);
  await page.addInitScript((key) => localStorage.setItem('myx-mgmt-key', key), env('CONSOLE_E2E_KEY'));
  // The canary throws in the actual loaded document, not in a test-side mock checker.
  if (process.env.CONSOLE_NEXT_E2E_MUTANT === 'throw') {
    await page.addInitScript(() => { throw new Error('synthetic replacement page threw'); });
  }
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/' + path);
  await expect(page.getByRole('navigation', { name: 'Pages', exact: true })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('#/' + path);
  return faults;
}

export async function assertHealthy(page: Page, faults: ReturnType<typeof watch>): Promise<void> {
  await expect(page.getByRole('main')).not.toBeEmpty();
  expect(await page.getByRole('main').innerText()).not.toMatch(/\bundefined\b|\bNaN\b|\[object Object\]/);
  expect(faults.pageErrors, 'uncaught page errors').toEqual([]);
  const consoleFailure = faults.consoleErrors.some((error) => error.includes('net::ERR_NETWORK_CHANGED'))
    ? 'environment changed: net::ERR_NETWORK_CHANGED'
    : 'console errors';
  expect(faults.consoleErrors, consoleFailure).toEqual([]);
  expect(faults.failedReads, 'daemon refused reads').toEqual([]);
}

export async function read<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(env('CONSOLE_E2E_BASE') + path, {
    headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') },
  });
  expect(response.ok(), path + ' should be served by the real daemon').toBe(true);
  return response.json() as Promise<T>;
}
