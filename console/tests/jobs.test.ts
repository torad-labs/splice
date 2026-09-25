// THE JOBS WALL (V4-219). Every page the source holds declares what it is for (its `job` in
// coverage.ts): the question a person brings, what they leave knowing, and its actions. The pages
// are read from the pages directory, never from the declarations, so a page that declares nothing is
// still counted; docs/design/JOBS.md must equal the render of the declarations, so the document
// cannot drift from the pages. The planted cases are the mutation proof.
import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

import { checkJobs, renderJobs } from '../src/shared/coverage/jobs';
import type { PageJob } from '../src/shared/coverage/jobs';

const here = path.dirname(fileURLToPath(import.meta.url));
const pagesDir = path.join(here, '..', 'src', 'pages');
const jobsFile = path.join(here, '..', '..', 'docs', 'design', 'JOBS.md');

const pages = readdirSync(pagesDir, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && existsSync(path.join(pagesDir, entry.name, 'index.tsx')))
  .map((entry) => entry.name)
  .sort();

const modules = import.meta.glob<{ job?: PageJob }>('../src/pages/*/coverage.ts', { eager: true });
const jobs = new Map<string, PageJob | undefined>(
  Object.entries(modules).map(([file, module]) => [file.split('/').at(-2) ?? file, module.job]),
);

describe('the jobs wall', () => {
  test('every page in the source declares what it is for', () => {
    console.log(`jobs: ${pages.length} pages from ${path.relative(path.join(here, '..'), pagesDir)}`);
    expect(pages.length).toBeGreaterThanOrEqual(13);
    expect(checkJobs(pages, jobs)).toEqual([]);
  });

  test('JOBS.md is the render of the declarations', async () => {
    // A file snapshot: CI fails on any difference, and `vitest run -u tests/jobs.test.ts` re-renders.
    const declared = pages.map((page) => [page, jobs.get(page)] as const).filter((entry): entry is readonly [string, PageJob] => entry[1] !== undefined);
    expect(existsSync(jobsFile)).toBe(true);
    await expect(renderJobs(declared)).toMatchFileSnapshot(jobsFile);
  });

  test('a page with no job, or a job with a blank answer, fails by name', () => {
    const job: PageJob = { question: 'Q?', leaves: 'L.', actions: [{ name: 'Act' }] };
    expect(checkJobs(['a', 'fake-page'], new Map([['a', job]]))).toEqual([{ page: 'fake-page', problem: 'no job' }]);
    expect(checkJobs(['a'], new Map([['a', { question: ' ', leaves: '', actions: [] }]]))).toEqual([
      { page: 'a', problem: 'blank question' },
      { page: 'a', problem: 'blank leaves' },
      { page: 'a', problem: 'no action' },
    ]);
    expect(checkJobs(['a'], new Map([['a', { ...job, actions: [{ name: '' }] }]]))).toEqual([{ page: 'a', problem: 'blank action' }]);
  });

  test('an action not built yet names the row that builds it', () => {
    const out = renderJobs([['fleet', { question: 'Q?', leaves: 'L.', actions: [{ name: 'Restart' }, { name: 'Add a backend', row: 'V4-220' }] }]]);
    expect(out).toContain('- Restart\n- Add a backend (V4-220)');
  });
});
