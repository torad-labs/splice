import { Empty, PageHead } from '../ui';
import { C } from './copy';

/** A place the replacement has not built yet: it says so instead of showing nothing. */
export function Pending({ place }: { place: string }) {
  return (
    <>
      <PageHead title={place} />
      <Empty title={C.pending} />
    </>
  );
}
