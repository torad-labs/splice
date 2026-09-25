// This page's dispositions (CONTRACTS.md section 4). Needs you owns no route and no verb: every read
// it makes and every write it offers belongs to the page it summarises (heads to Fleet, sessions to
// Sessions, the doctor report to Doctor), and each name has exactly one owner.
import type { Disposition, PageJob } from '@shared/coverage';

export const dispositions: Disposition[] = [];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'What needs me right now, and what do I do about it?',
  leaves: 'Every head, plan, account, turn, session, team seat and doctor check that needs them, worst first, each with its fix, and which inputs could not be read.',
  actions: [
    { name: 'Start or restart a head' },
    { name: 'Restart the daemon' },
    { name: 'Copy a fix command' },
    { name: 'Open the page that holds an item' },
    { name: 'Open the item itself on its page', row: 'V4-219' },
    { name: 'Apply a doctor check\'s fix' },
  ],
};
