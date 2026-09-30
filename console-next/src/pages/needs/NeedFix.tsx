import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { failureText } from '../../api/client';
import { useDoctorFix, useRestartDaemon } from '../../api/doctor';
import { useHeadAction, useStopTurn } from '../../api/queries';
import { useLiveTurnOf } from '../../api/sessions';
import { routeOf } from '../../lib/needs-page';
import { clearRestartPending } from '../../lib/restart-pending';
import type { Need } from '../../types/needs';
import { Button } from '../../ui';
import { SignIn } from '../shared/SignIn';
import { A } from './copy';

type Fix = Need['fix'];

/** A command copied to the clipboard, with the words for it working or not. */
function CopyCommand({ command }: { command: string }) {
  const [state, setState] = useState<{ kind: 'idle' | 'copied' } | { kind: 'failed'; message: string }>({ kind: 'idle' });
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return (
    <>
      <Button
        kind="go"
        small
        onClick={() => {
          navigator.clipboard.writeText(command).then(
            () => {
              setState({ kind: 'copied' });
              window.clearTimeout(timer.current);
              timer.current = window.setTimeout(() => setState({ kind: 'idle' }), 2_000);
            },
            (err: unknown) => setState({ kind: 'failed', message: failureText(err) }),
          );
        }}
      >
        {state.kind === 'copied' ? A.copied : A.copy}
      </Button>
      {state.kind === 'failed' ? <span className="hint alert" role="alert">{A.copyFailed} {state.message}</span> : null}
    </>
  );
}

/** A fix that is a link: the page where the act is. `fallback` is the command the row also carries, printed quietly. */
function OpenFix({ fix }: { fix: Extract<Fix, { kind: 'open' }> }) {
  return (
    <>
      <Link className="btn go" to={routeOf(fix.href)}>{fix.label}</Link>
      {fix.fallback === undefined ? null : <code className="fallback">{fix.fallback}</code>}
    </>
  );
}

function HeadAct({ head, act }: { head: string; act: 'start' | 'restart' }) {
  const action = useHeadAction();
  const busy = action.isPending;
  const label = act === 'start' ? (busy ? A.starting : A.start) : busy ? A.restarting : A.restart;
  return (
    <>
      <Button kind="go" disabled={busy} onClick={() => action.mutate({ head, action: act })}>{label}</Button>
      {action.isError ? <span className="hint alert" role="alert">{A.failed} {failureText(action.error)}</span> : null}
    </>
  );
}

function DaemonRestart() {
  const restart = useRestartDaemon();
  return (
    <>
      <Button
        kind="go"
        disabled={restart.isPending}
        onClick={() => restart.mutate(undefined, { onSuccess: clearRestartPending })}
      >
        {restart.isPending ? A.restartingSplice : A.restartSplice}
      </Button>
      {restart.isError ? <span className="hint alert" role="alert">{A.failed} {failureText(restart.error)}</span> : null}
    </>
  );
}

/** Stops the live turn of one session. A session whose head runs no turn for it has nothing to stop, so its one act is opening it. */
function StopFix({ head, session }: { head: string; session: string }) {
  const turn = useLiveTurnOf(head, session);
  const stop = useStopTurn();
  if (turn === null) return <Link className="btn go" to={`/sessions/${encodeURIComponent(session)}`}>{A.openSession}</Link>;
  return (
    <>
      <Button kind="go" disabled={stop.isPending} onClick={() => stop.mutate({ head, id: turn })}>{stop.isPending ? A.stopping : A.stopTurn}</Button>
      {stop.isError ? <span className="hint alert" role="alert">{A.failed} {failureText(stop.error)}</span> : null}
    </>
  );
}

function DoctorFix({ id, fallback }: { id: string; fallback: string | undefined }) {
  const fix = useDoctorFix();
  const refused = fix.data !== undefined && !fix.data.applied ? fix.data.refusal : null;
  return (
    <>
      <Button kind="go" disabled={fix.isPending} onClick={() => fix.mutate(id)}>{fix.isPending ? A.fixing : A.fix}</Button>
      {fix.isError ? <span className="hint alert" role="alert">{A.failed} {failureText(fix.error)}</span> : null}
      {refused === null ? null : <span className="hint alert" role="alert">{A.refused} {refused}</span>}
      {fallback === undefined ? null : <code className="fallback">{fallback}</code>}
    </>
  );
}

/** The one act a card offers, as the derivation named it. Every kind of fix in lib/needs.ts has its arm here. */
export function NeedFix({ fix }: { fix: Fix }) {
  switch (fix.kind) {
    case 'start':
      return <HeadAct head={fix.head} act="start" />;
    case 'restart':
      return <HeadAct head={fix.head} act="restart" />;
    case 'restart-daemon':
      return <DaemonRestart />;
    case 'login':
      return (
        <SignIn head={fix.head} purpose={fix.label === undefined ? 'add' : 'renew'} {...(fix.label === undefined ? {} : { label: fix.label })}>
          <Button kind="go">{fix.label === undefined ? A.signIn : A.signInAgain}</Button>
        </SignIn>
      );
    case 'stop-turn':
      return <StopFix head={fix.head} session={fix.session} />;
    case 'copy':
      return <CopyCommand command={fix.command} />;
    case 'masked':
      return <p className="hint">{A.masked}</p>;
    case 'doctor-fix':
      return <DoctorFix id={fix.id} fallback={fix.fallback} />;
    case 'open':
      return <OpenFix fix={fix} />;
  }
}
