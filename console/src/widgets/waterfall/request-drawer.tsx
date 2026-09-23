// The request drawer: a head's body-capture switch, and what the console can say about a turn's
// bodies.
//
// It lives here, beside the waterfall, because it is part of a turn's detail and the turns page
// renders it beside the perf row (FEATURES.md 4.9); the logs page opens the same drawer for the head
// it tails. Capture is OFF by default and the drawer says so in words (PRODUCT.md: nothing is
// recorded that the operator did not ask for, and the console says when it is on).
//
// WHAT THE DAEMON SERVES, AND SO WHAT THIS PRINTS. GET/PUT /api/heads/{head}/capture carry the
// head's trace SETTINGS and nothing else (CaptureRoutes.captureJson): no route serves a captured
// body, so the drawer never frames one. A write lands in splice.toml and runs after the daemon's
// next restart, which the drawer prints beside the running value rather than flipping the switch
// to a state the daemon is not in.
import { captureView } from '@entities/perf';
import type { CaptureState } from '@entities/perf';
import { Fault, Flag } from '@shared/controls';
import { fmtInt } from '@shared/lib';
import { Empty, StripField } from '@shared/ui';
import { S } from './strings';
import './waterfall.css';

/** The sentence for a head that records nothing, which is the default state of every head
 *  (FEATURES.md 4.9 and 4.12). */
export const CAPTURE_OFF = 'capture off for this head';

/** The sentence for a head that records bodies. */
export const CAPTURE_ON = 'capture on for this head: request and response bodies are recorded on local disk';

/** The sentence for a write the daemon took and runs only after a restart (`restart_required`). */
export const CAPTURE_AT_RESTART = 'written to splice.toml: the daemon applies it at its next restart';

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
    <div className="myx-wf-drawer">
      <div className="myx-wf-drawer-fields">
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
        <StripField w={10} label={S.running} value={view.running ? S.on : S.off} mono={false} />
        <StripField w={12} label={S.retention} value={`${settings.retention_days} d`} />
        <StripField w={18} label={S.bodyCap} value={`${fmtInt(settings.max_body_chars)} chars`} />
      </div>
      <p className="myx-wf-off">{view.running ? CAPTURE_ON : CAPTURE_OFF}</p>
      {view.awaitingRestart ? <p className="myx-wf-off">{CAPTURE_AT_RESTART}</p> : null}
      {capture.refused === null ? null : <Fault message={capture.refused} />}
      {error === null ? null : <Fault message={error} />}
      {/* Bodies exist only while capture runs, and they stay on the operator's disk: the daemon's
          own pointer for reading them is its CLI (DoctorTraceChecks), because no route serves one. */}
      {view.running ? <Empty text="no daemon route serves captured bodies" source={`splice trace ${settings.head}`} /> : null}
    </div>
  );
}
