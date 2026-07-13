// The detection-verdicts feed — the second page the reference does not have.
import { useEffect } from 'react';
import { startCompactPolling } from '@entities/compact-stats';
import { CompactFeed } from '@widgets/compact-feed';

export function CompactionPage() {
  useEffect(() => startCompactPolling(5000), []);
  return <CompactFeed />;
}
