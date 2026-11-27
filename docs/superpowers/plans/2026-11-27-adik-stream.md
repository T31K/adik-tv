# ADIK Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Demon Slayer appears in the living-room TV's ADIK library as a normal movie. It streams from R2 as encrypted chunks that only that TV can decrypt, and plays in a new Netflix-layout player.

**Architecture:**
- The Mac encrypts each movie into one R2 object (`ADIKENC1` format, AES-256-GCM per 1 MiB chunk).
- main-server keeps each movie key sealed with a master key. It hands a paired TV that key wrapped with the TV's Keystore RSA key, plus a 6 h presigned URL.
- The app gives stream movies the `localUri` `adikstream://<id>`. The player resolves that through a localhost decrypting proxy that libVLC reads like a normal HTTP file.

**Tech Stack:** Kotlin/Compose/Hilt/Retrofit/OkHttp/libVLC (app) · Node/Express/pg/@aws-sdk + node-forge (server) · Python 3 + `cryptography` + `boto3` via `uv run` (Mac tools).

**Spec:** `docs/superpowers/specs/2026-11-27-adik-stream-r2-encrypted-design.md`

## Global Constraints

- **Cost:** R2 free tier = 10 GB **across the whole account** (currently ≈ 2.4 GB). The uploader must refuse an upload that would cross 10 GB. No Durable Objects, Workers or paid features. The TV reads R2 directly via presigned URLs.
- Format constants:
  - magic `ADIKENC1`, version `1`, header **64 bytes**, `chunk_size` **1,048,576**, GCM tag 16 bytes.
  - nonce = `nonce_prefix(4) || chunk_index(u64 BE)`.
  - AAD = `"ADIK1" || movie_uuid(16) || chunk_index(u64 BE) || is_last(1)`.
  - chunk i starts at offset `64 + i*(chunk_size+16)`.
- Header layout, big-endian:
  - magic(8) · version u16 · chunk_size u32 · plaintext_size u64 · chunk_count u32 · nonce_prefix(4) · movie_uuid(16)
  - then zero padding to 64 bytes.
- R2 bucket `adik-stream`; object key `v/<32 hex chars>`. Titles never go to R2.
- Device key: RSA-3072 in AndroidKeyStore, PURPOSE_DECRYPT, OAEP with **SHA-256 digest + MGF1-SHA1** (`OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT)`). Signing key: EC P-256, SHA256withECDSA.
- `device_id = "S_" + hex(sha256(rsaSpkiDer))[0:16]`.
- `pairing_code = base32(sha256(rsaSpkiDer || ecSpkiDer))[0:8]`, RFC 4648 alphabet with no padding, displayed `XXXX-XXXX`.
- Presigned URL TTL **6 h**. Proxy read-ahead 4–8 chunks; at most ~16 MiB of plaintext in memory; never written to disk.
- Stream movies live only in `adik_stream_movies`, **never** in `megaflix_items`. No badge in the UI.
- Never delete DB rows. Approve/revoke are `UPDATE`s. Ask the owner before pushing main-server `master` (it auto-deploys to prod) and before setting Coolify env.
- Test build only on the living-room TCL `192.168.100.12:5555` via adb. **No GitHub release.** Never touch Mom's TV.
- File edits use Edit/Write only (no sed/scripts on source). No `"X, not Y"` copy in any UI text.

## Review Focus

1. **Seek to the last few seconds / a range that ends mid-chunk or at EOF.** The proxy must return exactly the requested bytes, with `Content-Range` correct and the last short chunk handled. → test in Task 7.
2. **Presigned URL expires during a long pause (> 6 h), or R2 returns 403 mid-play.** The proxy re-calls `/play` once and continues. → test in Task 7.
3. **A tampered or truncated chunk.** Playback must stop with an error and never feed unverified bytes to VLC. → tests in Tasks 4 and 6.
4. **A TV reinstalls the app.** It gets a new device id and shows up as a fresh pending device. The old approved row stays unchanged and can't be taken over. → test in Task 2.
5. **Stream entries never reach the downloader.** `SyncPlan`/download manager never see them, and drive deletion never targets them. → test in Task 8.

