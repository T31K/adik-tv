# ADIK TV — Project Status & Architecture

_Last updated: 2026-11-17 (v0.2.0: libVLC player, ARVIO purge, drive-safe downloads, /adik routes)_

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

**Route** `routes/megaflix.js` (mounted at both `/adik` and the legacy `/megaflix`, read-only, token-gated → bad token = 404). **v0.2.0+ apps call `/adik/feed.json`, `/adik/rev`, `/adik/rows`** (`MegaflixFeedApi`, base URL = API host root); older fleet builds still use `/megaflix/...`, so keep both mounts until every TV is on 0.2.0+.
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
- **Local playback:** files are handed to the player as **`file://` URIs**.
- **What's left of ARVIO (v0.2.0 purge):** Home (hero + feed rows + continue watching), Details (TMDB metadata, seasons/episodes, cast, similar, local watchlist toggle), Search (TMDB search filtered to the library), Settings, local Profiles, the GitHub self-updater, crash reporting. Everything else — streams/addons/plugins (Cloudstream/QuickJS), IPTV/live TV/sports/EPG, Trakt/Simkl/MdbList, Supabase cloud sync + login, home server, Discord, Chromecast, launcher channels, the ExoPlayer/Media3 player (Dolby Vision, seek previews, skip-intro, AI subtitles), WorkManager — is deleted. Kotlin went from ~179.5k to ~37k lines. Only the `sideload` flavor exists.

**Player — libVLC (`ui/screens/player/VlcPlayerScreen.kt` + `VlcPlayerViewModel.kt`, `org.videolan.android:libvlc-all:3.6.5`)**
- Route `vlc_player?uri&title&tmdbId&type&season&episode&startMs` (`navigation/VlcPlayerRoute`, built from `LocalPlayRequest`); Details' Play/Resume and episode clicks call it directly.
- Hardware decoding with automatic software fallback (`Media.setHWDecoderEnabled(true, false)`); on `EncounteredError` it retries once with hardware decoding off, then shows "Can't play this video".
- Options from Settings (`util/PlayerPrefs.kt`, device-wide): preferred audio language (default `eng`), subtitles off by default unless a default language is set, subtitle size/colour/background box.
- Remote: OK = play/pause + controls; Left/Right = −/+10 s (30/60/120 s while held); Up = controls; Down = controls, then the audio/subtitle track menu (embedded tracks + Off); Back = close menu → hide controls → exit; media keys (play/pause, FF/RW ±30 s, next episode, stop). Controls auto-hide after 4 s.
- Progress: `data/repository/PlaybackProgressStore.kt` (DataStore `playback_progress_prefs`, profile-scoped, keyed tmdbId + TMDB season/episode). Saved every 3 s while playing, on pause, on exit; ≥90 % = watched. On first use per profile it imports the old `local_continue_watching_v1` list. Home's continue-watching row, Details' resume point / episode progress bars / watched checks all read it.
- Series: at the end of an episode the next downloaded one (e+1, else next season e1, via `MegaflixLibraryBuilder.cachedEpisodePath`) plays in place.
- `player/PlaybackState` (@Singleton `isPlaying`) is true while the player is open and in the foreground (backgrounding pauses playback and clears it).

