# Megaflix Torrent Engine (Milestone D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Automatically download COMING_SOON feed items onto the USB drive via an embedded BitTorrent engine, updating each card DOWNLOADING→READY, so items appear and become playable with no user action.

**Architecture:** libtorrent4j (embedded libtorrent) wrapped in a `TorrentEngine`. A `MegaflixDownloadManager` pulls COMING_SOON records from the existing `DownloadStateStore`, downloads them one at a time into `DriveManager`'s folders, and writes progress back to the store. A foreground `DownloadService` keeps the work alive and shows a notification. `MegaflixSyncManager` starts the service when there's anything to fetch, gated on the "All files access" permission.

**Tech Stack:** Kotlin, libtorrent4j `2.1.0-39` (org.libtorrent4j), Hilt, coroutines 1.7.3, Android foreground service + notifications. Builds on Milestone C (`com.arflix.tv.megaflix`).

**Spec:** `docs/superpowers/specs/2026-09-19-megaflix-content-pipeline-design.md`
**Predecessor:** `docs/superpowers/plans/2026-09-19-megaflix-feed-catalog.md` (Milestone C, complete)

## Global Constraints

- Reuses Milestone C: `DownloadStateStore`, `DownloadRecord`, `DownloadStatus`, `DriveManager`, `MegaflixSyncManager`, `FeedItemDto`.
- minSdk 23, targetSdk 36. ABI armeabi-v7a + arm64-v8a (release); x86_64 only under `-PincludeX86Abis=true` (for emulator testing).
- libtorrent4j is added as plain `implementation` (both flavors) — the user only ships `sideload`, and a source-set/flavor split adds risk for no shipping benefit. Note the tradeoff in the commit.
- Real magnet API: `SessionManager.download(String magnetUri, File saveDir, torrent_flags_t flags)`. Alerts via `AlertListener` on `AlertType.ADD_TORRENT` (call `handle().resume()`), `AlertType.BLOCK_FINISHED` / `AlertType.PIECE_FINISHED` (`handle().status().progress()` → 0f..1f), `AlertType.TORRENT_FINISHED`. `s.start()` / `s.stop()`.
- Downloads write into `DriveManager.driveRoot()/<feed path>`; needs `MANAGE_EXTERNAL_STORAGE` on Android 11+.
- On-device torrent behaviour cannot be fully proven in unit tests. Pure logic (queue selection, progress mapping) is unit-tested; engine + service are compile-verified and emulator-smoke-tested (x86_64 build against Big Buck Bunny), with final proof on the real TV.
- Ships as **v0.1.4** together with Milestone C, AFTER a real content seed (else near-empty library).

---

### Task 1: Dependency, ABI, manifest permissions + service declaration

**Files:**
- Modify: `app/build.gradle.kts` (deps ~line 369 near media3-ffmpeg; abiFilters ~line 76)
- Modify: `app/src/main/AndroidManifest.xml` (permissions + `<service>`)

- [ ] **Step 1: Add libtorrent4j deps** — in `app/build.gradle.kts` dependencies block:

```kotlin
    // Megaflix: embedded BitTorrent engine (downloads feed items to the USB drive).
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64-v8a:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-armeabi-v7a:2.1.0-39")
    // x86_64 native libs only when building for the emulator (-PincludeX86Abis=true).
    if (project.hasProperty("includeX86Abis")) {
        implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
    }
```

- [ ] **Step 2: Confirm abiFilters already gate x86 behind the flag** — verify `defaultConfig.ndk.abiFilters` (~line 76) adds x86/x86_64 only when `-PincludeX86Abis=true`. No change if already so (the infra map confirmed it).

- [ ] **Step 3: Add permissions + service** — in `app/src/main/AndroidManifest.xml`, add permissions near the existing ones:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

and inside `<application>`, add:

```xml
        <service
            android:name=".megaflix.DownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Step 4: Verify compile (deps resolve, manifest merges)**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "feat(megaflix): add libtorrent4j + foreground-service permissions & declaration"
```

---

### Task 2: TorrentEngine (libtorrent4j wrapper)

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/TorrentEngine.kt`

**Interfaces:**
- Produces: `@Singleton class TorrentEngine @Inject constructor()` with `fun ensureStarted()`, `fun shutdown()`, and `suspend fun download(magnetUri: String, saveDir: File, onProgress: (Float) -> Unit): File` — resolves to the largest video file under `saveDir` when the torrent finishes; throws on failure/timeout.
- Consumes: `DriveManager.isVideoFile` (Task 4 of Milestone C, via companion).

- [ ] **Step 1: Create TorrentEngine** — `app/src/main/kotlin/com/arflix/tv/megaflix/TorrentEngine.kt`

```kotlin
package com.arflix.tv.megaflix

