// The account actions: start a login, finish it, pin the next account, relabel, remove, refresh.
//
// Buttons here are raw <button> elements styled from this feature's own CSS rather than the old
// `Btn`/`ConfirmBtn` primitives. That is deliberate and matches CONTRACTS.md section 2: those
// exports "keep working until M2 removes its last consumer", so an M2 page is exactly the consumer
// they are waiting to lose. The shell's own features/views takes the same route.
//
// Nothing here is modal. A destructive action arms in place and disarms on its own, the way
// ConfirmBtn did, because the brief rules out a dialog over the room.
import { useEffect, useReducer, useState } from 'react';
import { fetchLoginStatus, refreshAuth, relabelAccount, removeAccount, startLogin, switchAccount } from '@entities/auth';
import type { LoginStartPayload } from '@entities/auth';
import { Empty, FieldBox, HolderEdge, Reveal, Strip, StripField } from '@shared/ui';
import { poll } from '@shared/lib';
import { IDLE, LOGIN_PENDING_EMPTY, canStart, next, stepMessage } from './model';
import type { LoginEvent } from './model';
import { NOT_REPORTED } from '@entities/account';
import { familyName } from '@entities/heads';
import { S } from './strings';
import './account-login.css';

const POLL_MS = 2000;


const LOGIN_STATUS = 'login-status';
const LOGIN_START = 'login';

async function beginLogin(
  head: string,
  label: string,
  dispatch: (event: LoginEvent) => void,
): Promise<void> {
  dispatch({ kind: 'start' });
  try {
    const outcome = await startLogin(head, label);
    if ('pending' in outcome) {
      dispatch({ kind: 'pending', row: outcome.pending });
      return;
    }
    if (outcome.action === LOGIN_START) dispatch({ kind: 'started', payload: outcome.result });
  } catch (err) {
    dispatch({ kind: 'failed', note: err instanceof Error ? err.message : String(err) });
  }
}

async function pollLogin(
  head: string,
  loginId: string,
  dispatch: (event: LoginEvent) => void,
): Promise<void> {
  try {
    const outcome = await fetchLoginStatus(head, loginId);
    if ('pending' in outcome) {
      dispatch({ kind: 'pending', row: outcome.pending });
      return;
    }
    if (outcome.action === LOGIN_STATUS) dispatch({ kind: 'status', payload: outcome.result });
  } catch (err) {
    dispatch({ kind: 'failed', note: err instanceof Error ? err.message : String(err) });
  }
}

/** A copy affordance that admits when the clipboard is unavailable instead of silently doing
 *  nothing: a console served over plain http has no navigator.clipboard. */
function CopyButton({ value, label }: { value: string; label: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="myx-acct-btn"
      onClick={() => {
        void navigator.clipboard?.writeText(value).then(
          () => setDone(true),
          () => setDone(false),
        );
      }}
    >
      {done ? S.copied : label}
    </button>
  );
}

/** What the operator needs to finish a login somewhere else: the code and its link, or a URL. */
function LoginTicket({ start }: { start: LoginStartPayload }) {
  if (start.flow === 'browser') {
    return (
      <div className="myx-acct-ticket">
        {start.browser_url === undefined ? null : (
          <>
            <a className="myx-acct-link" href={start.browser_url}>{start.browser_url}</a>
            <CopyButton value={start.browser_url} label={S.copy} />
          </>
        )}
      </div>
    );
  }
  return (
    <div className="myx-acct-ticket">
      {start.user_code === undefined ? null : (
        <>
          <span className="myx-acct-code">{start.user_code}</span>
          <CopyButton value={start.user_code} label={`${S.copy} ${S.code}`} />
        </>
      )}
      {start.verification_uri === undefined ? null : (
        <>
          <a className="myx-acct-link" href={start.verification_uri}>{start.verification_uri}</a>
          <CopyButton value={start.verification_uri} label={`${S.copy} ${S.link}`} />
        </>
      )}
    </div>
  );
}

/** Start a login for a new account on one head. The form stays behind a Reveal so the bay is a
 *  rack of strips and not a form with strips in it. */
export function AccountLogin({ head }: { head: string }) {
  const [state, dispatch] = useReducer(next, IDLE);
  const loginId = state.start?.login_id;

  useEffect(() => {
    if (state.step !== 'awaiting' || loginId === undefined) return;
    return poll(() => {
      void pollLogin(head, loginId, dispatch);
    }, POLL_MS);
  }, [state.step, loginId, head]);

  if (state.step === 'pending') {
    return <Empty text={LOGIN_PENDING_EMPTY.text} source={LOGIN_PENDING_EMPTY.source} />;
  }

  const message = stepMessage(state);

  return (
    <Reveal label={S.add}>
      <div className="myx-acct-form">
        <FieldBox
          label={S.label}
          value={state.label}
          provenance="state file"
          hot
          onChange={(value) => dispatch({ kind: 'label', value })}
        />
        <div className="myx-acct-row">
          <button
            type="button"
            className="myx-acct-btn"
            disabled={!canStart(state)}
            onClick={() => void beginLogin(head, state.label.trim(), dispatch)}
          >
            {S.start}
          </button>
          {state.step === 'idle' ? null : (
            <button type="button" className="myx-acct-btn" onClick={() => dispatch({ kind: 'reset' })}>
              {S.cancel}
            </button>
          )}
        </div>
        {state.start === null ? null : <LoginTicket start={state.start} />}
        {message === null ? null : <p className="myx-acct-note" role="status">{message}</p>}
      </div>
    </Reveal>
  );
}

