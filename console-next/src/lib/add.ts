// The Add-a-plan draft and what it asks the daemon, pure so both are tested without a renderer. The profile decides what is
// asked (`asks`): a plan name when the catalogue has none, a provider address when the provider has no fixed one, and models
// when the catalogue lists none. A blank field sends nothing, so the daemon's own default holds (AddRequestReader).
import type { AddAsk, AddCheck, AddProfile, AddRequest, AddView } from '../types/add';
import { AD, PLAN_LABELS, PLAN_NAMES, PLAN_WHY } from './words-add';

/** The known successful window check in operator words; failures and other checks keep their diagnostic. */
export function checkDetail(check: AddCheck): string {
  return check.name === 'windows' && check.ok && check.detail === AD.windowFitsDiagnostic
    ? AD.windowFits : check.detail;
}

/** A field the daemon can ask to correct while opening an add. */
export type AddField = AddAsk | 'command';

export function openingField(body: unknown): AddField | null {
  if (body === null || typeof body !== 'object' || !('field' in body)) return null;
  const field = body.field;
  return field === 'name' || field === 'command' || field === 'base_url' || field === 'models' ? field : null;
}

export interface ModelRow {
  id: string;
  /** Whole tokens as typed; blank sends no window, and the daemon writes its default. */
  window: string;
}

export interface AddDraft {
  profile: string;
  name: string;
  command: string;
  baseUrl: string;
  models: ModelRow[];
}

export const EMPTY_ROW: ModelRow = { id: '', window: '' };
export const draftFor = (profile: string): AddDraft => ({ profile, name: '', command: '', baseUrl: '', models: [EMPTY_ROW] });

export interface PlanChoice {
  /** The profile's name, the key of its add. */
  id: string;
  label: string;
  why: string;
  /** Null when this splice build does not offer it. */
  profile: AddProfile | null;
}

const KNOWN = Object.keys(PLAN_NAMES) as (keyof typeof PLAN_NAMES)[];

/** The six first-hour choices joined to this daemon, followed by its other profiles with human labels where known. */
export function planChoices(profiles: readonly AddProfile[]): PlanChoice[] {
  const known = KNOWN.map((id) => ({ id, label: PLAN_NAMES[id], why: PLAN_WHY[id], profile: profiles.find((profile) => profile.name === id) ?? null }));
  const others = profiles
    .filter((profile) => !KNOWN.some((id) => id === profile.name))
    .map((profile) => ({ id: profile.name, label: planLabel(profile.name), why: profile.summary, profile }));
  return [...known, ...others];
}

const WHOLE = /^\d+$/;
const namedRows = (draft: AddDraft): ModelRow[] => draft.models.filter((row) => row.id.trim() !== '');

/** Whether a plan needs anything typed before it can be opened. */
export const asksAnything = (profile: AddProfile): boolean => profile.asks.length > 0;

/** Whether the draft can open an add: every ask answered, and every window a whole number. */
export function ready(draft: AddDraft, profile: AddProfile | null, recovery: readonly AddField[] = []): boolean {
  if (profile === null) return false;
  const fields = [...profile.asks, ...recovery];
  if (fields.includes('name') && draft.name.trim() === '') return false;
  if (fields.includes('command') && draft.command.trim() === '') return false;
  if (fields.includes('base_url') && draft.baseUrl.trim() === '') return false;
  if (fields.includes('models') && namedRows(draft).length === 0) return false;
  return namedRows(draft).every((row) => row.window.trim() === '' || WHOLE.test(row.window.trim()));
}

/** POST /api/add's body for the draft: the profile, and each field the operator filled. */
export function requestOf(draft: AddDraft): AddRequest {
  const body: AddRequest = { profile: draft.profile };
  if (draft.name.trim() !== '') body.name = draft.name.trim();
  if (draft.command.trim() !== '') body.command = draft.command.trim();
  if (draft.baseUrl.trim() !== '') body.base_url = draft.baseUrl.trim();
  const rows = namedRows(draft);
  if (rows.length > 0) {
    body.models = rows.map((row) => (row.window.trim() === '' ? { id: row.id.trim() } : { id: row.id.trim(), context_window: Number(row.window.trim()) }));
  }
  return body;
}

/** An add still moving is read again, until it is saved. */
export const live = (view: AddView | null): boolean => view !== null && view.saved === null;

/** A login's credential can already exist before this add opens, or land while it is open: save it once per add id. */
export function autoSaveTarget(view: AddView | null, attempted: string | null): string | null {
  if (view === null || view.saved !== null || view.sign_in_by !== 'login' || attempted === view.id) return null;
  const signedIn = view.sign_in === null || view.sign_in.state === 'signed_in' || view.sign_in.state === 'live_after_restart';
  return view.credential.present && signedIn ? view.id : null;
}

/** A login that is running, so the poll keeps going in a background tab while the sign-in page has focus. */
export const loginRunning = (view: AddView | null): boolean => view?.sign_in?.state === 'starting' || view?.sign_in?.state === 'waiting';

/** The plan's name as a person says it. */
export const planLabel = (profile: string): string => (PLAN_LABELS as Record<string, string>)[profile] ?? profile;
