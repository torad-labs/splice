import { fetchSeatdAssets, readSeatdPin } from "../lib/seatd.ts";

export const usage = "seatd fetch <empty-directory>        fetch the four reviewed seatd-pipe release archives";

export async function seatd(argv: readonly string[], repoRoot: string): Promise<number> {
  const [verb, output, ...rest] = argv;
  if (verb !== "fetch" || !output || rest.length > 0) {
    console.error(`usage: bun tools/release ${usage}`);
    return 2;
  }
  try {
    const pin = readSeatdPin(repoRoot);
    await fetchSeatdAssets(pin, output);
    console.log(`release: verified seatd-pipe v${pin.version} in ${output}`);
    return 0;
  } catch (error) {
    console.error(`release: ${error instanceof Error ? error.message : String(error)}`);
    return 1;
  }
}
