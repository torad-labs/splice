// This page's dispositions (CONTRACTS.md section 4). The baseline files
// /api/projects/{id}/files under the teams row, but the route belongs to THIS
// page: the repositories list, the opened repo's own row and the repo's own
// files are what a project page shows, and a page declaration overrides the
// baseline for its name.
//
// All three are ProjectsRoutes (V4-131, served) and read-only: the page fetches
// them at entities/project/api/index.ts and writes none of them (M4-05).
import type { Disposition, PageJob } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/projects', disposition: 'read-only' },
  { kind: 'route', name: '/api/projects/{id}', disposition: 'read-only' },
  { kind: 'route', name: '/api/projects/{id}/files', disposition: 'read-only' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'Which repositories have sessions run in, and what is running there now?',
  leaves: 'Every repo the daemon has seen, with its sessions, teams, today\'s turns and cost.',
  actions: [
    { name: 'Open a project' },
    // V4-313: through PUT /api/topology, which Settings dispositions (one page per route); the fields
    // are settings/coverage.ts's too, since its topology editor writes every one the file carries.
    { name: 'Edit the repo\'s standing prompt and compaction rule' },
  ],
};
