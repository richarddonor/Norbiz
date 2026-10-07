"""Copy legacy item pictures (tblItems.Picture, stored in the database) into Norbiz's item-image
store and point items.image_path at them — the same layout ItemImageController writes:

    <upload dir>/<company id>/items/images/<item code, sanitized><ext>

JPEGs are written as-is. Bitmaps (which Norbiz doesn't serve) are converted to PNG. Anything else
is skipped and logged to migration.issues. Run after migrate.py (items must exist).

Usage:
    .venv/bin/python images.py [--upload-dir DIR] [--limit N] [--clean]

--upload-dir defaults to $ITEM_IMAGE_UPLOAD_DIR, else ~/norbiz (the app's own default); it must be
the directory the Norbiz server reads (app.item-image.upload-dir). --limit copies only the first N
pictures, for a quick rehearsal. --clean first empties the migrated company's image folder, so a
re-run leaves no pictures from a previous load behind (run_migration.sh always passes it).
"""
import argparse
import io
import os
import re
import sys
import time

import psycopg
from PIL import Image

from extract import mssql_connect

BATCH = 200


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
    ap.add_argument("--limit", type=int)
    ap.add_argument("--clean", action="store_true")
    args = ap.parse_args()

    dsn = os.environ.get("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")
    started = time.time()
    with psycopg.connect(dsn) as pg, mssql_connect() as ms:
        company_id = pg.execute("SELECT company_id FROM migration.ctx").fetchone()[0]
        codes = dict(pg.execute("SELECT id, item_code FROM items WHERE company_id = %s", (company_id,)).fetchall())
        rel_dir = "{}/items/images".format(company_id)
        target_dir = os.path.join(args.upload_dir, str(company_id), "items", "images")
        if args.clean and os.path.isdir(target_dir):
            removed = 0
            for name in os.listdir(target_dir):
                path = os.path.join(target_dir, name)
                if os.path.isfile(path):
                    os.remove(path)
                    removed += 1
            print("Removed {:,} picture(s) from a previous load".format(removed), flush=True)
        os.makedirs(target_dir, exist_ok=True)
        print("Writing item pictures to " + target_dir, flush=True)

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
                with open(os.path.join(target_dir, filename), "wb") as f:
                    f.write(content)
                updates.append(("{}/{}".format(rel_dir, filename), item_id))
                written += 1
                converted += ext == ".png" and bytes(data[:2]) == b"BM"
                total_bytes += len(content)
            if len(updates) >= 5000:
                pg.cursor().executemany("UPDATE items SET image_path = %s WHERE id = %s", updates)
                pg.commit()
                updates = []
                print("  {:,} pictures ({:,.0f} MB)".format(written, total_bytes / 1048576), flush=True)

        pg.cursor().executemany("UPDATE items SET image_path = %s WHERE id = %s", updates)
        pg.cursor().executemany("INSERT INTO migration.issues (kind, entity, legacy_id, detail) VALUES (%s,%s,%s,%s)", issues)
        pg.commit()
    print("Done: {:,} pictures ({:,} bitmaps converted to PNG), {:,.0f} MB, {} skipped, {:.0f}s".format(
        written, converted, total_bytes / 1048576, len(issues), time.time() - started))


if __name__ == "__main__":
    sys.exit(main())