---

## File Structure

**main-server** (`~/Projects/main/main-server`, branch `master`)
- `lib/adik-stream-crypto.js`: device id, pairing code, master-key seal/open, RSA wrap, ECDSA verify.
- `lib/adik-stream-store.js`: pg queries plus the table bootstrap.
- `routes/adik-stream.js`: `createStreamRouter(deps)` plus the default export wired to real pg/S3.
- `app.js`: mount at `/adik/stream` **before** the `/adik` megaflix mount.
- `test/adik-stream-crypto.test.js`, `test/adik-stream.test.js` (`node --test`, memory store, like `test/ran.test.js`).

**Mac tools** (`~/Projects/Main/Tools/adik-curator`, not a git repo)
- `adikenc.py`: format encrypt/decrypt library.
- `test_adikenc.py`: pytest; also writes the Kotlin fixtures.
- `stream_upload.py`: encrypt, upload and register.
- `adik_stream.py`: `devices | approve <code> | revoke <device_id>`.

**App** (`~/Projects/AdikTV/app/src/main/kotlin/com/arflix/tv/stream/`, new package)
- `AdikEncFormat.kt`: header parse, range→chunks math, `decryptChunk`.
- `StreamKeys.kt`: Keystore keys, device id, pairing code, unwrap, sign.
- `StreamApi.kt` / `StreamModels.kt`: Retrofit routes and DTOs.
- `StreamCatalog.kt`: cached `/movies` list.
- `StreamSession.kt`: `/play`, URL refresh, owns a `DecryptingProxy`.
- `DecryptingProxy.kt`: localhost Range server.
- Modify:
  - `megaflix/MegaflixLibraryBuilder.kt`: merge stream movies.
  - `ui/screens/player/VlcPlayerViewModel.kt`: `adikstream://` resolution and network caching.
  - `ui/screens/player/VlcPlayerScreen.kt`: Netflix chrome.
  - `ui/screens/settings/SettingsScreen.kt` + `SettingsViewModel.kt`: a "Streaming" row showing device id, pairing code and status.
  - `app/build.gradle.kts`: versionName `0.2.6-stream`, versionCode 21.
- Tests: `app/src/test/kotlin/com/arflix/tv/stream/*Test.kt`; fixtures in `app/src/test/resources/adikenc/`.

---

### Task 1: Server crypto helpers

**Files:** Create `lib/adik-stream-crypto.js`, `test/adik-stream-crypto.test.js`; `npm i node-forge` (pure JS, needed because Node's `publicEncrypt` can't pair an OAEP SHA-256 digest with MGF1-SHA1).

**Interfaces — Produces:**
- `deviceIdFor(rsaSpkiDer: Buffer): string`
- `pairingCodeFor(rsaSpkiDer: Buffer, ecSpkiDer: Buffer): string` (8 chars, no dash)
- `sealCk(ck: Buffer(32), masterKey: Buffer(32)): Buffer` (= `iv(12)||ct||tag(16)`, AES-256-GCM)
- `openCk(sealed: Buffer, masterKey: Buffer): Buffer`
- `wrapForDevice(ck: Buffer, rsaSpkiDer: Buffer): Buffer` (RSA-OAEP, md sha256, mgf1 sha1, via node-forge)
- `verifyDeviceSig(ecSpkiDer: Buffer, message: Buffer, derSig: Buffer): boolean` (ECDSA P-256 / SHA-256)

