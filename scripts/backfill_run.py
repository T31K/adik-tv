#!/usr/bin/env python3
"""Drive backfill_links over the live feed and collect ranked magnet lists.

Reads the feed, and for every item NOT already flat on the drive (or ALL with
--all), fetches the top-N PirateBay magnets. Writes results JSON to the path in
argv[1]. Read-only against apibay + the feed; writes no DB.
"""
import sys, json, re, urllib.request
import backfill_links as bl

FEED = "https://api.kaleidoscopical.com/megaflix/feed.json?k=mgfx_7b2cedc4b5f053072628a02e152d8a87&limit=500"
N = 5

def year_of(item):
    m = re.search(r"-(\d{4})$", item["id"])
    if m:
        return m.group(1)
    m = re.search(r"_(\d{4})\.mp4$", item.get("path", ""))
    return m.group(1) if m else ""

def main():
    out_path = sys.argv[1]
    only_missing = "--all" not in sys.argv
    flat = set()
    if only_missing and len(sys.argv) > 2 and sys.argv[2] not in ("--all",):
        flat = set(l.strip() for l in open(sys.argv[2]) if l.strip())
    feed = json.loads(bl.curate.get(FEED))  # curate.get sends a browser UA (feed 403s the default urllib UA)
    items = feed["items"]
    targets = [it for it in items if not (only_missing and it.get("path") in flat)]
    results = []
    for i, it in enumerate(targets, 1):
        title, year = it["title"], year_of(it)
        try:
            cands = bl.top_n(title, year, N)
            links = [bl.build_magnet(n, h) for (_s, _z, n, h) in cands]
            rec = {
                "id": it["id"], "title": title, "year": year, "path": it.get("path"),
                "links": links,
                "seeders": [s for (s, _z, _n, _h) in cands],
                "sizes_gb": [round(z / 1e9, 2) for (_s, z, _n, _h) in cands],
                "releases": [n for (_s, _z, n, _h) in cands],
                "error": None if links else "no clean release",
            }
        except Exception as e:
            rec = {"id": it["id"], "title": title, "year": year, "path": it.get("path"),
                   "links": [], "seeders": [], "sizes_gb": [], "releases": [], "error": str(e)}
        results.append(rec)
        top = rec["seeders"][0] if rec["seeders"] else "-"
        sys.stderr.write(f"[{i}/{len(targets)}] {it['id']:42} top_seeders={top} n={len(rec['links'])} {rec['error'] or ''}\n")
        sys.stderr.flush()
    json.dump(results, open(out_path, "w"), ensure_ascii=False, indent=1)
    ok = sum(1 for r in results if r["links"])
    sys.stderr.write(f"\nDONE: {ok}/{len(results)} items got magnets -> {out_path}\n")

if __name__ == "__main__":
    main()
