"""Step 25 — Outlet Delivery Returns.

Legacy returns carry no link to the sale they reverse, but a Norbiz Outlet Delivery Return must name
its source Outlet Delivery Receipt (and each line its source sale line). This step matches them:

  * Returns are processed in date order. For each one, candidate sales are the same outlet's
    non-voided sales dated on or before the return, with enough not-yet-returned quantity.
  * The most recent sale that covers the most of the return's lines wins; if it doesn't cover them
    all, the rest are matched the same way against other sales, splitting the return into one Norbiz
    return per source sale ("<ref>-2", ...). A line is only covered whole, never split across sales.
  * Lines no sale covers get a RECONSTRUCTED zero-price Outlet Delivery Receipt ("RCN-ODR-000001")
    dated the return day, which the return then reverses.
  * Within the chosen sale, a line's quantity is taken from that sale's lines for the item oldest
    first (as OutletDeliveryReturnService does), so one return line may become several Norbiz lines
    sharing a line number. Unit price and agent come from the sale, as in Norbiz.
  * Voided returns are matched the same way but don't consume returnable quantity.

Usage: .venv/bin/python match_returns.py   (PG_DSN as for extract.py)
"""
import os
import sys
from collections import defaultdict
from decimal import Decimal

import psycopg

ZERO = Decimal(0)


def main():
    dsn = os.environ.get("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")
    with psycopg.connect(dsn) as pg:
        run(pg)
        pg.commit()


