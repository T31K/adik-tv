# ADIK TV — Content Curation Playbook

How Claude (on the Mac) adds a movie/episode to the feed so the TV downloads it.
Loop: **search → pick a good torrent → INSERT into Postgres → TV auto-downloads (poller ~60s).**

## 1. Search (torrent-search-mcp CLI)

```bash
INCLUDE_LINKS=true uvx torrent-search-mcp --mode cli "<Title> <Year> 1080p"
```

Output lines: `<id> (seeders|leechers|size) - <name>`. The magnet(s) print as `Result: magnet:?...`.
Sources: ThePirateBay(apibay), 1337x, YTS/YIFY, Nyaa, EZTV, FitGirl, SubsPlease, UIndex.

## 2. Selection rules (the important part)

**Goal: fast download + small file. Most seeders, but ~1–2 GB, never the 12 GB remuxes.**

Rank candidates by:
1. **Seeders — high.** More seeders = faster on the TV. Prefer 100+ seeders; 20+ is fine.
2. **Size ~1–2 GB (max ~3 GB).** A 1080p movie should be 1.5–2.5 GB. If it's 8–15 GB it's a REMUX/BluRay-untouched — **skip it.**
   - apibay results show size as `N/A` — judge by the **release name** instead (below).
   - YTS/YIFY results *do* show size and are reliably ~1.5–2.5 GB.

**Prefer these release types (small + compatible):**
- `YIFY` / `YTS` (≈2 GB, always 1080p, huge seeders) — usually the best pick.
- `GalaxyRG`, `RARBG`, `x264 WEBRip/WEB-DL ~2GB`, `x265/HEVC 10bit` (even smaller).

**AVOID:**
- `REMUX`, `BluRay AVC/VC-1 untouched`, `2160p` / `4K` / `UHD` → 15–60 GB. Too big.
- `TrueHD` / `Atmos` / `DTS-HD MA` heavy audio packs → often 8–20 GB.
- `CAM`, `TS`, `HDTS`, `HD-TS`, `TELESYNC`, `SCREENER` → bad quality (a camcorder rip). **Never.**
- `HDR`/`DoVi` only matters for 4K; on 1080p it's fine but usually bundled with big files.

**Codec compatibility (TCL):** H.264 and H.265/HEVC both play. Any audio is fine (the app bundles an ffmpeg software decoder). So the only hard rejects are AV1 (rare at 1080p) and the CAM/TS junk above.

**Rule of thumb:** search `"<Title> <Year> 1080p"`, and pick the **YIFY/YTS** result if present (small + most seeders); otherwise the highest-seeded result whose name is **not** REMUX/2160p/CAM and looks ~2 GB (x264/x265 WEB-DL/WEBRip/BluRay).

## 3. Get the magnet

```bash
INCLUDE_LINKS=true uvx torrent-search-mcp --mode cli "<Title> <Year> 1080p" 2>/dev/null | grep -i "magnet:"
```
Copy the magnet for the chosen release.

## 4. Insert into the feed (Postgres via `db`)

Filename convention (flat, lowercase, underscores):
- Movie: `slug(title)_year.mp4` → `dune_part_two_2024.mp4`
- Episode: `slug(show)_s{season}e{episode}.mp4` → `game_of_thrones_s6e1.mp4`

```bash
db -c "INSERT INTO megaflix_items (id, type, tmdb_id, title, description, link, path, size_bytes) VALUES (
  '<slug-id>', 'movie', <tmdbId>, '<Title>', '<overview>',
  '<magnet>', '<slug_title_year>.mp4', <approxBytes>)
ON CONFLICT (id) DO UPDATE SET link=EXCLUDED.link, path=EXCLUDED.path;"
```
- `tmdb_id`: the movie/show's TMDB id (drives posters/detail).
- `type`: `movie` or `series` (series = one row per episode with `season`+`episode`).
- `size_bytes`: rough (for capacity math); fine to estimate ~2000000000 for a 2 GB movie.

## 5. TV downloads it automatically

- The app polls `/megaflix/rev` every ~60s while open → sees the new row → foreground download service torrents it into `/storage/<usb>/Megaflix/<filename>` via libtorrent4j → flips the card to Ready → plays off the drive.
- No TV interaction needed beyond having ADIK open.

## Capacity

- Drive is **FAT32** (`ADIK`), so keep each file **under 4 GB** (FAT32 hard limit) — which aligns with the 1–2 GB rule anyway.
- ~300 movies fit on 1 TB at ~2 GB each; the feed self-caps as you add/remove.
- To free space / remove: `DELETE FROM megaflix_items WHERE id='<slug-id>';` → TV deletes the file on next sync.

## Quick recipe (what Claude runs for "add X")

1. `INCLUDE_LINKS=true uvx torrent-search-mcp --mode cli "X 2024 1080p"` → read seeders + names.
2. Pick YIFY/YTS or the highest-seeded ~2 GB non-REMUX/non-CAM release.
3. `... | grep magnet:` → grab that release's magnet.
4. Look up the TMDB id.
5. `db -c "INSERT ..."` with the flat filename.
6. Tell the user; the TV picks it up within ~60s.
