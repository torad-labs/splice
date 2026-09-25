// WHAT EACH PAGE IS FOR (V4-219). The operator asked what the goal of each screen is; each page's
// `coverage.ts` answers in code, as its `job`: the question a person brings to the page, what they
// leave knowing, and the actions it takes. docs/design/JOBS.md is rendered from these declarations,
// and the wall (tests/jobs.test.ts) fails a page with no job, a job with a blank answer, and a
// JOBS.md that differs from the render, so the document cannot drift from the pages.

/** One thing a page does. `row` names the item that will build it; absent, the page does it today. */
export interface PageAction {
  readonly name: string;
  readonly row?: string;
}

export interface PageJob {
  /** The question a person opens the page with, in their words. */
  readonly question: string;
  /** What they know when they leave. */
  readonly leaves: string;
  readonly actions: readonly PageAction[];
}

export type JobProblem = 'no job' | 'blank question' | 'blank leaves' | 'no action' | 'blank action';

export interface JobFinding {
  readonly page: string;
  readonly problem: JobProblem;
}

const blank = (text: string | undefined): boolean => text === undefined || text.trim() === '';

/** Every page the source holds judged against the job it declares, by name. `pages` comes from the
 *  pages directory, never from the declarations, so a page that declares nothing is still counted. */
export function checkJobs(pages: readonly string[], jobs: ReadonlyMap<string, PageJob | undefined>): JobFinding[] {
  const findings: JobFinding[] = [];
  for (const page of [...new Set(pages)].sort()) {
    const job = jobs.get(page);
    if (job === undefined) {
      findings.push({ page, problem: 'no job' });
      continue;
    }
    if (blank(job.question)) findings.push({ page, problem: 'blank question' });
    if (blank(job.leaves)) findings.push({ page, problem: 'blank leaves' });
    if (job.actions.length === 0) findings.push({ page, problem: 'no action' });
    if (job.actions.some((action) => blank(action.name))) findings.push({ page, problem: 'blank action' });
  }
  return findings;
}

/** JOBS.md: one entry per page, in the order given. */
export function renderJobs(jobs: readonly (readonly [string, PageJob])[]): string {
  const entries = jobs.map(([page, job]) => [
    `## ${page}`,
    '',
    `**Question.** ${job.question}`,
    '',
    `**Leaves knowing.** ${job.leaves}`,
    '',
    '**Actions.**',
    '',
    ...job.actions.map((action) => (action.row === undefined ? `- ${action.name}` : `- ${action.name} (${action.row})`)),
  ].join('\n'));
  return [
    '# What each console page is for',
    '',
    '<!-- Rendered from each page\'s `job` in console/src/pages/*/coverage.ts. tests/jobs.test.ts fails when',
    '     this file differs; edit the declarations, then `vitest run -u tests/jobs.test.ts` in console/. -->',
    '',
    'Each page answers one question. An action with a row in brackets is not built yet; the row builds it.',
    '',
    entries.join('\n\n'),
    '',
  ].join('\n');
}
