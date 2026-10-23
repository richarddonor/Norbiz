"""Copy the legacy tables the migration needs from SQL Server (MSSQL_DATABASE: jbsKarutora,
jbsNeostrata, ...) into a fresh `legacy` schema in the target Postgres database, as-is (column names
lower-cased, no transformation).

Every run drops and recreates the `legacy` schema, so it always reflects the SQL Server backup that
is currently restored. Binary columns (item pictures) are skipped here — images.py handles those.

The legacy databases share one schema but not every database has every table or column (a company
on older updates lacks e.g. tblAssembly or tblStockTransferUserActions). legacy_columns.json lists
every table/column the loader may read, with its SQL Server type; whatever the source lacks is
created empty / NULL, so the same SQL runs against every legacy database.

Connection settings come from the environment (see config.env.example). Usage:
    .venv/bin/python extract.py
"""
import json
import os
import sys
import time

import psycopg
import pymssql

# Table -> optional WHERE clause. tblInventory has ~75M rows, almost all zero balances: only
# non-zero ones matter for reconciliation. Legacy auto-raised one debit memo per outlet sale; those are
# not migrated (docs/LEGACY_MIGRATION.md), only memos encoded by hand.
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
    "tblPricePoints": None,
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
    "tblStockTransferUserActions": None,
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
    # sales and receivables / payables (docs/LEGACY_MIGRATION.md "Accounting")
    "tblTerms": None,
    "tblBanks": None,
    "tblBankAccounts": None,
    "tblModesOfPayment": None,
    "tblSITransactionTypes": None,
    "tblCustomerBranches": None,
    "tblCustomerCreditMemoTypes": None,
    "tblSalesInvoices": None,
    "tblSalesInvoiceDetails": None,
    "tblSIReturnSlips": None,
    "tblSIReturnSlipDetails": None,
    "tblItemEntries": None,
    "tblItemEntryDetails": None,
    "tblItemDisbursements": None,
    "tblItemDisbursementDetails": None,
    "tblCustomerPayments": None,
    "tblCustomerPaymentDetails": None,
    "tblCustomerPaymentLinks": None,
    "tblDeposits": None,
    "tblDepositDetails": None,
    "tblCustomerCreditMemos": None,
    "tblCustomerDebitMemos": "Description IS NULL OR Description NOT LIKE 'ODR-%Generated from Outlet Sales Invoice%'",
    "tblCustomerRefunds": None,
    "tblSupplierPayments": None,
    "tblSupplierPaymentDetails": None,
    "tblSupplierPaymentLinks": None,
}

# Every column the loader may read, per table (the union of the known legacy databases).
SPEC = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "legacy_columns.json")))

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
    spec = [(c, t) for c, t in SPEC.get(table, [])]
    if not columns and not spec:
        raise SystemExit("Table not found in SQL Server: " + table)
    # Columns this database lacks (it is behind on legacy updates) are created NULL.
    present = {c.lower() for c, _ in columns}
    missing = [(c, t) for c, t in spec if c.lower() not in present]

    unknown = [t for _, t in columns + missing if t not in PG_TYPES]
    if unknown:
        raise SystemExit("Unmapped SQL Server type(s) in {}: {}".format(table, sorted(set(unknown))))

    pg_table = "legacy." + table.lower()
    ddl = ", ".join('"{}" {}'.format(c.lower(), PG_TYPES[t]) for c, t in columns + missing)
    pg.execute("CREATE TABLE {} ({})".format(pg_table, ddl))
    if not columns:
        print("  {:<40} {:>10} (not in this database: created empty)".format(table, "-"), flush=True)
        return 0
    if missing:
        print("  {:<40} missing column(s) created NULL: {}".format(table, ", ".join(c for c, _ in missing)), flush=True)

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
    database = env("MSSQL_DATABASE", "jbsKarutora")
    print("Extracting {} tables from SQL Server {} into {} (schema legacy)".format(
        len(TABLES), database, pg_dsn.rsplit("@", 1)[-1]), flush=True)
    started = time.time()
    with mssql_connect() as ms, psycopg.connect(pg_dsn, autocommit=False) as pg:
        pg.execute("DROP SCHEMA IF EXISTS legacy CASCADE")
        pg.execute("CREATE SCHEMA legacy")
        # Which legacy database this copy came from — the loader scopes the run to its company.
        pg.execute("CREATE TABLE legacy.source (database text NOT NULL, extracted_at timestamptz NOT NULL DEFAULT now())")
        pg.execute("INSERT INTO legacy.source (database) VALUES (%s)", (database,))
        total = 0
        for table, where in TABLES.items():
            total += copy_table(ms, pg, table, where)
        pg.commit()
    print("Done: {:,} rows in {:.0f}s".format(total, time.time() - started))


if __name__ == "__main__":
    sys.exit(main())
