// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
// Needs you owns no route of its own: it reads the doctor, plans and sessions other pages declare, and acts through their routes.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [

];

export const job: PageJob = {
  question: 'What needs me right now?',
  leaves: 'Every plan or session that is stuck, out of quota, signed out or unhealthy, each with the one act that clears it.',
  actions: [
    { name: 'Sign a plan in again' },
    { name: 'Switch to another account' },
    { name: 'Start or restart a plan' },
    { name: 'Fix a problem the health check found' },
    { name: 'Stop a turn that is stuck' },
    { name: 'Restart splice' },
  ],
};
