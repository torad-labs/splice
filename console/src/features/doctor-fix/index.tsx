// A fix the daemon runs itself (V4-220 item 4), as ONE control: Doctor's check detail and Needs you
// both mount it, so the confirmation and the way an answer prints cannot drift between the two.
//
// INLINE, TWO PRESSES (the world's Confirm): the first arms the key, whose word says what the second
// press does. install --all rewrites links under the operator's bin dir, the kind of write the brief
// keeps a two-step for; it interrupts no session, so there is none to name.
//
// THE ANSWER IS DOCTOR RE-RUN. runDoctorFix puts the report the daemon took after the fix into the
// doctor store, which both hosts read: a fix that took leaves no row calling for it, so this control
// leaves with its check's fix (the check turns OK in Doctor and leaves Needs you), and there is no
// success state to print. A refusal prints the daemon's own sentence while the check stays.
import { useState } from 'react';
import { runDoctorFix } from '@entities/doctor';
import { Confirm, Fault } from '@shared/controls';
import { S } from './strings';
import './doctor-fix.css';

export function DoctorFix({ id }: { id: string }) {
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  const send = () => {
    setBusy(true);
    setRefusal(null);
    runDoctorFix(id).then(
      (done) => setRefusal(done.applied ? null : done.refusal),
      (err: unknown) => setRefusal(err instanceof Error ? err.message : String(err)),
    ).finally(() => setBusy(false));
  };

  return (
    <div className="myx-dfix">
      <Confirm label={S.run} confirmLabel={S.confirm[id] ?? S.confirmOther} busy={busy} onConfirm={send} />
      {refusal === null ? null : <Fault message={refusal} />}
    </div>
  );
}
