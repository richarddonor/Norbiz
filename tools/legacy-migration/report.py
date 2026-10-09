"""Migration report: what was loaded, what was reconstructed or fixed, and whether the result
reconciles with legacy. Prints a plain-text report and exits non-zero when a hard check fails
(run_migration.sh stops on that).

Usage: .venv/bin/python report.py [--out FILE]   (PG_DSN as for extract.py)
"""
import argparse
import os
import sys

import psycopg

# (Norbiz table, legacy sources (table, filter) whose rows it carries, label)
DOCUMENTS = [
    ("inventory_adjustments", [("tblinventoryadjustment", None), ("tbloutletinventoryadjustment", None)], "Inventory Adjustment"),
    ("stock_transfers", [("tblstocktransfer", None)], "Stock Transfer"),
    ("delivery_receipts", [("tbldeliveryreceipts", None)], "Delivery Receipt"),
    ("outlet_receives", [("tbloutletreceives", None)], "Outlet Receive"),
    ("outlet_delivery_receipts", [("tbloutletdeliveryreceipts", None)], "Outlet Delivery Receipt"),
    ("outlet_delivery_returns", [("tbloutletdeliveryreturns", None)], "Outlet Delivery Return"),
    ("outlet_pull_outs", [("tblreturnslips", None)], "Outlet Pull Out"),
    ("pull_out_receives", [("tbloutletpullout", None)], "Pull Out Receive"),
    ("purchase_invoices", [("tblsupplierinvoices", None)], "Purchase Invoice"),
    ("purchase_receives", [("tblitemreceive", None)], "Purchase Receive"),
    ("assemblies", [("tblassembly", None)], "Assembly"),
]

ENTITY_OF = {
    "inventory_adjustments": ("InventoryAdjustment", "OutletInventoryAdjustment"),
    "stock_transfers": ("StockTransfer",), "delivery_receipts": ("DeliveryReceipt",), "outlet_receives": ("OutletReceive",),
    "outlet_delivery_receipts": ("OutletDeliveryReceipt",), "outlet_delivery_returns": ("OutletDeliveryReturn",),
    "outlet_pull_outs": ("OutletPullOut",), "pull_out_receives": ("PullOutReceive",), "purchase_invoices": ("PurchaseInvoice",),
    "purchase_receives": ("PurchaseReceive",), "assemblies": ("Assembly",),
}


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


