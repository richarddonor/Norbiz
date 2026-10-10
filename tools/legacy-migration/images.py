"""Copy legacy item pictures (tblItems.Picture, stored in the database) into Norbiz's item-image
store and point items.image_path at them — the same layout ItemImageController writes:

    <upload dir>/<company id>/items/images/<item code, sanitized><ext>

JPEGs are written as-is. Bitmaps (which Norbiz doesn't serve) are converted to PNG. Anything else
is skipped and logged to migration.issues. Run after migrate.py (items must exist).

Usage:
    .venv/bin/python images.py [--upload-dir DIR | --remote USER@HOST:VOLUME] [--limit N] [--clean]

--upload-dir defaults to $ITEM_IMAGE_UPLOAD_DIR, else ~/norbiz (the app's own default); it must be
the directory the Norbiz server reads (app.item-image.upload-dir). --remote (default
$ITEM_IMAGE_REMOTE; wins over --upload-dir) is for a Norbiz server on another machine: pictures are
streamed over SSH as one tar into that Docker volume (the prod app's item_images volume, mounted at
its upload dir), nothing is written locally, and the files get the volume's owner (the app user).
--limit copies only the first N pictures, for a quick rehearsal. --clean first empties the migrated
company's image folder, so a re-run leaves no pictures from a previous load behind
(run_migration.sh always passes it).
"""
import argparse
import io
import os
import re
import shlex
import subprocess
import sys
import tarfile
import time

import psycopg
from PIL import Image

from extract import mssql_connect

BATCH = 200
REMOTE_IMAGE = "alpine:latest"


class LocalStore:
    """Writes pictures into an upload dir on this machine."""

    def __init__(self, upload_dir, rel_dir, clean):
        self.upload_dir = upload_dir
        self.where = os.path.join(upload_dir, rel_dir)
        if clean and os.path.isdir(self.where):
            removed = 0
            for name in os.listdir(self.where):
                path = os.path.join(self.where, name)
                if os.path.isfile(path):
                    os.remove(path)
                    removed += 1
            print("Removed {:,} picture(s) from a previous load".format(removed), flush=True)
        os.makedirs(self.where, exist_ok=True)

    def write(self, rel_path, content):
        with open(os.path.join(self.upload_dir, rel_path), "wb") as f:
            f.write(content)

    def close(self):
        pass


class RemoteStore:
    """Streams pictures as one tar over SSH into a Docker volume on the Norbiz server."""

    def __init__(self, remote, rel_dir, clean):
        host, sep, volume = remote.rpartition(":")
        if not sep or not host or not volume:
            sys.exit("--remote must look like user@host:volume, got " + remote)
        self.where = "{}:{} -> {}".format(host, volume, rel_dir)
        target = "/dst/" + rel_dir
        script = " && ".join(filter(None, [
            "mkdir -p {}".format(shlex.quote(target)),
            # Report what --clean removes, like the local store does.
            'echo "Removed $(find {0} -maxdepth 1 -type f | wc -l) picture(s) from a previous load" >&2'
            " && find {0} -maxdepth 1 -type f -delete".format(shlex.quote(target)) if clean else None,
            "tar -x -C /dst",
            # Hand everything to the volume owner (the app user), so the app can replace pictures later.
            "chown -R $(stat -c %u:%g /dst) /dst/{}".format(shlex.quote(rel_dir.split("/")[0])),
        ]))
        docker = "docker run --rm -i -v {}:/dst {} sh -ec {}".format(
            shlex.quote(volume), REMOTE_IMAGE, shlex.quote(script))
        self.proc = subprocess.Popen(["ssh", "-o", "BatchMode=yes", "-o", "ServerAliveInterval=30", host, docker],
                                     stdin=subprocess.PIPE)
        self.tar = tarfile.open(fileobj=self.proc.stdin, mode="w|")
        self.now = int(time.time())

    def write(self, rel_path, content):
        info = tarfile.TarInfo(rel_path)
        info.size, info.mode, info.mtime = len(content), 0o644, self.now
        self.tar.addfile(info, io.BytesIO(content))

    def close(self):
        self.tar.close()
        self.proc.stdin.close()
        if self.proc.wait() != 0:
            sys.exit("Remote copy failed (exit {}) — no picture paths from the last batch were saved".format(self.proc.returncode))


