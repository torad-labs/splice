import type { CurrentInventory, SourceUnit } from "./current-inventory";

// Exact review of rust-v0.165.0 immutable Git objects. This does not infer a
// private module from a public type's spelling, and never covers a method body.
const revision = "360319d8dee62d87e2d59fc0c21805d79519cfed";
const sourceTree = "307e4fa207fe563a11f3f7af09cee6fccd2d8376";
const root = "codex-rs/core/src/";
const filePins = [
  ["lib.rs", "9e42b1c5aee046ca08247adaf22b886f263918bb1af9220712438e7c8d88e394", "2c91c8e487fcd781ef8682d6ff471616eb3d3765"],
  ["tools/mod.rs", "5dec1f3c4c0e48a73d8bfe1ffdb46a55f8e7db6a96ed12df311099f1ac8ed964", "73473f41d1d088d45702b074bf7bb2b8e6f121bb"],
  ["tools/context.rs", "f7c4945eebc54bedb8a53723cf9efe75331f67736ceab95c8b42ada11d9e2f73", "c80a89ddd1fe2adb5a36b36016cdd5f4a3198b43"],
  ["tools/router.rs", "004482b5398df9b02c503a9115f4899802f6793661f84212e00af76a167ff2d4", "36fbfe442724f9f0ae7b003f336a104713591880"],
  ["tools/lifecycle.rs", "a0af244f3e3461838f5f25f424de5402f2af1bf2659d2f35d1cd9502cbc4ab33", "81aa6b70b9192c15e8841cb4aee8af96cf3bba77"],
  ["tools/registry.rs", "fa928f78a1c2b06778d97138c195f46c574072e4538a9cb99bdec33ecd171a15", "c60feef0be83c9fb2fa29327bf80987cfb5ab239"],
  ["session/step_context.rs", "a0a110311756ecebe4597d2283ad5abf7c7db1fe51b5fe4a50cb973661fbe331", "5d4162a500624b4defa994c0b2d29d33d329f6bc"],
] as const;
type Reviewed = readonly [suffix: string, label: string, line: number, end: number, sha256: string];
const owners: readonly Reviewed[] = [
  ["tools/context.rs#enum_item@2154", "ToolCallSource", 57, 2598, "5e9ac75976aeeaa598c3863d361a760ba160b89b18bfa49a52ea831d8343ae00"],
  ["tools/context.rs#struct_item@10227", "FunctionToolOutput", 302, 10392, "ff483746593e962ee2f10f15f3eb483dd1ab4631c73c7842ff9547ab36890ce8"],
  ["tools/context.rs#struct_item@12577", "AbortedToolOutput", 390, 12634, "bbb6d2987075bb888ade5372c6846e28dc2165be82b3005940d0697ffa7b9197"],
  ["tools/context.rs#struct_item@13534", "ExecCommandToolOutput", 424, 14132, "b81a4dba59cbde29be8091618a6e96bb460f8a1a69913507a03fa9361efa9cc3"],
  ["tools/context.rs#struct_item@3895", "ToolInvocation", 112, 4446, "47bc52f670ba8f111ff552bc65ea7fc7901935144c83c69fad5884da304d9a05"],
  ["tools/context.rs#struct_item@5900", "McpToolOutput", 170, 6254, "3b3a00ceba0cecfe860050612decf73c1d589754cce3812c25e13d254c2a97e9"],
];
const members: readonly Reviewed[] = [
  ["tools/context.rs#enum_item@2154", "ToolCallSource", 57, 2598, "5e9ac75976aeeaa598c3863d361a760ba160b89b18bfa49a52ea831d8343ae00"],
  ["tools/context.rs#enum_variant@2184", "Direct", 58, 2190, "002c7c68468b4f550fbaa7fb8588155c58f8b5760fde69b3fe1773ae2a197ba2"],
  ["tools/context.rs#enum_variant@2196", "DirectPlaintextMessage", 59, 2218, "968f0c17d624a34804e91a9002d71b92a5976ba567eb44835ee7f3ec71b0b764"],
  ["tools/context.rs#enum_variant@2224", "CodeMode", 60, 2595, "a6e73160c97749116c6cd99702328b80e8d2c1f0676e5127a8c6a899a3da657f"],
  ["tools/context.rs#field_declaration@10263", "body", 303, 10307, "9d674ccb71aebb6e2d222531d1855ede2407d87fe81523e654ec2da5da444524"],
  ["tools/context.rs#field_declaration@10313", "success", 304, 10338, "7cbca4c5fba93a35f7181271004dff2061f6144079fe45aa6e5a8291a3486289"],
  ["tools/context.rs#field_declaration@12612", "message", 391, 12631, "3219f3b28b2c25a954a3703c9e513a2219e05ae5ad84f0e48e0c2097eee4ae2c"],
  ["tools/context.rs#field_declaration@13604", "chunk_id", 426, 13624, "0be5d5b68e241646155a64e8ae4e77e848f4c6bf73424094f61d79043f576a2e"],
  ["tools/context.rs#field_declaration@13630", "wall_time", 427, 13653, "c2f521f4f157de8734ab956e20d6aaf08a328860b16681d49a2e408f3b56ed50"],
  ["tools/context.rs#field_declaration@13736", "raw_output", 429, 13759, "d9f2b82a05418d3af1f3339d0be2975ca65565146ecb5761d7b962bf3ae0de72"],
  ["tools/context.rs#field_declaration@13810", "max_output_tokens", 431, 13846, "cdf5c3a7b686b0bbed155206839312eacfc37b21419ec5c39e3a029b8b1f464d"],
  ["tools/context.rs#field_declaration@13852", "process_id", 432, 13879, "b0bf520e89175f0a70d707f54a83984827c17e04b4217763b9f346181598584c"],
  ["tools/context.rs#field_declaration@13885", "exit_code", 433, 13911, "5a35b72eecac9ed54a151cfc63bc2ebf06eed887ac40c60d32d1f4c63b9cdb7c"],
  ["tools/context.rs#field_declaration@13917", "original_token_count", 434, 13956, "d5c0e7a28c7ba2a44c12b524e1593bb4e0ca7a8be17f7b1181c00f1be44594ff"],
  ["tools/context.rs#field_declaration@14045", "output_omitted_bytes", 436, 14091, "12f88a82e810cad9115c5d1b1a1796f2ee8c21cc0ff22cf82f3d5e7b91b5d7f4"],
  ["tools/context.rs#field_declaration@2305", "cell_id", 62, 2320, "8dac9a9fac81b120729b6939b6002c0533b2e83475bc60975257edce619e40e8"],
  ["tools/context.rs#field_declaration@2560", "runtime_tool_call_id", 66, 2588, "2101a6eaef0bfdd1eede2bb65ba5723d78160b635eecd11aab224609f0c8a863"],
  ["tools/context.rs#field_declaration@3927", "session", 113, 3952, "8731edc775a579cde9503434e577a93e9931ddb0c6a7841d999f2c568f2eb07b"],
  ["tools/context.rs#field_declaration@4049", "turn", 115, 4075, "07c2ac993178449e56565178138efacf57e92d7565cc62c72f933752fa55a048"],
  ["tools/context.rs#field_declaration@4081", "step_context", 116, 4122, "0d56d4f63d579a43a233731a88cebe0100537b9ee7d8627366440c299f827c7d"],
  ["tools/context.rs#field_declaration@4128", "cancellation_token", 117, 4169, "2f3cdd07e2a134b4ede0b448efc48347cc5d70ef57039c1b7f84cdbbe9bd119f"],
  ["tools/context.rs#field_declaration@4175", "tracker", 118, 4209, "52fef3766165ddcd4cc44d57de9871e330656bb925af5a3efaef78d73b5dea33"],
  ["tools/context.rs#field_declaration@4215", "call_id", 119, 4234, "e0406b57b51f20e84b7a3a218e68b382e063eee900e3b0c22fbc9ff9b2a7eb3e"],
  ["tools/context.rs#field_declaration@4240", "tool_name", 120, 4263, "d6e2cbd40b09206163e29c154b85a3c2c79715ec06c212ba40e688c66f4cac63"],
  ["tools/context.rs#field_declaration@4269", "source", 121, 4295, "27806fba2d48071e516c1fb9223ac66e5cd0cd99330ba486e01ed7deeb815bf1"],
  ["tools/context.rs#field_declaration@4301", "payload", 122, 4325, "3bcdbef366512c1906498d19d18f7d206e5ce1eed4bc0917f9722ea277e3f16f"],
  ["tools/context.rs#field_declaration@4402", "environments", 124, 4443, "0d640980d7ff4d3fba623e258514a02bc1f454853d7ce8dacca3fa9285263013"],
  ["tools/context.rs#field_declaration@5931", "result", 171, 5957, "083373fc88169e7d7db3d98a00d6a8b0f635f95502045a0cae926c0b50df8c34"],
  ["tools/context.rs#struct_item@10227", "FunctionToolOutput", 302, 10392, "ff483746593e962ee2f10f15f3eb483dd1ab4631c73c7842ff9547ab36890ce8"],
  ["tools/context.rs#struct_item@12577", "AbortedToolOutput", 390, 12634, "bbb6d2987075bb888ade5372c6846e28dc2165be82b3005940d0697ffa7b9197"],
  ["tools/context.rs#struct_item@3895", "ToolInvocation", 112, 4446, "47bc52f670ba8f111ff552bc65ea7fc7901935144c83c69fad5884da304d9a05"],
];
const executable: readonly Reviewed[] = [
  ["tools/context.rs#function_item@10424", "from_text", 309, 10656, "357a89741d9f8d9aed12a609bfcb560deee2221894896ef80cfba757008a57ee"],
  ["tools/context.rs#function_item@10662", "from_content", 317, 10903, "1dbbe596fd357b0fcb763be0b219439dc770ae7364318cd691dd5906e48f433f"],
  ["tools/context.rs#function_item@11081", "log_output", 334, 11202, "1cc4c33ffd981a60a2db3e639a9ebea9d19a8a3c103d1d9d713788ef869f6890"],
  ["tools/context.rs#function_item@11208", "success_for_logging", 338, 11290, "e63832f9a3aafe5105e7a11bc0287caeff19a12aa9f526cc2af658b51838570e"],
  ["tools/context.rs#function_item@11296", "to_response_item", 342, 11471, "0ed36ae2c4aa1fb8ef99102f3beff94bde1cbf267ff831c27e6c5ee5eaced03c"],
  ["tools/context.rs#function_item@12452", "code_mode_result", 385, 12573, "9abfcb28b786c9806a46031976924715e5436194fe3b6e85f16d0e435f1fb795"],
  ["tools/context.rs#function_item@15843", "code_mode_result", 492, 17182, "51be17b1686c01d2579f22e17cf7b2805e61ad3cbd7e5c7f0b1260acad4990cf"],
  ["tools/context.rs#function_item@1591", "boxed_tool_output", 39, 1719, "ac02309183bd60e773d73aa5c0163e90622bed9a63101e42aa4ecbad70810938"],
  ["tools/context.rs#function_item@17557", "truncated_output", 535, 17711, "448415560a4f4fb1816c5e1ae03ecc24de5243e9cab0532aabc7daf842e6f570"],
  ["tools/context.rs#function_item@17717", "truncated_output_with_policy", 539, 18775, "263580c8803dcc7c19269c022eaa556e46b6e3d4df0169626d0448a52625468d"],
  ["tools/context.rs#function_item@20927", "function_tool_response", 622, 21743, "71fca54d628c0421fbf789798c297c4375c2d77317ca94960f32a2136105679e"],
  ["tools/context.rs#function_item@3150", "single_id", 88, 3565, "03aba86ec9367bbe4913909224cf50ad1d06c48c79b4d01ba895e552013e69c5"],
  ["tools/context.rs#function_item@3571", "hook_ids", 100, 3874, "424afda9d4a87758a68fd3672f11d7b635a581485330d46df82c370b19a169b2"],
  ["tools/context.rs#function_item@4915", "originating_call", 138, 5872, "dbafe8a228c1f76f5d65961baf81888d0412c1ee2d7a7af465b70fd04b633efa"],
  ["tools/context.rs#function_item@7214", "code_mode_result", 208, 7330, "f495e31dad61fab10af9b26118b3bd1860b156cec4f9cdfe94765af5995c298b"],
  ["tools/lifecycle.rs#function_item@3796", "notify_tool_finish", 110, 4132, "5aa5b3a1597d64599bc6d00b19da0cf35c1252ae9f487a9eaef7671a0ca4889a"],
  ["tools/lifecycle.rs#function_item@4134", "notify_tool_aborted", 122, 4465, "e4e8611c9b598a85ee1755178696002c11731cfad0364621120d9b01a02cdc32"],
  ["tools/lifecycle.rs#function_item@4467", "notify_tool_finish_parts", 140, 5241, "97d023cd0a2c05235cf6f43b303985d17f7ae8fd6eb4a580a28d78788c4821bb"],
  ["tools/lifecycle.rs#function_item@5243", "extension_tool_call_source", 164, 5699, "b5dc66c3b1d057f4ad07a50c0128be5c24d70060c5b78355f3af656094cceada"],
  ["tools/lifecycle.rs#function_item@709", "notify_tool_start", 21, 2302, "a8c5b55e666e14598bec4e554a3785e156aee8e203f8e3db44104405d89e09fe"],
  ["tools/registry.rs#function_item@11237", "code_mode_result", 308, 11413, "5e4f2e9f7243351fc692d5a94cf028d00b01f96a32336901376b3f07b7b26cb7"],
  ["tools/registry.rs#function_item@12185", "code_mode_result", 342, 12299, "e2d7227d2e55406578dcd0172591efc2c9fd75b09f9de6c4891127d2c4c66d85"],
  ["tools/registry.rs#function_item@13890", "spec", 390, 14125, "0b28ad923e4d198d73f43c4eec40657bff42ba4ab7205555d497406d063cae14"],
  ["tools/registry.rs#function_item@16143", "prepare_tool", 445, 16554, "31e8d88b146a72e12574bb32c200301fcb76b55817bc7b878ed1d858ebe5d7dc"],
  ["tools/registry.rs#function_item@18260", "prepend_trusted", 519, 18802, "9be5af382e0b13a569e8387995f3728fe277c277c4a4cacaeef4dcad491c832e"],
  ["tools/registry.rs#function_item@20774", "entries", 590, 20879, "1e5655a40b9cee9d5ee170ece31b168047edc9229ce9557720689ce9be5ffff4"],
  ["tools/registry.rs#function_item@22967", "tool", 652, 23139, "244472a40b80f8b64ea1739981f823f049af14264ba46046f25245bacb506ba3"],
  ["tools/registry.rs#function_item@23145", "registered_tool", 657, 23296, "26871182c47416fd15c031765e88d5b33f65f86b14b54fa50410ee896a70176c"],
  ["tools/registry.rs#function_item@23907", "supports_parallel_tool_calls", 682, 24172, "43bccbcd36c707fc56855ef5d571d64bbee064295fc3b75d556153a1ea265fb2"],
  ["tools/registry.rs#function_item@24316", "dispatch_any_with_state", 691, 36476, "08ded4928a3b6e648bbdb0e78913233f07a5bab8bc8b99b54ce962a38ca3f5ea"],
  ["tools/registry.rs#function_item@2970", "immutable_spec", 75, 3041, "795b3722d0253e6a894b3c44c448ca27f323f439d7cb6c7f8431bc71290d502a"],
  ["tools/registry.rs#function_item@3305", "cached_code_mode_definitions", 82, 3522, "9a33cdb3e9c8d8fc23c1eb4cae05147bd35165f970214413ca93172f46a4686c"],
  ["tools/registry.rs#function_item@36480", "notify_tool_finish_if_unclaimed", 986, 36828, "f69e941185cdd742a4fe820f4461c22a2eaf8fc656ee9127d63a28d180ad16df"],
  ["tools/registry.rs#function_item@36830", "handle_any_tool", 999, 37881, "34ed7528ad99488be6252c9d7d69046b1e6c7ef490304130d6966aa55b1321be"],
  ["tools/registry.rs#function_item@37883", "function_hook_tool_name", 1030, 38287, "e3078563d8d89a95b59b9eaba331140c644302ab2847f312e5a0851d2c365a74"],
  ["tools/registry.rs#function_item@38289", "function_hook_tool_input", 1041, 38537, "0093e3a8b9e482f4b8b23de4433d93c25f447bd8dc88ea09c2f93f4267b4f049"],
  ["tools/registry.rs#function_item@38539", "unsupported_tool_call_message", 1049, 38803, "84ed6b86bb55b2cbf7b65a0f7c7f9607d7d5d2743dba53a8dd4e190969e02b3a"],
  ["tools/registry.rs#function_item@3866", "is_third_party_tool", 97, 3925, "35e8f04aeda14d53e7bd92246e9d1ed52362fce898b05569a2236415c84599c8"],
  ["tools/registry.rs#function_item@4964", "post_tool_use_payload", 133, 6365, "9772eaded3c88da0724180de4405bb79df50d300fa49109accc529e5104b9a20"],
  ["tools/router.rs#function_item@11050", "dispatch_tool_call_with_code_mode_result", 313, 11648, "4f2d91940496dc7fb9ab7ce0c41f6b5e7a0c39427b8e04c4e4329000b83453f2"],
  ["tools/router.rs#function_item@11745", "dispatch_tool_call_with_state", 336, 12376, "219f723e206c6470ac667125c4c43d1d1813b97c0b189141372aa48badd07053"],
  ["tools/router.rs#function_item@12423", "dispatch_tool_call_with_code_mode_result_inner", 359, 13500, "105142ecfbaee2478cd33e5683c4f428de29c68ac2318909b8ad2ffe964b0d2e"],
  ["tools/router.rs#function_item@2487", "tool_log_payload", 74, 2756, "08bd6189b71f25ea9dee5409f81259f9f2ff34f1f6edd7109c9e206aa3bbbbe7"],
  ["tools/router.rs#function_item@5014", "requires_code_mode_worker", 157, 5154, "35946258379c35b77091c10485143b4a63d3a33561de61c4d1ba2567d899027a"],
  ["tools/router.rs#function_item@5241", "code_mode_tool_names", 162, 5356, "fcf0d8ebc7405b5be3cd59dce67e886755e62f0f4982c32bd94ee7c8af57996f"],
  ["tools/router.rs#function_item@8598", "tool_supports_parallel", 246, 8778, "8a58de73bcb748e164daf866439627783da391dbe4c3364e37b704171bd25e41"],
  ["tools/router.rs#function_item@8784", "registered_tool", 252, 8926, "aef0fce9bcc3ceb52a8b2e99eaf4ef638475c31b28f3a299393e0fa4e9d2f9fe"],
];
const allowedKinds = new Set(["struct_item", "enum_item", "enum_variant", "field_declaration"]);
const executableFiles = new Set(executable.map(row => row[0].split("#")[0]!));

