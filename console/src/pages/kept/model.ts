import { H, S } from './strings';

export interface KeptRow {
  id: string;
  name: string;
  knobs: readonly string[];
  holds: string;
  window: string;
  location: string;
  switch: string;
}

export function ageOutText(day: string | null): string | null {
  if (day === null) return null;
  const at = new Date(`${day}T12:00:00Z`);
  if (Number.isNaN(at.getTime())) return null;
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(at);
}

export const keptRows: readonly KeptRow[] = [
  { id: 'edges', name: S.edges, knobs: ['MESSAGE_EDGES', 'ACTIVITY_RETENTION_DAYS'], holds: H.edges, window: H.edgeWindow, location: H.edgePath, switch: H.edgeSwitch },
  { id: 'labels', name: S.labels, knobs: ['ACTIVITY_STORE_HEADS'], holds: H.labels, window: H.labelWindow, location: H.labelPath, switch: H.labelSwitch },
  { id: 'trace', name: S.trace, knobs: ['TRACE', 'TRACE_RETENTION_DAYS', 'TRACE_MAX_BODY_CHARS'], holds: H.trace, window: H.traceWindow, location: H.tracePath, switch: H.traceSwitch },
  { id: 'wire-tap', name: S.wireTap, knobs: ['WIRE_TAP'], holds: H.wireTap, window: H.wireWindow, location: H.wirePath, switch: H.wireSwitch },
  { id: 'transcripts', name: S.transcripts, knobs: ['TRANSCRIPT_VIEW'], holds: H.transcripts, window: H.transcriptWindow, location: H.transcriptPath, switch: H.transcriptSwitch },
  { id: 'turns', name: S.turnStats, knobs: ['PERF_ARCHIVE_RETENTION_DAYS'], holds: H.turnStats, window: H.turnWindow, location: H.turnPath, switch: H.turnSwitch },
];
