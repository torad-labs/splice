// A console add's wire (V4-220 item 3, #291): the catalogue, the request that opens an add, and the
// ONE session view every /api/add/{id} answer is (AddViews.kt). Typed from AddViews' serializers and
// held to them by tests/add-backend.test.ts, which reads the Kotlin.
import type { LoginView } from '@shared/api';

/** One model row of a profile or an add: its id, the label the catalogue gives it, its context
 *  window in tokens, and the slots (opus, sonnet, haiku) it fills when the head pins by slot. */
export interface AddModel {
  id: string;
  label: string;
  context_window: number;
  slots: string[];
}

/** What the operator must still supply before a profile can be added. */
export type AddAsk = 'name' | 'base_url' | 'models';

/** GET /api/add/profiles, one row. `head_key` empty and `base_url` null are asks, not defaults. */
export interface AddProfile {
  name: string;
  summary: string;
  auth_kind: string;
  base_url: string | null;
  head_key: string;
  /** The wrapper's command; empty means `claude-<key>`. */
  command: string;
  models: AddModel[];
  asks: AddAsk[];
}

export interface AddProfilesPayload {
  profiles: AddProfile[];
}

/** POST /api/add's body: the CLI's own arguments. A model's window is whole tokens. */
export interface AddRequest {
  profile: string;
  name?: string;
  base_url?: string;
  command?: string;
  models?: { id: string; context_window?: number }[];
}

/** One of the CLI's checks (AddChecks.all), as run for this add. */
export interface AddCheck {
  name: string;
  ok: boolean;
  detail: string;
}

/** The restart a save took: the drain began, it waits for the compactions named, or it was refused
 *  (an unsupervised daemon, whose file is written but which nothing will restart). */
export interface AddRestart {
  status: 'draining' | 'waiting' | 'refused';
  error?: string;
  compactions?: { head: string; age_ms: number }[];
}

export interface AddSaved {
  /** The splice.toml the head was written into. */
  path: string;
  wrapper: { linked: boolean; error?: string };
  restart: AddRestart;
}

/** How the head proves who it is: a browser or device login, a key in the key store, or none of its
 *  own (a client head forwards the operator's Claude login). */
export type AddSignInBy = 'login' | 'key' | 'none';

/** Every /api/add/{id} answer, read at the moment it is written: the credential re-checked, the
 *  sign-in polled. */
export interface AddView {
  id: string;
  profile: string;
  key: string;
  command: string;
  auth_kind: string;
  base_url: string | null;
  models: AddModel[];
  sign_in_by: AddSignInBy;
  /** The variable a key-signed head reads its key from; null for the other two. */
  key_env: string | null;
  credential: { present: boolean; detail: string };
  sign_in: LoginView | null;
  /** The last checks run, null until a verify or a save ran them. */
  checks: AddCheck[] | null;
  saved: AddSaved | null;
}

/** A verify or a save the checks refused: the daemon's sentence and every row it ran. */
export interface AddChecksFailed {
  error: string;
  checks: AddCheck[];
}
