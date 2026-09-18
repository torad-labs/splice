// This page's dispositions (CONTRACTS.md section 4). The baseline files
// /api/projects/{id}/files under the teams row, but the route belongs to THIS
// page: the repositories list and the repo's own files are what a project page
// shows, and a page declaration overrides the baseline for its name.
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/projects/{id}/files', disposition: 'pending', where: 'V4-131' },
];
