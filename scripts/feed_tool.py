#!/usr/bin/env python3
"""Review or emit SQL for curated rows in /tmp/adik.jsonl.

Usage:
  python3 feed_tool.py show <from> <to>            # compact review table (1-indexed, inclusive)
  python3 feed_tool.py sql  <from> <to> [skipids]  # emit INSERT SQL (skipids = comma-separated ids to drop)
"""
import sys, json

JSONL = "/tmp/adik.jsonl"

def rows():
    out = []
    for l in open(JSONL):
        l = l.strip()
        if not l:
            continue
        try:
            out.append(json.loads(l))
        except Exception:
            out.append({"skip": l})
    return out

def esc(s):
    return (s or "").replace("'", "''")

def main():
    mode, a, b = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    skip = set(sys.argv[4].split(",")) if len(sys.argv) > 4 else set()
    data = rows()[a-1:b]
    if mode == "json":
        print(json.dumps([d for d in data if "skip" not in d], indent=2, ensure_ascii=False))
    elif mode == "show":
        for i, d in enumerate(data, a):
            if "skip" in d:
                print(f"{i:2}. ⚠️  {d['skip']}")
            else:
                print(f"{i:2}. {d['title']:<40} {d['size_gb']:>4}GB {d['seeders']:>5}s  {d['release'][:50]}")
    elif mode == "sql":
        n = 0
        for d in data:
            if "skip" in d or d["id"] in skip:
                continue
            print(
                "INSERT INTO megaflix_items (id,type,tmdb_id,title,description,link,path,size_bytes) VALUES ("
                f"'{d['id']}','movie',{d['tmdb_id']},'{esc(d['title'])}','{esc(d['overview'])}',"
                f"'{d['magnet']}','{d['path']}',{d['size_bytes']}) "
                "ON CONFLICT (id) DO UPDATE SET link=EXCLUDED.link,path=EXCLUDED.path,tmdb_id=EXCLUDED.tmdb_id,size_bytes=EXCLUDED.size_bytes;"
            )
            n += 1
        sys.stderr.write(f"{n} rows\n")

if __name__ == "__main__":
    main()
