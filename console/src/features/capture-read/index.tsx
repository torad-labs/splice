// What a head recorded, read when the operator asks (V4-239): its trace files, what `splice trace`
// prints, and its wire tap, what `splice wire` prints. It sits in the request drawer beside the
// capture switch.
//
// NOTHING IS READ UNTIL A KEY IS PRESSED, and nothing is polled. The trace list carries no body (the
// route serves none in a list); a body reaches the console only for the one turn opened, and even then
// it stays out of the document behind a reveal until the reader asks for it (shared/ui Reveal). The
// wire tap's bodies ride its one read, each behind its own reveal the same way.
//
// WHAT IS ON DISK, CAPTURE ON OR OFF. The trace files outlive the switch: a head whose capture was
// turned off keeps the days it recorded until retention deletes them, and the route serves them as
// the verb reads them. When capture is off and files remain, the list says they were recorded
// earlier, so an old day never reads as a head recording now. Deleting them stays on the CLI
// (`splice trace --purge`).
import { useState } from 'react';
import { readTrace, readTraceTurn, readWire } from '@entities/perf';
import type { TraceListWire, TraceRecord, TraceSide, TracedTurnWire, TraceTurnWire, WireRead, WireRecordWire } from '@entities/perf';
import { Fault, Key } from '@shared/controls';
import { ABSENT, fmtInt, fmtMs } from '@shared/lib';
import { Badge, DataTable, Empty, InfoTip, KeyValue, Reveal } from '@shared/ui';
import type { Column } from '@shared/ui';
import { H, S, U } from './strings';
import './capture-read.css';

// why: the same 8-character session tag `splice trace` prints (TraceView SESSION_COLUMN_CHARS).
const SESSION_CHARS = 8;

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** A read's answer to its caller, which clears the fault, or its failure in the daemon's words. */
function settle<T>(read: Promise<T>, onRead: (value: T) => void, onFault: (fault: string | null) => void): Promise<void> {
  return read.then(
    (value) => {
      onRead(value);
      onFault(null);
    },
    (err: unknown) => onFault(messageOf(err)),
  );
}

/** Read trace: the newest turns on the head's trace files. */
export function openTrace(head: string, onList: (list: TraceListWire) => void, onFault: (fault: string | null) => void): Promise<void> {
  return settle(readTrace(head), onList, onFault);
}

/** Open turn: that turn's records, bodies included. */
export function openTraceTurn(
  head: string,
  turn: string,
  onTurn: (read: TraceTurnWire) => void,
  onFault: (fault: string | null) => void,
): Promise<void> {
  return settle(readTraceTurn(head, turn), onTurn, onFault);
}

/** Upstream bodies: the wire tap's kept bodies, or the daemon's sentence that it is off. */
export function openWire(head: string, onWire: (read: WireRead) => void, onFault: (fault: string | null) => void): Promise<void> {
  return settle(readWire(head), onWire, onFault);
}

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS of an epoch millisecond, local and 24 hour like every clock in the console. */
function clock(ts: number): string {
  const at = new Date(ts);
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
}

const count = (value: number | null): string => (value === null ? ABSENT : fmtInt(value));

const TURN_COLUMNS: Column<TracedTurnWire>[] = [
  { key: 'time', label: S.time, cell: (turn) => clock(turn.ts), mono: true },
  { key: 'id', label: S.turn, cell: (turn) => turn.id, mono: true, primary: true },
  { key: 'session', label: S.session, cell: (turn) => turn.session?.slice(0, SESSION_CHARS) ?? ABSENT, mono: true },
  { key: 'model', label: S.model, cell: (turn) => turn.model, mono: true, wrap: true },
  {
    key: 'outcome',
    label: S.outcome,
    cell: (turn) => (turn.open ? <Badge tone="warn" quiet>{S.open}</Badge> : turn.outcome ?? ABSENT),
  },
  { key: 'rounds', label: S.rounds, cell: (turn) => count(turn.rounds), align: 'end', mono: true },
  { key: 'attempts', label: S.attempts, cell: (turn) => count(turn.attempts), align: 'end', mono: true },
  { key: 'total', label: S.total, cell: (turn) => (turn.total_ms === null ? ABSENT : fmtMs(turn.total_ms)), align: 'end', mono: true },
];

/** The trace's turns, oldest first, with no body; each opens its records. */
export function TraceList({ list, capturing, onOpen }: {
  list: TraceListWire;
  /** Whether the head records now (the capture read's running value). */
  capturing: boolean;
  onOpen?: (turn: string) => void;
}) {
  if (list.on_disk === 0) return <Empty text={S.noTraced} source={H.noTraced} />;
  return (
    <div className="myx-cr-list">
      <div className="myx-cr-head">
        <strong>{S.trace}</strong>
        <span className="myx-cr-count">{`${fmtInt(list.turns.length)} ${U.of} ${fmtInt(list.on_disk)}`}</span>
        {capturing ? null : (
          <>
            <Badge tone="neutral" quiet>{S.recordedEarlier}</Badge>
            <InfoTip text={H.recordedEarlier} label={S.aboutRecorded} />
          </>
        )}
        {list.skipped_lines === 0 ? null : <Badge tone="warn" quiet>{`${S.skipped} ${fmtInt(list.skipped_lines)}`}</Badge>}
      </div>
      <KeyValue rows={[[S.files, <code key="files" className="myx-cr-path">{list.files}</code>]]} />
      <DataTable
        columns={TURN_COLUMNS}
        rows={list.turns}
        rowKey={(turn) => turn.id}
        label={S.turns}
        {...(onOpen === undefined ? {} : { onOpen: (turn: TracedTurnWire) => onOpen(turn.id), openLabel: (turn: TracedTurnWire) => `${S.openTurn} ${turn.id}` })}
      />
    </div>
  );
}

