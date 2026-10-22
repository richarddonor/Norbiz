# Accounting (AR/AP subledger)

Tracks what customers owe us (receivables), what we owe suppliers (payables), the payments, advances, memos and refunds
that settle them, and the bank side: deposits, received/issued checks (cleared, bounced, cancelled) and bank
reconciliation. It is a **subledger** only: no general ledger, chart of accounts, VAT or withholding tax.

The design follows the legacy jbsKarutora accounting schema (`tblCustomerPayments` / `…PaymentDetails` /
`…PaymentLinks`, `tblCustomerDebitMemos`, `tblSupplierPayments`, `tblDeposits`, `tblCheckStatus`,
`tblBankReconciliations`, `tblTerms`). That company never used it, so it was used as a reference, not migrated.

## Open items and allocations

Everything rests on two tables, written only by `OpenItemService`:

- **`open_items` (`OpenItem`)** is one receivable/payable or credit per document, or per payment tender.
  - `side` is `RECEIVABLE` (customer) or `PAYABLE` (supplier). Exactly one of `customer_id` / `supplier_id` is set.
  - `direction`:
    - `CHARGE` raises the party's balance: Sales Invoice, Purchase Invoice, customer debit memo, supplier credit memo, refunds.
    - `CREDIT` lowers it: payment tenders, customer credit memo, supplier debit memo.
  - `amount`, `applied_amount`, `balance` (= amount − applied). `status` is `OPEN`, `CLOSED` (fully applied) or `VOIDED`.
  - `pending_amount` (charges) is the part of what's applied that is paid by checks not yet cleared (legacy "PDC").
  - `bounced_amount` (charges) is the running total of payments reversed because their check bounced.
  - `cleared` (credits) is false while the paying check hasn't cleared. Allocations from it are then pending.
  - A voided item keeps `voided_date` and `void_reason`:
    - `VOIDED`: its document was voided. Reports treat it as never posted.
    - `BOUNCED` / `CANCELLED`: its check bounced or was cancelled. It stood until that date, and ledgers and as-of aging show that.
- **`open_item_allocations` (`OpenItemAllocation`)** applies part of a CREDIT item to a CHARGE item of the same party.
  - The row records the transaction that made it (`source_type`/`source_id`), the business date, and `pending`.
  - It is never deleted. Voiding, bouncing and cancelling **reverse** it (`reversed`, `reversed_date`, `reversal_reason`).
  - These rows are the truth: the open item amounts are a cache of the active ones. `AccountingFlowTest` checks that after every test.

Concurrency: every change locks the items it touches (`SELECT … FOR UPDATE`, in id order) and re-checks balances
under the lock. DB checks (`OPEN_ITEMS_AMOUNTS_CK`, `OPEN_ITEMS_PARTY_CK`) backstop the invariants.
`PaymentStatus` (UNPAID / PARTIALLY_PAID / PAID) on Sales and Purchase Invoices is kept in sync from the open item.

## Start date (cut-off)

`PUT /accounting/settings` (`MANAGE_ACCOUNTING_SETTINGS`) sets the company's **accounting start date**.

- Until it is set, every AR/AP document is rejected (400).
- **Document dates.** Sales Invoices, payments, memos, applications, refunds and deposits must be dated on/after it.
  The exception is `OPENING_BALANCE` memos, which must be dated before it. They carry balances from the old system;
  set their `dueDate` to the original due date so aging is right, and `originalReference` to the legacy number.
- **Purchase Invoices** are tracked (open a PAYABLE CHARGE) when they are `NATIVE`, not voided, dated on/after the
  start date, and have a positive net amount. Migrated and earlier ones stay untracked and keep their stored
  `paymentStatus`.
- **Moving the date.** Setting or moving it re-decides which Purchase Invoices are tracked. It can no longer move once
  anything has been applied, or any other AR/AP document has posted.

## Documents

Every one follows the standard transaction rules (docs/TRANSACTIONS.md):
- immutable, void-only;
- `sheetNumber`, `remarks`, `origin`;
- CREATED/VOIDED history;
- a `<Type> - Detailed` report (`/reports/detailed/<kebab-plural>`);
- a print template.

