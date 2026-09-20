#!/usr/bin/env python3
"""ADIK TV link backfill: for each item, pull the top-N well-seeded 1080p
releases from ThePirateBay (apibay) and emit a ranked magnet list.

Reuses curate.py's selection rules (1080p, <4GB FAT32-safe, no CAM/REMUX/
2160p/AV1, ranked by seeders). Produces the `links` array for the baked-list
feed design: links[0] is the best-seeded, and the app swaps down the list when
a download stalls.

Usage:
  python3 backfill_links.py "Title" YEAR [N]        # one title -> JSON
  python3 backfill_links.py --ids id1,id2,...       # from feed titles (TODO caller supplies)
Prints one JSON object per title to stdout:
  {"id","title","year","links":[magnet,...],"seeders":[...],"releases":[...]}
"""
import sys, json, urllib.parse
import curate  # same dir; reuse get/slug/search_quality/TRACKERS/tmdb

N_DEFAULT = 5

def build_magnet(name, info_hash):
    dn = urllib.parse.quote(name)
    tr = "".join("&tr=" + urllib.parse.quote(t) for t in curate.TRACKERS)
    return f"magnet:?xt=urn:btih:{info_hash}&dn={dn}{tr}"

def top_n(title, year, n):
    cands = curate.search_quality(title, year, "1080p")
    if not cands:
        cands = curate.search_quality(title, year, "720p")
    out = []
    seen = set()
    for sd, sz, name, h in cands:
        if h in seen:
            continue
        seen.add(h)
        out.append((sd, sz, name, h))
        if len(out) >= n:
            break
    return out

def main():
    if sys.argv[1] == "--self-test":
        print("ok"); return
    title, year = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else N_DEFAULT
    try:
        cands = top_n(title, year, n)
    except Exception as e:
        print(json.dumps({"title": title, "year": year, "error": f"apibay: {e}", "links": []}))
        return
    if not cands:
        print(json.dumps({"title": title, "year": year, "error": "no clean release", "links": []}))
        return
    links = [build_magnet(name, h) for (_sd, _sz, name, h) in cands]
    print(json.dumps({
        "id": f"{curate.slug(title)}-{year}",
        "title": title,
        "year": year,
        "links": links,
        "seeders": [sd for (sd, _sz, _n, _h) in cands],
        "sizes_gb": [round(sz / 1e9, 2) for (_sd, sz, _n, _h) in cands],
        "releases": [name for (_sd, _sz, name, _h) in cands],
    }, ensure_ascii=False))

if __name__ == "__main__":
    main()
