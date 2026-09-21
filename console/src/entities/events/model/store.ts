// The connection's state: what the transport is doing, and when it last said anything.
//
// It is deliberately NOT a rolling log of frames. An entity that kept the events would be a second
// source of truth beside the stores each event is about; what the console needs to know is whether
// the stream is live and how old its last word is, which is what the rule prints.
import { create } from 'zustand';

export type ConnectionStatus = 'live' | 'reconnecting' | 'off';

export interface ConnectionState {
  /** `live` while a stream is open, `reconnecting` between attempts, `off` before the first
   *  connect and after a 401 (the management key is what opens it, and the key gate owns that). */
  status: ConnectionStatus;
  /** Epoch ms of the last FRAME: an event with a name and a payload. Null until one arrives. */
  lastFrameAt: number | null;
  /** Epoch ms of the last BYTE from the daemon, heartbeats included. It says the link is alive
   *  even when nothing is happening, which is a different fact from the one above. */
  lastBeatAt: number | null;
  /** The last id seen. It is sent as `Last-Event-ID` when the stream is reopened, so a reconnect
   *  replays what was missed instead of starting from now. */
  lastId: number | null;
  /** Frames the parser could not read. Non-zero means the console is missing events and says so,
   *  rather than looking quiet. */
  dropped: number;
}

const useStore = create<ConnectionState>(() => ({
  status: 'off',
  lastFrameAt: null,
  lastBeatAt: null,
  lastId: null,
  dropped: 0,
}));

/** The same `{ use, get, … }` shape every other store in the app exposes, so a view subscribes
 *  with a selector and the api segment is the only writer. */
export const eventsStore = {
  use: <U>(selector: (state: ConnectionState) => U): U => useStore(selector),
  get: (): ConnectionState => useStore.getState(),
  set: (patch: Partial<ConnectionState>): void => useStore.setState(patch),
};