| Type | Prefix | Endpoint | Effect |
|---|---|---|---|
| Sales Invoice | `SI` | `/sales-invoices` | RECEIVABLE CHARGE for `netAmount`. **Direct**: own lines; deducts the main warehouse's on-hand stock (void returns it). **From a Delivery Receipt**: bills one plain-customer DR in full (lines copied, no stock effect, `DeliveryReceipt.billed`). Outlet DRs can't be billed (consignment): bill outlets with debit memos. |
| Customer Memo | `CDM` / `CCM` | `/customer-memos` | DEBIT → RECEIVABLE CHARGE; CREDIT → RECEIVABLE CREDIT, optionally applied inline. Reasons: SALES_RETURN, REBATE, PRICE_ADJUSTMENT, BILLING, OPENING_BALANCE, OTHER. |
| Supplier Memo | `SDM` / `SCM` | `/supplier-memos` | DEBIT (we debit the supplier, e.g. a return) → PAYABLE CREDIT, optionally applied inline; CREDIT (supplier credits their account, i.e. bills more) → PAYABLE CHARGE. |
| Customer Payment | `CPY` | `/customer-payments` | One RECEIVABLE CREDIT per tender (cash, check, transfer, card, others), plus optional applications and existing credits used (see below). **What's left unapplied is the customer's advance.** |
| Supplier Payment | `SPY` | `/supplier-payments` | One PAYABLE CREDIT per tender (our check, transfer, cash). Checks and transfers post a disbursement to the bank book. Unapplied = advance to the supplier. Checked/approved sign-offs use Transaction Actions. |
| Customer Refund | `CRF` | `/customer-refunds` | Pays unused customer credit back: a RECEIVABLE CHARGE fully applied from the chosen credits. Paid by one issued instrument (a check stays ISSUED until reconciled). Credits paid by an uncleared check can't be refunded yet. |
| Supplier Refund | `SRF` | `/supplier-refunds` | A supplier pays us back unused credit: a PAYABLE CHARGE fully applied from the chosen credits. Posted straight to the receiving bank account; if their check bounces, void the refund. |
| Deposit | `DEP` | `/deposits` | Takes ON_HAND cash/checks to a bank account (one DEPOSIT bank entry). Checks → DEPOSITED, cash → CLEARED. Post-dated checks can't be deposited before their date. |
| Bank Reconciliation | `BRC` | `/bank-reconciliations` | See below. |
| Statement of Account | `SOA` | `/statements-of-account` | A customer's open items as of a date, aged by due date, frozen for printing. `GET /statements-of-account/preview` shows it without saving. No ledger effect. |

Applications on a payment draw from a named tender (`tenderIndex`), or else from the settled tenders first and then
checks by check date. Nothing can be applied beyond either side's balance.

A payment can also use the party's **existing credits** (`credits`: credit → charge lines), e.g. a supplier debit
memo plus a check against one invoice, or an earlier advance applied to a new invoice. This is the only way to apply
an existing credit after it was posted (there is no separate credit-application document): a payment may consist of
credits alone, with no tenders and a `totalAmount` of zero; it needs at least one tender or one credit. Credits are
applied before the tenders and recorded under the payment, so voiding the payment reverses them (and the memo can't
be voided until then). A bounced/cancelled check only reverses what the check itself paid. Memos and advances are not
tenders: they never appear in the payment's `totalAmount`, tender list or bank book. Each credit used is stored as a
payment line (`customer_payment_credits` / `supplier_payment_credits`: credit item, charge item, amount), numbered
after the tenders and kept as posted even after a void; the `<Payment> - Detailed` report lists tenders and credits
together (`Customer/SupplierPaymentReportLine`, a read-only `@Subselect` over both), so its Amount total is money
paid plus credits used.

Customer/Supplier Credit Applications (`CCA`/`SCA`) existed until migration `008_remove_credit_applications.sql`,
which drops them and refuses to run if any was posted.

Payment responses (and list rows) carry `paidDocuments` (per charge: `paidAmount` from the tenders, `creditAmount`
from credits, `amount`), `creditsUsed` (per credit) and `creditsUsedAmount`, also bindable in print templates. They
count active allocations only, except on a voided payment, which keeps showing what it had paid.

