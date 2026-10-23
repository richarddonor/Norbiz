"""Migration report: what was loaded, what was reconstructed or fixed, and whether the result
reconciles with legacy. Prints a plain-text report and exits non-zero when a hard check fails
(run_migration.sh stops on that).

Everything is scoped to the company this run loaded (migration.ctx): other migrated companies in the
same database are neither counted nor checked.

Usage: .venv/bin/python report.py [--out FILE]   (PG_DSN as for extract.py)
"""
import argparse
import os
import sys

import psycopg

# (Norbiz table, legacy sources (table, filter) whose rows it carries, label, issue entities)
DOCUMENTS = [
    ("inventory_adjustments", [("tblinventoryadjustment", None), ("tbloutletinventoryadjustment", None),
                               ("tblitementries", None), ("tblitemdisbursements", None)],
     "Inventory Adjustment", ("InventoryAdjustment", "OutletInventoryAdjustment")),
    ("stock_transfers", [("tblstocktransfer", None)], "Stock Transfer", ("StockTransfer",)),
    ("delivery_receipts", [("tbldeliveryreceipts", None)], "Delivery Receipt", ("DeliveryReceipt",)),
    ("outlet_receipts", [("tbloutletreceives", None)], "Outlet Receipt", ("OutletReceipt",)),
    ("outlet_delivery_receipts", [("tbloutletdeliveryreceipts", None)], "Outlet Delivery Receipt", ("OutletDeliveryReceipt",)),
    ("outlet_delivery_returns", [("tbloutletdeliveryreturns", None)], "Outlet Delivery Return", ("OutletDeliveryReturn",)),
    ("outlet_pull_outs", [("tblreturnslips", None)], "Outlet Pull-out", ("OutletPullOut",)),
    ("pull_out_receipts", [("tbloutletpullout", None)], "Pull-out Receipt", ("PullOutReceipt",)),
    ("purchase_invoices", [("tblsupplierinvoices", None)], "Purchase Invoice", ("PurchaseInvoice",)),
    ("purchase_receipts", [("tblitemreceive", None)], "Purchase Receipt", ("PurchaseReceipt",)),
    ("assemblies", [("tblassembly", None)], "Assembly", ("Assembly",)),
    ("sales_invoices", [("tblsalesinvoices", None)], "Sales Invoice", ("SalesInvoice",)),
    ("sales_invoice_returns", [("tblsireturnslips", None)], "Sales Invoice Return", ("SalesInvoiceReturn",)),
    ("customer_memos", [("tblcustomercreditmemos", None), ("tblcustomerdebitmemos", None)], "Customer Memo",
     ("CustomerCreditMemo", "CustomerDebitMemo")),
    ("customer_payments", [("tblcustomerpayments", "coalesce(customercmmasterid, 0) = 0")], "Customer Payment", ("CustomerPayment",)),
    ("deposits", [("tbldeposits", None)], "Deposit", ("Deposit",)),
    ("customer_refunds", [("tblcustomerrefunds", None)], "Customer Refund", ("CustomerRefund",)),
    ("supplier_payments", [("tblsupplierpayments", None)], "Supplier Payment", ("SupplierPayment",)),
]


class Report:
    def __init__(self):
        self.lines = []
        self.failures = []

    def h(self, title):
        self.lines += ["", title, "=" * len(title)]

    def p(self, text=""):
        self.lines.append(text)

    def table(self, header, rows):
        widths = [max(len(str(x)) for x in col) for col in zip(header, *rows)] if rows else [len(h) for h in header]
        fmt = "  ".join("{:<%d}" % w if i == 0 else "{:>%d}" % w for i, w in enumerate(widths))
        self.p(fmt.format(*header))
        self.p("  ".join("-" * w for w in widths))
        for r in rows:
            self.p(fmt.format(*[("{:,}".format(x) if isinstance(x, int) else str(x)) for x in r]))

    def check(self, name, ok, detail):
        self.p("CHECK  {:<46} {}  {}".format(name, "PASS" if ok else "FAIL", detail))
        if not ok:
            self.failures.append(name)


def one(pg, sql, *args):
    # No params -> no placeholder parsing, so literal % in LIKE patterns needs no escaping.
    return pg.execute(sql, args or None).fetchone()[0]