def build(pg):
    r = Report()
    company, cutover = pg.execute("SELECT c.name, x.cutover FROM migration.ctx x JOIN companies c ON c.id = x.company_id").fetchone()
    r.p("Legacy jbsKarutora -> Norbiz migration report")
    r.p("Company: {}    Cutover: {:%Y-%m-%d %H:%M %Z}".format(company, cutover))

    # ---- documents ----
    r.h("Documents")
    rows = []
    for table, sources, label in DOCUMENTS:
        legacy = sum(one(pg, "SELECT count(*) FROM legacy." + t) for t, _ in sources)
        total, migrated, recon, voided = pg.execute(
            "SELECT count(*), count(*) FILTER (WHERE origin = 'MIGRATED'), count(*) FILTER (WHERE origin = 'RECONSTRUCTED'), "
            "count(*) FILTER (WHERE voided) FROM " + table).fetchone()
        entities = ENTITY_OF[table]
        skipped = one(pg, "SELECT count(*) FROM migration.issues WHERE kind = 'skipped' AND entity = ANY(%s)", list(entities))
        # A split document is logged once with its part count ("Received against N ...", "Reverses N ...").
        split = one(pg, "SELECT coalesce(sum(substring(detail FROM '(\\d+)')::int - 1), 0) FROM migration.issues "
                        "WHERE kind = 'split' AND entity = ANY(%s)", list(entities))
        rows.append((label, legacy, skipped, split, migrated, recon, voided, total))
        r_expected = legacy - skipped + split
        if r_expected != migrated:
            r.failures.append("document count " + label)
    r.table(("Type", "Legacy", "Skipped", "+Split", "Migrated", "Reconstr.", "Voided", "Norbiz"), rows)
    r.p("Migrated = Legacy - Skipped + extra parts from splits. Skipped documents are listed under Issues.")

    # ---- reconstructed ----
    r.h("Reconstructed documents")
    rows = pg.execute("""
        SELECT label, count(*) FROM (
            SELECT CASE WHEN reason LIKE '%%stock balance at cutover%%' THEN 'Inventory Adjustment — on-hand correction to legacy balance'
                        ELSE 'Inventory Adjustment — receive took more than its source had' END label FROM inventory_adjustments WHERE origin = 'RECONSTRUCTED'
            UNION ALL SELECT 'Pull Out Receive — legacy pull out went straight to on-hand' FROM pull_out_receives WHERE origin = 'RECONSTRUCTED'
            UNION ALL SELECT 'Purchase Receive — legacy invoice posted straight to on-hand' FROM purchase_receives WHERE origin = 'RECONSTRUCTED'
            UNION ALL SELECT 'Outlet Delivery Receipt (zero price) — return matched no sale' FROM outlet_delivery_receipts WHERE origin = 'RECONSTRUCTED'
        ) x GROUP BY label ORDER BY label""").fetchall()
    r.table(("Reconstructed", "Count"), rows)

    # ---- master data ----
    r.h("Master data")
    rows = [(label, one(pg, "SELECT count(*) FROM " + t)) for label, t in [
        ("Users (password reset required)", "users WHERE password LIKE '!legacy-migration%'"), ("Employees", "employees"),
        ("Sales agents", "employee_tags WHERE tag = 'AGENT'"), ("Items", "items"), ("Item SKUs", "item_skus"),
        ("  of which without an item", "item_skus WHERE item_id IS NULL"),
        ("Item categories", "item_categories"), ("Item groups", "item_groups"), ("Brands", "brands"), ("Suppliers", "suppliers"),
        ("Customers", "customers"), ("  of which outlets", "customers WHERE type = 'OUTLET'"), ("Warehouses", "warehouses"),
        ("Pull out reasons", "pull_out_reasons"), ("Bills of materials", "bills_of_materials")]]
    r.table(("Record", "Count"), rows)

    # ---- issues ----
    r.h("Issues (fixed or skipped rows)")
    rows = pg.execute("SELECT kind, entity, count(*), min(detail) FROM migration.issues GROUP BY kind, entity ORDER BY kind, entity").fetchall()
    r.table(("Kind", "Entity", "Rows", "Example"), [(k, e, n, (d or "")[:90]) for k, e, n, d in rows])
    r.p("Full list: SELECT * FROM migration.issues ORDER BY kind, entity, legacy_id;")

    # ---- balances ----
    r.h("Stock balances vs legacy (tblInventory)")
    before = pg.execute("""
        SELECT CASE WHEN warehouse_id = 1 THEN 'Main' ELSE 'Outlets' END, count(*) FILTER (WHERE abs(norbiz - legacy) > 0.00005),
               coalesce(sum(abs(norbiz - legacy)), 0), count(*) FILTER (WHERE abs(norbiz_transit - legacy_transit) > 0.00005),
               coalesce(sum(abs(norbiz_transit - legacy_transit)), 0)
        FROM migration.onhand_before GROUP BY 1 ORDER BY 1""").fetchall()
    r.table(("Before correction", "On-hand pairs", "On-hand units", "Transit pairs", "Transit units"),
            [(a, b, "{:,.4f}".format(c), d, "{:,.4f}".format(e)) for a, b, c, d, e in before])
    corrections = pg.execute("SELECT count(DISTINCT warehouse_id), count(*), coalesce(sum(quantity), 0) FROM migration.correction").fetchone()
    r.p("Corrected by {} reconstructed adjustment(s): {:,} line(s), net {:,.4f} units.".format(*corrections))
    after_onhand = one(pg, """
        SELECT count(*) FROM inventory_balances n FULL JOIN legacy.tblinventory l ON l.warehouseid = n.warehouse_id AND l.itemid = n.item_id
        WHERE abs(coalesce(n.quantity, 0) - round(coalesce(l.quantity, 0)::numeric, 4)) > 0.00005""")
    after_transit = pg.execute("""
        SELECT count(*), coalesce(sum(abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4))), 0)
        FROM inventory_balances n FULL JOIN legacy.tblinventory l ON l.warehouseid = n.warehouse_id AND l.itemid = n.item_id
        WHERE abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4)) > 0.00005""").fetchone()
    ledger = one(pg, """
        SELECT count(*) FROM (SELECT item_id, warehouse_id, sum(quantity_delta) q, sum(transit_quantity_delta) t FROM inventory_movements GROUP BY 1, 2) m
        FULL JOIN inventory_balances b ON b.item_id = m.item_id AND b.warehouse_id = m.warehouse_id
        WHERE abs(coalesce(m.q, 0) - coalesce(b.quantity, 0)) > 0.00005 OR abs(coalesce(m.t, 0) - coalesce(b.transit_quantity, 0)) > 0.00005""")
    r.p("After: {:,} on-hand difference(s); {:,} transit difference(s) totalling {:,.4f} units (transit is reported, not corrected).".format(
        after_onhand, after_transit[0], after_transit[1]))
    worst = pg.execute("""
        SELECT w.code, i.item_code, coalesce(n.transit_quantity, 0), round(coalesce(l.intransitqty, 0)::numeric, 4)
        FROM inventory_balances n FULL JOIN legacy.tblinventory l ON l.warehouseid = n.warehouse_id AND l.itemid = n.item_id
        JOIN warehouses w ON w.id = coalesce(n.warehouse_id, l.warehouseid) JOIN items i ON i.id = coalesce(n.item_id, l.itemid)
        WHERE abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4)) > 0.00005
        ORDER BY abs(coalesce(n.transit_quantity, 0) - round(coalesce(l.intransitqty, 0)::numeric, 4)) DESC, w.code, i.item_code LIMIT 10""").fetchall()
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
        SELECT (SELECT count(*) FROM delivery_receipt_lines WHERE quantity_loaded > quantity)
             + (SELECT count(*) FROM outlet_delivery_receipt_lines WHERE quantity_loaded > quantity)
             + (SELECT count(*) FROM outlet_pull_out_lines WHERE quantity_loaded > quantity)
             + (SELECT count(*) FROM purchase_invoice_lines WHERE quantity_loaded > quantity)
             + (SELECT count(*) FROM stock_transfer_lines WHERE quantity_loaded > quantity)""")
    r.check("no line loaded beyond its quantity", over == 0, "{} over-loaded lines".format(over))
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
