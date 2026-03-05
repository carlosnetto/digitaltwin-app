# Mini-Core Banking API — Caller Reference

Integration guide for callers connecting to the Mini-Core banking system.
Base URL: `http://localhost:5001`

Mini-Core is a PostgreSQL-backed banking simulation. All business logic lives in
database triggers — the API is a thin HTTP wrapper. Errors from trigger validation
come back as HTTP 422 with a plain-text `error` field.

---

## Starting Mini-Core

```bash
# 1. Start PostgreSQL + run Liquibase migrations (first time or after reset):
cd $HOME/Git/mini-core
LIQUIBASE_CONTEXTS=seed docker-compose up   # includes seed accounts + transactions
# or: docker-compose up                     # schema + reference data only

# 2. Start the API server:
cd server
python server.py    # listens on http://localhost:5001
```

---

## Accounts

### List accounts

```
GET /api/accounts
GET /api/accounts?search=<term>      # filters on account_number or account_id (case-insensitive)
```

Response — array of account objects:
```json
[
  {
    "account_id": 1000,
    "account_number": "1000000001",
    "product_type": "DDA",
    "status": "ACTIVE",
    "available_balance": 17950.00,
    "collected_balance": 17950.00,
    "currency_code": "USD",
    "created_by": null,
    "created_at": "2026-02-13T18:19:43.890614+00:00",
    "updated_at": "2026-02-13T18:19:43.894656+00:00"
  }
]
```

### Get one account

```
GET /api/accounts/<account_id>
```

Returns a single account object. 404 if not found.

### Create account

```
POST /api/accounts
Content-Type: application/json
```

Body:
```json
{
  "account_number": "DT-USD-001",
  "product_type": "DDA",
  "currency_code": "USD",
  "created_by": "digitaltwin-app"
}
```

- `product_type`: `DDA`, `SAV`, `MMA`, `HSA`, `CD`
- `currency_code`: any code in the `currencies` table (USD, BRL, USDC, USDT, etc.)
- `status` defaults to `ACTIVE`; `available_balance` and `collected_balance` default to `0`
- `created_by` is optional (VARCHAR 20)

Returns the created account (HTTP 201).

### Update account status

```
PATCH /api/accounts/<account_id>
Content-Type: application/json
```

Body:
```json
{ "status": "FROZEN" }
```

Valid statuses: `ACTIVE`, `DORMANT`, `FROZEN`, `CLOSED`.
Status transitions are validated by PostgreSQL — invalid transitions return 422.

---

## Transactions

### List transactions for an account

```
GET /api/accounts/<account_id>/transactions
```

Returns newest-first. Each row includes running balances at the time of that transaction:

```json
[
  {
    "transaction_id": 100008,
    "account_id": 1000,
    "original_transaction_id": null,
    "transaction_code": 20012,
    "transaction_description": "ACH DEBIT",
    "amount": 1500.00,
    "direction": "DEBIT",
    "status": "POSTED",
    "json_payload": { "description": "ACH payment - Electric Co" },
    "effective_date": "2026-02-12",
    "created_by": null,
    "created_at": "2026-02-13T18:19:43.894656+00:00",
    "post_available_balance": 17950.00,
    "post_collected_balance": 17950.00
  }
]
```

**Field notes:**
- `post_available_balance` / `post_collected_balance` — the account balances *after* this
  transaction was applied. These are historical snapshots; the live balance is always on
  the account object itself.
- `transaction_code` serializes as a float from the API (e.g. `20012.0`) — coerce to int
  before comparing or displaying.
- `original_transaction_id` — non-null on modifier rows (cancel or confirm). Points to the
  original PENDING transaction.
- `json_payload` — optional freeform JSON the creator attached. May be `null`.

### Create a transaction

```
POST /api/transactions
Content-Type: application/json
```

Minimum body:
```json
{
  "account_id": 1000,
  "transaction_code": 10006,
  "amount": 500.00,
  "direction": "CREDIT",
  "status": "POSTED"
}
```

Full body with optional fields:
```json
{
  "account_id": 1000,
  "transaction_code": 10006,
  "amount": 500.00,
  "direction": "CREDIT",
  "status": "POSTED",
  "effective_date": "2026-03-05",
  "json_payload": { "description": "Payroll", "source": "ACME Corp" },
  "created_by": "digitaltwin-app"
}
```

