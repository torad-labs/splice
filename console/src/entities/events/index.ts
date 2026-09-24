import { eventsStore } from './model/store';

export { connect, disconnect, subscribe, subscribeAll, subscribeReopen } from './api';
export type { ConnectionState, ConnectionStatus } from './model/store';
export { EVENT_KINDS } from '@shared/lib/live';
export type { EventFrame, EventKind } from '@shared/lib/live';
export const useEvents = eventsStore.use;