/** One side of a record, headers and body alike, behind one reveal: nothing of it is in the
 *  document until the reader asks. */
function Side({ label, side, text }: { label: string; side: TraceSide | undefined; text: 'body' | 'text' }) {
  if (side === undefined) return null;
  const content = side[text] ?? '';
  return (
    <Reveal label={`${label} · ${fmtInt(content.length)} ${U.chars}`}>
      {side.headers === undefined ? null : (
        <KeyValue rows={Object.entries(side.headers).map(([name, value]) => [name, value] as const)} />
      )}
      <pre className="myx-cr-body">{content}</pre>
      {side.truncated === true ? <Badge tone="warn" quiet>{S.truncated}</Badge> : null}
    </Reveal>
  );
}

function Attempt({ record }: { record: TraceRecord }) {
  const facts: (readonly [string, string])[] = [
    [S.round, count(record.round ?? null)],
    [S.transport, record.transport ?? ABSENT],
    [S.status, String(record.response?.status ?? ABSENT)],
    [S.duration, record.durationMs === undefined ? ABSENT : fmtMs(record.durationMs)],
    [S.url, record.url ?? ABSENT],
    ...(record.failure === undefined ? [] : [[S.failure, record.failure] as const]),
  ];
  return (
    <li className="myx-cr-record">
      <strong>{`${S.attempt} ${record.attempt ?? ABSENT}`}</strong>
      <KeyValue rows={facts} />
      <Side label={S.request} side={record.request} text="body" />
      <Side label={S.response} side={record.response} text="text" />
    </li>
  );
}

function Closing({ record }: { record: TraceRecord }) {
  const client = record.client;
  return (
    <li className="myx-cr-record">
      <strong>{S.outcome}</strong>
      <KeyValue
        rows={[
          [S.outcome, record.outcome ?? ABSENT],
          [S.clientRequest, client === undefined ? ABSENT : `${client.method ?? ''} ${client.path ?? ''}`.trim()],
          [S.answer, String(record.answer?.status ?? ABSENT)],
        ]}
      />
      <Side label={S.clientRequest} side={client} text="body" />
      <Side label={S.answer} side={record.answer} text="body" />
    </li>
  );
}

/** One turn's records in the order they were written: the upstream attempts, then the turn record. */
export function TraceTurn({ read }: { read: TraceTurnWire }) {
  return (
    <div className="myx-cr-turn" aria-label={`${S.trace} ${read.turn.id}`}>
      <div className="myx-cr-head">
        <strong className="myx-cr-path">{read.turn.id}</strong>
        {read.turn.open ? <Badge tone="warn" quiet>{S.open}</Badge> : null}
      </div>
      <ol className="myx-cr-records">
        {read.records.map((record, index) => (record.kind === 'turn'
          ? <Closing key={`turn-${index}`} record={record} />
          : <Attempt key={`attempt-${index}`} record={record} />))}
      </ol>
    </div>
  );
}

function WireBody({ record }: { record: WireRecordWire }) {
  return (
    <li className="myx-cr-record">
      <div className="myx-cr-head">
        <span className="myx-cr-count">{clock(record.ts)}</span>
        <code>{record.model}</code>
        {record.session === undefined ? null : <code>{record.session.slice(0, SESSION_CHARS)}</code>}
        {record.compact ? <Badge tone="neutral" quiet>{S.compact}</Badge> : null}
      </div>
      <Reveal label={`${S.request} · ${fmtInt(record.body.length)} ${U.chars}`}>
        <pre className="myx-cr-body">{record.body}</pre>
      </Reveal>
    </li>
  );
}

/** The wire tap: its kept bodies oldest first, or the daemon's sentence that it is off. */
export function WireList({ read }: { read: WireRead }) {
  if ('off' in read) {
    return (
      <div className="myx-cr-head">
        <Badge tone="neutral">{S.tapOff}</Badge>
        <span className="myx-cr-off">{read.off}</span>
      </div>
    );
  }
  const { tap } = read;
  return (
    <div className="myx-cr-list">
      <div className="myx-cr-head">
        <strong>{S.wire}</strong>
        <span className="myx-cr-count">{`${S.kept} ${fmtInt(tap.records.length)} ${U.of} ${fmtInt(tap.keep)}`}</span>
        <InfoTip text={H.wire} label={S.aboutWire} />
      </div>
      {tap.records.length === 0 ? <Empty text={S.noBodies} source={H.noBodies} /> : (
        <ol className="myx-cr-records">
          {tap.records.map((record, index) => <WireBody key={`${record.ts}-${index}`} record={record} />)}
        </ol>
      )}
    </div>
  );
}

/** The two reads, for the head the drawer shows. */
export function CaptureRead({ head, capturing }: { head: string; capturing: boolean }) {
  const [trace, setTrace] = useState<TraceListWire | null>(null);
  const [turn, setTurn] = useState<TraceTurnWire | null>(null);
  const [wire, setWire] = useState<WireRead | null>(null);
  const [fault, setFault] = useState<string | null>(null);
  return (
    <div className="myx-cr">
      <div className="myx-cr-keys">
        <Key onClick={() => void openTrace(head, (list) => { setTurn(null); setTrace(list); }, setFault)}>{S.readTrace}</Key>
        <Key onClick={() => void openWire(head, setWire, setFault)}>{S.readWire}</Key>
      </div>
      {fault === null ? null : <Fault message={fault} />}
      {trace === null ? null : (
        <TraceList list={trace} capturing={capturing} onOpen={(id) => void openTraceTurn(head, id, setTurn, setFault)} />
      )}
      {turn === null ? null : <TraceTurn read={turn} />}
      {wire === null ? null : <WireList read={wire} />}
    </div>
  );
}
