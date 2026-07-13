import { create } from 'zustand';

export interface SessionState {
  locked: boolean;
  hasKey: boolean;
}

export const sessionStore = create<SessionState>(() => ({
  locked: false,
  hasKey: false,
}));