**Voiding** reverses the allocations the document made. It is blocked while *other* documents have applied to or
from its items (void those first). Specifically:
- a paid Sales or Purchase Invoice can't be voided;
- a payment can't be voided once its cash/check was deposited, or its credit was used by a later payment or refund;
- anything whose bank entry is reconciled can't be voided.

Every `void` writes the change down by flushing at once. One non-voided Sales Invoice per DR and one non-voided
Purchase Invoice per PO are enforced by partial unique indexes.

## Check lifecycles

`/customer-payment-tenders` lists received tenders (checks on hand, post-dated checks). `/supplier-payment-tenders`
lists issued ones (outstanding checks). Every status change is a `STATUS_CHANGED` event on the payment's history.

- **Received** (`ReceivedCheckStatus`):
  - **States.** `ON_HAND` → `DEPOSITED` (Deposit) → `CLEARED` (by hand via `POST /customer-payment-tenders/clear`, or by Bank Reconciliation).
  - **Bounce** (`POST /customer-payment-tenders/{id}/bounce` with a `BounceReason`: DAIF, DAUD, STOP_PAYMENT, ACCOUNT_CLOSED, GARNISHED, SIGNATURE_DIFFERS, OTHER), from `ON_HAND` or `DEPOSITED`:
    - every allocation the check funded is reversed, including later applications and refunds, so the invoices reopen and their `bouncedAmount` grows;
    - its credit is voided (`BOUNCED`);
    - a deposited check is booked as `CHECK_RETURNED` against the deposit's account.
  - **Replacement.** A new payment tender with `replacesTenderId` covers the bounced check, which becomes `REPLACED` once fully covered.
  - **Cleared checks can't bounce.** Book a returned cleared check as a reconciliation adjustment plus a customer debit memo.
- **Issued** (`IssuedCheckStatus`):
  - **States.** `ISSUED` (outstanding) → `CLEARED` (by hand or by Bank Reconciliation).
  - **Cancel** (`POST /supplier-payment-tenders/{id}/cancel`: STOP_PAYMENT, STALE, RETURNED_UNPAID, OTHER) reverses what the check paid, voids its credit and its bank entry (refused once reconciled), and ends in `CANCELLED`.
  - **Replacement.** A new tender with `replacesTenderId` replaces it.

Clearing by hand only settles the AR/AP side (pending → paid). The bank entry itself is cleared by a reconciliation.

## Bank book and reconciliation

`bank_entries` (`BankEntry`) is each bank account's book. Amounts are signed: in is positive, out is negative.

- **Entry types:** `DEPOSIT`, `TRANSFER_RECEIPT` (customer transfers and cards, supplier refunds), `CHECK_DISBURSEMENT`, `TRANSFER_DISBURSEMENT`, `CHECK_RETURNED`, and reconciliation `BANK_CHARGE` / `INTEREST` / `ADJUSTMENT`.
- **Book balance** = `bank_accounts.opening_balance` + all non-voided entries up to the date.
- **Voiding the source** of an entry voids the entry. That is refused once the entry is reconciled.