- [ ] **Step 1: Write failing tests**, `node:test`:
  - `deviceIdFor` returns `/^S_[0-9a-f]{16}$/` and is stable for the same key.
  - `pairingCodeFor` returns `/^[A-Z2-7]{8}$/` and changes when the EC key changes.
  - `openCk(sealCk(ck,mk),mk)` equals `ck`; flipping one byte of the sealed blob throws.
  - `wrapForDevice`: generate RSA-3072 with `crypto.generateKeyPairSync`, wrap, then unwrap with node-forge `privateKey.decrypt(…,'RSA-OAEP',{md:sha256,mgf1:{md:sha1}})` → equals ck. Also assert Node's `privateDecrypt` with `oaepHash:'sha256'` **fails** (proves MGF1-SHA1).
  - `verifyDeviceSig`: true for a `crypto.sign('sha256', msg, ecPriv)` signature; false for another message.
- [ ] **Step 2:** `node --test test/adik-stream-crypto.test.js`. Expected: FAIL (module missing).
- [ ] **Step 3:** Implement in `lib/adik-stream-crypto.js`. Base32 uses the RFC 4648 alphabet `A-Z2-7`.
- [ ] **Step 4:** Re-run. Expected: all pass.
- [ ] **Step 5:** `git add lib/adik-stream-crypto.js test/adik-stream-crypto.test.js package.json package-lock.json && git commit -m "feat(adik-stream): crypto helpers"` (stage only these files).

### Task 2: Server store + router

**Files:** Create `lib/adik-stream-store.js`, `routes/adik-stream.js`, `test/adik-stream.test.js`. Modify `app.js` (mount `/adik/stream` above line 2881).

