import { useState } from 'react';
import { failureText } from '../../api/client';
import { useCapture, useSetCapture } from '../../api/turns';
import { captureState } from '../../lib/turns-page';
import { P } from '../../lib/words-turns';
import { Switch } from '../../ui';
import { DaemonRestart } from '../shared/DaemonRestart';

/** A head's body capture: the switch shows what splice.toml holds, the daemon's re-read says what it records now, and while the two
 *  differ the page says a restart applies the change and offers it. A refused write changes nothing on screen but its reason. */
export function CaptureControl({ head, plan }: { head: string; plan: string }) {
  const capture = useCapture(head);
  const set = useSetCapture();
  const [written, setWritten] = useState<boolean | null>(null);
  const running = capture.data?.enabled ?? null;
  if (running === null && !capture.isError) return null;
  const state = captureState(running, written);
  const change = set.data;
  return (
    <div className="capture-control">
      <span className="capture-switch">
        <Switch
          label={P.captureSwitch}
          checked={state.on}
          disabled={running === null || set.isPending}
          onChange={(next) => set.mutate({ head, enabled: next }, { onSuccess: (done) => { if (done.write.ok) setWritten(done.write.answer.enabled); } })}
        />
        <span>{P.captureSwitch}</span>
      </span>
      {state.pending ? (
        <>
          <span className="hint">{P.captureWaits(state.on)}</span>
          <DaemonRestart small />
        </>
      ) : state.recording ? <span className="hint">{P.captureRecording(plan)}</span> : null}
      {change === undefined || change.write.ok ? null : <span className="hint alert" role="alert">{P.captureRefused} {change.write.reason}</span>}
      {change?.fault == null ? null : <span className="hint alert" role="alert">{change.fault}</span>}
      {capture.isError ? <span className="hint alert" role="alert">{failureText(capture.error)}</span> : null}
      {set.isError ? <span className="hint alert" role="alert">{failureText(set.error)}</span> : null}
    </div>
  );
}
