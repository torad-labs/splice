// This page's dispositions (CONTRACTS.md section 4). The baseline files
// /api/projects/{id}/files under the teams row, but the route belongs to THIS
// page: the repositories list and the repo's own files are what a project page
// shows, and a page declaration overrides the baseline for its name.
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  // The repositories list itself, which nothing disposed until now: the page fetches it at
  // entities/project/api/index.ts:19 and gateway control serves no /api/projects (grepped
  // 2026-09-18, 0 literal occurrences). Same V4-131 as the files route beside it (M1-37, M1-41).
  { kind: 'route', name: '/api/projects', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/projects/{id}/files', disposition: 'pending', where: 'V4-131' },
];
