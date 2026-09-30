// The lint walls are only worth having if they can fail: each one is run against a synthetic violation
// and against the compliant form.
import { ESLint } from 'eslint';
import { describe, expect, test } from 'vitest';

const eslint = new ESLint({ cwd: new URL('..', import.meta.url).pathname });
async function rulesHit(source: string, filePath: string): Promise<string[]> {
  const [result] = await eslint.lintText(source, { filePath: new URL(`../${filePath}`, import.meta.url).pathname });
  return (result?.messages ?? []).map((message) => message.ruleId ?? 'parse');
}

describe('the network is one file', () => {
  test('fetch in a page is refused', async () => {
    expect(await rulesHit('export const x = () => fetch("/api/heads");', 'src/pages/needs/x.ts')).toContain('no-restricted-globals');
  });
  test('fetch in the client is allowed', async () => {
    expect(await rulesHit('export const x = () => fetch("/api/heads");', 'src/api/client.ts')).not.toContain('no-restricted-globals');
  });
});

describe('an import only goes down', () => {
  test('a lib importing the api is refused; the api importing a lib is allowed', async () => {
    expect(await rulesHit('import { request } from "../api/client"; export const x = request;', 'src/lib/x.ts')).toContain('no-restricted-imports');
    expect(await rulesHit('import { fmtInt } from "../lib/format"; export const x = fmtInt;', 'src/api/x.ts')).not.toContain('no-restricted-imports');
  });
  test('a ui component importing a page is refused', async () => {
    expect(await rulesHit('import { Foo } from "../pages/foo"; export const x = Foo;', 'src/ui/x.tsx')).toContain('no-restricted-imports');
  });
  test('a page importing the app is refused', async () => {
    expect(await rulesHit('import { C } from "../../app/copy"; export const x = C;', 'src/pages/needs/x.ts')).toContain('no-restricted-imports');
  });
  test('the payload types import nothing above them', async () => {
    expect(await rulesHit('import { fmtInt } from "../lib/format"; export const x = fmtInt;', 'src/types/x.ts')).toContain('no-restricted-imports');
  });
});
