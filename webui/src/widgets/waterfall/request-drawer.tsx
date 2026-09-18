// The request drawer: a turn's captured request and response, or the sentence that says why there
// is nothing to show.
//
// It lives here, beside the waterfall, because it is part of a turn's detail and the turns page
// renders it beside the perf row (FEATURES.md 4.9); the logs page opens the same drawer for a line
// that names a turn. Capture is OFF by default and the drawer says so in words, never with an
// empty frame that could be mistaken for a turn whose body was too large to keep.
import { Empty, Reveal, StripField } from '@shared/ui';
import type { CaptureSlice } from '@entities/perf';
import { S } from './strings';
import './waterfall.css';

/** The one sentence the drawer prints when capture is off, which is the default state of every
 *  head (FEATURES.md 4.9 and 4.12: nothing is recorded unless it was asked for). */
export const CAPTURE_OFF = 'capture off for this head';

export function RequestDrawer({ capture }: { capture: CaptureSlice | null }) {
  if (capture === null) return null;
  if ('pending' in capture) {
    return <Empty text="body capture has no route yet" source="row V4-133" />;
  }
  if (!capture.enabled) {
    return <p className="myx-wf-off">{CAPTURE_OFF}</p>;
  }
  if (capture.request === undefined && capture.response === undefined) {
    return <Empty text="nothing captured for this turn" source="/api/heads/{head}/capture" />;
  }
  return (
    <div className="myx-wf-drawer">
      <div className="myx-wf-drawer-fields">
        <StripField w={20} label={S.request} value={capture.request === undefined ? '-' : `${capture.request.length}`} />
        <StripField w={20} label={S.response} value={capture.response === undefined ? '-' : `${capture.response.length}`} />
        <StripField w={12} label={S.redacted} value="yes" mono={false} />
      </div>
      {capture.request === undefined ? null : (
        <Reveal label={S.request}>
          <pre className="myx-wf-body">{capture.request}</pre>
        </Reveal>
      )}
      {capture.response === undefined ? null : (
        <Reveal label={S.response}>
          <pre className="myx-wf-body">{capture.response}</pre>
        </Reveal>
      )}
    </div>
  );
}
