// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/teams', disposition: 'editable' },
  { kind: 'route', name: '/api/teams/{id}', disposition: 'editable' },
  { kind: 'route', name: '/api/teams/{id}/activity', disposition: 'read-only' },
  { kind: 'route', name: '/api/teams/{id}/archive', disposition: 'excluded', reason: 'the composer\'s archived flag saves through PUT /api/teams/{id}, which writes the same flag' },
  { kind: 'route', name: '/api/teams/{id}/chat', disposition: 'read-only' },
  { kind: 'route', name: '/api/teams/{id}/economics', disposition: 'read-only' },
  { kind: 'route', name: '/api/teams/{id}/edges', disposition: 'excluded', reason: 'the chat read serves the same edges one day at a time with their text, and the board draws one day' },
  { kind: 'route', name: '/api/teams/{id}/sessions', disposition: 'editable' },
  { kind: 'route', name: '/api/teams/{id}/slots/{slot}/instructions', disposition: 'excluded', reason: 'the composer writes every slot\'s instructions through PUT /api/teams/{id}' },
];

export const job: PageJob = {
  question: 'Who on the team is working, and who is waiting on whom?',
  leaves: 'A team\'s seats and who sits in them, the day\'s talk and activity, and what the team has spent.',
  actions: [
    { name: 'Create or edit a team' },
    { name: 'Bind or unbind a session to a seat' },
    { name: 'Archive a team' },
  ],
};