**Interfaces — Consumes:** Task 1. **Produces (HTTP, all JSON):**
- Read routes need `?k=<MEGAFLIX_TOKEN>`. Write routes need `Authorization: Bearer <ADIK_WRITE_TOKEN>`. Any failure → `404` (stealth, as in megaflix.js). There's no `DEVICES[]` check.
- `POST /pair` `{rsa_pub, ec_pub, security_level, attestation_chain[]}` (base64 DER) → `{device_id, pairing_code, status}`. It inserts a `pending` row if the id is new and **never** modifies an existing row (it returns that row's status).
- `GET /challenge?d=` → `{nonce}` (32 random bytes b64). Kept in memory for 120 s, single use.
- `GET /movies?d=&nonce=&sig=` → `{items:[{id,tmdb_id,title,plaintext_size}]}`. Approved devices only.
- `POST /play/:id` `{device_id, nonce, sig}` → `{wrapped_ck, url, expires_at, movie_uuid, nonce_prefix, chunk_size, chunk_count, plaintext_size}`.
  - Approved devices only, with a valid signature over `nonce || ":" || id` (for `/movies`: `nonce || ":movies"`).
  - Logs `[adik-stream] play <id> <- <device_id>`.
- Write routes:
  - `POST /movies` `{id,tmdb_id,title,r2_key,movie_uuid,nonce_prefix,plaintext_size,chunk_size,chunk_count,ck}` (ck b64; server seals it). Insert-only: 409 if the id exists.
  - `GET /devices`
  - `POST /devices/approve {code}` → `UPDATE … status='approved', approved_at=now() WHERE pairing_code=$1 AND status='pending'`. 404 if no row.
  - `POST /devices/revoke {device_id}`
- `createStreamRouter({store, presign(key, ttlSec) → url, masterKey, token, writeToken, now})`

Tables (created `IF NOT EXISTS` on boot):
- `adik_stream_devices(device_id PK, pairing_code UNIQUE, rsa_pub bytea, ec_pub bytea, security_level text, attestation_chain jsonb, status text CHECK in (pending|approved|revoked), created_at, approved_at, last_seen_at)`
- `adik_stream_movies(id PK, tmdb_id int, title text, r2_key text, movie_uuid bytea, nonce_prefix bytea, plaintext_size bigint, chunk_size int, chunk_count int, ck_sealed bytea, added_at)`

- [ ] **Step 1: Write failing tests** with a memory store, real keys and a stub `presign`:
  - pair → `pending`; `/play` → 404 until approved.
  - approve by code → `/play` returns a `wrapped_ck` that the test's private key unwraps to the registered ck.
  - re-pairing with **different keys** → a new device_id, and the old row is unchanged.
  - a reused or expired nonce → 404; a bad signature → 404.
  - a revoked device → 404.
  - `POST /movies` without the bearer → 404; a duplicate id → 409.
  - `/movies` never includes `r2_key` or ck.
- [ ] **Step 2:** `node --test test/adik-stream.test.js`. Expected: FAIL.
- [ ] **Step 3:** Implement the store (pg, parameterized) and the router. The default export reads `ADIK_STREAM_MASTER_KEY` (64 hex chars). If it's unset, the whole router returns 404. `presign` uses `@aws-sdk/s3-request-presigner`, `GetObjectCommand`, Bucket `adik-stream`, the existing R2 env creds, `expiresIn: 21600`.
- [ ] **Step 4:** Re-run both test files. Expected: pass. Also run `node -e "require('./routes/adik-stream')"` and check it loads.
- [ ] **Step 5:** Commit only these files plus `app.js`: `feat(adik-stream): pairing, catalog, play routes`.
- [ ] **Step 6: Ship (ask the owner first).**
  - Generate the master key, store it in Keychain: `openssl rand -hex 32 | tokenator add adik-stream-master-key --stdin --service main-server --env ADIK_STREAM_MASTER_KEY --note "seals ADIK stream movie keys"`.
  - Ask the owner to paste it into Coolify env `ADIK_STREAM_MASTER_KEY` (or OK me doing it via the Coolify API).
  - Push `master`.
  - Verify: `curl -s -o /dev/null -w "%{http_code}" "https://api.kaleidoscopical.com/adik/stream/challenge?d=x&k=<token>"` → `200`.

### Task 3: Mac format library + fixtures

**Files:** Create `adikenc.py`, `test_adikenc.py` in `~/Projects/Main/Tools/adik-curator`.

**Interfaces — Produces:**
- `encrypt_file(src_path, write: Callable[[bytes],None], chunk_size=1048576) -> dict(ck, movie_uuid, nonce_prefix, plaintext_size, chunk_count)`. Streaming: it calls `write` with the header first, then each sealed chunk.
- `parse_header(b: bytes) -> dict`
- `decrypt_chunk(ck, hdr, i, blob) -> bytes`

- [ ] **Step 1: Failing pytest:**
  - round-trip a random 2.5 × 4096-byte file with chunk_size 4096.
  - the header is exactly 64 bytes with magic `ADIKENC1`.
  - swapping chunks 0 and 1 → `InvalidTag`.
  - truncating the file (drop the last chunk, keep `chunk_count`) → the last remaining chunk fails, because `is_last` sits in the AAD.
  - `test_write_kotlin_fixture` writes `~/Projects/AdikTV/app/src/test/resources/adikenc/fixture.bin` (chunk_size 4096, 10,000 bytes of `bytes(i % 251 for i in range(10000))`) and `fixture.json` `{ck_hex, plaintext_sha256}`.
- [ ] **Step 2:** `uv run -q --with cryptography --with pytest pytest -q test_adikenc.py`. Expected: FAIL.
- [ ] **Step 3:** Implement with `cryptography.hazmat.primitives.ciphers.aead.AESGCM`.
- [ ] **Step 4:** Re-run. Expected: pass; the fixture files exist.
- [ ] **Step 5:** `git -C ~/Projects/AdikTV add app/src/test/resources/adikenc && git commit -m "test: ADIKENC1 fixture from the Mac encoder"`.

### Task 4: Uploader + device CLI, and upload Demon Slayer

**Files:** Create `stream_upload.py`, `adik_stream.py`.

- `stream_upload.py <file> --id demon_slayer_infinity_castle_2025 --tmdb <id> --title "…" [--dry-run]`:
  1. ffprobe codec check (warn only).
  2. Sum `npx wrangler@4 r2 bucket info` sizes across every bucket. **Abort** if `used + file_size > 10e9`.
  3. Encrypt-stream into an S3 multipart upload (part 64 MiB) to `adik-stream/v/<secrets.token_hex(16)>`.
  4. Self-check: ranged GET of chunk 0 and the last chunk, decrypt, compare to the source bytes.
  5. `POST /adik/stream/movies` with the write token.
  6. Print the id and size, **never the ck**.
  - Run via `tokenator exec cloudflare-account-id r2-access-key-id r2-secret-access-key adik-write-token -- uv run -q --with boto3 --with cryptography python -I stream_upload.py …`. If no `adik-write-token` exists, run `tokenator find adik` first.
- `adik_stream.py devices|approve <code>|revoke <device_id>` → write routes; prints a table.

- [ ] **Step 1:** `--dry-run` on the 30-min clip prints the plan and the usage check, and uploads nothing.
- [ ] **Step 2:** Look up the TMDB id for *Demon Slayer: Kimetsu no Yaiba Infinity Castle* (2025) with the app's TMDB key (`tokenator find tmdb`).
- [ ] **Step 3:** Real upload of the full 2.3 GB file. Expected: self-check OK; the account total stays around 4.7 GB.
- [ ] **Step 4:** `adik_stream.py devices` → empty list, 200.

### Task 5: App — format + crypto core

**Files:** Create `stream/AdikEncFormat.kt`, test `stream/AdikEncFormatTest.kt`.

**Interfaces — Produces:**
- `data class EncHeader(chunkSize:Int, plaintextSize:Long, chunkCount:Int, noncePrefix:ByteArray, movieUuid:ByteArray)`
- `object AdikEncFormat`:
  - `HEADER_SIZE = 64`, `TAG = 16`
  - `parseHeader(b: ByteArray): EncHeader`
  - `chunkOffset(h, i): Long`
  - `chunkCipherLen(h, i): Int`
  - `chunksFor(h, start: Long, endInclusive: Long): IntRange`
  - `decryptChunk(ck: ByteArray, h, i, blob: ByteArray): ByteArray` (throws `AEADBadTagException`)

- [ ] **Step 1: Failing tests:**
  - parse `fixture.bin` (classpath resource) → chunkSize 4096, plaintextSize 10000, chunkCount 3.
  - decrypt every chunk; the sha256 of the concatenation == `fixture.json.plaintext_sha256`.
  - `chunksFor(h, 4095, 4096) == 0..1`; `chunksFor(h, 9999, 9999) == 2..2`.
  - flipping one ciphertext byte → throws.
- [ ] **Step 2:** `./gradlew :app:testSideloadDebugUnitTest --tests '*AdikEncFormatTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement with `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, nonce)`, `updateAAD`.
- [ ] **Step 4:** Re-run. Expected: PASS.
- [ ] **Step 5:** Commit `feat(stream): ADIKENC1 format + chunk decrypt`.

### Task 6: App — keys, API, pairing row

**Files:** Create `stream/StreamKeys.kt`, `stream/StreamApi.kt`, `stream/StreamModels.kt`, `stream/StreamCatalog.kt`, test `stream/StreamIdsTest.kt`. Modify `di/AppModule.kt` (provide `StreamApi` on the existing Retrofit at `MEGAFLIX_FEED_BASE_URL`) and the Settings screen and view model.

**Interfaces — Produces:**
- `object StreamIds { fun deviceId(rsaSpki: ByteArray): String; fun pairingCode(rsaSpki: ByteArray, ecSpki: ByteArray): String }` (pure, JVM-testable)
- `@Singleton class StreamKeys`:
  - `ensureKeys()` (aliases `adik_stream_rsa`, `adik_stream_ec`)
  - `rsaSpki(): ByteArray`, `ecSpki(): ByteArray`
  - `securityLevel(): String` (`strongbox|tee|software`, via `KeyInfo`)
  - `unwrap(wrapped: ByteArray): ByteArray` (OAEP params in Global Constraints)
  - `sign(msg: ByteArray): ByteArray`
- `@Singleton class StreamCatalog`:
  - `state: StateFlow<PairState>` (`Unpaired|Pending(deviceId, code)|Approved(deviceId)`)
  - `movies: StateFlow<List<StreamMovieDto>>`
  - `suspend fun refresh()` (pair, then `/movies` when approved; called from the existing sync loop in `MegaflixSyncManager`)
- `StreamApi`: Retrofit `suspend` funcs for `adik/stream/pair`, `challenge`, `movies`, `play/{id}`, carrying `k=Constants.MEGAFLIX_FEED_TOKEN`.

- [ ] **Step 1: Failing test:** `StreamIds` reproduces the server's id and code for a fixed pair of SPKI byte arrays. Generate the vectors in Node once with Task 1's helpers and paste them into the test as hex constants.
- [ ] **Step 2:** Run. Expected: FAIL. **Step 3:** Implement. **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Add a Settings row "Streaming" showing `Waiting for approval · Code XXXX-XXXX` or `Connected`.
- [ ] **Step 6:** Commit `feat(stream): device keys, pairing, catalog`.

### Task 7: App — decrypting proxy + session

**Files:** Create `stream/DecryptingProxy.kt`, `stream/StreamSession.kt`, test `stream/DecryptingProxyTest.kt`.

**Interfaces — Produces:**
- `fun interface CipherSource { fun read(offset: Long, length: Int): ByteArray }` (ranged GET; throws `UrlExpired` on 403)
- `class DecryptingProxy(ck, header, source: CipherSource, onExpired: () -> CipherSource)`:
  - `start(): String` (returns `http://127.0.0.1:<port>/<32-hex secret>/movie`)
  - `stop()` (zeroes ck)
  - A hand-rolled `ServerSocket` on `127.0.0.1`, port 0, one daemon thread per connection, no new dependency.
  - Handles `GET`/`HEAD` and `Range: bytes=a-` / `a-b`.
  - Replies `206` with `Content-Range: bytes a-b/total`, `Accept-Ranges: bytes`, `Content-Type: video/x-matroska`.
  - Read-ahead uses an LRU of plaintext chunks (cap 16).
  - On `AEADBadTagException`: retry the fetch once, then close the socket. Never write unverified bytes.
