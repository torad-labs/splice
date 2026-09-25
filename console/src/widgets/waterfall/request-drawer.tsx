// The request drawer: a head's body-capture switch, and what the console can say about a turn's
// bodies.
//
// It lives here, beside the waterfall, because it is part of a turn's detail and the turns page
// renders it beside the perf row (FEATURES.md 4.9); the logs page opens the same drawer for the head
// it tails. Capture is OFF by default and the drawer says so (PRODUCT.md: nothing is recorded that
// the operator did not ask for, and the console says when it is on).
//
// WHAT THE DAEMON SERVES, AND SO WHAT THIS PRINTS. GET/PUT /api/heads/{head}/capture carry the
// head's trace SETTINGS and nothing else (CaptureRoutes.captureJson): no route serves a captured
// body, so the drawer never frames one. A write lands in splice.toml and runs after the daemon's
// next restart, which the drawer prints beside the running value rather than flipping the switch
// to a state the daemon is not in. Each state is a badge (the 2026-09-25 voice ruling).
import { captureView } from '@entities/perf';
import type { CaptureState } from '@entities/perf';
import { Fault, Flag } from '@shared/controls';
import { fmtInt } from '@shared/lib';
import { Badge, InfoTip, KeyValue } from '@shared/ui';
import { S, H, U } from './strings';
import './waterfall.css';

/** A head that records bodies. */
export const CAPTURE_ON = S.captureOn;

/** A write the daemon took and runs only after a restart (`restart_required`). */
export const CAPTURE_AT_RESTART = S.atRestart;

export function RequestDrawer({ capture, error = null, onSwitch }: {
  capture: CaptureState | null;
  /** A read that failed, in the daemon's words. */
  error?: string | null;
  /** Writes the switch. Absent, the switch is shown and cannot be pressed (a fixture capture). */
  onSwitch?: ((enabled: boolean) => void) | undefined;
}) {
  if (capture === null) return error === null ? null : <Fault message={error} />;
  const view = captureView(capture);
  const settings = capture.running;
  return (
    <div className="myx-rd">
      <div className="myx-rd-head">
        <Flag
          on={view.asked}
          onLabel={S.on}
          offLabel={S.off}
          ariaLabel={S.capture}
          disabled={onSwitch === undefined}
          // A press while a write is in flight is dropped rather than queued: the re-read that lands
          // decides what the switch shows, and a second write racing it could only confuse that.
          onChange={(next) => {
            if (!capture.writing) onSwitch?.(next);
          }}
        />
        {/* The switch prints what was ASKED; the badges print what RUNS where the two can differ.
            Off and running off is the switch alone: a badge beside it said the same word twice. */}
        {view.running ? <Badge tone="warn">{CAPTURE_ON}</Badge> : null}
        {view.awaitingRestart ? <Badge tone="warn" quiet>{CAPTURE_AT_RESTART}</Badge> : null}
        <InfoTip text={H.capture} label={S.about} />
      </div>
      <KeyValue
        rows={[
          [S.retention, `${settings.retention_days} ${U.days}`],
          [S.bodyCap, `${fmtInt(settings.max_body_chars)} ${U.chars}`],
          // Bodies exist only while capture runs, and they stay on the operator's disk: the daemon's
          // own pointer for reading them is its CLI (DoctorTraceChecks), because no route serves one.
          ...(view.running ? [[S.readWith, <code key="cmd" className="myx-rd-cmd">{`splice trace ${settings.head}`}</code>] as const] : []),
        ]}
      />
      {capture.refused === null ? null : <Fault message={capture.refused} />}
      {error === null ? null : <Fault message={error} />}
    </div>
  );
}