**Reconciling an account:**
1. `GET /bank-reconciliations/candidates?bankAccountId&statementDate` returns the previous ending balance (or the
   account's opening balance), the book balance, and every uncleared entry up to the statement date.
2. Post the ticked entries plus adjustments: `BANK_CHARGE` (negative), `INTEREST` (positive), `ADJUSTMENT`.
   `previous + cleared + adjustments` must equal `statementEndingBalance`; the error states the difference.
   The statement date must be after the previous reconciliation's.

**Effects of posting:**
- Clearing a DEPOSIT entry clears its still-DEPOSITED checks.
- Clearing a check disbursement clears that supplier check or customer refund check. Pending amounts drop to zero.
- The reconciliation stores the book balance and the outstanding total (deposits in transit, outstanding checks).

**Voiding.** Only an account's latest reconciliation can be voided. Voiding un-clears its entries, voids its
adjustments, and puts the instruments it cleared back to DEPOSITED / ISSUED.

## Reports

| Report | Endpoint | Permission |
|---|---|---|
| AR / AP aging (Current, 1-30, 31-60, 61-90, 91-120, 120+; by due or document date; per party, `detail=true` for items) | `GET /reports/ar-aging`, `/reports/ap-aging` | `VIEW_AR_REPORT` / `VIEW_AP_REPORT` |
| Customer / supplier ledger (opening balance, charges, credits, bounced/cancelled checks, running balance) | `GET /reports/customer-ledger`, `/reports/supplier-ledger` | same |
| Open items (filter/sort/totals; `overdueAsOf`) and one item with its payment history | `GET /open-items`, `/open-items/{id}` | same, by side |
| Bank book | `GET /reports/bank-book` | `VIEW_BANK_BOOK` |

**Dashboard widgets** `receivables-aging`, `payables-due` and `cash-and-checks` summarize the current open items, bank
book and checks (see docs/DASHBOARD.md). They read live state, not the as-of rules below: `asOf` only sets "today"
for aging.

**As-of rules** for aging, Statement of Account and ledgers:
- An item counts if it is dated on/before the date, and either wasn't voided or was voided later because its check bounced/was cancelled.
- An allocation counts if it is dated on/before the date and wasn't reversed by then.
- `pendingAmount` as of a past date uses each allocation's current pending flag, so it is approximate for past dates.

## Master data and lookups

- **Banks** (`/banks`) are drawee banks and the banks of our accounts. **Bank Accounts** (`/bank-accounts`) are our own
  accounts, with an opening balance and date. Both follow the master-data pattern: Auditable, cached lists,
  `ENTITY_IN_USE` on delete.
- **Terms.** `termDays` (0–365) on Customer and Supplier is the default due date for invoices and charge memos:
  document date + days, overridable per document.
- **Lookups:**
  - `/lookups/banks` and `/lookups/bank-accounts` are cached.
  - `/lookups/open-items` (one party's OPEN items, `direction=CHARGE|CREDIT`) is **uncached**.
  - `/lookups/billable-delivery-receipts` is cached.
  - Permissions are in `LookupAccess` (`BANK`, `BANK_ACCOUNT`, `OPEN_ITEM`, `DELIVERY_RECEIPT`).

## Permissions

| Area | Permissions |
|---|---|
| Each document type | `VIEW_` / `CREATE_` / `VOID_` × SALES_INVOICE, CUSTOMER_MEMO, SUPPLIER_MEMO, CUSTOMER_PAYMENT, SUPPLIER_PAYMENT, CUSTOMER_REFUND, SUPPLIER_REFUND, DEPOSIT, BANK_RECONCILIATION, STATEMENT_OF_ACCOUNT |
| Banks and bank accounts | `VIEW_` / `CREATE_` / `UPDATE_` / `DELETE_` × BANK, BANK_ACCOUNT |
| Check status | `UPDATE_RECEIVED_CHECK_STATUS`, `UPDATE_ISSUED_CHECK_STATUS` |
| Settings | `MANAGE_ACCOUNTING_SETTINGS` |
| Reports | `VIEW_AR_REPORT`, `VIEW_AP_REPORT`, `VIEW_BANK_BOOK`, and a detailed-report permission per type |

All of these are seeded and granted to SYSTEM_ADMIN and SUPER_ADMIN, except the bank deletes, which are SUPER_ADMIN only.

Open-item amounts and balances are visible with the AR/AP permissions; Purchase Invoice line costs stay behind
`VIEW_COST_PRICE`.

## Schema

All of it is in `db/migrations/007_accounting.sql`, folded into `db/init.sql`:
- the new tables;
- `term_days` on customers and suppliers;
- `billed` on delivery receipts;
- `net_amount` (backfilled), `term_days`, `due_date` and `open_item_id` on purchase invoices;
- the PO unique constraint turned partial.

Memos number from two sequences per type (`CUSTOMER_MEMO_DEBIT` / `_CREDIT`, `SUPPLIER_MEMO_DEBIT` / `_CREDIT`). This
is the one exception to "transaction type = sequence key".