import kotlinx.coroutines.CompletableDeferred
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.BlockFinishedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over libtorrent4j's SessionManager. One session for the process;
 * downloads run one at a time (the manager serialises them). Seeding is stopped
 * on completion (we never upload from the TV).
 */
@Singleton
class TorrentEngine @Inject constructor() {
    private val session = SessionManager()

    @Synchronized
    fun ensureStarted() {
        if (!session.isRunning) session.start()
    }

    @Synchronized
    fun shutdown() {
        if (session.isRunning) session.stop()
    }

    /**
     * Downloads [magnetUri] into [saveDir], reporting fractional progress (0f..1f).
     * Suspends until the torrent finishes, then returns the largest video file found.
     */
    suspend fun download(magnetUri: String, saveDir: File, onProgress: (Float) -> Unit): File {
        ensureStarted()
        saveDir.mkdirs()
        val done = CompletableDeferred<Unit>()

        val listener = object : AlertListener {
            override fun types(): IntArray? = null // all alerts
            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> (alert as AddTorrentAlert).handle().resume()
                    AlertType.BLOCK_FINISHED ->
                        onProgress((alert as BlockFinishedAlert).handle().status().progress())
                    AlertType.PIECE_FINISHED ->
                        onProgress((alert as PieceFinishedAlert).handle().status().progress())
                    AlertType.TORRENT_FINISHED -> {
                        onProgress(1f)
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    else -> {}
                }
            }
        }

        session.addListener(listener)
        try {
            session.download(magnetUri, saveDir, torrent_flags_t())
            done.await()
        } finally {
            session.removeListener(listener)
        }

        return saveDir.walkTopDown()
            .filter { it.isFile && DriveManager.isVideoFile(it.name) }
            .maxByOrNull { it.length() }
            ?: throw IllegalStateException("Torrent finished but no video file in $saveDir")
    }
}
```

- [ ] **Step 2: Verify compile (libtorrent4j API resolves)**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL. If `session.isRunning` / `removeListener` differ, adjust to the actual `SessionManager` API (it exposes `isRunning`, `addListener`, `removeListener`, `start`, `stop`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/TorrentEngine.kt
git commit -m "feat(megaflix): TorrentEngine — libtorrent4j magnet download with progress"
```

---

### Task 3: Download manager + queue selection (pure logic tested)

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadQueue.kt` (pure selection)
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixDownloadManager.kt`
- Test: `app/src/test/kotlin/com/arflix/tv/megaflix/DownloadQueueTest.kt`

**Interfaces:**
- Produces: `object DownloadQueue { fun nextToDownload(records: Map<String, DownloadRecord>): String? }` (first COMING_SOON or FAILED id, deterministic by insertion). `@Singleton class MegaflixDownloadManager @Inject constructor(engine, store, driveManager, syncManager)` with `suspend fun runOnce(): Boolean` (download the next queued item; returns false when nothing left) and `suspend fun drain()` (loop runOnce until empty).
- Consumes: `TorrentEngine` (T2), `DownloadStateStore`, `DriveManager`, `MegaflixSyncManager.feedItems` (for magnet + path lookup).

- [ ] **Step 1: Write failing test** — `app/src/test/kotlin/com/arflix/tv/megaflix/DownloadQueueTest.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadQueueTest {
    private fun rec(id: String, s: DownloadStatus) = DownloadRecord(id, s, null, 0f, null)

    @Test fun picksFirstComingSoon() {
        val recs = linkedMapOf(
            "a" to rec("a", DownloadStatus.READY),
            "b" to rec("b", DownloadStatus.COMING_SOON),
            "c" to rec("c", DownloadStatus.COMING_SOON))
        assertThat(DownloadQueue.nextToDownload(recs)).isEqualTo("b")
    }

    @Test fun picksFailedWhenNoComingSoon() {
        val recs = linkedMapOf(
            "a" to rec("a", DownloadStatus.READY),
            "b" to rec("b", DownloadStatus.FAILED))
        assertThat(DownloadQueue.nextToDownload(recs)).isEqualTo("b")
    }

    @Test fun nullWhenAllReadyOrDownloading() {
        val recs = mapOf(
            "a" to rec("a", DownloadStatus.READY),
            "b" to rec("b", DownloadStatus.DOWNLOADING))
        assertThat(DownloadQueue.nextToDownload(recs)).isNull()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.DownloadQueueTest"`
Expected: FAIL — `DownloadQueue` unresolved.