def run(pg):
    company_id, actor = pg.execute("SELECT company_id, actor FROM migration.ctx").fetchone()

    # Sales lines, grouped per outlet: (odr_id, day, agent) and per-item lines with remaining quantity.
    sales = {}                                   # odr_id -> dict
    by_outlet = defaultdict(list)                # customer_id -> [odr_id] (newest first after sort)
    for odr_id, customer_id, day, agent_id, warehouse_id in pg.execute(
            "SELECT id, customer_id, delivery_date, agent_id, warehouse_id FROM outlet_delivery_receipts WHERE NOT voided"):
        sales[odr_id] = {"customer": customer_id, "day": day, "agent": agent_id, "warehouse": warehouse_id,
                         "lines": defaultdict(list)}
        by_outlet[customer_id].append(odr_id)
    for line_id, odr_id, item_id, qty, price, line_no in pg.execute(
            "SELECT l.id, l.outlet_delivery_receipt_id, l.item_id, l.quantity, l.unit_price, l.line_number "
            "FROM outlet_delivery_receipt_lines l JOIN outlet_delivery_receipts o ON o.id = l.outlet_delivery_receipt_id "
            "WHERE NOT o.voided ORDER BY l.id"):
        sales[odr_id]["lines"][item_id].append({"id": line_id, "remaining": qty, "price": price, "qty": qty, "line_number": line_no})
    for ids in by_outlet.values():
        ids.sort(key=lambda i: (sales[i]["day"], i), reverse=True)

    returns = pg.execute(
        "SELECT r.id, r.customerid, r.void, btrim(r.odrsno), migration.doc_day(r.date, r.creationdate), "
        "       migration.trunc(r.sheetno, 100), migration.trunc(r.comments, 255), migration.created(r.creationdate, r.date), "
        "       migration.actor(r.createdbyid), "
        "       CASE WHEN r.void THEN coalesce(migration.at(r.datevoid), migration.created(r.creationdate, r.date)) END, "
        "       CASE WHEN r.void THEN migration.actor(r.voidedby) END, r.agentid, c.warehouse_id "
        "FROM legacy.tbloutletdeliveryreturns r JOIN customers c ON c.id = r.customerid "
        "ORDER BY migration.doc_day(r.date, r.creationdate), r.id").fetchall()
    lines_by_return = defaultdict(list)
    for line_id, ret_id, item_id, qty in pg.execute(
            "SELECT id, returnslipmasterid, itemid, migration.qty(quantityreturned) FROM legacy.tbloutletdeliveryreturndetails "
            "WHERE quantityreturned > 0 ORDER BY id"):
        lines_by_return[ret_id].append({"id": line_id, "item": item_id, "qty": qty})

    max_return_id = pg.execute("SELECT coalesce(max(id), 0) FROM legacy.tbloutletdeliveryreturns").fetchone()[0]
    max_return_line_id = pg.execute("SELECT coalesce(max(id), 0) FROM legacy.tbloutletdeliveryreturndetails").fetchone()[0]
    next_odr_id = pg.execute("SELECT coalesce(max(id), 0) FROM outlet_delivery_receipts").fetchone()[0]
    next_odr_line_id = pg.execute("SELECT coalesce(max(id), 0) FROM outlet_delivery_receipt_lines").fetchone()[0]

    out_returns, out_return_lines, recon_odrs, recon_odr_lines, issues = [], [], [], [], []
    loaded = defaultdict(lambda: ZERO)           # odr line id -> returned quantity (live returns only)
    extra_return_ids = 0
    extra_line_ids = 0
    recon_count = 0

    for (ret_id, customer, voided, ref, day, sheet, remarks, created_at, created_by,
         voided_at, voided_by, ret_agent, warehouse) in returns:
        pending = list(lines_by_return.get(ret_id, []))
        if not pending:
            issues.append(("skipped", "OutletDeliveryReturn", ret_id, "No lines with a quantity"))
            continue
        live = not voided
        parts = []                                # [(odr_id, [(return_line, [(odr_line, qty)])])]
        while pending:
            best, best_cover = None, []
            for odr_id in by_outlet.get(customer, []):
                sale = sales[odr_id]
                if sale["day"] > day:
                    continue
                # Lines are covered whole, drawing down the sale's returnable quantity per item as we go
                # (a return may list the same item more than once).
                available, cover = {}, []
                for l in pending:
                    if l["item"] not in available:
                        available[l["item"]] = sum((x["remaining"] for x in sale["lines"].get(l["item"], [])), ZERO)
                    if available[l["item"]] >= l["qty"]:
                        cover.append(l)
                        available[l["item"]] -= l["qty"]
                if len(cover) > len(best_cover):
                    best, best_cover = odr_id, cover
                    if len(cover) == len(pending):
                        break                      # newest sale covering everything
            if not best:
                break
            allocations = []
            for line in best_cover:
                need, pieces = line["qty"], []
                for src in sales[best]["lines"][line["item"]]:
                    if need <= 0:
                        break
                    take = min(need, src["remaining"])
                    if take > 0:
                        pieces.append((src, take))
                        need -= take
                        if live:
                            src["remaining"] -= take
                allocations.append((line, pieces))
            parts.append((best, allocations))
            covered = {l["id"] for l in best_cover}
            pending = [l for l in pending if l["id"] not in covered]

        if pending:
            # Nothing covers these lines: reconstruct the zero-price sale they reverse.
            recon_count += 1
            next_odr_id += 1
            odr_id = next_odr_id
            recon_odrs.append((odr_id, company_id, customer, warehouse, ret_agent, "RCN-ODR-{:06d}".format(recon_count), sheet, day,
                               ("Reconstructed: no legacy sale on or before return " + ref + " had these items to return")[:255],
                               created_at, actor, voided, voided_at, voided_by if voided else None))
            allocations = []
            for n, line in enumerate(pending, start=1):
                next_odr_line_id += 1
                src = {"id": next_odr_line_id, "price": ZERO, "reconstructed": True}
                recon_odr_lines.append((next_odr_line_id, odr_id, line["item"], line["qty"], ZERO, n, line["qty"] if live else ZERO))
                allocations.append((line, [(src, line["qty"])]))
            sales[odr_id] = {"agent": ret_agent, "warehouse": warehouse}
            parts.append((odr_id, allocations))
            issues.append(("reconstructed", "OutletDeliveryReturn", ret_id,
                           "{} line(s) matched no sale; reverses reconstructed RCN-ODR-{:06d}".format(len(pending), recon_count)))

        if len(parts) > 1:
            issues.append(("split", "OutletDeliveryReturn", ret_id, "Reverses {} different sales; split into one return per sale".format(len(parts))))

        for part_no, (odr_id, allocations) in enumerate(parts, start=1):
            if part_no == 1:
                return_id = ret_id
            else:
                extra_return_ids += 1
                return_id = max_return_id + extra_return_ids
            sale = sales[odr_id]
            part_remarks = " — ".join(x for x in [
                "Split from legacy {} (it reversed several sales)".format(ref) if part_no > 1 else None, remarks] if x)
            out_returns.append((return_id, company_id, odr_id, customer, sale["warehouse"], sale["agent"],
                                ref if part_no == 1 else "{}-{}".format(ref, part_no), sheet, day, part_remarks[:255] or None,
                                created_at, created_by, voided, voided_at, voided_by))
            line_no = 0
            for line, pieces in allocations:
                line_no += 1
                for i, (src, qty) in enumerate(pieces):
                    # Each legacy line belongs to exactly one part: its first piece keeps the legacy ID.
                    if i == 0:
                        line_id = line["id"]
                    else:
                        extra_line_ids += 1
                        line_id = max_return_line_id + extra_line_ids
                    out_return_lines.append((line_id, return_id, line["item"], src["id"], qty, src["price"], line_no))
                    if live and not src.get("reconstructed"):
                        loaded[src["id"]] += qty

    with pg.cursor() as cur:
        cur.executemany(
            "INSERT INTO outlet_delivery_receipts (id, company_id, customer_id, warehouse_id, agent_id, reference_number, sheet_number, "
            " delivery_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s, NOT %s, 'RECONSTRUCTED')",
            [r + (r[11],) for r in recon_odrs])  # a live reconstructed sale is fully returned, so loaded
        cur.executemany(
            "INSERT INTO outlet_delivery_receipt_lines (id, outlet_delivery_receipt_id, item_id, quantity, unit_price, line_number, quantity_loaded) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s)", recon_odr_lines)
        cur.executemany(
            "INSERT INTO outlet_delivery_returns (id, company_id, outlet_delivery_receipt_id, customer_id, warehouse_id, agent_id, "
            " reference_number, sheet_number, return_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s, false, 'MIGRATED')", out_returns)
        cur.executemany(
            "INSERT INTO outlet_delivery_return_lines (id, outlet_delivery_return_id, item_id, outlet_delivery_receipt_line_id, "
            " quantity, unit_price, line_number, quantity_loaded) VALUES (%s,%s,%s,%s,%s,%s,%s,0)", out_return_lines)
        cur.executemany("UPDATE outlet_delivery_receipt_lines SET quantity_loaded = least(quantity, %s) WHERE id = %s",
                        [(q, line_id) for line_id, q in loaded.items()])
        cur.execute(
            "UPDATE outlet_delivery_receipts o SET loaded = true WHERE o.id IN ("
            " SELECT DISTINCT outlet_delivery_receipt_id FROM outlet_delivery_receipt_lines WHERE quantity_loaded > 0) "
            "AND NOT EXISTS (SELECT 1 FROM outlet_delivery_receipt_lines l WHERE l.outlet_delivery_receipt_id = o.id AND l.quantity_loaded < l.quantity)")
        cur.executemany("INSERT INTO migration.issues (kind, entity, legacy_id, detail) VALUES (%s,%s,%s,%s)", issues)

    print("  {} legacy returns -> {} Norbiz returns ({} reverse a reconstructed sale), {} return lines".format(
        len(returns), len(out_returns), len(recon_odrs), len(out_return_lines)))
    print("  {} reconstructed zero-price sale(s) with {} line(s)".format(len(recon_odrs), len(recon_odr_lines)))


if __name__ == "__main__":
    sys.exit(main())
