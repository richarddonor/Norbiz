"""Point items.image_path back at item pictures already in the image store, without copying them again —
for when images.py's files landed but the items' paths didn't (e.g. the DB was re-migrated with
--skip-images, or a --clean run was interrupted after clearing the paths).

The mapping is rebuilt the way images.py named the files: items in id order (Norbiz item ids are the
legacy tblItems.IDs), each named <sanitized item code><ext>, or <sanitized code>-<id><ext> when an
earlier item already took that name; the extension comes from the file that exists. Doesn't need the
legacy SQL Server. Where several items share a sanitized code and the plain file's owner can't be
proven, the item is reported as ambiguous and left NULL.

Only items whose image_path is NULL are updated; a path set since (e.g. an upload in the app) is kept.

Usage:
    .venv/bin/python backfill_images.py [--remote USER@HOST:VOLUME | --upload-dir DIR] [--apply]

Without --apply it's a dry run that only prints what it would change. Afterwards flush the Redis query
cache (this is a bulk UPDATE, which bypasses cache invalidation) and run the recognition reindex.
"""
import argparse
import os
import shlex
import subprocess
import sys

import psycopg

from images import REMOTE_IMAGE, sanitize


EXTENSIONS = (".jpg", ".png", ".gif", ".webp")  # what images.py writes


def list_files(args, rel_dir):
    if args.remote:
        host, _, volume = args.remote.rpartition(":")
        docker = "docker run --rm -v {}:/v:ro {} ls -1 /v/{}".format(
            shlex.quote(volume), REMOTE_IMAGE, shlex.quote(rel_dir))
        out = subprocess.run(["ssh", "-o", "BatchMode=yes", host, docker], check=True, capture_output=True, text=True)
        return set(out.stdout.splitlines())
    return set(os.listdir(os.path.join(args.upload_dir, rel_dir)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--upload-dir", default=os.environ.get("ITEM_IMAGE_UPLOAD_DIR", os.path.expanduser("~/norbiz")))
    ap.add_argument("--remote", default=os.environ.get("ITEM_IMAGE_REMOTE") or None)
    ap.add_argument("--apply", action="store_true", help="write the paths (default: dry run)")
    args = ap.parse_args()

    dsn = os.environ.get("PG_DSN", "postgresql://norbiz:changeme@localhost:5432/norbiz_mig")
    with psycopg.connect(dsn, keepalives=1, keepalives_idle=30) as pg:
        dbname = pg.execute("SELECT current_database()").fetchone()[0]
        company_id = pg.execute("SELECT company_id FROM migration.ctx").fetchone()[0]
        rel_dir = "{}/items/images".format(company_id)
        items = {row[0]: (row[1], row[2]) for row in pg.execute(
            "SELECT id, item_code, image_path FROM items WHERE company_id = %s", (company_id,))}
        files = list_files(args, rel_dir)
        print("Database {}, company {}: {:,} items ({:,} with image_path); {:,} files in {}".format(
            dbname, company_id, len(items), sum(1 for _, p in items.values() if p), len(files), rel_dir), flush=True)

        # Replay images.py's naming in id order (Norbiz item ids are the legacy tblItems.IDs). An item
        # owns a name only if a file with that stem exists — items without a picture never took one.
        by_stem = {}
        for f in files:
            stem, dot, ext = f.rpartition(".")
            if dot and "." + ext.lower() in EXTENSIONS:
                by_stem.setdefault(stem, []).append(f)
        groups = {}
        for item_id, (code, _) in items.items():
            groups.setdefault(sanitize(code).lower(), []).append(item_id)

        used, updates, matched = set(), [], set()
        kept = ambiguous = 0
        for item_id in sorted(items):
            code, current = items[item_id]
            name = sanitize(code)
            if name.lower() in used:
                name = "{}-{}".format(name, item_id)
            candidates = by_stem.get(name, [])
            if len(candidates) != 1:
                continue
            group = groups[sanitize(code).lower()]
            if name == sanitize(code) and len(group) > 1:
                # Several items share this name; the plain file belongs to the first of them that had a
                # picture, which can't be told apart from "first in id order" without the legacy DB
                # unless every later member's own <name>-<id> file accounts for it.
                later = [i for i in group if i > item_id]
                if any("{}-{}".format(sanitize(items[i][0]), i) not in by_stem for i in later):
                    ambiguous += 1
                    print("  ambiguous: {} (items {})".format(candidates[0], ", ".join(map(str, sorted(group)))))
                    used.add(name.lower())
                    continue
            used.add(name.lower())
            matched.add(candidates[0])
            if current is not None:
                kept += 1
                continue
            updates.append(("{}/{}".format(rel_dir, candidates[0]), item_id))

        unmatched = sorted(files - matched)
        print("Pictures matched to a file: {:,} — {:,} to set, {:,} already have an image_path (kept)".format(
            len(matched), len(updates), kept))
        print("Skipped as ambiguous (left NULL): {:,}".format(ambiguous))
        print("Files not matched to any item: {:,}{}".format(
            len(unmatched), " (e.g. " + ", ".join(unmatched[:5]) + ")" if unmatched else ""))
        for path, item_id in updates[:5]:
            print("  item id={} -> {}".format(item_id, path))

        if not args.apply:
            print("Dry run — nothing written. Re-run with --apply to set {:,} path(s).".format(len(updates)))
            return
        with pg.cursor() as c:
            c.executemany("UPDATE items SET image_path = %s WHERE id = %s AND image_path IS NULL", updates)
        pg.commit()
        print("Set image_path on {:,} item(s). Now flush the Redis query cache and run the recognition reindex.".format(
            len(updates)))


if __name__ == "__main__":
    sys.exit(main())
