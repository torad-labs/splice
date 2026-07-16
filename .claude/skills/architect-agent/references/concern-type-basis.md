# Architectural Concern Type Basis

The 9 irreducible types. The LLM classifies into this set — it does not invent.

## Types

### domain
Pure business logic. No I/O. The rules of the system.
State: stateless. Output: depends on return shape.
Example: "Calculate shipping cost based on weight and destination zone."

### command
State-mutating operation. Changes something.
State: persistent or session. Output: `Output.text()` (confirmation).
Example: "Create a new user account with the provided registration data."

### query
Read operation. Retrieves without mutating.
State: stateless. Output: `Output.object()` (single) or `Output.array()` (list).
Example: "Fetch all orders for a user within a date range."

### event
Side effect triggered by state change. Fire-and-forget.
State: stateless. Output: `Output.text()` (acknowledgment).
Example: "Send welcome email after user registration completes."

### adapter_in
External system → internal boundary. Receives from outside.
State: stateless. Output: `Output.object()` (structured external data).
Example: "Receive webhook payload from Stripe and normalize to internal format."

### adapter_out
Internal → external system boundary. Sends to outside.
State: stateless. Output: `Output.text()` (send confirmation).
Example: "Push notification to Firebase Cloud Messaging."

### transformer
Data shape conversion. Pure function, no side effects.
State: stateless. Output: `Output.object()` (converted shape).
Example: "Convert internal UserProfile to public API response format."

### validator
Constraint enforcement. Returns pass/fail + violations.
State: stateless. Output: `Output.object({ schema })` with pass/fail + array of violations.
Example: "Validate order against business rules: minimum amount, stock availability."

### policy
Decision logic. Selects between alternative paths.
State: stateless. Output: `Output.choice({ options })`.
Example: "Determine shipping method: standard, express, or overnight based on rules."

## Decomposition Rules

1. Each concern is irreducible — cannot split further without losing identity.
2. A concern that does two things is two concerns.
3. Every data type flowing between concerns must be a named shared type.
4. Concern IDs are kebab-case: "user-auth-validate", "order-price-calculate".
5. `reads` and `writes` reference shared type names — these are data contracts.
6. Invariants are specific: "price must be positive", not "validate data".
7. `externalSystems`: name the specific API, database, or service.
