# Shared Responses conversion

The `openai-responses` dialect translates the supported Anthropic conversation into public Responses input. Selecting that dialect does not select a JavaScript executor. Provider authentication, Codex routing, internal Lite markers and native-host construction remain provider-owned.

This is a conversion contract, not a claim that every provider implements every optional Responses capability. Provider profiles retain their explicit capability restrictions. The WebSocket transport remains off by default.

## Conversation and tools

| Source requirement | Responses behavior | Behavioral coverage |
| --- | --- | --- |
| Ordered user, assistant and system messages | Retain roles and boundaries. A system message is not automatically rewritten as developer. | `SharedResponsesContractTest` |
| Known assistant phases | Preserve commentary/final_answer provenance before inference, including phase-less history resends and native re-anchor. | `ResponsesAssistantPhaseProvenanceTest`, `ResponsesReanchorTest` |
| Literal tool-result text | Keep a string, including JSON-looking text. Do not parse text into an invented object. | `ResponsesToolResultContentTest` |
| Mixed tool-result content | Keep ordered public text/image/file parts on the matching function result. Provider media opt-outs retain explicit omission text. | `ResponsesToolResultVariantsTest`, `SharedResponsesContractTest` |
| Present tool-result error verdict | Preserve the verdict in text. It is not the Responses item's lifecycle status. | `ResponsesToolResultContentTest` |
| Text/content document sources, title and context | Preserve ordered content and literal metadata. Titles do not determine file extensions. Metadata participates in cache identity. | `ResponsesDocumentSourcesTest`, `ResponsesDocumentMetadataTest` |
| Enabled source document citations | Refuse before sending because the adapter has no faithful ordinary Responses annotation switch. | `ResponsesDocumentAdmissionTest`, `SharedResponsesContractTest` |
| Foreign file identifiers | Emit an explicit omission rather than reusing another vendor's identifier. | `ResponsesDocumentSourcesTest` |
| Non-strict functions | Write explicit strict:false instead of allowing Responses to normalize an omitted strictness field. Codex retains its explicit forced-false policy. | `ResponsesStrictnessSerializationTest`, `SharedResponsesContractTest` |
| Ordinary source type:custom tools | Convert to functions. This source type does not request OpenAI free-form custom tools or an executor. | `ResponsesToolDeclarationAdmissionTest` |
| Caller restrictions | A declaration permitting direct calls may use the direct-function path. Offered programmatic-only and unsupported native tools receive admission refusals. | `ResponsesToolDeclarationAdmissionTest`, `SharedResponsesContractTest` |
| Tool input examples | Preserve model-visible examples without changing the input schema. | `ResponsesToolDeclarationAdmissionTest` |
| Explicit non-eager tool input | Nonstream callers can receive buffered arguments. Streaming callers are refused when the requested buffering cannot be honored. | `ResponsesToolDeclarationAdmissionTest` |
| Deferred declarations and additions | Offer real schemas at their source boundary. Bare tool-search references load the current known deferred schema instead of a marker alone. | `ResponsesDynamicToolStateTest` |
| Withdrawals and re-offering | Preserve original declarations and schema history. Restrict current direct-function availability with public allowed_tools, without fabricating remove_tools. | `ResponsesDynamicToolStateTest`, `SharedResponsesContractTest` |

Lite is a wire shape, not an executor selector. Direct lite requests apply the same source deferral and allowed_tools restrictions as other direct requests.

The synthetic code-mode execution and waiting functions are not ordinary nested client tools. The Codex provider replaces direct selection with its outer executor choice and consumes the shared source timeline for nested membership, current schemas and boundary-local loaded-tool descriptions. Unwithdrawn native deferred tools remain reachable through tools and ALL_TOOLS even when omitted from the manual. Source withdrawal does not rewrite the initial executor declaration. Later source schemas remain visible in nested executor manuals at their original boundaries, without advertising direct client functions outside the executor. `CodeModeDynamicToolsTest` covers this provider-owned integration. Generic conversion never enables that runtime.

## Controls and transport

| Requirement | Disposition | Behavioral coverage |
| --- | --- | --- |
| Supported sampling and output controls | Carry public equivalents through shared conversion. Keep provider-specific service-tier policies. Unsupported source requirements receive explicit admission refusals. | `ResponsesRawRequestControlsTest` |
| Positive source output caps | Use public max_output_tokens where the endpoint profile enables it. Codex retains its non-enforcement policy, including compaction. | `CodexProviderTest`, `ResponsesRequestBuilderTest` |
| Source cache-only warmup | Refuse max_tokens:0 rather than silently generating an answer. | `ResponsesRawRequestControlsTest` |
| Explicit effort directives | Preserve caller/head precedence and apply the effective public reasoning setting. Do not vary effort automatically by turn type. | `ResponsesRawRequestControlsTest` |
| Public reasoning context | Support auto, current_turn and all_turns independently of Lite. Effort, summary and context remain separate controls. | `ResponsesReasoningContextTest` |
| Valid source cache hints | Use automatic Responses caching without inventing source breakpoints, retention guarantees or destination content fields. | `ResponsesRawRequestControlsTest`, `ResponsesToolDeclarationAdmissionTest` |
| Public text delta/done/terminal snapshots | Append compatible missing text once, without duplicating or retracting visible output. | `ResponsesTextCompletionTest` |
| Public WebSocket warmup | Prepare reusable leading instruction/tool prefixes with generate:false. Consume warmup internally, then chain generation from its response identifier. | `ResponsesWsPrefillTest`, `ResponsesWsRunnerTest` |
| Endpoint negotiation | Ordinary Responses uses public authentication. Codex explicitly retains its own negotiation and narrower warmup admission. | `ResponsesWebSocketHeaderBoundaryTest` |
| Runtime isolation | Provider names, URLs, GPT-looking model names and discovery hints alone never select the JavaScript executor or its host. | `SharedResponsesContractTest` |

Source-only account, workspace and geographic requirements are not cross-vendor identifiers to copy blindly. Explicit opaque `metadata.user_id` becomes a stable SHA-256 hexadecimal `safety_identifier` on supporting endpoint profiles. The digest is exactly 64 characters, fitting the public field limit without forwarding the raw account, device or session envelope. The entire source value stays opaque; the converter does not parse vendor-specific identity fields. Attribution never becomes resource metadata, prompt content, splice session identity or a prompt-cache key. No identifier is inferred when the source omits it. Codex explicitly preserves its existing attribution bytes. Other request annotation stays source-side, apart from the existing effort alias.

Upstream SSE does not change whether the source client receives a streaming response. Cache-only requests must not silently become generation. Unknown raw fields do not trigger a blanket reject-unknown policy.

Attribution follows the [source user-id contract](https://raw.githubusercontent.com/anthropics/anthropic-sdk-python/main/src/anthropic/types/metadata_param.py) and the [public Responses safety-identifier contract](https://developers.openai.com/api/docs/guides/safety-checks). The generated production-provider property in `SharedResponsesContractTest` requires every other request field to stay unchanged. `CodexResponsesArmTest` preserves the native endpoint exception.

Provider-specific checks live in their provider module. Production assembly contracts live under `app/src/test/kotlin/splice/app/provider`. The remaining named checks live in the Responses dialect test package. Generated cases cover conversion and timeline properties where a finite list of examples would be insufficient. Local acceptance is not a live-provider receipt or a benchmark claim.

Public contracts:

- [Responses function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [Responses WebSocket mode](https://developers.openai.com/api/docs/guides/websocket-mode/)
- [Source request fields](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/message_create_params.py)
- [Source client tools](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/tool_param.py)
- [Source documents](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/document_block_param.py)
