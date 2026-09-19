# ADIK TV — Project Status & Architecture

_Last updated: 2026-09-19_

ADIK TV is a family media-streaming app for Android TV (a fork of ARVIO, Kotlin + Compose).
It shows a movie library on the TV, downloads the movies onto a connected USB drive via
BitTorrent, and plays them locally. Curation happens on the Mac; the TV is a dumb terminal
that mirrors a server-side feed.

Wordplay: mom's app is "Abang TV" (big brother); this is the "adik" (younger brother).

---

## 1. The system (one line)

**Claude on the Mac curates → Postgres holds the desired library → api.kaleidoscopical.com
serves it → the TV downloads new items onto the USB drive → plays them off the drive.**

```
Mac (curate)            main-server (Coolify)              TCL TV + USB drive
  torrent-search-mcp      Postgres: megaflix_items          ADIK TV app (com.adik.tv)
  scripts/curate.py  ──►  megaflix_rows            ──GET──► polls feed/rev/rows (~60s)
  INSERT via `db`         routes/megaflix.js                foreground svc → libtorrent4j
                          GET /feed.json /rev /rows         → /storage/<usb>/Megaflix/<file>
                                                            → TMDB (by tmdb_id) for art/detail
                                                            → plays file:// off the drive
```

Two APIs power the TV: **our feed** (the list: id, tmdb_id, filename, magnet) and **TMDB**
(posters, backdrops, overview, cast, genres — keyed by each item's `tmdb_id`).

---

## 2. Backend (`~/Projects/main/main-server`, repo `T31K/harmonize-server`, branch `master`, Coolify auto-deploy on push)

Postgres (Coolify, same VPS; access from the Mac via the `db` shell function → SSH tunnel → psql).

**Tables**
- `megaflix_items` — the library / desired-state of the drive:
  `id (slug PK), type (movie|series), tmdb_id, season, episode, title, description, link (magnet), path (flat filename), size_bytes, added_at`
- `megaflix_rows` — dynamic home category rows: `id, title, sort_order`
  (row id encodes the filter: `cat_new` = recent, `cat_all`, `cat_g_<tmdbGenreId>` = genre)

**Route** `routes/megaflix.js` (mounted `/megaflix`, read-only, token-gated → bad token = 404):
- `GET /megaflix/feed.json?k=TOKEN&limit&offset` → `{ total, offset, limit, items[] }`
- `GET /megaflix/rev?k=TOKEN` → `{ rev }` (COUNT + MAX(added_at); TV polls this ~60s)
- `GET /megaflix/rows?k=TOKEN` → `{ rows[] }` (ordered category rows)

**Token:** `mgfx_7b2cedc4b5f053072628a02e152d8a87` (baked into the app; overridable via Coolify env `MEGAFLIX_TOKEN`).

---

## 3. Curator (Mac)

- **torrent-search-mcp** installed: `INCLUDE_LINKS=true uvx torrent-search-mcp --mode cli "<title> <year> 1080p"` (aggregates apibay/YTS/1337x…).
- **`scripts/curate.py`** — picks the best release per movie: apibay q.php (browser UA + retry),
  filter to 1080p (720p fallback) under 4 GB, avoid CAM/TELESYNC/REMUX/2160p/AV1, rank by seeders;
  TMDB lookup for id + overview + genres; builds the magnet + flat filename; emits JSON.
- **`scripts/feed_tool.py`** — `show`/`json`/`sql` over a curated jsonl (review + generate INSERTs).
- **`scripts/movies.txt`** — the title|year list.
- **Playbook:** `docs/curation.md` (selection rules, filename convention, INSERT template).

**Filename convention (flat):** movies `slug(title)_year.mp4`; episodes `slug(show)_s{season}e{episode}.mp4`.

**Add/remove:** `INSERT` a row → TV downloads it (~60s). `DELETE` a row → TV deletes the file next sync.

> NOTE: Bash/tool output is not reliably visible to the human — paste results directly in chat.

---

## 4. App (`~/Projects/AdikTV`, repo `T31K/adik-tv`, branch `main`, remote `adik`)

- **applicationId** `com.adik.tv`; internal package stays `com.arflix.tv` (+ `com.arflix.tv.megaflix`). Internal names (endpoint `/megaflix`, table `megaflix_items`, `DriveManager.MEDIA_DIR="Megaflix"`) intentionally kept — invisible to users.
- **Flat-file model:** feed `path` is a bare filename in a single `Megaflix/` folder on the drive.
- **Series:** one feed row per episode (shared `tmdb_id` + season/episode); grouped into one show card; each episode plays its own flat file (resolved via `MegaflixLibraryBuilder.cachedEpisodePath`).
- **Local playback:** files are handed to the player as **`file://` URIs** (a bare path gets `https://` prepended by the stream resolver → network error).

**`com.arflix.tv.megaflix` package**
- `MegaflixFeedApi` / `FeedModels` — Retrofit + Gson (provider in `di/AppModule`).
- `DownloadStateStore` — per-item state (DataStore `megaflix_downloads_prefs` + Gson).
- `DriveManager` — scans `/storage/*`, `/mnt/media_rw/*`, external volume roots for the `Megaflix/` folder (USB mount varies on TV); resolves flat filenames.
- `SyncPlanner` (pure convergence) + `MegaflixSyncManager` (paged fetch, converge, declarative delete, rev poll, starts the download service).
- `TorrentEngine` — libtorrent4j `SessionManager`; downloads a magnet, reports progress, returns the video file.
- `MegaflixDownloadManager` — one-at-a-time queue; **downloads into per-item temp `Megaflix/.dl_<id>/`** then moves the file out to the flat name (prevents scrambling).
- `DownloadService` — foreground service (notification) that drains the queue.
- `MegaflixLibraryBuilder` — builds `MediaItem`s from the feed via TMDB (posters/genres); caches the movie list by feed reference; `cachedMoviePath`/`cachedEpisodePath` restore localUri on the detail screen.
- `StoragePermission` — All-files access (MANAGE_EXTERNAL_STORAGE), requested on launch until granted.

**Home = category rows** (dynamic from `/megaflix/rows`): `CatalogRepository` fetches rows (cached, fallback to defaults); `MediaRepository.loadLocalCatalog` maps the row id to a filter (recent / all / TMDB genre). A movie appears in each genre row it belongs to.

**UI sounds:** `res/raw/nav_hover.mp3` (focus) + `nav_select.mp3` (click) via `util/NavSound.kt` (SoundPool, init in `ArflixApplication.onCreate`, hooked in `ui/skin/ArvioFocus.kt`).

**Branding:** app name "ADIK TV"; icon (mipmaps), banner (`app_banner`), splash + profile logo from `res/drawable-nodpi/adik_logo.png`.

---

## 5. Drive

- Toshiba 500 GB USB, **exFAT failed on the TCL** ("corrupt") → reformatted **FAT32 (MBR), volume `ADIK`** (`diskutil eraseDisk FAT32 ADIK MBR /dev/disk10`). FAT32 = universal TV compat; **4 GB per-file limit** → curation keeps releases ≤~3 GB (aligns with the 1–2 GB target).
- Single flat folder: `/<drive>/Megaflix/`. On the TCL it mounts at `/storage/8764-1816/Megaflix/`.
- ~230 movies fit at ~2 GB each; the feed self-caps as items are added/removed.
- Bulk seeding option: torrent on the Mac with `aria2` straight to the drive, then plug into the TV (files show READY without the TV downloading). Currently the TV downloads them itself.

---

## 6. Versions (repo `T31K/adik-tv`, OTA via in-app "Check for update"; download page t31k.github.io/adik-tv → `adik-tv.apk`)

- **v0.1.0** — first ADIK build (rebrand from Megaflix; appId com.adik.tv).
- **v0.1.1** — robust USB drive detection (scan all mount points).
- **v0.1.2** — local playback fix (`file://` URIs) — **playback works on real TV.**
- **v0.1.3** — R8 keep-rules for libtorrent4j (fixed download crash) — **downloads work on real TV;** splash/profile logos.
- **v0.1.4** — dynamic category rows, TV in nav, no "View all", playback spinner, cast not clickable, hover/select sounds; **fix: per-item download folders (no more scrambled filenames).**

(Predecessor Megaflix builds shipped from `T31K/megaflix-tv` as com.megaflix.tv2 — now superseded.)

---

## 7. Real-device debugging (adb over network)

- TV: Settings → System → About → click build 7× → Developer options → **USB debugging ON** (Wireless debugging greys out on Ethernet; plain USB debugging still exposes adb on `:5555`).
- `adb connect 192.168.100.12:5555` (same LAN), tap **Allow** on the TV prompt.
- Release builds are **not debuggable** (no `run-as`); use `adb logcat` (tags `PlaybackDiag`, `HubFix`, `ExoPlayer`) + `adb shell ls /storage/*/Megaflix/`.

---

## 8. Known gaps / next steps

- **Milestone D verification:** downloading + playback proven on the TCL; keep an eye on the per-item-folder fix under load (many sequential downloads).
- Series: per-episode grouping is built + unit-tested but not yet exercised with real series in the feed.
- Leftover `.dl_<id>` temp folders should be pruned if a download is interrupted (manager deletes on success).
- Optional polish: libtorrent `rename_file` up-front (no temp folder), nightly WorkManager auto-sync, Settings "Sync now" + sound on/off toggles, capacity guard in the curator, Rotten Tomatoes via a baked-in MDBList key (`MdbListApi` already present).
- Bigger content seed (currently ~61 curated; ~230 fit on the drive).

---

## 9. Key paths

- App: `~/Projects/AdikTV` (was `megaflix-tv2`) · specs/plans in `docs/superpowers/`
- Backend: `~/Projects/main/main-server/routes/megaflix.js`
- Curator: `~/Projects/AdikTV/scripts/{curate.py,feed_tool.py,movies.txt}` · `docs/curation.md`
- Drive: `/Volumes/ADIK/Megaflix/` (Mac) · `/storage/8764-1816/Megaflix/` (TV)
