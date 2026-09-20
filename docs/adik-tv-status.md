# ADIK TV — Project Status & Architecture

_Last updated: 2026-09-20_

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
  `id (slug PK), type (movie|series), tmdb_id, season, episode, title, description, link (magnet), links (jsonb, ranked alt magnets best-seeded-first), path (flat filename), size_bytes, added_at`
- `megaflix_rows` — dynamic home category rows: `id, title, sort_order`
  (row id encodes the filter: `cat_new` = recent, `cat_all`, `cat_g_<tmdbGenreId>` = genre)

**Route** `routes/megaflix.js` (mounted `/megaflix`, read-only, token-gated → bad token = 404):
- `GET /megaflix/feed.json?k=TOKEN&limit&offset` → `{ total, offset, limit, items[] }` — each item carries `links[]` (falls back to `[link]`).
- `GET /megaflix/rev?k=TOKEN` → `{ rev }` (COUNT + MAX(added_at); TV polls this ~60s. To force a fleet re-sync after editing rows without changing the count, bump: `UPDATE megaflix_items SET added_at = added_at + interval '1 second';`)
- `GET /megaflix/rows?k=TOKEN` → `{ rows[] }` (ordered category rows)
- **Fleet device IDs** (v0.1.8): hardcoded `DEVICES[]` in the route; app sends `d=` next to the token; unknown id → stealth 404.

**Token:** `mgfx_7b2cedc4b5f053072628a02e152d8a87` (baked into the app; overridable via Coolify env `MEGAFLIX_TOKEN`).

---

## 3. Curator (Mac)

- **torrent-search-mcp** installed: `INCLUDE_LINKS=true uvx torrent-search-mcp --mode cli "<title> <year> 1080p"` (aggregates apibay/YTS/1337x…).
- **`scripts/curate.py`** — picks the best release per movie: apibay q.php (browser UA + retry),
  filter to 1080p (720p fallback) under 4 GB, avoid CAM/TELESYNC/REMUX/2160p/AV1, rank by seeders;
  TMDB lookup for id + overview + genres; builds the magnet + flat filename; emits JSON.