def sanitize(code):
    # Same rule as ItemImageController.sanitizeFilename.
    return re.sub(r"[^a-zA-Z0-9\-_]", "_", code)


def encode(data):
    """(bytes, extension) as Norbiz should store the picture, or None when it isn't an image we handle."""
    if data[:3] == b"\xff\xd8\xff":
        return data, ".jpg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return data, ".png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return data, ".gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return data, ".webp"
    if data[:2] == b"BM":
        out = io.BytesIO()
        Image.open(io.BytesIO(data)).save(out, format="PNG", optimize=True)
        return out.getvalue(), ".png"
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--upload-dir", default=os.environ.get("ITEM_IMAGE_UPLOAD_DIR", os.path.expanduser("~/norbiz")))
    ap.add_argument("--remote", default=os.environ.get("ITEM_IMAGE_REMOTE") or None)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--clean", action="store_true")
    args = ap.parse_args()

    dsn = os.environ.get("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")
    started = time.time()
    # The Postgres connection sits idle while pictures stream from SQL Server; keepalives stop a
    # firewall/NAT between here and a remote server from dropping it ("Operation timed out").
    with psycopg.connect(dsn, keepalives=1, keepalives_idle=30, keepalives_interval=10,
                         keepalives_count=6) as pg, mssql_connect() as ms:
        company_id = pg.execute("SELECT company_id FROM migration.ctx").fetchone()[0]
        codes = dict(pg.execute("SELECT id, item_code FROM items WHERE company_id = %s", (company_id,)).fetchall())
        rel_dir = "{}/items/images".format(company_id)
        if args.remote:
            store = RemoteStore(args.remote, rel_dir, args.clean)
        else:
            store = LocalStore(args.upload_dir, rel_dir, args.clean)
        if args.clean:  # the removed files' paths, so an interrupted run leaves none dangling
            pg.execute("UPDATE items SET image_path = NULL WHERE company_id = %s AND image_path LIKE %s",
                       (company_id, rel_dir + "/%"))
            pg.commit()
        print("Writing item pictures to " + store.where, flush=True)

        cur = ms.cursor()
        cur.execute("SET TEXTSIZE 2147483647")  # FreeTDS otherwise truncates image columns
        cur.execute("SELECT {}ID, Picture FROM dbo.tblItems WHERE Picture IS NOT NULL ORDER BY ID".format(
            "TOP {} ".format(args.limit) if args.limit else ""))

        used, updates, issues = set(), [], []
        written = converted = total_bytes = 0
        while True:
            rows = cur.fetchmany(BATCH)
            if not rows:
                break
            for item_id, data in rows:
                code = codes.get(item_id)
                if code is None or not data:
                    continue
                try:
                    encoded = encode(bytes(data))
                except Exception as e:  # unreadable bitmap
                    issues.append(("skipped", "ItemImage", item_id, "Unreadable picture: {}".format(e)[:255]))
                    continue
                if encoded is None:
                    issues.append(("skipped", "ItemImage", item_id, "Unsupported picture format (first bytes {})".format(bytes(data[:4]).hex())))
                    continue
                content, ext = encoded
                name = sanitize(code)
                if name.lower() in used:  # different codes can sanitize to the same file name
                    name = "{}-{}".format(name, item_id)
                used.add(name.lower())
                filename = name + ext
                rel_path = "{}/{}".format(rel_dir, filename)
                store.write(rel_path, content)
                updates.append((rel_path, item_id))
                written += 1
                converted += ext == ".png" and bytes(data[:2]) == b"BM"
                total_bytes += len(content)
            if len(updates) >= 5000:
                pg.cursor().executemany("UPDATE items SET image_path = %s WHERE id = %s", updates)
                pg.commit()
                updates = []
                print("  {:,} pictures ({:,.0f} MB)".format(written, total_bytes / 1048576), flush=True)

        store.close()
        pg.cursor().executemany("UPDATE items SET image_path = %s WHERE id = %s", updates)
        pg.cursor().executemany("INSERT INTO migration.issues (kind, entity, legacy_id, detail) VALUES (%s,%s,%s,%s)", issues)
        pg.commit()
    print("Done: {:,} pictures ({:,} bitmaps converted to PNG), {:,.0f} MB, {} skipped, {:.0f}s".format(
        written, converted, total_bytes / 1048576, len(issues), time.time() - started))


if __name__ == "__main__":
    sys.exit(main())
