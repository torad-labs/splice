import { createResource } from '@shared/lib';
import type { TopologyState } from './types';

/** The parsed splice.toml (GET /api/topology). A union with PendingRoute because the route does
 *  not exist yet (V4-128): the store holds the honest empty, never a mocked topology. */
export const topologyStore = createResource<TopologyState>();