- `class StreamSession @Inject constructor(api, keys)`: `suspend fun open(id: String): DecryptingProxy` (challenge → sign → `/play` → unwrap → OkHttp-backed `CipherSource` on the presigned URL).

- [ ] **Step 1: Failing tests** (in-memory `CipherSource` over `fixture.bin`, real sockets via `HttpURLConnection`):
  - a full GET returns 10,000 bytes matching the sha.
  - `Range: bytes=4090-4100` returns exactly those 11 bytes (crossing a chunk boundary), with `Content-Range: bytes 4090-4100/10000`.
  - `Range: bytes=9990-` returns 10 bytes.
  - a wrong secret path → 403.
  - a source whose chunk 1 is corrupted → the connection closes before byte 4096 is delivered.
  - a source that throws `UrlExpired` once → `onExpired` is called and the read succeeds.
- [ ] **Step 2:** Run. Expected: FAIL. **Step 3:** Implement. **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat(stream): localhost decrypting proxy`.

### Task 8: App — merge stream movies into the library

**Files:** Modify `megaflix/MegaflixLibraryBuilder.kt` (inject `StreamCatalog`; in `libraryItems`, build one `MediaItem` per stream movie through the same TMDB path as `buildMovieItem`, with `localUri = "adikstream://<id>"` and `downloadStatus = READY`; add them to `moviePathCache`). Test: `MegaflixLibraryBuilderTest.kt`.

- [ ] **Step 1: Failing tests:**
  - with one stream movie and one feed movie, `libraryItems(MOVIE)` returns both, and the stream one has `localUri == "adikstream://demon_slayer_infinity_castle_2025"` and status READY.
  - `cachedMoviePath(streamTmdbId)` returns that URI.
  - the movie cache invalidates when `StreamCatalog.movies` changes.
  - `SyncPlan` inputs (the feed list) contain no stream entry. Assert that `syncManager.feedItems` is untouched.
- [ ] **Step 2:** Run. Expected: FAIL. **Step 3:** Implement. **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat(stream): stream movies appear as normal library entries`.