- [ ] **Step 3: Create DownloadQueue** — `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadQueue.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus

object DownloadQueue {
    /** Next id to fetch: first COMING_SOON, else first FAILED (retry), else null. */
    fun nextToDownload(records: Map<String, DownloadRecord>): String? {
        records.entries.firstOrNull { it.value.status == DownloadStatus.COMING_SOON }?.let { return it.key }
        records.entries.firstOrNull { it.value.status == DownloadStatus.FAILED }?.let { return it.key }
        return null
    }
}
```

- [ ] **Step 4: Create MegaflixDownloadManager** — `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixDownloadManager.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MegaflixDownloadManager @Inject constructor(
    private val engine: TorrentEngine,
    private val store: DownloadStateStore,
    private val driveManager: DriveManager,
    private val syncManager: MegaflixSyncManager
) {
    /** Downloads the next queued item. Returns false when nothing is queued. */
    suspend fun runOnce(): Boolean {
        val records = store.all()
        val id = DownloadQueue.nextToDownload(records) ?: return false
        val feedItem = syncManager.feedItems.value.firstOrNull { it.id == id } ?: return false
        val saveDir = driveManager.itemDir(feedItem.path) ?: return false

        store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, 0f, feedItem.sizeBytes)))
        return try {
            val file = engine.download(feedItem.link, saveDir) { p ->
                // Coarse throttle: only persist on ~1% steps happens in the service; here persist as-is.
                kotlinx.coroutines.runBlocking {
                    store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, p, feedItem.sizeBytes)))
                }
            }
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.READY, file.absolutePath, 1f, feedItem.sizeBytes)))
            true
        } catch (t: Throwable) {
            store.putAll(listOf(DownloadRecord(id, DownloadStatus.FAILED, null, 0f, feedItem.sizeBytes)))
            true // keep draining; failed item will retry next cycle
        }
    }

    /** Sequentially download everything queued. */
    suspend fun drain() {
        while (runOnce()) { /* one at a time */ }
    }
}
```

*Note: the `runBlocking` inside the progress callback is a placeholder throttle; Task 4 moves progress persistence to a throttled path in the service so we don't hammer DataStore every block. If the reviewer prefers, persist progress at most every ~2s.*

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.DownloadQueueTest"`
Expected: PASS (3).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/DownloadQueue.kt \
        app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixDownloadManager.kt \
        app/src/test/kotlin/com/arflix/tv/megaflix/DownloadQueueTest.kt
git commit -m "feat(megaflix): download manager + queue selection (one-at-a-time)"
```

---

### Task 4: Foreground DownloadService + notification, throttled progress

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadService.kt`
- (service already declared in Task 1 manifest edit)

