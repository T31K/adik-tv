# Megaflix Content Pipeline — Design Spec

**Date:** 2026-09-19
**Status:** Approved design, pre-implementation
**Repos touched:** `~/Projects/main/main-server` (feed API), `~/Projects/megaflix-tv2` (TV app)

## One-line summary

Claude Code on the Mac curates titles → rows in Postgres → `api.kaleidoscopical.com/megaflix/*`
serves them → the TV app converges the USB hard drive to match the feed (downloads via an
embedded torrent engine) → the library updates itself.

## Core principle: the table is the desired state of the drive

`megaflix_items` is **not** an append-only log of "things to download." It is a declarative
description of what the drive *should* contain. On every sync the TV converges toward it:

- Row present, files missing → download it.
- Row deleted → delete those files, free the space.
- Row present, files already there → mark ready, download nothing.

All intelligence (what to add, torrent quality, capacity management, deletions) lives with the
**curator** (Claude on the Mac). The **TV is a dumb terminal** that mirrors the feed.

---

## 1. Database — Postgres (Coolify, same VPS)

Single table, accessed by the Mac via the `db` shell function (SSH tunnel → psql on the Coolify
Postgres), and read by main-server via the existing `config/db.js` pool.

```sql
CREATE TABLE megaflix_items (
  id          TEXT PRIMARY KEY,      -- stable slug: 'dune-part-two-2024'
  type        TEXT NOT NULL,         -- 'movie' | 'series'
  tmdb_id     INTEGER NOT NULL,      -- powers all app art/detail pages
  season      INTEGER,               -- series season packs only
  title       TEXT NOT NULL,
  description TEXT,
  link        TEXT NOT NULL,         -- magnet link
  path        TEXT NOT NULL,         -- destination FOLDER on drive
  size_bytes  BIGINT,                -- for capacity math on the curator side
  added_at    TIMESTAMPTZ DEFAULT now()
);
```

Notes:
- `tmdb_id` is the linchpin — the app's whole UI is keyed by TMDB id, so posters/backdrops/cast/
  seasons come for free. Title/description are fallback + human readability.
- `path` is a **folder**, not a filename — a torrent brings its own internal filenames. After
  download the app records which video file(s) actually landed there.
- Self-caps at ~300 rows for a 1TB drive (deletes make room for adds); paging supported anyway.
- Capacity target: **300 movies max** for 1TB (1080p ≈ 2–4 GB each → ~250–350 fit). Sizing
  math uses `SUM(size_bytes)` against a ~950 GB ceiling.

---

## 2. main-server — `routes/megaflix.js` (read-only)

Mounted under `/megaflix` like the other project routes. Deploys via push to `main` (Coolify
auto-pulls). No HTTP writes, no cron, no file cache.

### `GET /megaflix/rev?k=TOKEN`
Cheap change-detector. Returns `COUNT(*)` + `MAX(added_at)`:
```json
{ "rev": "203:2026-09-19T02:11:33Z" }
```
~50 bytes, negligible DB cost. The TV polls this every ~60s while foregrounded; only fetches the
full feed when `rev` changes.

### `GET /megaflix/feed.json?k=TOKEN&limit=100&offset=0`
```json
{ "total": 203, "offset": 0, "limit": 100,
  "items": [
    { "id": "dune-part-two-2024", "type": "movie", "tmdbId": 693134,
      "title": "Dune: Part Two", "description": "Paul Atreides unites...",
      "link": "magnet:?xt=...", "path": "Megaflix/Movies/Dune Part Two (2024)",
      "sizeBytes": 4200000000 } ] }
```
`SELECT ... ORDER BY added_at DESC LIMIT/OFFSET`. Wrong/missing `k` token → 404 (hides the magnet
list from public URL scanners). Token is a static string baked into the app.

*Later, if ever needed:* in-memory last-good cache in the route to survive a DB restart. Not v1.

---

## 3. Curator — Claude Code on the Mac

The **writer**. No server-side agent exists; rows get in only via the `db` tunnel.

**"add X":**
1. Resolve TMDB id + description.
2. Find a magnet passing the compatibility gate (below).
3. Compose `path` (`Megaflix/Movies/<Title> (<Year>)` or `Megaflix/TV/<Show>/Season NN`).
4. Check `SUM(size_bytes)` fits ~950 GB; if not, warn and suggest evictions.
5. `INSERT`. Live on the feed instantly; TV notices within ~60s via `/rev`.

**"remove X" / "make room":** `DELETE` rows → TV deletes those folders next sync.

