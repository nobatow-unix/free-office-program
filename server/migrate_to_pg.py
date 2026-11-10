#!/usr/bin/env python3
"""
Moves everything from the old SQLite database (one server) into PostgreSQL (the cluster).
Users, chats, messages, groups, channels, publications, balances, sessions — all of it.

    python3 migrate_to_pg.py --sqlite /opt/icq-server/icq_v2.db --pg postgresql://icq:PASS@127.0.0.1:5432/icq

Stop the server first (systemctl stop icq). The PostgreSQL tables are made by server.py itself,
then filled with the SQLite rows; the old SQLite file is not changed (it stays as a backup).
"""
import argparse
import os
import sqlite3
import sys
import time


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sqlite", required=True)
    ap.add_argument("--pg", required=True)
    ap.add_argument("--force", action="store_true", help="PostgreSQL already has data: wipe it and copy again")
    a = ap.parse_args()
    if not os.path.exists(a.sqlite):
        print("SQLite faýly tapylmady:", a.sqlite)
        return 1

    # 1) the schema: exactly what server.py makes on start
    os.environ["ICQ_DB_URL"] = a.pg
    os.environ.setdefault("ICQ_UPLOADS", os.path.join(os.path.dirname(os.path.abspath(a.sqlite)), "uploads"))
    os.environ.setdefault("ICQ_AVATARS", os.path.join(os.path.dirname(os.path.abspath(a.sqlite)), "avatars"))
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import psycopg
    import server  # noqa: F401  (runs init_db() against PostgreSQL)
    import db

    src = sqlite3.connect(a.sqlite)
    src.row_factory = sqlite3.Row
    pg = psycopg.connect(a.pg)
    tables = [r[0] for r in src.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchall()]
    pg_tables = {r[0] for r in pg.execute(
        "SELECT table_name FROM information_schema.tables WHERE table_schema='public'").fetchall()}

    users_pg = pg.execute("SELECT COUNT(*) FROM users").fetchone()[0]
    msgs_pg = pg.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
    if msgs_pg > 0 and not a.force:
        print(f"PostgreSQL-de eýýäm {msgs_pg} habar bar. Täzeden göçürmek üçin --force goş.")
        return 1

    copy = [t for t in tables if t in pg_tables]
    print("==> PostgreSQL arassalanýar:", ", ".join(copy))
    pg.execute("TRUNCATE " + ", ".join(f'"{t}"' for t in copy) + " RESTART IDENTITY")

    total = 0
    for t in copy:
        s_cols = [r["name"] for r in src.execute(f'PRAGMA table_info("{t}")').fetchall()]
        p_info = {r[0]: r[1] for r in pg.execute(
            "SELECT column_name, data_type FROM information_schema.columns WHERE table_name=%s", (t,)).fetchall()}
        nn = {r[0] for r in pg.execute(
            "SELECT column_name FROM information_schema.columns WHERE table_name=%s AND is_nullable='NO'", (t,)).fetchall()}
        cols = [c for c in s_cols if c in p_info]
        if not cols:
            continue
        ints = {c for c in cols if p_info[c] in ("bigint", "integer", "smallint")}

        def fix(col, v):
            if col in ints:
                if v is None:
                    return 0 if col in nn else None
                if isinstance(v, (int, float)):
                    return int(v)
                try:
                    return int(str(v).strip() or 0)
                except ValueError:
                    return 0 if col in nn else None
            if isinstance(v, bytes):
                return v.decode("utf-8", "replace")
            if v is None and col in nn:
                return ""
            return v

        q = (f'INSERT INTO "{t}"(' + ", ".join(f'"{c}"' for c in cols) + ") VALUES("
             + ", ".join(["%s"] * len(cols)) + ") ON CONFLICT DO NOTHING")
        n = 0
        cur = src.execute(f'SELECT {", ".join(chr(34) + c + chr(34) for c in cols)} FROM "{t}"')
        with pg.cursor() as pc:
            while True:
                batch = cur.fetchmany(2000)
                if not batch:
                    break
                pc.executemany(q, [tuple(fix(c, r[i]) for i, c in enumerate(cols)) for r in batch])
                n += len(batch)
        total += n
        print(f"   {t}: {n}")

    # identity counters continue after the copied ids
    for t in sorted(db.SERIAL):
        if t in copy:
            pg.execute(f"SELECT setval(pg_get_serial_sequence('{t}', 'id'), GREATEST((SELECT COALESCE(MAX(id), 0) FROM \"{t}\"), 1))")
    pg.commit()

    # check
    bad = []
    for t in copy:
        a1 = src.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
        b1 = pg.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
        if a1 != b1:
            bad.append(f"{t}: sqlite {a1} / pg {b1}")
    print(f"==> Göçürildi: {total} setir.")
    if bad:
        print("Tapawutlar (gaýtalanýan ýa-da ýalňyş setirler goýulmady):")
        for b in bad:
            print("   ", b)
    else:
        print("Ähli tablisalar deň: hemme zat göçdi.")
    _ = users_pg, time
    return 0


if __name__ == "__main__":
    sys.exit(main())
