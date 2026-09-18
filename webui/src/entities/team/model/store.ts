import { createResource } from '@shared/lib';
import type { TeamState, TeamsState } from './types';

/** Every team (GET /api/teams). A union with PendingRoute because the route does
 *  not exist yet (V4-131): the store holds the honest empty, never a mocked row. */
export const teamsStore = createResource<TeamsState>();

/** One team with its members, messages and activity (GET /api/teams/{id}). */
export const teamStore = createResource<TeamState>();
