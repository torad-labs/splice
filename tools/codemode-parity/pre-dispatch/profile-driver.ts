import { producePreDispatchProfile } from "../current-pre-dispatch-profile";
await producePreDispatchProfile(await Bun.file(process.argv[2]!).json());
