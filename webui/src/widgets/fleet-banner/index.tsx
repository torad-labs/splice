// Absent at rest. Names any head at warn/critical in the warn ink, so a
// glance at the top of the fleet tab catches it without scanning every plate.
import { useUsage } from '@entities/usage';

export function FleetBanner() {
  // Select a STABLE reference (the store's own heads array); derive the filtered
  // list here in render, never inside the selector. A new [] / .filter() array on
  // every selector call reads as a changed snapshot to useSyncExternalStore
  // ("getSnapshot should be cached" infinite loop) and unmounts the whole tree.
  const heads = useUsage((s) => s.data?.heads);
  const flagged = (heads ?? []).filter((h) => h.usage && h.usage.warn.level !== 'ok');
  if (!flagged.length) return null;

  return (
    <div className="myx-fleet-banner" role="alert">
      {flagged.map((h) => (
        <span key={h.key} className={h.usage?.warn.level === 'critical' ? 'myx-ink-neg' : 'myx-ink-amber'}>
          {h.label}: {h.usage?.warn.level} at {h.usage?.warn.pct}%
        </span>
      ))}
    </div>
  );
}
