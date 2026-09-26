import { createResource } from '@shared/lib';
import type { TeamPanels, TeamsState } from './types';

/** Every team (GET /api/teams). A union with PendingRoute for a daemon older than V4-131: the store
 *  holds the honest empty, never a mocked row. */
export const teamsStore = createResource<TeamsState>();

/** The opened team's chat, activity and economics, each its own outcome. */
export const teamPanelsStore = createResource<TeamPanels>();
