import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router';
import { failureText } from '../../api/client';
import { useDoctorFix } from '../../api/doctor';
import { useHeadAction } from '../../api/queries';
import { routeOf } from '../../lib/needs-page';
import type { Fix } from '../../types/needs';
import { Button } from '../../ui';
import { DaemonRestart } from '../shared/DaemonRestart';
import { SignIn } from '../shared/SignIn';
import { A } from './copy';

/** `go` is the one charged act (a card's own button); `quiet` is the same act where the page already has its one, as in Settings' health list. */
type Tone = 'go' | 'quiet';

/** A command copied to the clipboard, with the words for it working or not. */
function CopyCommand({ command, tone }: { command: string; tone: Tone }) {
  const [state, setState] = useState<{ kind: 'idle' | 'copied' } | { kind: 'failed'; message: string }>({ kind: 'idle' });
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return (
    <>
      <Button
        kind={tone}
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
function OpenFix({ fix, tone }: { fix: Extract<Fix, { kind: 'open' }>; tone: Tone }) {
  const location = useLocation();
  const target = routeOf(fix.href);
  return (
    <>
      {target === location.pathname || target === location.pathname + location.search ? null : <Link className={`btn ${tone}`} to={target}>{fix.label}</Link>}
      {fix.fallback === undefined ? null : <span className="fallback">{fix.fallback}</span>}
    </>
  );
}

function HeadAct({ head, act, tone }: { head: string; act: 'start' | 'restart'; tone: Tone }) {
  const action = useHeadAction();
  const busy = action.isPending;
  const label = act === 'start' ? (busy ? A.starting : A.start) : busy ? A.restarting : A.restart;
  return (
    <>
      <Button kind={tone} disabled={busy} onClick={() => action.mutate({ head, action: act })}>{label}</Button>
      {action.isError ? <span className="hint alert" role="alert">{A.failed} {failureText(action.error)}</span> : null}
    </>
  );
}

function DoctorFix({ id, fallback, tone }: { id: string; fallback: string | undefined; tone: Tone }) {
  const fix = useDoctorFix();
  const refused = fix.data !== undefined && !fix.data.applied ? fix.data.refusal : null;
  return (
    <>
      <Button kind={tone} disabled={fix.isPending} onClick={() => fix.mutate(id)}>{fix.isPending ? A.fixing : A.fix}</Button>
      {fix.isError ? <span className="hint alert" role="alert">{A.failed} {failureText(fix.error)}</span> : null}
      {refused === null ? null : <span className="hint alert" role="alert">{A.refused} {refused}</span>}
      {fallback === undefined ? null : <span className="fallback">{fallback}</span>}
    </>
  );
}

/** The one act a card offers, as the derivation named it. Every kind of fix in lib/needs.ts has its arm here. */
export function NeedFix({ fix, tone = 'go' }: { fix: Fix; tone?: Tone }) {
  switch (fix.kind) {
    case 'start':
      return <HeadAct head={fix.head} act="start" tone={tone} />;
    case 'restart':
      return <HeadAct head={fix.head} act="restart" tone={tone} />;
    case 'restart-daemon':
      return <DaemonRestart />;
    case 'login':
      return (
        <SignIn head={fix.head} purpose={fix.label === undefined ? 'add' : 'renew'} {...(fix.label === undefined ? {} : { label: fix.label })}>
          <Button kind={tone}>{fix.label === undefined ? A.signIn : A.signInAgain}</Button>
        </SignIn>
      );
    case 'copy':
      return <CopyCommand command={fix.command} tone={tone} />;
    case 'masked':
      return <p className="hint">{A.masked}</p>;
    case 'doctor-fix':
      return <DoctorFix id={fix.id} fallback={fix.fallback} tone={tone} />;
    case 'open':
      return <OpenFix fix={fix} tone={tone} />;
  }
}
