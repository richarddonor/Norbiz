"""Copy the legacy jbsKarutora tables the migration needs from SQL Server into a fresh `legacy`
schema in the target Postgres database, as-is (column names lower-cased, no transformation).

Every run drops and recreates the `legacy` schema, so it always reflects the SQL Server backup that
is currently restored. Binary columns (item pictures) are skipped here — images.py handles those.

Connection settings come from the environment (see config.env.example). Usage:
    .venv/bin/python extract.py
"""
import os
import sys
import time

import psycopg
import pymssql

# Table -> optional WHERE clause. tblInventory has ~75M rows, almost all zero balances: only
# non-zero ones matter for reconciliation.
TABLES = {
    # master data
    "tblCompany": None,
    "tblWareHouses": None,
    "tblSecurityUsers": None,
    "tblEmployees": None,
    "tblBrands": None,
    "tblCategoryChartsOrig": None,
    "tblItemDescriptions": None,
    "tblItems": None,
    "tblSMSKUs": None,
    "tblSuppliers": None,
    "tblCustomers": None,
    "tblCustomerTypes": None,
    "tblPullOutReasons": None,
    "tblBillofMaterial": None,
    "tblBillOfMaterialRawDetail": None,
    # transactions
    "tblInventoryAdjustment": None,
    "tblInventoryAdjustmentDetails": None,
    "tblOutletInventoryAdjustment": None,
    "tblOutletInventoryAdjustmentDetails": None,
    "tblStockTransfer": None,
    "tblStockTransferDetails": None,
    "tblDeliveryReceipts": None,
    "tblDeliveryReceiptDetails": None,
    "tblOutletReceives": None,
    "tblOutletReceiveDetails": None,
    "tblOutletDeliveryReceipts": None,
    "tblOutletDeliveryReceiptsDetails": None,
    "tblOutletDeliveryReturns": None,
    "tblOutletDeliveryReturnDetails": None,
    "tblReturnSlips": None,
    "tblReturnSlipDetails": None,
    "tblOutletPullOut": None,
    "tblOutletPullOutDetail": None,
    "tblSupplierInvoices": None,
    "tblSupplierInvoiceDetails": None,
    "tblItemReceive": None,
    "tblItemReceiveDetails": None,
    "tblAssembly": None,
    "tblAssemblyDetail": None,
    "tblAssemblyRawMaterial": None,
    # legacy ledger and balances — used to classify documents and to reconcile the result
    "tblTransactionTypes": None,
    "tblTransactions": None,
    "tblTransactionDetails": None,
    "tblInventory": "Quantity <> 0 OR IntransitQty <> 0",
}

SKIPPED_TYPES = {"image", "varbinary", "binary", "timestamp"}

PG_TYPES = {
    "int": "integer", "bigint": "bigint", "smallint": "smallint", "tinyint": "smallint",
    "bit": "boolean",
    "money": "numeric", "smallmoney": "numeric", "decimal": "numeric", "numeric": "numeric",
    "float": "double precision", "real": "real",
    "datetime": "timestamp", "smalldatetime": "timestamp", "datetime2": "timestamp", "date": "date",
    "varchar": "text", "nvarchar": "text", "char": "text", "nchar": "text", "text": "text", "ntext": "text",
    "uniqueidentifier": "text",
}

BATCH = 20000


def env(name, default):
    return os.environ.get(name, default)


def mssql_connect():
    return pymssql.connect(
        server=env("MSSQL_HOST", "localhost"),
        port=int(env("MSSQL_PORT", "1433")),
        user=env("MSSQL_USER", "sa"),
        password=env("MSSQL_PASSWORD", ""),
        database=env("MSSQL_DATABASE", "jbsKarutora"),
        login_timeout=15,
    )


def clean(value):
    # Postgres text can't hold NUL characters, which some legacy free-text fields contain.
    if isinstance(value, str) and "\x00" in value:
        return value.replace("\x00", "")
    return value


def copy_table(ms, pg, table, where):
    started = time.time()
    cur = ms.cursor(as_dict=True)
    cur.execute(
        "SELECT c.name AS col, t.name AS type FROM sys.columns c "
        "JOIN sys.types t ON t.user_type_id = c.user_type_id "
        "WHERE c.object_id = OBJECT_ID(%s) ORDER BY c.column_id", (table,))
    columns = [(r["col"], r["type"]) for r in cur.fetchall() if r["type"] not in SKIPPED_TYPES]
    if not columns:
        raise SystemExit("Table not found in SQL Server: " + table)

    unknown = [t for _, t in columns if t not in PG_TYPES]
    if unknown:
        raise SystemExit("Unmapped SQL Server type(s) in {}: {}".format(table, sorted(set(unknown))))

    pg_table = "legacy." + table.lower()
    ddl = ", ".join('"{}" {}'.format(c.lower(), PG_TYPES[t]) for c, t in columns)
    pg.execute("CREATE TABLE {} ({})".format(pg_table, ddl))

    select = "SELECT {} FROM dbo.[{}]{}".format(
        ", ".join("[{}]".format(c) for c, _ in columns), table, " WHERE " + where if where else "")
    data = ms.cursor()
    data.execute(select)
    count = 0
    with pg.cursor().copy("COPY {} ({}) FROM STDIN".format(
            pg_table, ", ".join('"{}"'.format(c.lower()) for c, _ in columns))) as copy:
        while True:
            rows = data.fetchmany(BATCH)
            if not rows:
                break
            for row in rows:
                copy.write_row([clean(v) for v in row])
            count += len(rows)
    print("  {:<40} {:>10,} rows  {:6.1f}s".format(table, count, time.time() - started), flush=True)
    return count


def main():
    pg_dsn = env("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")
    print("Extracting {} tables from SQL Server {} into {} (schema legacy)".format(
        len(TABLES), env("MSSQL_DATABASE", "jbsKarutora"), pg_dsn.rsplit("@", 1)[-1]), flush=True)
    started = time.time()
    with mssql_connect() as ms, psycopg.connect(pg_dsn, autocommit=False) as pg:
        pg.execute("DROP SCHEMA IF EXISTS legacy CASCADE")
        pg.execute("CREATE SCHEMA legacy")
        total = 0
        for table, where in TABLES.items():
            total += copy_table(ms, pg, table, where)
        pg.commit()
    print("Done: {:,} rows in {:.0f}s".format(total, time.time() - started))


if __name__ == "__main__":
    sys.exit(main())
