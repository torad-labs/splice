# Scaffold Output Format

The architect agent produces a typed JSON scaffold. This file documents every field.
The implementing agent reads this scaffold to know exactly what SDK elements to create.

## Top-Level Structure

```json
{
  "tools": [ /* one per concern */ ],
  "agents": [ /* one per partition */ ],
  "middleware": [ /* guardrails, logging, normalization */ ],
  "telemetry": [ /* lifecycle hooks per agent */ ],
  "sharedTypes": [ /* Valibot schemas that flow between tools */ ],
  "biomeConfig": { /* lint thresholds for implementing code agent */ },
  "coherenceScore": 0.87,
  "violations": [],
  "pass": true
}
```

## Tool Definition

One per architectural concern. Each tool maps to one `tool()` call in the SDK.

```json
{
  "name": "order-price-calculate",
  "description": "Calculate order total from line items, discounts, and tax rate",
  "concernIds": ["price-calculate"],
  "inputFields": [
    { "name": "lineItems", "type": "LineItem[]", "description": "Cart line items with SKU and quantity" },
    { "name": "discounts", "type": "Discount[]", "description": "Applicable discounts" }
  ],
  "outputFields": [
    { "name": "subtotal", "type": "number", "description": "Pre-tax total" },
    { "name": "tax", "type": "number", "description": "Tax amount" },
    { "name": "total", "type": "number", "description": "Final total" }
  ],
  "outputStrategy": "Output.object",
  "inputSchemaCode": "v.object({ lineItems: v.array(LineItemSchema), discounts: v.array(DiscountSchema) })",
  "outputSchemaCode": "v.object({ subtotal: v.number(), tax: v.number(), total: v.number() })",
  "executeSignature": "async ({ lineItems, discounts }) => { /* TODO: pure pricing logic */ }",
  "toModelOutput": "Return subtotal and total only — strip line item details",
  "toModelOutputCode": "(result) => ({ subtotal: result.subtotal, total: result.total })",
  "customOutputCode": null,
  "customOutputReason": null,
  "state": "stateless",
  "strict": false,
  "inputExamples": [{ "lineItems": [{ "sku": "WIDGET-1", "quantity": 2, "unitPrice": 29.99 }], "discounts": [] }]
}
```

### Field Reference

| Field | Purpose | When Null/Empty |
|-------|---------|----------------|
| `name` | Maps to `tool({ ... })` name. Kebab-case. | Never null |
| `concernIds` | Which architectural concerns this tool covers | Never empty |
| `inputFields` / `outputFields` | Human-readable schema preview | Never empty |
| `outputStrategy` | `Output.object`, `Output.array`, `Output.choice`, `Output.text`, `Output.json`, or `custom` | Never null |
| `inputSchemaCode` | Literal Valibot expression for `valibotSchema()` | Never null |
| `outputSchemaCode` | Literal Valibot expression for Output schema | Null when `custom` |
| `executeSignature` | Function signature with typed params | Never null |
| `toModelOutput` | Natural language: how to compress for model context | Null if output < 500 tokens |
| `toModelOutputCode` | Literal JS: `(result) => compressed` | Null if no compression needed |
| `customOutputCode` | Full `Output` interface implementation | Null unless `outputStrategy: "custom"` |
| `customOutputReason` | Why built-in Output wasn't sufficient | Null unless custom |
| `state` | `stateless`, `session`, `persistent` | Never null |
| `strict` | Whether input validation should reject unknown fields | Default false |
| `inputExamples` | 1-2 example inputs for Anthropic-native example support | Optional |

## Agent Definition

One per partition from the Louvain modularity maximization.

```json
{
  "name": "checkout-agent",
  "role": "Handle atomic checkout: pricing, inventory reservation, payment, order creation",
  "modelTier": "sonnet",
  "tools": ["order-price-calculate", "inventory-reserve", "payment-charge", "order-create"],
  "instructions": "You manage the checkout flow. Price → reserve → charge → create order. All four operations are atomic...",
  "stopCondition": "[stepCountIs(10), hasToolCall('checkout-complete')]",
  "subAgents": [],
  "prepareStepCode": "async ({ stepNumber }) => ({ activeTools: stepNumber === 0 ? ['order-price-calculate'] : undefined })",
  "prepareCallCode": "async ({ options }) => ({ instructions: buildCheckoutPrompt(options.cartId) })",
  "callOptionsSchemaCode": "v.object({ cartId: v.string(), userId: v.string() })",
  "telemetryIntegrationCode": "class CheckoutTelemetry implements TelemetryIntegration { ... }"
}
```

### Model Tier Selection

Based on cognitive shape, not capability tier:

| Tier | Cognitive Shape | When |
|------|----------------|------|
| `haiku` | Pattern matching, classification, simple extraction | Scoring, grading, routing |
| `sonnet` | Pathfinding, code generation, multi-step reasoning | Implementation, orchestration |
| `opus` | Full structural analysis, complex architectural decisions | Architecture, decomposition |

## Middleware Requirements

```json
[
  { "name": "stripe-guardrail", "purpose": "Validate payment intents before charge", "type": "guardrail" },
  { "name": "cost-logger", "purpose": "Track token usage per agent per call", "type": "logging" }
]
```

Types: `guardrail` (blocks bad calls), `logging` (observes), `normalization` (provider compat).

## Telemetry Hooks

```json
[
  { "event": "onToolCallFinish", "purpose": "Track payment processing latency for SLA" },
  { "event": "onFinish", "purpose": "Log total pipeline cost and coherence score" }
]
```

## Target Skill Folder

When the GENERATE block is implemented, the scaffold produces a complete skill folder:

```
{feature-slug}/
├── SKILL.md                    ← auto-generated from scaffold metadata
├── agents/                     ← one .md per agent from partition
│   ├── {agent-a}.md           ← role, tools, instructions, prepareStep logic
│   └── {agent-b}.md           ← different context fork per cognitive task
├── scripts/
│   ├── schemas.ts              ← Valibot schemas from sharedTypes
│   ├── agent.ts                ← ToolLoopAgent wiring from agent definitions
│   ├── tools/
│   │   ├── {concern-a}.ts     ← tool() with inputSchemaCode, executeSignature
│   │   ├── {concern-b}.ts     ← each tool is one file, one concern
│   │   └── ...
│   └── middleware/
│       └── telemetry.ts        ← TelemetryIntegration class per agent
├── references/
│   ├── spec.md                 ← original crystallize spec (pass-through)
│   └── rejection.md            ← original crystallize rejection (pass-through)
├── evals/
│   ├── evals.json              ← auto-generated assertions from spec requirements
│   └── cases/
│       └── {feature-slug}.json ← the crystallize input that produced this skill
└── biome.json                  ← auto-generated lint config
```
