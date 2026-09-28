// The add form's draft and what it asks the daemon, pure and DOM-free so both are tested without a
// renderer. The profile decides what is asked (`asks`): a head name when the catalogue has none, a
// base URL when the provider has no fixed one, and models when the catalogue lists none. Anything
// else the operator types overrides the profile's default; a blank field sends nothing, so the
// daemon's own default holds (AddRequestReader).
import type { AddProfile, AddRequest, AddView } from '@entities/add';
import type { PlaygroundWire } from '@entities/playground';
import { H } from './strings';

export interface ModelRow {
  id: string;
  /** Whole tokens as typed; blank sends no window, and the daemon writes its default. */
  window: string;
}

export interface AddDraft {
  profile: string;
  name: string;
  baseUrl: string;
  command: string;
  models: ModelRow[];
}

export const EMPTY_ROW: ModelRow = { id: '', window: '' };

export function draftFor(profile: string): AddDraft {
  return { profile, name: '', baseUrl: '', command: '', models: [EMPTY_ROW] };
}

export function firstDraft(profiles: readonly AddProfile[], preferred?: string): AddDraft {
  if (preferred !== undefined) return draftFor(profiles.some((profile) => profile.name === preferred) ? preferred : '');
  return draftFor(profiles[0]?.name ?? '');
}

const WHOLE = /^\d+$/;

/** The rows that name a model: a blank id is a row the operator has not filled. */
function namedRows(draft: AddDraft): ModelRow[] {
  return draft.models.filter((row) => row.id.trim() !== '');
}

/** Whether the draft can open an add: every ask answered, and every window a whole number. */
export function ready(draft: AddDraft, profile: AddProfile | null): boolean {
  if (profile === null) return false;
  if (profile.asks.includes('name') && draft.name.trim() === '') return false;
  if (profile.asks.includes('base_url') && draft.baseUrl.trim() === '') return false;
  if (profile.asks.includes('models') && namedRows(draft).length === 0) return false;
  return namedRows(draft).every((row) => row.window.trim() === '' || WHOLE.test(row.window.trim()));
}

/** POST /api/add's body for the draft: the profile, and each field the operator filled. */
export function requestOf(draft: AddDraft): AddRequest {
  const body: AddRequest = { profile: draft.profile };
  if (draft.name.trim() !== '') body.name = draft.name.trim();
  if (draft.baseUrl.trim() !== '') body.base_url = draft.baseUrl.trim();
  if (draft.command.trim() !== '') body.command = draft.command.trim();
  const rows = namedRows(draft);
  if (rows.length > 0) {
    body.models = rows.map((row) => (row.window.trim() === ''
      ? { id: row.id.trim() }
      : { id: row.id.trim(), context_window: Number(row.window.trim()) }));
  }
  return body;
}

/** Whether the add is still moving, so the form keeps reading it: until it is saved. */
export function live(view: AddView | null): boolean {
  return view !== null && view.saved === null;
}

/** An OAuth credential can already exist before this add opens; save it once per add id. */
export function autoSaveTarget(view: AddView | null, attempted: string | null): string | null {
  if (view === null || view.saved !== null || view.sign_in_by !== 'login' || attempted === view.id) return null;
  return view.credential.present && (view.sign_in === null || view.sign_in.state === 'signed_in' || view.sign_in.state === 'live_after_restart')
    ? view.id : null;
}

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function firstText(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() === '' ? null : value;
  if (Array.isArray(value)) return value.map(firstText).filter((text): text is string => text !== null).join('\n') || null;
  const row = object(value);
  if (row === null) return null;
  for (const key of ['text', 'output_text', 'content', 'message', 'output', 'choices']) {
    const text = firstText(row[key]);
    if (text !== null) return text;
  }
  return null;
}

/** Interpret only vendor text and its model, never the echoed prompt or auth-bearing headers. */
export function tryReply(wire: PlaygroundWire): { model: string; text: string } | { error: string } {
  const response = object(wire.response.body);
  const reason = object(response?.error)?.message;
  if (typeof reason === 'string') return { error: reason };
  if (wire.response.status >= 400) return { error: H.providerStatus(wire.response.status) };
  const request = object(wire.request.body);
  const model = response?.model ?? request?.model;
  if (typeof model !== 'string' || model === '') return { error: H.noModel };
  const text = firstText(response);
  return text === null ? { error: H.noReply } : { model, text };
}