- `effective_date` defaults to today if omitted
- `json_payload` is optional; pass any JSON object or omit entirely
- `original_transaction_id` — only for modifier rows (see below)

Returns the created transaction row (HTTP 201). Balance updates happen automatically
via database triggers — do not compute or send balance deltas.

### Posting or cancelling a PENDING transaction

Transactions are immutable (never updated). To change a PENDING transaction's status,
insert a *modifier row* that references it:

**Cancel a PENDING transaction:**
```json
{
  "account_id": 1000,
  "original_transaction_id": 100013,
  "transaction_code": 10001,
  "amount": 100.00,
  "direction": "CREDIT",
  "status": "CANCELLED"
}
```

**Confirm (post) a PENDING transaction:**
```json
{
  "account_id": 1000,
  "original_transaction_id": 100013,
  "transaction_code": 10001,
  "amount": 100.00,
  "direction": "CREDIT",
  "status": "POSTED"
}
```

Rules for modifier rows (enforced by DB trigger — violations return 422):
- `original_transaction_id` must point to a **PENDING** born transaction
- The original must **not** itself be a modifier
- `account_id` must match the original's account
- Only one modifier allowed per original (UNIQUE constraint)

---

## Transaction Codes

```
GET /api/transaction-codes
```

Returns all transaction codes with their balance effects:

```json
[
  {
    "transaction_code": 10006,
    "description": "DIRECT DEPOSIT - PAYROLL",
    "effects": [
      { "balance_name": "AVAILABLE", "effect": 1 },
      { "balance_name": "COLLECTED", "effect": 1 }
    ]
  }
]
```

`effect` is `1` (increase) or `-1` (decrease). Balance effects are applied automatically
by triggers — callers never need to send balance deltas.

---

## Business Rules (enforced by the database)

These are enforced by PostgreSQL triggers and returned as HTTP 422 on violation.

### Direction must match the transaction code
- Codes in the credit range → `"direction": "CREDIT"`
- Codes in the debit/fee ranges → `"direction": "DEBIT"`

Calling `GET /api/transaction-codes` and inspecting `effects` is the reliable way to
determine direction for any code — a positive effect means CREDIT, negative means DEBIT.

### CREDIT transactions cannot be PENDING
Born credit transactions must be created with `"status": "POSTED"`.
Only debit transactions may be created as PENDING.

### Balances are computed, not supplied
Never pass balance values when creating transactions. The trigger reads
`transaction_code_balance_effects` and applies the correct deltas automatically.

### Account must be ACTIVE to accept transactions
Inserting a transaction against a FROZEN, DORMANT, or CLOSED account will fail.

---

## Error Responses

All errors return JSON with an `error` string:

```json
{ "error": "Credit transactions cannot be PENDING" }
```

| HTTP status | Meaning |
|-------------|---------|
| 400 | Bad request (missing required fields, no fields to update) |
| 404 | Resource not found |
| 409 | Duplicate record (unique constraint violated) |
| 422 | Business rule violation (trigger raised an exception, FK or check violation) |
| 500 | Unexpected database error |

---

## Supported Currencies

The `currencies` reference table contains the codes the `currency_code` field accepts.
At minimum: `USD`, `BRL`, `USDC`, `USDT`, `POL`, `ETH`.

Use `currency_code` on account creation to designate the account's currency.
The system does no FX conversion — amounts are stored as-is.

---

## Notes for Callers

- **`account_id` is a sequential integer** starting at 1000. Do not assume
  account numbers and account IDs are the same — `account_number` is a free-text
  string set by the creator; `account_id` is the PK.
- **Current balance is on the account object**, not derived from transactions.
  Always call `GET /api/accounts/<id>` for the live balance.
- **`transaction_code` comes back as a float** from the API (e.g. `20012.0`).
  Coerce to int before comparing: `Math.round(tx.transaction_code)` or `int(code)`.
- **Timestamps are ISO 8601 with UTC offset** (`+00:00`). Parse with a standard
  date library; do not assume a fixed format.
- **CORS is open** — the API accepts requests from any origin (dev convenience only).
