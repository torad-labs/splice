// One prompt through one head. A refusal throws the daemon's sentence: 400 a blank prompt or unknown head, 502 a run that failed
// before or during the upstream call, 503 no probe wired. An upstream that answered with an error is a 200. The exchange is held
// only by the mutation that asked, so the next send replaces it and nothing caches a body.
import { useMutation } from '@tanstack/react-query';
import { request } from './client';
import type { PlaygroundBody, PlaygroundWire } from '../types/playground';

export const usePlayground = () =>
  useMutation({
    gcTime: 0,
    mutationFn: (body: PlaygroundBody) => request<PlaygroundWire>('/api/playground', { method: 'POST', body: JSON.stringify(body) }),
  });
