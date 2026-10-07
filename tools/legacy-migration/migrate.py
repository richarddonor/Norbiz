"""Run the legacy jbsKarutora -> Norbiz migration end to end against the database in PG_DSN:

    extract (SQL Server -> legacy schema)  ->  00 prepare  ->  01 reset Norbiz data  ->  10 master data
    ->  20 documents  ->  25 outlet delivery returns  ->  40 ledger + history  ->  50 reconcile
    ->  60 finalize  ->  report

Every step runs in its own transaction and the whole run is repeatable: it always starts by wiping
Norbiz down to its seeded basics, so a rehearsal and the real cutover are the same command.
Because step 01 is destructive, the run refuses to start unless --confirm-reset names the target
database. run_migration.sh adds the remaining cutover guards (app stopped, pg_dump backup, Redis).

Usage:
    .venv/bin/python migrate.py --confirm-reset norbiz_mig [--skip-extract] [--report-dir reports]
"""
import argparse
import datetime
import os
import subprocess
import sys
import time

import psycopg

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable

STEPS = [
    ("sql", "00_prepare.sql", "index the legacy copy"),
    ("sql", "01_reset_data.sql", "reset Norbiz to its seeded basics"),
    ("sql", "10_master.sql", "master data"),
    ("sql", "20_documents.sql", "documents"),
    ("py", "match_returns.py", "outlet delivery returns"),
    ("sql", "40_ledger.sql", "ledger and history"),
    ("sql", "50_reconcile.sql", "reconcile on-hand with legacy"),
    ("sql", "60_finalize.sql", "finalize"),
]


def run_sql(dsn, path):
    with open(path) as f:
        script = f.read()
    with psycopg.connect(dsn) as pg:
        pg.execute(script)  # no parameters: sent as one multi-statement script
        pg.commit()


def run_py(script, env):
    subprocess.run([PY, os.path.join(HERE, script)], check=True, env=env)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--confirm-reset", required=True, metavar="DBNAME",
                    help="name of the target database — required because the run wipes its Norbiz data")
    ap.add_argument("--skip-extract", action="store_true", help="reuse the legacy schema from the previous run")
    ap.add_argument("--report-dir", default=os.path.join(HERE, "reports"))
    args = ap.parse_args()

    env = dict(os.environ)
    dsn = env.setdefault("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")
    with psycopg.connect(dsn) as pg:
        dbname = pg.execute("SELECT current_database()").fetchone()[0]
        has_legacy = pg.execute("SELECT count(*) FROM pg_namespace WHERE nspname = 'legacy'").fetchone()[0] == 1
    if args.confirm_reset != dbname:
        sys.exit("Refusing to run: --confirm-reset {!r} does not match the target database {!r}.".format(args.confirm_reset, dbname))
    if args.skip_extract and not has_legacy:
        sys.exit("--skip-extract given but {} has no legacy schema; run without it first.".format(dbname))

    started = time.time()
    print("Migrating into database {} ({:%Y-%m-%d %H:%M})".format(dbname, datetime.datetime.now()), flush=True)
    steps = STEPS if not args.skip_extract else [s for s in STEPS if s[1] != "00_prepare.sql"]
    if not args.skip_extract:
        t = time.time()
        print("== extract", flush=True)
        run_py("extract.py", env)
        print("   {:.0f}s".format(time.time() - t), flush=True)
    for kind, name, label in steps:
        t = time.time()
        print("== {} ({})".format(name, label), flush=True)
        if kind == "sql":
            run_sql(dsn, os.path.join(HERE, "sql", name))
        else:
            run_py(name, env)
        print("   {:.0f}s".format(time.time() - t), flush=True)

    os.makedirs(args.report_dir, exist_ok=True)
    report = os.path.join(args.report_dir, "migration-{:%Y%m%d-%H%M%S}.txt".format(datetime.datetime.now()))
    print("== report -> " + report, flush=True)
    result = subprocess.run([PY, os.path.join(HERE, "report.py"), "--out", report], env=env)
    print("Finished in {:.0f} min{}".format((time.time() - started) / 60, "" if result.returncode == 0 else " — REPORT CHECKS FAILED"))
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