def money(x):
    return "{:,.2f}".format(x or 0)


# This company's rows of a child table (lines, balances) — joined to their company-scoped parent.
WAREHOUSE_SCOPE = "warehouse_id IN (SELECT id FROM warehouses WHERE company_id = migration.cid())"


def build(pg):
    r = Report()
    company, cutover, database, offset, reloaded = pg.execute(
        "SELECT c.name, x.cutover, x.legacy_database, x.id_offset, x.reloaded FROM migration.ctx x JOIN companies c ON c.id = x.company_id").fetchone()
    r.p("Legacy {} -> Norbiz migration report".format(database))
    r.p("Company: {}    Cutover: {:%Y-%m-%d %H:%M %Z}".format(company, cutover))
    r.p("{}; legacy IDs offset by {:,}. Issue IDs below are legacy IDs.".format(
        "Re-load (the company's previous load was wiped)" if reloaded else "First load (new company)", offset))

    # ---- documents ----
    r.h("Documents")
    rows = []
    for table, sources, label, entities in DOCUMENTS:
        legacy = sum(one(pg, "SELECT count(*) FROM legacy.{}{}".format(t, " WHERE " + f if f else "")) for t, f in sources)
        total, migrated, recon, voided = pg.execute(
            "SELECT count(*), count(*) FILTER (WHERE origin = 'MIGRATED'), count(*) FILTER (WHERE origin = 'RECONSTRUCTED'), "
            "count(*) FILTER (WHERE voided) FROM " + table + " WHERE company_id = migration.cid()").fetchone()
        skipped = one(pg, "SELECT count(*) FROM migration.issues WHERE kind IN ('skipped', 'merged') AND entity = ANY(%s)", list(entities))
        # A split document is logged once with its part count ("Received against N ...", "Reverses N ...").
        split = one(pg, "SELECT coalesce(sum(substring(detail FROM '(\\d+)')::int - 1), 0) FROM migration.issues "
                        "WHERE kind = 'split' AND entity = ANY(%s)", list(entities))
        if legacy == 0 and total == 0:
            continue
        rows.append((label, legacy, skipped, split, migrated, recon, voided, total))
        if legacy - skipped + split != migrated:
            r.failures.append("document count " + label)
    r.table(("Type", "Legacy", "Skipped", "+Split", "Migrated", "Reconstr.", "Voided", "Norbiz"), rows)
    r.p("Migrated = Legacy - Skipped + extra parts from splits. Skipped documents are listed under Issues.")
    r.p("Inventory Adjustments include legacy Item Entries and Item Disbursements. A return slip's Sales Return credit memo is")
    r.p("its Sales Invoice Return's credit, so it is counted as merged under Customer Memo.")

    # ---- reconstructed ----
    r.h("Reconstructed documents")
    rows = pg.execute("""
        SELECT label, count(*) FROM (
            SELECT CASE WHEN reason LIKE '%%stock balance at cutover%%' THEN 'Inventory Adjustment — on-hand correction to legacy balance'
                        ELSE 'Inventory Adjustment — receipt took more than its source had' END label
            FROM inventory_adjustments WHERE origin = 'RECONSTRUCTED' AND company_id = migration.cid()
            UNION ALL SELECT 'Pull-out Receipt — legacy pull-out went straight to on-hand' FROM pull_out_receipts WHERE origin = 'RECONSTRUCTED' AND company_id = migration.cid()
            UNION ALL SELECT 'Purchase Receipt — legacy invoice posted straight to on-hand' FROM purchase_receipts WHERE origin = 'RECONSTRUCTED' AND company_id = migration.cid()
            UNION ALL SELECT 'Outlet Delivery Receipt (zero price) — return matched no sale' FROM outlet_delivery_receipts WHERE origin = 'RECONSTRUCTED' AND company_id = migration.cid()
        ) x GROUP BY label ORDER BY label""").fetchall()
    r.table(("Reconstructed", "Count"), rows)

    # ---- master data ----
    r.h("Master data")
    rows = [(label, one(pg, "SELECT count(*) FROM " + t)) for label, t in [
        ("Users of the company", "user_companies WHERE company_id = migration.cid()"),
        ("  new (password reset required)", "users u JOIN user_companies c ON c.user_id = u.id AND c.company_id = migration.cid() "
                                            "WHERE u.password LIKE '!legacy-migration%'"),
        ("  linked from another company", "migration.issues WHERE kind = 'linked' AND entity = 'User'"),
        ("Employees", "employees WHERE company_id = migration.cid()"),
        ("Sales agents", "employee_tags t JOIN employees e ON e.id = t.employee_id WHERE t.tag = 'AGENT' AND e.company_id = migration.cid()"),
        ("Items", "items WHERE company_id = migration.cid()"), ("Item SKUs", "item_skus WHERE company_id = migration.cid()"),
        ("  of which without an item", "item_skus WHERE item_id IS NULL AND company_id = migration.cid()"),
        ("Item categories", "item_categories WHERE company_id = migration.cid()"), ("Item groups", "item_groups WHERE company_id = migration.cid()"),
        ("Brands", "brands WHERE company_id = migration.cid()"), ("Suppliers", "suppliers WHERE company_id = migration.cid()"),
        ("Customers", "customers WHERE company_id = migration.cid()"),
        ("  of which outlets", "customers WHERE type = 'OUTLET' AND company_id = migration.cid()"),
        ("Warehouses", "warehouses WHERE company_id = migration.cid()"),
        ("Pull-out reasons", "pull_out_reasons WHERE company_id = migration.cid()"),
        ("Bills of materials", "bills_of_materials WHERE company_id = migration.cid()"),
        ("Banks", "banks WHERE company_id = migration.cid()"), ("Bank accounts", "bank_accounts WHERE company_id = migration.cid()")]]
    r.table(("Record", "Count"), rows)

    # ---- stock transfer actions ----
    rows = pg.execute("""
        SELECT d.code, d.name, d.active, count(e.id) FROM transaction_action_definitions d
        LEFT JOIN transaction_events e ON e.action_definition_id = d.id
        WHERE d.transaction_type = 'STOCK_TRANSFER' AND d.company_id = migration.cid() GROUP BY d.id ORDER BY d.sort_order, d.code""").fetchall()
    if rows:
        r.h("Stock Transfer actions (tblStockTransferUserActions)")
        r.table(("Code", "Name", "Active", "Taken"), [(c, n, "yes" if a else "no", t) for c, n, a, t in rows])
        r.p("Every action allows only SUPER_ADMIN: assign the real roles after cutover.")

    # ---- receivables and payables ----
    start = one(pg, "SELECT start_date FROM accounting_settings WHERE company_id = migration.cid()")
    items = pg.execute("""
        SELECT source_type, count(*), sum(amount), sum(applied_amount), sum(balance) FILTER (WHERE status <> 'VOIDED'),
               sum(pending_amount), sum(bounced_amount), count(*) FILTER (WHERE status = 'VOIDED')
        FROM open_items WHERE company_id = migration.cid() GROUP BY 1 ORDER BY 1""").fetchall()
    if items:
        r.h("Receivables and payables")
        r.p("Accounting start date: {}".format("{:%Y-%m-%d}".format(start) if start else "not set"))
        r.table(("Open items", "Count", "Amount", "Applied", "Balance", "Pending (PDC)", "Bounced", "Voided"),
                [(t, n, money(a), money(b), money(c), money(d), money(e), v) for t, n, a, b, c, d, e, v in items])
        allocations = pg.execute("""
            SELECT source_type, count(*) FILTER (WHERE NOT reversed), sum(amount) FILTER (WHERE NOT reversed),
                   count(*) FILTER (WHERE reversed), sum(amount) FILTER (WHERE reversed)
            FROM open_item_allocations WHERE company_id = migration.cid() GROUP BY 1 ORDER BY 1""").fetchall()
        r.table(("Allocations by", "Active", "Amount", "Reversed (bounced)", "Amount"),
                [(t, a, money(b), c, money(d)) for t, a, b, c, d in allocations])
        tenders = pg.execute("""
            SELECT t.mode, t.status, count(*), sum(t.amount) FROM customer_payment_tenders t
            JOIN customer_payments p ON p.id = t.customer_payment_id WHERE p.company_id = migration.cid() AND NOT p.voided
            GROUP BY 1, 2 ORDER BY 1, 2""").fetchall()
        r.table(("Received tenders", "Status", "Count", "Amount"), [(m, s, n, money(a)) for m, s, n, a in tenders])
        book = pg.execute("""
            SELECT a.name, count(e.id), coalesce(sum(e.amount) FILTER (WHERE NOT e.voided), 0) FROM bank_accounts a
            LEFT JOIN bank_entries e ON e.bank_account_id = a.id WHERE a.company_id = migration.cid() GROUP BY a.id ORDER BY a.name""").fetchall()
        r.table(("Bank book", "Entries", "Book balance"), [(n, c, money(b)) for n, c, b in book])

        # Legacy's own view of each invoice: net - what its non-bounced, non-voided tenders paid (= legacy
        # AmountPaid + PDC). Norbiz must show the same balance, except where the loader had to drop or cut
        # an application (listed under Issues as skipped/trimmed PaymentLink).
        pg.execute("""
            CREATE TEMP TABLE si_compare AS
            SELECT s.id, s.reference_number, round(greatest(coalesce(l.netamount, 0), 0), 2) - coalesce(k.paid, 0) AS legacy_balance,
                   coalesce(o.balance, 0) AS norbiz_balance, round(coalesce(l.amountdue, 0), 2) AS legacy_amount_due,
                   EXISTS (SELECT 1 FROM migration.issues i JOIN legacy.tblcustomerpaymentlinks x ON x.id = i.legacy_id
                           WHERE i.entity = 'PaymentLink' AND i.kind IN ('skipped', 'trimmed') AND x.payabletypeid = 1 AND x.payableid = s.id) AS explained
            FROM sales_invoices s JOIN legacy.tblsalesinvoices l ON l.id = s.id
            LEFT JOIN open_items o ON o.id = s.open_item_id
            LEFT JOIN (SELECT k.payableid, round(sum(k.amount), 2) paid FROM legacy.tblcustomerpaymentlinks k
                       JOIN legacy.tblcustomerpaymentdetails d ON d.id = k.customerpaymentdetailid
                       JOIN legacy.tblcustomerpayments p ON p.id = k.customerpaymentid
                       WHERE k.payabletypeid = 1 AND NOT p.void AND NOT coalesce(d.void, false) AND NOT coalesce(d.bounced, false)
                       GROUP BY 1) k ON k.payableid = s.id
            WHERE s.company_id = migration.cid() AND NOT s.voided""")
        n, legacy_total, norbiz_total, due_total, off, unexplained, due_off = pg.execute("""
            SELECT count(*), sum(legacy_balance) FILTER (WHERE NOT explained), sum(norbiz_balance) FILTER (WHERE NOT explained),
                   sum(legacy_amount_due) FILTER (WHERE abs(legacy_amount_due) < 1e9),
                   count(*) FILTER (WHERE abs(legacy_balance - norbiz_balance) > 0.01),
                   count(*) FILTER (WHERE abs(legacy_balance - norbiz_balance) > 0.01 AND NOT explained),
                   count(*) FILTER (WHERE abs(legacy_amount_due - norbiz_balance) > 0.01)
            FROM si_compare""").fetchone()
        r.p("Sales invoices: {:,} live; {:,} differ from legacy only where an application was cut or dropped (see Issues), {:,} unexplained.".format(
            n, off - unexplained, unexplained))
        r.p("Outside those: legacy balance (net - non-bounced payments) {}, Norbiz balance {}.".format(money(legacy_total), money(norbiz_total)))
        r.p("Legacy's stored AmountDue totals {} (absurd values left out) and disagrees with Norbiz on {:,} invoices (it is stale; informational).".format(
            money(due_total), due_off))
        worst = pg.execute("""SELECT reference_number, legacy_balance, norbiz_balance FROM si_compare
                              WHERE abs(legacy_balance - norbiz_balance) > 0.01 AND NOT explained
                              ORDER BY abs(legacy_balance - norbiz_balance) DESC LIMIT 10""").fetchall()
        if worst:
            r.table(("Unexplained invoice", "Legacy balance", "Norbiz balance"), [(a, money(b), money(c)) for a, b, c in worst])
        r.check("invoice balances match legacy", unexplained == 0, "{:,} unexplained of {:,}".format(unexplained, n))
        bad = one(pg, """
            SELECT count(*) FROM open_items o
            LEFT JOIN (SELECT i, sum(a) a FROM (SELECT charge_item_id i, amount a FROM open_item_allocations WHERE NOT reversed AND company_id = migration.cid()
                                                UNION ALL SELECT credit_item_id, amount FROM open_item_allocations WHERE NOT reversed AND company_id = migration.cid()) y
                       GROUP BY 1) x ON x.i = o.id
            WHERE o.company_id = migration.cid() AND (o.applied_amount <> coalesce(x.a, 0) OR o.balance <> o.amount - o.applied_amount)""")
        r.check("open items equal their allocations", bad == 0, "{:,} open item(s) out of step".format(bad))

    # ---- issues ----
    r.h("Issues (fixed or skipped rows)")
    rows = pg.execute("SELECT kind, entity, count(*), min(detail) FROM migration.issues GROUP BY kind, entity ORDER BY kind, entity").fetchall()
    r.table(("Kind", "Entity", "Rows", "Example"), [(k, e, n, (d or "")[:90]) for k, e, n, d in rows])
    r.p("Full list: SELECT kind, entity, migration.legacy_id(legacy_id), detail FROM migration.issues ORDER BY kind, entity, legacy_id;")

    # ---- balances ----
    r.h("Stock balances vs legacy (tblInventory)")
    before = pg.execute("""
        SELECT CASE WHEN warehouse_id = migration.main_wh() THEN 'Main' ELSE 'Outlets' END, count(*) FILTER (WHERE abs(norbiz - legacy) > 0.00005),
               coalesce(sum(abs(norbiz - legacy)), 0), count(*) FILTER (WHERE abs(norbiz_transit - legacy_transit) > 0.00005),
               coalesce(sum(abs(norbiz_transit - legacy_transit)), 0)
        FROM migration.onhand_before GROUP BY 1 ORDER BY 1""").fetchall()
    r.table(("Before correction", "On-hand pairs", "On-hand units", "Transit pairs", "Transit units"),
            [(a, b, "{:,.4f}".format(c), d, "{:,.4f}".format(e)) for a, b, c, d, e in before])
    corrections = pg.execute("SELECT count(DISTINCT warehouse_id), count(*), coalesce(sum(quantity), 0) FROM migration.correction").fetchone()
    r.p("Corrected by {} reconstructed adjustment(s): {:,} line(s), net {:,.4f} units.".format(*corrections))
    balances = "(SELECT * FROM inventory_balances WHERE " + WAREHOUSE_SCOPE + ")"
    legacy_inv = "(SELECT * FROM legacy.tblinventory WHERE " + WAREHOUSE_SCOPE.replace("warehouse_id", "warehouseid") + \
                 " AND itemid IN (SELECT id FROM items WHERE company_id = migration.cid()))"
    after_onhand = one(pg, """
        SELECT count(*) FROM {} n FULL JOIN {} l ON l.warehouseid = n.warehouse_id AND l.itemid = n.item_id
        WHERE abs(coalesce(n.quantity, 0) - round(coalesce(l.quantity, 0)::numeric, 4)) > 0.00005""".format(balances, legacy_inv))
    after_transit = pg.execute("""
        SELECT count(*), coalesce(sum(abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4))), 0)
        FROM {} n FULL JOIN {} l ON l.warehouseid = n.warehouse_id AND l.itemid = n.item_id
        WHERE abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4)) > 0.00005""".format(balances, legacy_inv)).fetchone()
    ledger = one(pg, """
        SELECT count(*) FROM (SELECT item_id, warehouse_id, sum(quantity_delta) q, sum(transit_quantity_delta) t FROM inventory_movements
                              WHERE company_id = migration.cid() GROUP BY 1, 2) m
        FULL JOIN {} b ON b.item_id = m.item_id AND b.warehouse_id = m.warehouse_id
        WHERE abs(coalesce(m.q, 0) - coalesce(b.quantity, 0)) > 0.00005 OR abs(coalesce(m.t, 0) - coalesce(b.transit_quantity, 0)) > 0.00005""".format(balances))
    r.p("After: {:,} on-hand difference(s); {:,} transit difference(s) totalling {:,.4f} units (transit is reported, not corrected).".format(
        after_onhand, after_transit[0], after_transit[1]))
    worst = pg.execute("""
        SELECT w.code, i.item_code, coalesce(n.transit_quantity, 0), round(coalesce(l.intransitqty, 0)::numeric, 4)
        FROM {} n FULL JOIN {} l ON l.warehouseid = n.warehouse_id AND l.itemid = n.item_id
        JOIN warehouses w ON w.id = coalesce(n.warehouse_id, l.warehouseid) JOIN items i ON i.id = coalesce(n.item_id, l.itemid)
        WHERE abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4)) > 0.00005
        ORDER BY abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4)) DESC, w.code, i.item_code LIMIT 10""".format(
        balances, legacy_inv)).fetchall()
    if worst:
        r.p("Largest transit differences:")
        r.table(("Warehouse", "Item", "Norbiz transit", "Legacy transit"), [(a, b, str(c), str(d)) for a, b, c, d in worst])

    # ---- checks ----
    r.h("Checks")
    r.check("document counts match legacy", not [f for f in r.failures if f.startswith("document count")],
            ", ".join(f[len("document count "):] for f in r.failures if f.startswith("document count")) or "all types")
    unlinked = one(pg, "SELECT count(*) FROM legacy.tbloutletdeliveryreturndetails d WHERE d.quantityreturned > 0 "
                       "AND NOT EXISTS (SELECT 1 FROM outlet_delivery_return_lines l WHERE l.id = d.id)")
    r.check("every ODR return line linked to a sale", unlinked == 0, "{} unlinked".format(unlinked))
    r.check("on-hand equals legacy balance", after_onhand == 0, "{} differing item/warehouse pairs".format(after_onhand))
    r.check("balances equal the ledger", ledger == 0, "{} pairs where balance != sum(movements)".format(ledger))
    over = one(pg, """
        SELECT (SELECT count(*) FROM delivery_receipt_lines l JOIN delivery_receipts h ON h.id = l.delivery_receipt_id
                WHERE h.company_id = migration.cid() AND l.quantity_loaded > l.quantity)
             + (SELECT count(*) FROM outlet_delivery_receipt_lines l JOIN outlet_delivery_receipts h ON h.id = l.outlet_delivery_receipt_id
                WHERE h.company_id = migration.cid() AND l.quantity_loaded > l.quantity)
             + (SELECT count(*) FROM outlet_pull_out_lines l JOIN outlet_pull_outs h ON h.id = l.outlet_pull_out_id
                WHERE h.company_id = migration.cid() AND l.quantity_loaded > l.quantity)
             + (SELECT count(*) FROM purchase_invoice_lines l JOIN purchase_invoices h ON h.id = l.purchase_invoice_id
                WHERE h.company_id = migration.cid() AND l.quantity_loaded > l.quantity)
             + (SELECT count(*) FROM stock_transfer_lines l JOIN stock_transfers h ON h.id = l.stock_transfer_id
                WHERE h.company_id = migration.cid() AND l.quantity_loaded > l.quantity)
             + (SELECT count(*) FROM sales_invoice_lines l JOIN sales_invoices h ON h.id = l.sales_invoice_id
                WHERE h.company_id = migration.cid() AND l.quantity_loaded > l.quantity)""")
    r.check("no line loaded beyond its quantity", over == 0, "{} over-loaded lines".format(over))
    legacy_actions = one(pg, "SELECT count(*) FROM legacy.tblstocktransferuseractions")
    skipped_actions = one(pg, "SELECT count(*) FROM migration.issues WHERE kind = 'skipped' AND entity = 'StockTransferAction'")
    actions = one(pg, "SELECT count(*) FROM transaction_events WHERE transaction_type = 'STOCK_TRANSFER' AND event_type = 'ACTION' "
                      "AND company_id = migration.cid()")
    r.check("every stock transfer action migrated", actions == legacy_actions - skipped_actions,
            "{:,} of {:,} legacy ({:,} skipped)".format(actions, legacy_actions, skipped_actions))
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    args = ap.parse_args()
    dsn = os.environ.get("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")
    with psycopg.connect(dsn) as pg:
        report = build(pg)
    text = "\n".join(report.lines) + "\n"
    print(text)
    if args.out:
        with open(args.out, "w") as f:
            f.write(text)
    if report.failures:
        print("FAILED checks: " + ", ".join(sorted(set(report.failures))), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