- **`scripts/feed_tool.py`** — `show`/`json`/`sql` over a curated jsonl (review + generate INSERTs).
- **`scripts/backfill_links.py` / `backfill_run.py` / `backfill_to_sql.py`** — fill `megaflix_items.links` with the top-N well-seeded 1080p magnets per item (reuses curate.py's rules): `backfill_run.py` sweeps the live feed for not-on-drive items → JSON; `backfill_to_sql.py` emits the `ADD COLUMN links` + per-item `UPDATE`s; apply with `db -f`.
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
- `SyncPlanner` (pure convergence) + `MegaflixSyncManager` (paged fetch, converge, declarative delete, rev poll, starts the download service). SyncPlanner preserves `PAUSED` records (the auto-sync never re-queues a user-paused item).
- `TorrentEngine` — libtorrent4j `SessionManager`; downloads a magnet, reports **live stats** (`TorrentProgress`: %, download rate, bytes done, peers), returns the video file. All handle access is on libtorrent's own alert thread. **`requestPause()`/`requestStop()` are no-ops as of v0.1.10** — see the crash note in §8.
- `MegaflixDownloadManager` — one-at-a-time queue; **downloads into per-item temp `Megaflix/.dl_<id>/`** then finalises the file to the flat name. `finalizeToFlat()` is robust (rename, else copy→fsync→intra-dir swap — a plain `renameTo` silently fails on the USB FUSE/FAT32 mount and used to strand finished files). `recoverStrandedDownloads()` runs at drain start: finalises complete leftovers, prunes orphans. Exposes `activeDownload` (live speed/ETA) + `pause/stop/start` (queued items only; active-item + swap disabled, see §8).
- `DownloadRecord` — per-item state incl. `status` (READY/DOWNLOADING/COMING_SOON/FAILED/**PAUSED**), `progress`, `sizeBytes`, `linkIndex` (which ranked magnet — for the future auto-swap).
- `DownloadService` — foreground service (notification) that drains the queue. A ~60s poller (in `HomeViewModel`) re-kicks it when idle with work pending.
- `MegaflixLibraryBuilder` — builds `MediaItem`s from the feed via TMDB (posters/genres); caches the movie list by feed reference; `cachedMoviePath`/`cachedEpisodePath` restore localUri on the detail screen.
- `StoragePermission` — All-files access (MANAGE_EXTERNAL_STORAGE), requested on launch until granted.

**Settings → Downloads tab** (`ui/screens/settings`, `"downloads"` section): live view of everything downloading — per-item title, % bar, MB/s + ETA + peers — plus queued/paused/failed groups and an on-drive count. OK on a row opens a Start/Pause/Stop sheet. Data is `SettingsUiState.downloads` (records × feed titles × live `activeDownloads`).

**Ranked alternate magnets:** the feed ships `links[]` per item (best-seeded first, from `megaflix_items.links` jsonb); the app downloads `links[linkIndex]`. Populated by `scripts/backfill_*.py` (top-N by seeders from apibay/ThePirateBay). `link` stays = `links[0]` for back-compat.

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
- **v0.1.5** — studio rows (Netflix Originals / Disney & Pixar / HBO / Max) filtered by TMDB production company. Row id `cat_co_<id-id-…>` (dash-separated company ids); `MediaItem.companyIds` from TMDB `production_companies`. Rows only render when they have matching movies.
- **v0.1.6 / v0.1.7** — download background survival + self-heal (v0.1.7); phase-1 dead-code purge (Telegram/TDLib etc., APK 113→73.5 MB).
- **v0.1.8** — family device IDs: 5-digit fleet ids (hardcoded server-side in `routes/megaflix.js`), app sends `d=` on feed calls, id typed in Settings → Accounts. (Committed; first shipped inside the v0.1.10 GitHub release.)
- **v0.1.9** — Settings → Downloads tab (live progress/MB/s/ETA + per-item Start/Pause/Stop, new `PAUSED` state); **fix: downloads that fetched fully but never finalised** (`finalizeToFlat` + `recoverStrandedDownloads`); ranked alternate magnets in the feed (`links[]` + backfill); player seeks snap to nearest keyframe (`SeekParameters.CLOSEST_SYNC`). (Committed; released as v0.1.10.)
- **v0.1.10** — **fix: hard native crash (libtorrent SIGSEGV).** Pausing/stopping/auto-swapping a live torrent called `session.remove()`/`handle.pause()` off libtorrent's alert thread → concurrent native access → SIGSEGV. Stopgap: interrupt of a live torrent is disabled (runs to completion); auto-swap + stall-timeout + parallel are all deferred to the single-thread-native fix (see §8). First public GitHub release of all the above (fleet was on v0.1.7).

(Predecessor Megaflix builds shipped from `T31K/megaflix-tv` as com.megaflix.tv2 — now superseded.)

---

## 7. Real-device debugging (adb over network)

- TV: Settings → System → About → click build 7× → Developer options → **USB debugging ON** (Wireless debugging greys out on Ethernet; plain USB debugging still exposes adb on `:5555`).
- `adb connect 192.168.100.12:5555` (same LAN), tap **Allow** on the TV prompt.
- Release builds are **not debuggable** (no `run-as`); use `adb logcat` (tags `PlaybackDiag`, `HubFix`, `ExoPlayer`) + `adb shell ls /storage/*/Megaflix/`.

---

## 8. Known gaps / next steps

- **THE big one — single-thread libtorrent (unblocks 3 features):** libtorrent4j's native handle methods (`session.remove`, `handle.pause`, `status`) are **not safe to call from any thread but its own alert thread** — doing so races and SIGSEGVs (reproduced on both `remove()` and `pause()`). v0.1.10 works around it by never interrupting a live torrent. The proper fix is to funnel **every** libtorrent call onto one dedicated thread; that single fix re-enables all of:
  1. **Auto-swap** of a stalled magnet to the next `links[]` entry (WIP is `git stash`ed: "parallel-downloads WIP"). The poller (`pollAndHeal`) currently only re-kicks an idle drain — it must NOT swap while interrupts are disabled (doing so re-queued the active item → drain wedged → tab showed "0 downloading").
  2. **Stall-timeout** — until then a genuinely dead torrent wedges the one-at-a-time queue until app relaunch (rare now the backfill made every `links[0]` well-seeded).
  3. **Parallel downloads (2–3 at once)** — the stashed refactor (per-infohash alert dispatch, `activeDownloads` map, MAX_PARALLEL) crashed on the same native race.
- **Pause/Stop of the actively-downloading item** is disabled (queued items work). Comes back with the same fix.
- **Crash residue:** an interrupted/corrupted `.dl_<id>` partial can jam resume (0% CPU, no progress); deleting the temp folder + relaunch clears it. Recovery prunes *complete* leftovers but not corrupt partials — worth a checksum/prune-on-resume pass.
- Series: per-episode grouping is built + unit-tested but not yet exercised with real series in the feed.
- Optional polish: libtorrent `rename_file` up-front (no temp folder), nightly WorkManager auto-sync, Settings "Sync now" + sound on/off toggles, capacity guard in the curator, Rotten Tomatoes via a baked-in MDBList key (`MdbListApi` already present).
- Bigger content seed (currently ~60 curated; ~230 fit on the drive).

---

## 9. Key paths

- App: `~/Projects/AdikTV` (was `megaflix-tv2`) · specs/plans in `docs/superpowers/`
- Backend: `~/Projects/main/main-server/routes/megaflix.js`
- Curator: `~/Projects/AdikTV/scripts/{curate.py,feed_tool.py,movies.txt}` · `docs/curation.md`
- Drive: `/Volumes/ADIK/Megaflix/` (Mac) · `/storage/8764-1816/Megaflix/` (TV)