/**
 * Everything that can be done TO one account. Each action is addressed the way its route is: a
 * switch and a refresh name a HEAD, because selection is per head and several heads can ride the
 * same login; remove and relabel name the KIND, because the pool belongs to the kind.
 */
export function AccountActions({ kind, label, heads }: {
  kind: string;
  label: string;
  heads: readonly string[];
}) {
  const [nextLabel, setNextLabel] = useState(label);
  const [armed, setArmed] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const run = (work: Promise<unknown>) => {
    work.then(
      () => setNote(null),
      (err: unknown) => setNote(err instanceof Error ? err.message : String(err)),
    );
  };

  return (
    <div className="myx-acct-actions">
      <div className="myx-acct-row">
        <HolderEdge state="grey" label={kind} />
        <span className="myx-acct-name">{label}</span>
      </div>

      {heads.map((head) => (
        <div className="myx-acct-row" key={head}>
          <button type="button" className="myx-acct-btn" onClick={() => run(switchAccount(head, label))}>
            {`${S.switch} ${head}`}
          </button>
          <button type="button" className="myx-acct-btn" onClick={() => run(refreshAuth(head))}>
            {`${S.refresh} ${head}`}
          </button>
        </div>
      ))}

      <div className="myx-acct-row">
        <FieldBox
          label={S.relabel}
          value={nextLabel}
          provenance="state file"
          hot
          onChange={setNextLabel}
        />
        <button
          type="button"
          className="myx-acct-btn"
          disabled={nextLabel.trim() === '' || nextLabel === label}
          onClick={() => run(relabelAccount(kind, label, nextLabel.trim()))}
        >
          {S.relabel}
        </button>
      </div>

      <div className="myx-acct-row">
        {armed ? (
          <>
            <button
              type="button"
              className="myx-acct-btn myx-acct-btn-armed"
              onClick={() => {
                setArmed(false);
                run(removeAccount(kind, label));
              }}
            >
              {`${S.remove} ${label}`}
            </button>
            <button type="button" className="myx-acct-btn" onClick={() => setArmed(false)}>
              {S.cancel}
            </button>
          </>
        ) : (
          <button type="button" className="myx-acct-btn" onClick={() => setArmed(true)}>
            {S.remove}
          </button>
        )}
      </div>

      {note === null ? null : <p className="myx-acct-note" role="alert">{note}</p>}
    </div>
  );
}

/**
 * One head's auth card, for the state before GET /api/accounts exists. It is a real STRIP, not a
 * card: the page it belongs to is a rack, and a fallback that rendered as a list of paragraphs
 * would be a different page wearing the same route. Its actions live in the detail column, the
 * same place every other strip's do.
 */
export function HeadAuthStrip({ head, kind, present, masked, note, selected, onOpen }: {
  head: string;
  kind: string;
  present: boolean;
  masked: string | null;
  note: string | null;
  selected?: boolean;
  onOpen?: () => void;
}) {
  return (
    <Strip
      className="myx-strip-fixed"
      edge={present ? 'green' : 'grey'}
      edgeLabel={present ? 'signed in' : 'no credential'}
      selected={selected ?? false}
      {...(onOpen === undefined ? {} : { onOpen })}
      ariaLabel={`${head} ${kind}`}
    >
      {/* Widths are the CONTENT width plus the field's own inline padding (~2.5ch at --text-3):
          'chatgpt-oauth' is 13 characters and truncated in a 13ch box. */}
      <StripField w={16} fixed label={S.head} value={head} mono={false} />
      <StripField w={16} fixed label={S.provider} value={familyName(kind)} mono={false} />
      <StripField w={20} fixed label={S.account} value={masked ?? NOT_REPORTED} mono={false} />
      {/* THE TRACK RENDERS EMPTY (M1-107), the fourth of four sites that made the FIELD vanish
          where twenty-two render it and fall back the value. An optional note is an empty cell in
          a track, not a row with one fewer column -- the grid's whole affordance is that field N
          is at the same x on every strip. The LAST track alone takes the slack: every edge before it
          stays at its declared x, which is all M1-107 fixed, and the strip no longer ends in a
          slab of bare paper once its rack fills the bay. */}
      <StripField w={24} label={S.note} value={note ?? ''} mono={false} />
    </Strip>
  );
}

/** The actions that belong to a head rather than to one of its accounts. */
export function HeadActions({ head }: { head: string }) {
  return (
    <div className="myx-acct-actions">
      <div className="myx-acct-row">
        <HolderEdge state="green" label="head" />
        <span className="myx-acct-name">{head}</span>
      </div>
      <div className="myx-acct-row">
        <button type="button" className="myx-acct-btn" onClick={() => void refreshAuth(head)}>
          {S.refresh}
        </button>
      </div>
      <AccountLogin head={head} />
    </div>
  );
}