**Compatibility gate** (video must hardware-decode on the TCL; audio is covered by the app's
bundled ffmpeg software decoder):
```
ACCEPT: 1080p WEB-DL/BluRay · H.264 or HEVC · mkv/mp4 · 2–6 GB · healthy seeders
REJECT: AV1 · CAM/TS · AVI/XviD · 4K remuxes (space)
AUDIO:  any (DTS/TrueHD/etc. handled in software by media3-ffmpeg-decoder)
```
No compatible release available → tell the user, don't add a dud. Bad file that slips through →
row-swap a different release (delete + re-insert), no transcoding anywhere.

*Later:* wrap as a `/megaflix-add` skill for one-command adds.

---

## 4. TV app (megaflix-tv2) — new `sync/` module

### 4a. SyncManager
Triggered on app launch, on the **"Sync now"** button in Settings, and by the **60s foreground
poller** (only pulls the feed when `/rev` changed). Convergence loop, diffing feed against a local
Room table (`id → status`):

1. New id, **files already exist at `path`** → mark `READY`, no download. (Makes a pre-seeded
   drive instant; survives reinstalls / drive swaps.)
2. New id, no files → enqueue → status `COMING_SOON` then `DOWNLOADING`.
3. Local `READY`/`DOWNLOADING` item whose id vanished from the feed → delete its folder, free space.

### 4b. TorrentService — foreground service + **libtorrent4j**
- libtorrent4j (native lib, ~15 MB, arm32+arm64) — the libtorrent engine, embedded; no external app.
- Magnet → DHT peer discovery → fetch metadata → download pieces from many peers, hash-verified →
  write into `<drive>/<path>`.
- **One item at a time**, optional speed cap so downloads don't stutter playback.
- **Resume data** persisted every few seconds → power-off resumes from where it stopped.
- **Seeding disabled** — stop torrent on completion (no uploading from the TV).
- Magnet with no seeders → stays queued, visible, non-fatal.

### 4c. Storage
- One-time **"All files access"** permission grant on the TV (standard for sideloaded apps).
- Auto-detect first removable USB volume as the drive root.
- Drive unplugged → sync pauses with a clear message.

### 4d. Catalog integration — feed IS the catalog
Every feed item appears in the library **immediately** with full TMDB art (art needs no file).
Status overlay drives the card + detail page:
- **READY** (file on drive) → normal card, Play works. Registers `tmdbId → file path(s)` into the
  existing local catalog (already TMDB-keyed). Season packs: parse `S02E05` filenames → episodes.
- **DOWNLOADING** → progress badge on card; detail page shows "Downloading… 43%" instead of Play.
- **COMING_SOON** → "COMING SOON" badge; detail page browsable; Play replaced by a label.

### 4e. Error handling
- Feed unreachable → skip silently, retry next launch/poll.
- Drive full → item stays queued; status list explains why.
- Corrupt/unplayable file → user reports → curator row-swap.

---

## 5. Build phases

| Phase | What | Where | Ships as |
|---|---|---|---|
| A | `megaflix_items` table + `/megaflix/feed.json` + `/megaflix/rev` + token; verify via curl | main-server | push to main |
| B | Curator flow: add 2–3 real movies via `db`, confirm on the feed | Mac | working session |
| C | SyncManager + Room state store + 60s poller + pre-existing-files rule + catalog/status overlay; test on emulator with legal torrents (Sintel/Big Buck Bunny) | app | v0.1.4 |
| D | TorrentService (libtorrent4j) + foreground service + Coming Soon/Downloading badges + declarative delete | app | v0.1.4 (with C) |
| E | First real seed: torrent ~200 movies on the Mac → drive → register rows → plug into TV → all appear instantly | operations | payoff |
| Later | nightly WorkManager auto-sync · in-route memory cache · discovery agent · `/megaflix-add` skill | — | if wanted |

## 6. Risks

- **libtorrent4j ABI** on the TCL — ship arm32 + arm64 (standard); verify on device in Phase D.
- **"All files access" UX** on TCL settings — one-time; provide exact click path.
- **Torrent speed on TV Wi-Fi** — mitigated: bulk via Mac pre-seed (Phase E), TV only trickles new adds.
- **HEVC 10-bit** on the specific panel — almost certainly fine on TCL; optional quick clip test.

## 7. Out of scope (v1)

Server-side write API; automatic discovery/curation; transcoding; nightly TV cron; multi-drive;
uploading/seeding.