**`com.arflix.tv.megaflix` package**
- `MegaflixFeedApi` / `FeedModels` — Retrofit + Gson (provider in `di/AppModule`).
- `DownloadStateStore` — per-item state (DataStore `megaflix_downloads_prefs` + Gson).
- `DriveManager` — scans `/storage/*`, `/mnt/media_rw/*`, external volume roots for the `Megaflix/` folder (USB mount varies on TV); resolves flat filenames.
- `SyncPlanner` (pure convergence) + `MegaflixSyncManager` (paged fetch, converge, declarative delete, rev poll, starts the download service). SyncPlanner preserves `PAUSED` records (the auto-sync never re-queues a user-paused item).
- `TorrentEngine` — libtorrent4j `SessionManager`; downloads a magnet, reports **live stats** (`TorrentProgress`: %, download rate, bytes done, peers), returns the video file. All handle access is on libtorrent's own alert thread. **`requestPause()`/`requestStop()` are no-ops as of v0.1.10** — see the crash note in §8.
- `MegaflixDownloadManager` — one-at-a-time queue. **v0.2.0 drive-safe downloads:** a torrent downloads into **internal storage `filesDir/dl/<id>/`** when free space ≥ item size + 1 GB, else into the drive temp `Megaflix/.dl_<id>/` as before (a partial resumes wherever it started). A finished internal download is piece-hash-checked where it sits, recorded as a pending move (`DownloadRecord.stagedPath`, shown as "Moving to drive…" in Settings), and copied to the flat drive name only while nothing plays — a copy cut short by playback restarts afterwards; `recoverStrandedDownloads` also scans `filesDir/dl/*` and resumes pending moves after a restart. While a movie plays: internal downloads keep going, new drive-temp downloads wait, and the library hash check, the hole scan and sync's rename-to-real-extension all wait. Drive-temp downloads still finalise to the flat name and are hash-checked there. `finalizeToFlat()` is robust (rename, else copy→fsync→intra-dir swap — a plain `renameTo` silently fails on the USB FUSE/FAT32 mount and used to strand finished files). `recoverStrandedDownloads()` runs at drain start: finalises complete leftovers, prunes orphans. Exposes `activeDownload` (live speed/ETA) + `pause/stop/start` (queued items only; active-item + swap disabled, see §8).
- `DownloadRecord` — per-item state incl. `status` (READY/DOWNLOADING/COMING_SOON/FAILED/**PAUSED**), `progress`, `sizeBytes`, `linkIndex` (which ranked magnet — for the future auto-swap).
- `DownloadService` — foreground service (notification) that drains the queue. A ~60s poller (in `HomeViewModel`) re-kicks it when idle with work pending.
- `MegaflixLibraryBuilder` — builds `MediaItem`s from the feed via TMDB (posters/genres); caches the movie list by feed reference; `cachedMoviePath`/`cachedEpisodePath` restore localUri on the detail screen.
- `StoragePermission` — All-files access (MANAGE_EXTERNAL_STORAGE), requested on launch until granted.

**Settings** (rewritten in v0.2.0) has six tabs: Accounts (Device ID, Check for update, About), Downloads, Subtitles (VLC style + default subtitle language), Language (app UI + preferred audio), Appearance, Profiles.

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
- **v0.1.11** — **fix: movies stopping partway through playback.** `recoverStrandedDownloads` judged a `.dl_<id>` partial "complete" by file length, but libtorrent allocates files at full size up front — so torrents killed mid-download (the 0.1.9/0.1.10 SIGSEGVs) were finalized with ~35–40% of their pieces missing (zero-filled); ExoPlayer stalls at the first hole. Recovery now never finalizes by size — it re-queues the partial and libtorrent re-checks/finishes it. New `VideoIntegrity` (one 4 KB sample per 16 MB; ≥2 all-zero samples = holes) scans READY files once after Home loads (fingerprints cached in DataStore) and deletes + re-queues broken ones. Dev TV: 22 of 61 movies were broken (Grand Budapest, Mad Max, Coco, Inception, Matrix, Titanic, …).
- **v0.1.12** — **torrent hash check = "healthy" flag.** On TORRENT_FINISHED the alert thread copies the torrent's v1 piece hashes + the video file's offset into a plain `PieceManifest`; after `finalizeToFlat`, `PieceVerifier` re-hashes the FINAL flat file piece-by-piece (pieces straddling sibling files skipped). Full match → fingerprint stored in DataStore `healthy_files`; mismatch → delete + FAILED (retry). One-time library check: when the queue is empty, the drain hash-checks ONE unverified READY file per loop — `TorrentEngine.fetchManifest` (libtorrent4j `fetchMagnet`, metadata only, safe because no download listener is live) tries the record's magnet first then the alternates, matched by exact file size; broken → delete + re-queue on that link; no size match → "uncheckable" until next launch. `pollAndHeal` also kicks the service while unverified files remain. Settings → Downloads shows **Verified N/M**.

- **v0.2.0** (versionCode 15, branch `v0.2-vlc-purge`) — **libVLC replaces ExoPlayer** (plays every container/codec off the drive; hardware decode with software fallback; English audio preferred; subtitles off by default; D-pad controls + track menu; resume + progress in the new local `PlaybackProgressStore`). **ARVIO purge** (~143k Kotlin lines + Media3/Supabase/Cast/Leanback/plugin-stack deps deleted; Settings rewritten to six tabs). **Drive-safe downloads** (internal-storage staging, moves/hash checks/hole scans wait for playback). Feed now on **`/adik/*`** routes.

(Predecessor Megaflix builds shipped from `T31K/megaflix-tv` as com.megaflix.tv2 — now superseded.)

---

## 7. Real-device debugging (adb over network)

- TV: Settings → System → About → click build 7× → Developer options → **USB debugging ON** (Wireless debugging greys out on Ethernet; plain USB debugging still exposes adb on `:5555`).
- `adb connect 192.168.100.12:5555` (same LAN), tap **Allow** on the TV prompt.
- Release builds are **not debuggable** (no `run-as`); use `adb logcat` (tags `VlcPlayer`, `MegaflixDownload`, `MegaflixSync`, `libvlc`) + `adb shell ls /storage/*/Megaflix/`.

---

## 8. Known gaps / next steps

### Built 2026-11-18 in v0.2.2 (not yet on Mom's TV — owner away, no adb)
- Torrent throttled to 512 KB/s (session `downloadRateLimit`) while `PlaybackState.isPlaying`; full speed after.
- `BootReceiver` (BOOT_COMPLETED / QUICKBOOT_POWERON / MY_PACKAGE_REPLACED) starts DownloadService; the service syncs the feed itself on a cold start (10 tries, 30 s apart). Android 15+ blocks dataSync FGS from boot — there it starts on next app open.
- Download diagnostics: `MegaflixDownload status:` log line every 30 s (progress, peers, session rate, DHT nodes, limit, playing, internal/drive) + Settings → Downloads shows "finding peers (N DHT nodes)" / "no network peers yet" / "slowed while watching".
- Pause: VLCVideoLayout can no longer take D-pad focus; every player KeyDown logged as `VlcKeys`.
- Next on Mom's TV: install v0.2.2, read `adb logcat -s MegaflixDownload MegaflixSync MegaflixBoot VlcKeys DownloadService`, then hide the USB popup.

### Next session on Mom's TV (owner turns it on, 2026-11-19+)
1. Install v0.2.3 (adb), read download/pause logs, hide the USB popup.
2. **Standby test for night downloads (screen off):** turn the TV off with the remote, check `adb connect 192.168.100.9:5555` still answers (= SoC asleep, not powered off; look for a "Quick start"/"Network standby" setting if not). If it sleeps: build a nightly `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)` window (~2–5 am) that starts DownloadService with a partial wake lock + Wi-Fi lock, screen stays off, releases when the queue is empty.
3. App: auto-switch a stalled torrent to the next link after ~5 min (single-thread libtorrent funnel) — Eternal Sunshine's dead BONE torrent blocked her whole queue.
4. One-time: update existing feed items' `links` to the re-picked healthy torrents (prod UPDATE — ask first). Picker: live tracker-scrape seeder counts.
5. Drive: the Ugreen 2.5" enclosure browns out at ~40 MB/s sustained writes (3 drops on 2026-11-19 at 3–4 parallel); stable at 2×5 MB/s. Recommend powered USB hub or portable SSD.

### TODO for 2026-11-18 (agreed with owner 2026-11-17)
Mom's TV = Sharp AQUOS TVX22A, `192.168.100.9:5555`, device id `MOM_63052_WS2`, armeabi-v7a, Android 14, only 4 GB app storage (1.9 GB free → internal downloads never fit; everything downloads to the drive). Screensaver + screen timeout disabled via adb (was screensaver_enabled=1, screen_off_timeout=600000).
1. **BUG — downloads don't start on Mom's TV.** v0.2.0: DownloadService runs ("Syncing library…") but no libtorrent threads, ~1 KB/s; first queued item `.dl_eternal-sunshine-…` never progresses. Wi-Fi is fine (RSSI -62, 78 Mbps link; house internet 45 Mbps). v0.2.1 logs download/sync at warn level + `drain: start` — install it and read `adb logcat -s MegaflixDownload MegaflixSync`.
2. **Throttle a running torrent while a movie plays** — session-wide download rate limit ~500 KB/s while `PlaybackState.isPlaying` (settings_pack via the session, not per-handle calls, which SIGSEGV off the alert thread); restore after.
3. **Start downloads on TV boot** — BOOT_COMPLETED receiver that starts sync + DownloadService, so the queue runs without anyone opening ADIK.
4. **Hide the "USB drive connected" popup** on Mom's TV — it's a system notification; find the posting package (`dumpsys notification`) and block that channel via adb.
5. **BUG — Mom "can't pause properly"** (VlcPlayerScreen key handling via root `onPreviewKeyEvent`; OK/ENTER/PLAY_PAUSE → `togglePlayPause`). Suspects: VLCVideoLayout (AndroidView) stealing focus so keys skip Compose; first press only reveals controls / lands mid-buffer; Sharp remote sending an unhandled keycode. Add Log.w for every player KeyDown (keyCode, repeat, focus owner, isPlaying) and reproduce on her TV with `adb shell input keyevent` + her real remote.
6. Promote v0.2.x to the fleet (`gh release edit --prerelease=false --latest`) only after it plays + downloads on Mom's TV.
- Done (in v0.2.1, unreleased to fleet): Netflix-style subtitles on by default, More Like This from the library, VLC read-ahead 1.5 s → 5 s for drive files.

- **BUG (found 2026-11-17) — every file is named `.mp4` regardless of its real container.** `scripts/curate.py:84` hardcodes `fn = f"{slug(title)}_{ryear}.mp4"`, and `MegaflixDownloadManager.finalizeToFlat` copies the torrent's largest video to that feed `path` byte-for-byte (no remux). Result on the `ADIK` drive: **18 of 49 files are Matroska (`1a45dfa3` magic) named `.mp4`** — 28 Years Later, Avatar Aang, Ballerina, Coco, Deadpool & Wolverine, F1, Goodfellas, How to Train Your Dragon (2025), Jurassic World Rebirth, Lilo & Stitch, Oppenheimer, Schindler's List, Sinners, Superman, Fantastic Four, Thunderbolts, Wicked, Wicked For Good. Finder/QuickTime can't preview them (VLC plays them fine). ExoPlayer sniffs content so the TV *should* still play them — the misnaming is cosmetic/confusing, not proven to be the TV playback failures.
  - **v0.1.13 (app side, shipped 2026-11-17):** `VideoContainer` sniffs the real container (EBML → `.mkv`, `ftyp` → `.mp4`); `DriveManager.findPlayableFile` matches feed paths by basename (any video ext); every sync renames misnamed files in place (hash-check/hole-scan flags carried over); new downloads land with their real extension. So changing a feed path's extension no longer triggers a re-download.
  - **Fix needed:** (1) curate.py must take the extension from the release's actual video file; (2) backfill `megaflix_items.path` `.mp4`→`.mkv` for MKV items + rename on drives. **Fleet caveat:** changing a feed `path` makes every other TV (`DriveManager.findPlayableFile` = exact filename) see the movie as missing and re-download it, leaving the old `.mp4` orphaned — ship an app change first (accept the same basename with any video extension and rename it in place) before backfilling paths. (3) alternate `links[]` may differ in container from `links[0]`, so the path extension can go stale after a magnet swap — the app-side tolerant match covers that too.

- **THE big one — single-thread libtorrent (unblocks 3 features):** libtorrent4j's native handle methods (`session.remove`, `handle.pause`, `status`) are **not safe to call from any thread but its own alert thread** — doing so races and SIGSEGVs (reproduced on both `remove()` and `pause()`). v0.1.10 works around it by never interrupting a live torrent. The proper fix is to funnel **every** libtorrent call onto one dedicated thread; that single fix re-enables all of:
  1. **Auto-swap** of a stalled magnet to the next `links[]` entry (WIP is `git stash`ed: "parallel-downloads WIP"). The poller (`pollAndHeal`) currently only re-kicks an idle drain — it must NOT swap while interrupts are disabled (doing so re-queued the active item → drain wedged → tab showed "0 downloading").
  2. **Stall-timeout** — until then a genuinely dead torrent wedges the one-at-a-time queue until app relaunch (rare now the backfill made every `links[0]` well-seeded).
  3. **Parallel downloads (2–3 at once)** — the stashed refactor (per-infohash alert dispatch, `activeDownloads` map, MAX_PARALLEL) crashed on the same native race.
- **Pause/Stop of the actively-downloading item** is disabled (queued items work). Comes back with the same fix.
- **Crash residue:** an interrupted/corrupted `.dl_<id>` partial can jam resume (0% CPU, no progress); deleting the temp folder + relaunch clears it. Recovery prunes *complete* leftovers but not corrupt partials — worth a checksum/prune-on-resume pass.
- Series: per-episode grouping is built + unit-tested but not yet exercised with real series in the feed.
- Optional polish: libtorrent `rename_file` up-front (no temp folder), Settings "Sync now" + sound on/off toggles, capacity guard in the curator. (WorkManager and the MDBList client were removed in v0.2.0.)
- v0.2.0 not yet exercised on a real TV: libVLC playback/track menu/seek feel, internal-storage staging + drive move timing, progress import from the old continue-watching list.
- Bigger content seed (currently ~60 curated; ~230 fit on the drive).

---

## 9. Key paths

- App: `~/Projects/AdikTV` (was `megaflix-tv2`) · specs/plans in `docs/superpowers/`
- Backend: `~/Projects/main/main-server/routes/megaflix.js`
- Curator: `~/Projects/AdikTV/scripts/{curate.py,feed_tool.py,movies.txt}` · `docs/curation.md`
- Drive: `/Volumes/ADIK/Megaflix/` (Mac) · `/storage/8764-1816/Megaflix/` (TV)