**Interfaces:**
- Produces: `class DownloadService : Service()` (Hilt via `@AndroidEntryPoint`), obtains `MegaflixDownloadManager` (constructor-injected field), runs `drain()` on a background scope inside `startForeground(...)`, updates a progress notification, stops itself when the queue empties. Companion `fun start(context: Context)`.
- Progress persistence throttled to ~1% or ~2s increments (moved out of the manager's raw callback).

- [ ] **Step 1: Create DownloadService** — `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadService.kt`

```kotlin
package com.arflix.tv.megaflix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var downloadManager: MegaflixDownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Syncing library…"))
        scope.launch {
            try {
                downloadManager.drain()
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Megaflix")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "megaflix_downloads"
        private const val NOTIF_ID = 4201

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
```

- [ ] **Step 2: Throttle progress persistence in the manager** — replace the raw `onProgress` body in `MegaflixDownloadManager.runOnce` so it only writes when progress advances ≥0.01 since the last write:

```kotlin
        var lastPersisted = 0f
        val file = engine.download(feedItem.link, saveDir) { p ->
            if (p - lastPersisted >= 0.01f || p >= 1f) {
                lastPersisted = p
                kotlinx.coroutines.runBlocking {
                    store.putAll(listOf(DownloadRecord(id, DownloadStatus.DOWNLOADING, null, p, feedItem.sizeBytes)))
                }
            }
        }
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/DownloadService.kt \
        app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixDownloadManager.kt
git commit -m "feat(megaflix): foreground DownloadService + throttled progress"
```

---

### Task 5: Permission flow + start service after sync

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/StoragePermission.kt`
- Modify: `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixSyncManager.kt` (start service when queued items exist & permission granted)
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/home/HomeViewModel.kt` (expose a "needs storage permission" flag if desired) — minimal: request via an activity intent from MainActivity on first run.

**Interfaces:**
- Produces: `object StoragePermission { fun hasAllFilesAccess(): Boolean; fun requestIntent(context: Context): Intent }`.
- `MegaflixSyncManager` gains a `@ApplicationContext context` dep and, at the end of `sync()`, calls `DownloadService.start(context)` when `StoragePermission.hasAllFilesAccess()` and any record is COMING_SOON/FAILED.

- [ ] **Step 1: Create StoragePermission** — `app/src/main/kotlin/com/arflix/tv/megaflix/StoragePermission.kt`

```kotlin
package com.arflix.tv.megaflix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

object StoragePermission {
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true // pre-R: covered by legacy storage permission already in the manifest

    fun requestIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
```

- [ ] **Step 2: Start the service after sync** — inject `@ApplicationContext context` into `MegaflixSyncManager` and, at the end of `sync()` (after `store.putAll` and rev save), add:

```kotlin
        val hasQueued = plan.upserts.any {
            it.status == com.arflix.tv.data.model.DownloadStatus.COMING_SOON ||
            it.status == com.arflix.tv.data.model.DownloadStatus.FAILED
        }
        if (hasQueued && StoragePermission.hasAllFilesAccess()) {
            DownloadService.start(context)
        }
```

- [ ] **Step 3: Request permission on first launch** — in `MainActivity`, if `!StoragePermission.hasAllFilesAccess()`, launch `StoragePermission.requestIntent(this)` once (guard with a settings flag so it doesn't nag). Keep minimal; if MainActivity is complex, add a one-line call in its `onCreate` after setContent, guarded by a DataStore boolean `megaflix_storage_prompted`.

- [ ] **Step 4: Verify compile + Hilt graph**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/StoragePermission.kt \
        app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixSyncManager.kt \
        app/src/main/kotlin/com/arflix/tv/MainActivity.kt
git commit -m "feat(megaflix): all-files-access flow + start download service after sync"
```

---

### Task 6: Emulator smoke test + device verification

- [ ] **Step 1: Build x86_64 debug for the emulator**

Run: `./gradlew :app:assembleSideloadDebug -PincludeX86Abis=true`
Expected: BUILD SUCCESSFUL; APK contains x86_64 libtorrent4j `.so`.

- [ ] **Step 2: Install, grant all-files access, launch**

```bash
adb install -r -g app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
adb shell appops set com.megaflix.tv2 MANAGE_EXTERNAL_STORAGE allow
adb shell am start -n com.megaflix.tv2/com.arflix.tv.MainActivity
```

- [ ] **Step 3: Verify Big Buck Bunny actually downloads**
Pick a profile; wait. Expected in logcat: `ADD_TORRENT` then rising `BLOCK_FINISHED` progress, then `TORRENT_FINISHED`. Then:
```bash
adb shell run-as com.megaflix.tv2 cat files/datastore/megaflix_downloads_prefs.preferences_pb | strings | grep big-buck
# expect status READY + a localFilePath under /storage/emulated/0/Megaflix/Movies/Big Buck Bunny (2008)/
adb shell ls -la "/storage/emulated/0/Megaflix/Movies/Big Buck Bunny (2008)/"
```
Expected: the real Big Buck Bunny video file present, record READY, card badge gone, Play works.

- [ ] **Step 4: Real TV verification (hand to user)**
Sideload the arm release on the TCL with a USB drive attached, grant All files access, confirm a small item downloads to the drive and plays. This is the only step that proves arm native libs + USB write on real hardware.

- [ ] **Step 5: Commit any fixes from smoke testing, then final build**

```bash
./gradlew :app:testSideloadDebugUnitTest
git commit -am "test(megaflix): torrent engine emulator smoke-test fixes" # if needed
```

## Self-Review

**Spec coverage:** embedded torrent engine (§4b) → T2; one-at-a-time queue + progress (§4b) → T3; foreground service + notification (§4b) → T4; MANAGE_EXTERNAL_STORAGE / all-files access (§4c) → T5; enqueue COMING_SOON→service→READY writeback (§4a/§4d) → T3/T5; DOWNLOADING badge already rendered by Milestone C's MediaCard/DetailsScreen → T6 verifies live.

**Placeholder scan:** Real code throughout, grounded in libtorrent4j 2.1.0-39 API (SessionManager/AlertType verified from the repo). One flagged `*Note*` (T3 progress throttle) is resolved in T4 step 2. Two adjust-if-differs notes (T2 `isRunning`/`removeListener`; T5 MainActivity insertion) point at the exact symbols to confirm on compile.

**Type consistency:** `DownloadRecord`/`DownloadStatus` reused from Milestone C unchanged. `TorrentEngine.download(magnetUri, saveDir, onProgress): File` consistent T2↔T3. `DownloadQueue.nextToDownload(records): String?` consistent T3. `DownloadService.start(context)` consistent T4↔T5.