### Task 9: App — player plays `adikstream://`

**Files:** Modify `ui/screens/player/VlcPlayerViewModel.kt` (inject `StreamSession`).

- In `startPlayback`: if `uri` starts with `adikstream://`, then `proxyUrl = session.open(id).start()` (on IO) and play `proxyUrl`. Add the media option `:network-caching=3000` for this source only. Stop the proxy in `onCleared`.
- Errors (not approved, network) → the existing error screen, with the message `"Streaming isn't available right now"`.
- The software-decoding retry reuses the same proxy.
- [ ] **Step 1:** Write a unit test with mockk: `toVlcUri` leaves `http://127.0.0.1…` untouched. Then build: `./gradlew :app:assembleSideloadDebug` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Commit `feat(stream): player resolves adikstream:// through the proxy`.

### Task 10: App — Netflix-layout player chrome

**Files:** Modify `ui/screens/player/VlcPlayerScreen.kt`. Port from `git show 7b421ad^:app/src/main/kotlin/com/arflix/tv/ui/screens/player/PlayerScreen.kt`:
- `PlayerIconButton` (L4986)
- `PlayerLabeledButton` (L5044)
- the centre cluster (~L4100–4140)
- the seek bar, remaining time and labeled row (~L4144–4440)

Layout (spec §5.4):
- back arrow top-left; title **centred** at top from `state.title`; no filename/URL ever.
- big play/pause in the centre, flanked wide by `Icons.Default.Replay10` / `Forward10`.
- thin bar with a red knob; played part red; time **remaining** on the right.
- bottom row "Episodes" (series only) and "Audio & Subtitles". No Next-episode, cast or screenshot buttons.
- D-pad: ←/→ ±10 s, hold = scrub, OK = play/pause, ↑/↓ moves focus between rows; auto-hide after 3 s.
- Keep `VlcPlayerViewModel`'s existing API. The audio/subtitle menu reuses the current track-menu composables.

