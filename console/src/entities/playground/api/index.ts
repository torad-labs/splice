// The playground entity's HTTP segment: one write route, no store and no rendering. There is no
// store on purpose: a run's request and response belong to the reducer that asked for them, and a
// store would be the body cache the route's own contract ("never recorded") rules out.
import { request } from '@shared/api';
import type { PlaygroundBody, PlaygroundWire } from '../model/types';

/**
 * Send one prompt through one head and hand back the raw exchange.
 *
 * A REFUSAL THROWS with the daemon's own sentence: 400 for a blank prompt or an unknown head, 502 for
 * a run that failed before or during the upstream call ("head 'x' has no credential configured"),
 * 503 when no probe is wired. An upstream that answered with an error is NOT a refusal: it is a 200
 * whose `response.status` carries the vendor's code, and the page shows it as the answer it is.
 */
export async function runPlayground(head: string, prompt: string): Promise<PlaygroundWire> {
  const body: PlaygroundBody = { head, prompt };
  return request<PlaygroundWire>('/api/playground', { method: 'POST', body: JSON.stringify(body) });
}
