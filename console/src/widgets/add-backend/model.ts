// The add form's draft and what it asks the daemon, pure and DOM-free so both are tested without a
// renderer. The profile decides what is asked (`asks`): a head name when the catalogue has none, a
// base URL when the provider has no fixed one, and models when the catalogue lists none. Anything
// else the operator types overrides the profile's default; a blank field sends nothing, so the
// daemon's own default holds (AddRequestReader).
import type { AddProfile, AddRequest, AddView } from '@entities/add';

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