- [ ] **Step 1:** `./gradlew :app:assembleSideloadDebug` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Commit `feat(player): Netflix-layout controls on the VLC player`.

### Task 11: Ship to the living-room TV and verify end to end

- [ ] **Step 1:** Set `versionName = "0.2.6-stream"`, `versionCode = 21` in `app/build.gradle.kts`. Build `assembleSideloadRelease` (keystore.properties exists).
- [ ] **Step 2:** `adb -s 192.168.100.12:5555 install -r <apk>`. The TV has 0.1.12, and the same signing key upgrades in place. If the signatures differ, **stop and ask** (an uninstall wipes data).
- [ ] **Step 3:** Open Settings → Streaming. Read the code from a screenshot. Run `adik_stream.py approve <code>` → `approved`.
- [ ] **Step 4:** Wait for a sync (or relaunch). Demon Slayer shows in the library like any movie. Play it. Check `adb logcat` for `[adik-stream]`/proxy errors, and `dumpsys media_session`.
- [ ] **Step 5:** Ask the owner to check: the start is quick; ±10 s and a long scrub; English subtitles; audio sync; a drive movie still plays; the player looks like the screenshot.
- [ ] **Step 6:** Commit the version bump plus a changelog line. **No GitHub release.** Update memory `adik-moms-tv`/a new `adik-stream` memory with the state.
- [ ] **Step 7:** Tell the owner that the spike object `adik-stream/spike/ds30.mkv` (541 MB) can be removed, and give them the command. Don't run it.
