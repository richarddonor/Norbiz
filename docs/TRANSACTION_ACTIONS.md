# TRANSACTION_ACTIONS.md

Transaction **history** and company-configured **actions** (sign-offs) on every transaction type. See `docs/TRANSACTIONS.md` for the transactions themselves.

## Concepts
- **`TransactionActionDefinition`** (`transaction_action_definitions`, company-scoped, `Auditable`): an action a company lets users take on one `TransactionType`. Example for Purchase Receive: `BARCODE_DISTRIBUTION`, `BARCODE_PRINTING` (prerequisite: `BARCODE_DISTRIBUTION`), `ENCODED_BY`.
  - `code` is UPPER_SNAKE and unique per `(company, transactionType)`; `name` is the display label; `sortOrder` orders the buttons; `active=false` hides it without losing history.
  - `allowedRoles` (join table `transaction_action_definition_roles`, at least one): a user holding any of these roles may take the action. `SUPER_ADMIN` always may.
  - `prerequisites` (join table `transaction_action_definition_prerequisites`): **all** of them must already have been taken on the transaction, by **any** user. They must be the same company and transaction type, and the graph must be acyclic (enforced on create/update).
  - `companyId` and `transactionType` can't change after creation. Delete is blocked once the action has been taken anywhere or is another action's prerequisite; deactivate instead.
- **`TransactionEvent`** (`transaction_events`, company-scoped, append-only, **not** `Auditable`, same reasoning as `InventoryMovement`): one row per history entry, keyed by `(transactionType, transactionId)`.
  - `CREATED` and `VOIDED` are written automatically by each transaction service's `create` / `void…` method (via `TransactionEventService.recordSystemEvent`, `Propagation.MANDATORY`, so they commit/roll back with the transaction).
  - `ACTION` rows snapshot `actionCode` / `actionName` so history survives renames.

## Rules for taking an action
1. Caller has the transaction type's `VIEW_<TYPE>` permission and access to the transaction's company.
2. Transaction is not voided. Actions are immutable; there is no undo.
3. Definition belongs to the same company and type, and is active.
4. Caller holds one of the definition's `allowedRoles` (else 403).
5. Caller hasn't taken **this** action on this transaction already. They may take other actions. Backstopped by unique constraint `TXN_EVENT_ACTION_USER_UQ (transaction_type, transaction_id, action_definition_id, performed_by)`; CREATED/VOIDED rows have a NULL definition id, so the constraint doesn't apply to them.
6. Every prerequisite has been taken by someone (else 400 naming the missing actions).

## Endpoints
| Method | Path | Auth |
|---|---|---|
| GET/POST | `/transaction-action-definitions` | `MANAGE_TRANSACTION_ACTIONS` |
| GET/PUT/DELETE | `/transaction-action-definitions/{id}` | `MANAGE_TRANSACTION_ACTIONS` + company access |
| GET | `/transactions/{transactionType}/{id}/history` | `VIEW_<TYPE>` + company access (paginated, oldest first) |
| GET | `/transactions/{transactionType}/{id}/actions` | `VIEW_<TYPE>` + company access: each active action with `takenBy`, `takenByMe`, `allowedForMe`, `prerequisitesMet`, `missingPrerequisites`, `canTake` |
| POST | `/transactions/{transactionType}/{id}/actions` | rules above; body `{ actionDefinitionId, remarks? }` |

`transactionType` is one of `INVENTORY_ADJUSTMENT`, `PURCHASE_ORDER`, `PURCHASE_INVOICE`, `PURCHASE_RECEIVE`, `DELIVERY_RECEIPT`, `OUTLET_RECEIVE`.

## Adding a new transaction type
1. Add a `TransactionType` value (with its `VIEW_` permission) and use `TransactionType.X.name()` as the service's `TRANSACTION_TYPE`.
2. Add a branch to `TransactionLookupService.resolve`.
3. Call `transactionEventService.recordSystemEvent(... CREATED ...)` after saving in `create`, and `(... VOIDED ...)` after saving in the void method.
4. Add the type to the backfill loop in `db/backfill_transaction_events.sql`.

## Backfill
`db/backfill_transaction_events.sql` inserts CREATED/VOIDED history for transactions posted before this feature existed. It's idempotent.
