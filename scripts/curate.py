#!/usr/bin/env python3
"""ADIK TV curator: pick a good ~2GB 1080p release for a movie and emit a feed INSERT.

Usage:
  python3 curate.py "Title" YEAR
Prints a human summary to stderr and one SQL INSERT line to stdout (or SKIP: ... on failure).
Source: apibay (ThePirateBay API) for the torrent + TMDB for metadata. Flat-file model.
"""
import sys, urllib.request, urllib.parse, json, re

TMDB = "8b6f7e9a19bd57cca4cd213917274d13"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
BAD = re.compile(r'telesync|hdts|hdcam|\bcam\b|\.ts\.|screener|remux|2160|\buhd\b|\bav1\b', re.I)
TRACKERS = [
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.torrent.eu.org:451/announce",
]

import time
def get(u):
    last = None
    for attempt in range(4):
        try:
            return urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA}), timeout=30).read().decode('utf-8', 'ignore')
        except Exception as e:
            last = e; time.sleep(2 + attempt * 2)
    raise last

def slug(s):
    s = re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")
    return re.sub(r"_+", "_", s)

def sql_escape(s):
    return (s or "").replace("'", "''")

def search_quality(title, year, quality):
    q = urllib.parse.quote(f"{title} {year} {quality}")
    data = json.loads(get(f"https://apibay.org/q.php?q={q}"))
    cands = []
    for r in data:
        n = r.get('name', '')
        if r.get('info_hash', '0'*40) == '0'*40:
            continue
        if BAD.search(n) or quality not in n.lower():
            continue
        sz = int(r.get('size', 0)); sd = int(r.get('seeders', 0))
        if sz > 4_000_000_000 or sz < 300_000_000:   # FAT32 <4GB, and skip fakes/samples
            continue
        cands.append((sd, sz, n, r['info_hash']))
    cands.sort(reverse=True)   # most seeders first
    return cands

def pick(title, year):
    # Prefer 1080p; fall back to 720p if nothing clean at 1080p.
    cands = search_quality(title, year, '1080p')
    if not cands:
        cands = search_quality(title, year, '720p')
    return cands[0] if cands else None

def tmdb(title, year):
    d = json.loads(get(f"https://api.themoviedb.org/3/search/movie?api_key={TMDB}&query={urllib.parse.quote(title)}&year={year}"))
    if not d.get('results'):
        d = json.loads(get(f"https://api.themoviedb.org/3/search/movie?api_key={TMDB}&query={urllib.parse.quote(title)}"))
    r = d['results'][0]
    return r['id'], (r.get('overview') or ''), (r.get('release_date') or str(year))[:4]

def main():
    title, year = sys.argv[1], sys.argv[2]
    try:
        tid, overview, ryear = tmdb(title, year)
    except Exception as e:
        print(f"SKIP: {title} ({year}) — tmdb error {e}"); return
    try:
        c = pick(title, year)
    except Exception as e:
        print(f"SKIP: {title} ({year}) — apibay error {e}"); return
    if not c:
        print(f"SKIP: {title} ({year}) — no clean 1080p release under 4GB")
        return
    sd, sz, name, h = c
    fn = f"{slug(title)}_{ryear}.mp4"
    sid = f"{slug(title)}-{ryear}"
    dn = urllib.parse.quote(name)
    tr = "".join("&tr=" + urllib.parse.quote(t) for t in TRACKERS)
    magnet = f"magnet:?xt=urn:btih:{h}&dn={dn}{tr}"
    sys.stderr.write(f"✓ {title} ({ryear}) tmdb={tid} {sz/1e9:.2f}GB {sd}s → {fn}\n")
    print(json.dumps({
        "id": sid, "type": "movie", "tmdb_id": tid, "title": title,
        "path": fn, "size_bytes": sz, "size_gb": round(sz/1e9, 2),
        "seeders": sd, "release": name, "magnet": magnet, "overview": overview[:300],
    }, ensure_ascii=False))

if __name__ == "__main__":
    main()
