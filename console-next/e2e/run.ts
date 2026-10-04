// NEW: V4-444 — a real browser pass followed by a page exception that the same observer must reject.
import { spawnSync } from 'node:child_process';

const args = ['playwright', 'test', '--config', 'e2e/playwright.config.ts'];
const green = spawnSync('bunx', args, { stdio: 'inherit' });
if (green.error !== undefined) throw green.error;
if (green.status !== 0) process.exit(green.status ?? 1);

const mutant = spawnSync('bunx', [
  ...args, '--project', 'renders', '--grep', 'accounts renders against', '--reporter', 'json',
  '--output', 'build/e2e-mutant',
], {
  env: { ...process.env, CONSOLE_NEXT_E2E_MUTANT: 'throw' },
  encoding: 'utf8',
  maxBuffer: 16 * 1024 * 1024,
});
if (mutant.error !== undefined) throw mutant.error;
interface Suite {
  suites?: Suite[];
  specs?: { tests: { results: { status: string; errors: { message?: string }[] }[] }[] }[];
}
const report = JSON.parse(mutant.stdout) as Suite & { errors: unknown[] };
function results(suite: Suite): { status: string; errors: { message?: string }[] }[] {
  return [
    ...(suite.specs ?? []).flatMap((spec) => spec.tests.flatMap((test) => test.results)),
    ...(suite.suites ?? []).flatMap(results),
  ];
}
const observed = results(report);
if (mutant.status !== 1 || report.errors.length !== 0 || observed.length !== 1 ||
    observed[0]?.status !== 'failed' ||
    !observed[0].errors.some((error) =>
      error.message?.includes('uncaught page errors') === true &&
      error.message.includes('synthetic replacement page threw'))) {
  process.stderr.write(mutant.stdout + mutant.stderr);
  throw new Error('throwing-page canary did not fail specifically on the uncaught page exception');
}
console.log('console-next e2e: throwing-page canary rejected the real document exception');
