import { useEffect } from 'react';
import { startLogsPolling } from '@entities/logs';
import { LogTail } from '@widgets/log-tail';

export function LogsPage() {
  useEffect(() => startLogsPolling(5000), []);
  return <LogTail />;
}
