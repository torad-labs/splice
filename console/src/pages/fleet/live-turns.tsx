// The opened head's live turns and the operator's stop (V4-319). A stop ends the turn for its client
// with an error saying the operator stopped it, and the daemon refuses the one re-send Claude Code
// makes of an errored stream, so the stopped work does not run again upstream. The table is read
// while the head is open and not otherwise: nothing else on the page needs a turn's id.
import { useEffect, useState } from 'react';
import { fetchLiveTurns, stopTurn } from '@entities/heads';
import type { LiveTurn, LiveTurnsPayload } from '@entities/heads';
import { Blank, Confirm, Fault } from '@shared/controls';
import { fmtMs, poll } from '@shared/lib';
import { Badge, DataTable, Empty } from '@shared/ui';
import type { Column } from '@shared/ui';
import { S } from './strings';

const LIVE_MS = 2000;

/** A session as the table prints it: its first eight characters, as the hand-offs list prints one. */
export function sessionText(turn: LiveTurn): string {
  return turn.session === null ? S.noSession : turn.session.slice(0, 8);
}

function turnColumns(stopping: string | null, onStop: (id: string) => void): Column<LiveTurn>[] {
  return [
    { key: 'session', label: S.session, cell: sessionText, mono: true, primary: true },
    {
      key: 'model',
      label: S.model,
      mono: true,
      cell: (turn) => (
        <span className="myx-fl-turn-model">
          {turn.model}
          {turn.compact ? <Badge tone="neutral" quiet>{S.compactTurn}</Badge> : null}
        </span>
      ),
    },
    { key: 'age', label: S.age, cell: (turn) => fmtMs(turn.age_ms), mono: true, align: 'end' },
    {
      key: 'stop',
      label: S.stop,
      align: 'end',
      cell: (turn) => (turn.stopped ? <Badge tone="warn" quiet>{S.stopped}</Badge> : (
        <Confirm label={S.stop} confirmLabel={S.stopTurnNow} busy={stopping === turn.id} onConfirm={() => onStop(turn.id)} />
      )),
    },
  ];
}

/** The table for one read of the head's turns. Pure, so a test can hand it a payload. */
export function LiveTurnsView({ payload, stopping = null, onStop = () => undefined }: {
  payload: LiveTurnsPayload;
  stopping?: string | null;
  onStop?: (id: string) => void;
}) {
  if (payload.turns.length === 0) return <Empty text={S.noLiveTurns} />;
  return (
    <DataTable
      columns={turnColumns(stopping, onStop)}
      rows={payload.turns}
      rowKey={(turn) => turn.id}
      label={S.liveTurns}
    />
  );
}

/** The opened head's turns, read every two seconds while it is open, with the stop and its refusal. */
export function LiveTurns({ head }: { head: string }) {
  const [read, setRead] = useState<{ payload: LiveTurnsPayload | null; fault: string | null }>({ payload: null, fault: null });
  const [stopping, setStopping] = useState<string | null>(null);
  const [stopFault, setStopFault] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let current = true;
    const stop = poll(async () => {
      try {
        const payload = await fetchLiveTurns(head);
        if (current) setRead({ payload, fault: null });
      } catch (err) {
        if (current) setRead((held) => ({ payload: held.payload, fault: err instanceof Error ? err.message : String(err) }));
      }
    }, LIVE_MS);
    return () => {
      current = false;
      stop();
    };
  }, [head, tick]);

  const onStop = (id: string) => {
    setStopping(id);
    setStopFault(null);
    stopTurn(head, id).then(
      () => undefined,
      (err: unknown) => setStopFault(err instanceof Error ? err.message : String(err)),
    ).finally(() => {
      setStopping(null);
      setTick((count) => count + 1);
    });
  };

  return (
    <div className="myx-fl-turns">
      {read.fault === null ? null : <Fault message={read.fault} />}
      {stopFault === null ? null : <Fault message={stopFault} />}
      {read.payload === null
        ? (read.fault === null ? <Blank strips={1} label={S.liveTurns} /> : null)
        : <LiveTurnsView payload={read.payload} stopping={stopping} onStop={onStop} />}
    </div>
  );
}
