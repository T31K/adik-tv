#!/usr/bin/env python3
"""Emit SQL from the backfill JSON: add the links column, then per-item set
links (jsonb) + link (=links[0], best-seeded). Prints to stdout."""
import sys, json

data = json.load(open(sys.argv[1]))
print("ALTER TABLE megaflix_items ADD COLUMN IF NOT EXISTS links jsonb;")
n = 0
for r in data:
    links = r.get("links") or []
    if not links:
        print(f"-- SKIP {r['id']}: {r.get('error')}")
        continue
    arr = json.dumps(links)                      # jsonb literal
    best = links[0].replace("'", "''")           # link = best-seeded
    arr_sql = arr.replace("'", "''")
    print(f"UPDATE megaflix_items SET links='{arr_sql}'::jsonb, link='{best}' WHERE id='{r['id']}';")
    n += 1
print(f"-- {n} items updated")
