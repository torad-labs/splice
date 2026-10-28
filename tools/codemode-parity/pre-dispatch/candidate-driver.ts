import { producePreDispatchCandidate } from "../current-pre-dispatch-candidate";
await producePreDispatchCandidate(await Bun.file(process.argv[2]!).json());
