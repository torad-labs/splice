# Harden Concerns as Construction Constraints

Harden detects production gaps after code exists. Forge prevents them during
construction. This reference inverts harden's pillar-specific detection targets
into constraints that the Build and Verify phases enforce.

## Constraint Table

| # | Harden Detection Target | Forge Construction Constraint | Verify Check |
|---|-------------------------|-------------------------------|--------------|
| C01 | Race conditions | All state mutations must be atomic or use a lock | Identify every state mutation. Confirm atomicity or locking mechanism. |
| C02 | Data loss | All write operations must verify success before proceeding | Every write must check its return value or await its promise. |
| C03 | Money loss | All financial operations must be idempotent | Financial endpoints must use idempotency keys or natural idempotency. |
| C04 | Authorization bypass | All endpoints must validate authorization before processing | Every route handler must check auth before business logic. |
| C05 | Token leaks | Tokens must not appear in logs, URLs, or client-accessible storage | Grep for token/secret/key in log statements, URL construction, and localStorage. |
| C06 | Transaction boundary issues | Multi-step operations must use explicit transaction boundaries | Operations with 2+ database writes must wrap in a transaction. |
| C07 | Orphaned records | Delete operations must cascade or check references | Every delete must handle dependent records. |
| C08 | Routing edge cases | All routes must handle trailing slashes, empty params, encoded characters | Route definitions must normalize or explicitly handle variants. |
| C09 | Content-type mismatches | Response content-type must match actual response body | JSON responses must set application/json. HTML responses must set text/html. |
| C10 | CORS misconfiguration | CORS headers must whitelist specific origins, not wildcard | No `Access-Control-Allow-Origin: *` on authenticated endpoints. |
| C11 | Sanitization bypass | All user input must be sanitized before storage and before rendering | Inputs pass through a sanitization function before DB write and before template interpolation. |
| C12 | Error handling gaps | All catch blocks must handle the error, not swallow it | Every catch block must log, re-throw, or return an error response. No empty catch blocks. |

## Pillar Mapping

When the Plan phase maps components to harden concerns, use these pillar
groupings to determine which constraints apply:

| Pillar | Applicable Constraints |
|--------|----------------------|
| Payment/Checkout | C01, C02, C03, C06 |
| Access/Auth | C04, C05, C10 |
| Data Integrity | C01, C02, C06, C07 |
| Content Delivery | C08, C09, C10, C11 |
| Email/External | C02, C05, C11, C12 |
| General (all components) | C12 |

## Usage in Forge Phases

- **Plan (block 2)**: Maps each component to applicable constraints using the
  pillar mapping above. Produces a CONSTRAINT_MATRIX.
- **Build (block 3)**: Each build invocation receives its component's constraints
  inline. The builder must satisfy them during implementation, not after.
- **Verify (block 4)**: Checks each constraint using the verify check column.
  Reports PASS/FAIL per constraint with evidence.
- **Fortify (block 6)**: Reads all constraint results. Looks for patterns that
  individual verify checks missed — structural weaknesses that span components.
