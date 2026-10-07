"""Guards and housekeeping used by run_migration.sh. Each subcommand prints a short result and exits
non-zero when the check fails, so the shell script can stop on it.

    cutover.py preflight --confirm-reset DBNAME   target DB reachable, name matches, Norbiz app not connected,
                                                  SQL Server reachable with the legacy database
    cutover.py dsn FIELD                          print user / dbname / host / port / password from PG_DSN
    cutover.py flush-redis                        FLUSHDB on REDIS_HOST:REDIS_PORT (db 0), the query cache
"""
import os
import socket
import sys

import psycopg
from psycopg.conninfo import conninfo_to_dict

from extract import env, mssql_connect

DSN = os.environ.get("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")


def fail(message):
    print("FAIL  " + message)
    sys.exit(1)


def preflight(confirm):
    try:
        with psycopg.connect(DSN, connect_timeout=10) as pg:
            dbname = pg.execute("SELECT current_database()").fetchone()[0]
            if confirm != dbname:
                fail("--confirm-reset {!r} does not match the target database {!r}".format(confirm, dbname))
            print("OK    target database {} reachable".format(dbname))
            # The Norbiz app connects through the PostgreSQL JDBC driver; any such session means it is running.
            app = pg.execute(
                "SELECT count(*), string_agg(DISTINCT coalesce(client_addr::text, 'local'), ', ') FROM pg_stat_activity "
                "WHERE datname = current_database() AND pid <> pg_backend_pid() AND application_name ILIKE '%JDBC%'").fetchone()
            if app[0]:
                fail("the Norbiz app is connected to {} ({} JDBC session(s) from {}) — stop it first".format(dbname, app[0], app[1]))
            print("OK    Norbiz app not connected")
            seeded = pg.execute("SELECT count(*) FROM users WHERE username = 'super_admin'").fetchone()[0]
            if not seeded:
                fail("{} has no super_admin user — start the Norbiz app against it once (and let DataInitializer finish) "
                     "so it creates the schema and seeds roles/permissions".format(dbname))
            print("OK    Norbiz schema and seed data present")
    except psycopg.OperationalError as e:
        fail("cannot connect to PG_DSN: {}".format(str(e).strip().splitlines()[0]))

    try:
        with mssql_connect() as ms:
            cur = ms.cursor()
            cur.execute("SELECT DB_NAME(), (SELECT COUNT(*) FROM dbo.tblDeliveryReceipts)")
            name, drs = cur.fetchone()
            print("OK    SQL Server database {} reachable ({:,} delivery receipts)".format(name, drs))
    except Exception as e:  # pymssql raises its own exception types
        fail("cannot read the legacy database {} on {}: {}".format(env("MSSQL_DATABASE", "jbsKarutora"), env("MSSQL_HOST", "localhost"),
                                                                  str(e).strip().splitlines()[0][:200]))


def dsn_field(field):
    info = conninfo_to_dict(DSN)
    print(info.get(field, {"host": "localhost", "port": "5432"}.get(field, "")))


def flush_redis():
    host, port = os.environ.get("REDIS_HOST", "localhost"), int(os.environ.get("REDIS_PORT", "6379"))
    password = os.environ.get("REDIS_PASSWORD", "")

    def command(sock, *parts):
        payload = "*{}\r\n".format(len(parts)) + "".join("${}\r\n{}\r\n".format(len(p.encode()), p) for p in parts)
        sock.sendall(payload.encode())
        return sock.recv(1024).decode(errors="replace").strip()

    try:
        with socket.create_connection((host, port), timeout=5) as sock:
            if password:
                reply = command(sock, "AUTH", password)
                if not reply.startswith("+OK"):
                    fail("Redis AUTH failed: " + reply)
            reply = command(sock, "FLUSHDB")
            if not reply.startswith("+OK"):
                fail("Redis FLUSHDB failed: " + reply)
    except OSError as e:
        fail("cannot reach Redis at {}:{} ({}). Flush it manually before starting the app.".format(host, port, e))
    print("OK    Redis query cache flushed ({}:{})".format(host, port))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "preflight" and len(sys.argv) == 4 and sys.argv[2] == "--confirm-reset":
        preflight(sys.argv[3])
    elif cmd == "dsn" and len(sys.argv) == 3:
        dsn_field(sys.argv[2])
    elif cmd == "flush-redis":
        flush_redis()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