function matches(unit: SourceUnit | undefined, proof: Reviewed): boolean {
  const [suffix, label, line, end, sha256] = proof;
  const [file, declaration] = suffix.split("#");
  const [kind, start] = declaration!.split("@");
  return !!unit && unit.id === root + suffix && unit.file === root + file && unit.kind === kind &&
    unit.label === label && unit.line === line && unit.start === Number(start) && unit.end === end &&
    unit.sha256 === sha256 && unit.ownership === "ported";
}

/** Only these reviewed representations are private; their observable projections remain required. */
export function effectiveLayoutContextReason(unit: SourceUnit, inventory: CurrentInventory): string | undefined {
  if (!allowedKinds.has(unit.kind) || inventory.sourceRevision !== revision || inventory.sourceTree !== sourceTree) return undefined;
  const proof = members.find(row => unit.id === root + row[0]);
  if (!proof || !matches(unit, proof)) return undefined;
  const bound = new Map<string, SourceUnit>();
  for (const found of inventory.units) {
    if (bound.has(found.id)) return undefined;
    bound.set(found.id, found);
  }
  if (!matches(bound.get(unit.id), proof)) return undefined;
  for (const [file, sha256, gitBlob] of filePins) {
    const entries = inventory.files.filter(entry => entry.path === root + file);
    if (entries.length !== 1 || entries[0]!.sha256 !== sha256 || entries[0]!.gitBlob !== gitBlob) return undefined;
  }
  const containers = owners.map(owner => bound.get(root + owner[0]));
  if (owners.some((owner, index) => !matches(containers[index], owner))) return undefined;
  const parent = containers.find(owner => owner!.file === unit.file && owner!.start <= unit.start && unit.end <= owner!.end);
  if (!parent) return undefined;
  // Selected behavior cannot disappear or be relabeled context to obtain layout context.
  if (executable.some(method => !matches(bound.get(root + method[0]), method) ||
    bound.get(root + method[0])!.foreignScope?.state !== "required")) return undefined;
  const selected = inventory.units.filter(entry => entry.kind === "function_item" &&
    executableFiles.has(entry.file.slice(root.length)) && entry.foreignScope?.state === "required");
  if (selected.length !== executable.length || selected.some(entry => !executable.some(method => entry.id === root + method[0]))) return undefined;
  if (inventory.units.some(entry => entry.kind === "function_item" && containers.some(owner =>
    owner!.file === entry.file && owner!.start <= entry.start && entry.end <= owner!.end))) return undefined;
  return "Exact effectively crate-private nonwire Rust representation for " + parent.label +
    ". Immutable core lib.rs keeps tools private; tools/mod.rs restricts context and router to the crate. " +
    "The only ToolCallSource reexport remains inside that restricted router. These exact owners have no serde/JSON/V8/FFI layout conversion; " +
    "the nested serialized UnifiedExecCodeModeResult and protocol/extension DTOs are separate obligations. " +
    "All 47 exact selected executable declarations, including dispatch, explicit result projection and extension conversion, remain required with their original hashes. " +
    "This representation receives no execution PASS. Owner: " + parent.id + "; source hash: " + parent.sha256 + ".";
}

