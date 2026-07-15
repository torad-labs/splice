// ARRIVE: which heads need attention, then the bench itself.
import { useEffect } from 'react';
import { startHeadsPolling } from '@entities/heads';
import { startUsagePolling } from '@entities/usage';
import { FleetBanner } from '@widgets/fleet-banner';
import { HeadPlates } from '@widgets/head-plate';

export function FleetPage() {
  useEffect(() => {
    const stops = [startHeadsPolling(2000), startUsagePolling(5000)];
    return () => stops.forEach((stop) => stop());
  }, []);

  return (
    <div className="myx-stack">
      <FleetBanner />
      <HeadPlates />
    </div>
  );
}
