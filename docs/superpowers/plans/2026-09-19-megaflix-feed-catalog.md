# Megaflix Feed Catalog (Milestone C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the TV app's "My Library" show items from the live Postgres feed (`api.kaleidoscopical.com/megaflix/*`), playing any whose files already exist on the drive and badging the rest as "Coming Soon" — the whole feed→catalog→status pipeline, minus the torrent engine.

**Architecture:** A new cycle-free `com.arflix.tv.megaflix` package fetches the feed (Retrofit+Gson), persists per-item download state (DataStore+Gson), resolves files on the USB drive, and computes a declarative sync plan. `MediaRepository.loadLocalCatalog` sources its items from this subsystem instead of the hardcoded `demoSeed`, tagging each `MediaItem` with a `downloadStatus`. The UI (MediaCard, DetailsScreen, Settings) reads that status for badges, Play-gating, and a "Sync now" button.

**Tech Stack:** Kotlin, Jetpack Compose (TV), Hilt (KSP), Retrofit 2.9 + Gson, DataStore Preferences, coroutines 1.7.3. Tests: JUnit4 + mockk + truth + robolectric (already configured).

**Spec:** `docs/superpowers/specs/2026-09-19-megaflix-content-pipeline-design.md`

## Global Constraints

- Package namespace `com.arflix.tv`; applicationId `com.megaflix.tv2`. Source root: `app/src/main/kotlin/com/arflix/tv/`.
- minSdk 23, targetSdk/compileSdk 36, Java 17, coroutines pinned 1.7.3.
- No version catalog — dependencies are direct coordinate strings in `app/build.gradle.kts`.
- Hilt repos use `@Singleton class X @Inject constructor(...)`; providers go in `di/AppModule.kt` (`@InstallIn(SingletonComponent::class)`, `@Provides @Singleton @JvmStatic`).
- Persistence pattern = DataStore delegate in `util/DataStores.kt` + Gson JSON blob under a string key.
- JSON lib = Gson (`@SerializedName`, `@androidx.annotation.Keep` on DTOs).
- Feed base URL `https://api.kaleidoscopical.com/megaflix/`; token `mgfx_7b2cedc4b5f053072628a02e152d8a87`.
- Dependency direction: `megaflix` package must NOT import `MediaRepository`/`CatalogRepository` (they import it). Prevents Hilt cycles.
- Build/verify the sideload debug variant: `./gradlew :app:compileSideloadDebugKotlin`; unit tests: `./gradlew :app:testSideloadDebugUnitTest --tests "<fqcn>"`.
- Milestone D (libtorrent4j engine + foreground download service + permissions) is a SEPARATE plan, written after C is validated. This plan leaves items in COMING_SOON; nothing auto-downloads yet.

---

### Task 1: Feed API layer (DTOs, Retrofit interface, DI provider, constants)

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/FeedModels.kt`
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixFeedApi.kt`
- Modify: `app/src/main/kotlin/com/arflix/tv/util/Constants.kt` (add two constants near line 41)
- Modify: `app/src/main/kotlin/com/arflix/tv/di/AppModule.kt` (add one provider near the other Retrofit providers, ~line 85)
- Test: `app/src/test/kotlin/com/arflix/tv/megaflix/FeedModelsTest.kt`

**Interfaces:**
- Produces: `FeedResponseDto(total:Int, offset:Int, limit:Int, items:List<FeedItemDto>)`; `FeedItemDto(id:String, type:String, tmdbId:Int, season:Int?, title:String, description:String?, link:String, path:String, sizeBytes:Long?)`; `RevResponseDto(rev:String)`; `MegaflixFeedApi.getFeed(token:String, limit:Int, offset:Int): FeedResponseDto`, `getRev(token:String): RevResponseDto`.

- [ ] **Step 1: Write the failing test** — `app/src/test/kotlin/com/arflix/tv/megaflix/FeedModelsTest.kt`

```kotlin
package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class FeedModelsTest {
    private val gson = Gson()

    @Test
    fun parsesFeedResponseFromServerJson() {
        val json = """
            {"total":1,"offset":0,"limit":100,"items":[
              {"id":"big-buck-bunny-2008","type":"movie","tmdbId":10378,
               "title":"Big Buck Bunny","description":"A rabbit.",
               "link":"magnet:?xt=urn:btih:abc","path":"Megaflix/Movies/Big Buck Bunny (2008)",
               "sizeBytes":276445467}]}
        """.trimIndent()

        val resp = gson.fromJson(json, FeedResponseDto::class.java)

        assertThat(resp.total).isEqualTo(1)
        assertThat(resp.items).hasSize(1)
        val item = resp.items[0]
        assertThat(item.id).isEqualTo("big-buck-bunny-2008")
        assertThat(item.tmdbId).isEqualTo(10378)
        assertThat(item.season).isNull()
        assertThat(item.sizeBytes).isEqualTo(276445467L)
        assertThat(item.type).isEqualTo("movie")
    }

    @Test
    fun parsesSeriesItemWithSeason() {
        val json = """{"total":1,"offset":0,"limit":100,"items":[
            {"id":"severance-s02","type":"series","tmdbId":95396,"season":2,
             "title":"Severance","description":null,"link":"magnet:?x","path":"Megaflix/TV/Severance/Season 02","sizeBytes":null}]}"""
        val resp = gson.fromJson(json, FeedResponseDto::class.java)
        assertThat(resp.items[0].season).isEqualTo(2)
        assertThat(resp.items[0].sizeBytes).isNull()
    }

    @Test
    fun parsesRev() {
        val rev = gson.fromJson("""{"rev":"203:2026-09-19T02:11:33Z"}""", RevResponseDto::class.java)
        assertThat(rev.rev).isEqualTo("203:2026-09-19T02:11:33Z")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.FeedModelsTest"`
Expected: FAIL — `FeedResponseDto` unresolved (compilation error).

- [ ] **Step 3: Create the DTOs** — `app/src/main/kotlin/com/arflix/tv/megaflix/FeedModels.kt`

```kotlin
package com.arflix.tv.megaflix

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class FeedResponseDto(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("limit") val limit: Int = 100,
    @SerializedName("items") val items: List<FeedItemDto> = emptyList()
)

@Keep
data class FeedItemDto(
    @SerializedName("id") val id: String = "",
    @SerializedName("type") val type: String = "movie",
    @SerializedName("tmdbId") val tmdbId: Int = 0,
    @SerializedName("season") val season: Int? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("link") val link: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("sizeBytes") val sizeBytes: Long? = null
)

@Keep
data class RevResponseDto(
    @SerializedName("rev") val rev: String = ""
)
```

- [ ] **Step 4: Create the Retrofit interface** — `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixFeedApi.kt`

```kotlin
package com.arflix.tv.megaflix

import retrofit2.http.GET
import retrofit2.http.Query

interface MegaflixFeedApi {
    @GET("feed.json")
    suspend fun getFeed(
        @Query("k") token: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): FeedResponseDto

    @GET("rev")
    suspend fun getRev(
        @Query("k") token: String
    ): RevResponseDto
}
```

- [ ] **Step 5: Add constants** — in `app/src/main/kotlin/com/arflix/tv/util/Constants.kt`, add after the `TMDB_BASE_URL` line (~line 41):

```kotlin
    const val MEGAFLIX_FEED_BASE_URL = "https://api.kaleidoscopical.com/megaflix/"
    const val MEGAFLIX_FEED_TOKEN = "mgfx_7b2cedc4b5f053072628a02e152d8a87"
```

- [ ] **Step 6: Add the Hilt provider** — in `app/src/main/kotlin/com/arflix/tv/di/AppModule.kt`, after `provideTraktApi` (~line 85), add (and add the import `import com.arflix.tv.megaflix.MegaflixFeedApi` at the top with the other imports):

```kotlin
    @Provides
    @Singleton
    @JvmStatic
    fun provideMegaflixFeedApi(okHttpClient: OkHttpClient): MegaflixFeedApi {
        return Retrofit.Builder()
            .baseUrl(Constants.MEGAFLIX_FEED_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MegaflixFeedApi::class.java)
    }
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.FeedModelsTest"`
Expected: PASS (3 tests).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/FeedModels.kt \
        app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixFeedApi.kt \
        app/src/main/kotlin/com/arflix/tv/util/Constants.kt \
        app/src/main/kotlin/com/arflix/tv/di/AppModule.kt \
        app/src/test/kotlin/com/arflix/tv/megaflix/FeedModelsTest.kt
git commit -m "feat(megaflix): feed API layer (DTOs, Retrofit service, DI provider)"
```

---

### Task 2: Download status model + MediaItem fields

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/data/model/DownloadStatus.kt`
- Modify: `app/src/main/kotlin/com/arflix/tv/data/model/Models.kt` (add two fields to `MediaItem` after line 78, `localUri`)
- Test: `app/src/test/kotlin/com/arflix/tv/data/model/MediaItemDownloadTest.kt`

**Interfaces:**
- Produces: `enum class DownloadStatus { READY, DOWNLOADING, COMING_SOON, FAILED }`; `MediaItem.downloadStatus: DownloadStatus` (default `READY`), `MediaItem.downloadProgress: Float` (default `0f`).
- Consumes: `MediaItem` from `data/model/Models.kt`.

- [ ] **Step 1: Write the failing test** — `app/src/test/kotlin/com/arflix/tv/data/model/MediaItemDownloadTest.kt`

```kotlin
package com.arflix.tv.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaItemDownloadTest {
    @Test
    fun defaultsToReadyWithNoProgress() {
        val item = MediaItem(id = 1, title = "X")
        assertThat(item.downloadStatus).isEqualTo(DownloadStatus.READY)
        assertThat(item.downloadProgress).isEqualTo(0f)
    }

    @Test
    fun canMarkComingSoon() {
        val item = MediaItem(id = 1, title = "X")
            .copy(downloadStatus = DownloadStatus.DOWNLOADING, downloadProgress = 0.42f)
        assertThat(item.downloadStatus).isEqualTo(DownloadStatus.DOWNLOADING)
        assertThat(item.downloadProgress).isWithin(0.001f).of(0.42f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.data.model.MediaItemDownloadTest"`
Expected: FAIL — `DownloadStatus` / `downloadStatus` unresolved.

- [ ] **Step 3: Create the enum** — `app/src/main/kotlin/com/arflix/tv/data/model/DownloadStatus.kt`

```kotlin
package com.arflix.tv.data.model

/** Megaflix per-item download state, surfaced on cards and the detail page. */
enum class DownloadStatus {
    READY,        // file present on the drive; playable
    DOWNLOADING,  // torrent in progress (Milestone D)
    COMING_SOON,  // in the feed, not yet on the drive
    FAILED        // download failed; will be retried (Milestone D)
}
```

- [ ] **Step 4: Add fields to MediaItem** — in `app/src/main/kotlin/com/arflix/tv/data/model/Models.kt`, immediately after the `localUri: String? = null` field (line 78), add:

```kotlin
    // Megaflix: download state for the feed-driven library (default READY so all
    // non-library items — search results, TMDB rows — render as normal playable cards).
    val downloadStatus: DownloadStatus = DownloadStatus.READY,
    val downloadProgress: Float = 0f,
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.data.model.MediaItemDownloadTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Verify the whole module still compiles** (MediaItem is widely constructed)

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL (defaults mean no existing call site breaks).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/data/model/DownloadStatus.kt \
        app/src/main/kotlin/com/arflix/tv/data/model/Models.kt \
        app/src/test/kotlin/com/arflix/tv/data/model/MediaItemDownloadTest.kt
git commit -m "feat(megaflix): DownloadStatus + MediaItem download fields"
```

---

### Task 3: Download state store (DataStore + Gson)

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/util/DataStores.kt` (add one delegate)
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadRecord.kt`
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadStateStore.kt`
- Test: `app/src/test/kotlin/com/arflix/tv/megaflix/DownloadStateStoreTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `DownloadStatus` (Task 2).
- Produces: `DownloadRecord(id:String, status:DownloadStatus, localFilePath:String?, progress:Float, sizeBytes:Long?)`; `@Singleton class DownloadStateStore @Inject constructor(@ApplicationContext context)` with `suspend fun all(): Map<String, DownloadRecord>`, `suspend fun putAll(records: List<DownloadRecord>)`, `suspend fun remove(ids: Collection<String>)`, `suspend fun setRev(rev: String)`, `suspend fun getRev(): String?`, `fun recordsFlow(): Flow<Map<String, DownloadRecord>>`.

- [ ] **Step 1: Write the failing test** — `app/src/test/kotlin/com/arflix/tv/megaflix/DownloadStateStoreTest.kt`

```kotlin
package com.arflix.tv.megaflix

import androidx.test.core.app.ApplicationProvider
import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadStateStoreTest {
    private fun store() = DownloadStateStore(ApplicationProvider.getApplicationContext())

    @Test
    fun putAllThenReadBack() = runTest {
        val s = store()
        s.putAll(listOf(
            DownloadRecord("a", DownloadStatus.READY, "/mnt/drive/a.mkv", 1f, 100L),
            DownloadRecord("b", DownloadStatus.COMING_SOON, null, 0f, null)
        ))
        val all = s.all()
        assertThat(all.keys).containsExactly("a", "b")
        assertThat(all["a"]!!.localFilePath).isEqualTo("/mnt/drive/a.mkv")
        assertThat(all["b"]!!.status).isEqualTo(DownloadStatus.COMING_SOON)
    }

    @Test
    fun removeDropsIds() = runTest {
        val s = store()
        s.putAll(listOf(DownloadRecord("a", DownloadStatus.READY, null, 1f, null),
                        DownloadRecord("b", DownloadStatus.READY, null, 1f, null)))
        s.remove(listOf("a"))
        assertThat(s.all().keys).containsExactly("b")
    }

    @Test
    fun revRoundTrips() = runTest {
        val s = store()
        assertThat(s.getRev()).isNull()
        s.setRev("5:2026-09-19T00:00:00Z")
        assertThat(s.getRev()).isEqualTo("5:2026-09-19T00:00:00Z")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.DownloadStateStoreTest"`
Expected: FAIL — `DownloadStateStore` / `DownloadRecord` unresolved.

- [ ] **Step 3: Add the DataStore delegate** — in `app/src/main/kotlin/com/arflix/tv/util/DataStores.kt`, add alongside the existing delegates (after line 19):

```kotlin
val Context.downloadsDataStore by preferencesDataStore(name = "megaflix_downloads_prefs")
```

(If the `preferencesDataStore` import is not already present at the top of the file, add `import androidx.datastore.preferences.preferencesDataStore`.)

- [ ] **Step 4: Create DownloadRecord** — `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadRecord.kt`

```kotlin
package com.arflix.tv.megaflix

import androidx.annotation.Keep
import com.arflix.tv.data.model.DownloadStatus

@Keep
data class DownloadRecord(
    val id: String,
    val status: DownloadStatus,
    val localFilePath: String? = null, // absolute path to the playable video file when READY
    val progress: Float = 0f,
    val sizeBytes: Long? = null
)
```

- [ ] **Step 5: Create DownloadStateStore** — `app/src/main/kotlin/com/arflix/tv/megaflix/DownloadStateStore.kt`

```kotlin
package com.arflix.tv.megaflix

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.downloadsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val recordsKey = stringPreferencesKey("records")
    private val revKey = stringPreferencesKey("rev")
    private val mapType = object : TypeToken<Map<String, DownloadRecord>>() {}.type

    private fun decode(json: String?): Map<String, DownloadRecord> =
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { gson.fromJson<Map<String, DownloadRecord>>(json, mapType) }.getOrNull() ?: emptyMap()

    suspend fun all(): Map<String, DownloadRecord> =
        decode(context.downloadsDataStore.data.first()[recordsKey])

    fun recordsFlow(): Flow<Map<String, DownloadRecord>> =
        context.downloadsDataStore.data.map { decode(it[recordsKey]) }

    suspend fun putAll(records: List<DownloadRecord>) {
        context.downloadsDataStore.edit { prefs ->
            val merged = decode(prefs[recordsKey]).toMutableMap()
            records.forEach { merged[it.id] = it }
            prefs[recordsKey] = gson.toJson(merged, mapType)
        }
    }

    suspend fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.downloadsDataStore.edit { prefs ->
            val merged = decode(prefs[recordsKey]).toMutableMap()
            ids.forEach { merged.remove(it) }
            prefs[recordsKey] = gson.toJson(merged, mapType)
        }
    }

    suspend fun setRev(rev: String) {
        context.downloadsDataStore.edit { it[revKey] = rev }
    }

    suspend fun getRev(): String? =
        context.downloadsDataStore.data.first()[revKey]
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.DownloadStateStoreTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/util/DataStores.kt \
        app/src/main/kotlin/com/arflix/tv/megaflix/DownloadRecord.kt \
        app/src/main/kotlin/com/arflix/tv/megaflix/DownloadStateStore.kt \
        app/src/test/kotlin/com/arflix/tv/megaflix/DownloadStateStoreTest.kt
git commit -m "feat(megaflix): persistent download state store (DataStore+Gson)"
```

---

### Task 4: DriveManager (file resolution on the USB drive)

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/DriveManager.kt`
- Test: `app/src/test/kotlin/com/arflix/tv/megaflix/DriveManagerTest.kt`

**Interfaces:**
- Produces: `@Singleton class DriveManager @Inject constructor(@ApplicationContext context)` with `fun driveRoot(): File?`, `fun itemDir(relativePath: String): File?`, `fun findPlayableFile(relativePath: String): File?`, and companion `fun isVideoFile(name: String): Boolean`, `fun pickPlayable(files: List<File>): File?`.
- Consumes: nothing from earlier tasks.
- Note: the pure helpers (`isVideoFile`, `pickPlayable`) are unit-tested; `driveRoot()` uses real volumes with a test override key, verified on the emulator in Task 8.

- [ ] **Step 1: Write the failing test** — `app/src/test/kotlin/com/arflix/tv/megaflix/DriveManagerTest.kt`

```kotlin
package com.arflix.tv.megaflix

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class DriveManagerTest {
    @Test
    fun recognisesVideoExtensions() {
        assertThat(DriveManager.isVideoFile("Movie.mkv")).isTrue()
        assertThat(DriveManager.isVideoFile("Movie.MP4")).isTrue()
        assertThat(DriveManager.isVideoFile("clip.avi")).isTrue()
        assertThat(DriveManager.isVideoFile("subtitles.srt")).isFalse()
        assertThat(DriveManager.isVideoFile("poster.jpg")).isFalse()
        assertThat(DriveManager.isVideoFile("readme")).isFalse()
    }

    @Test
    fun pickPlayableChoosesLargestVideo() {
        val small = File("/x/sample.mp4")   // e.g. a trailer
        val big = File("/x/feature.mkv")
        // pickPlayable ranks by the size hint list order (largest first); here we
        // simulate by passing a pre-sorted list — pickPlayable returns the first video.
        assertThat(DriveManager.pickPlayable(listOf(big, small))).isEqualTo(big)
        assertThat(DriveManager.pickPlayable(listOf(File("/x/a.txt"), small))).isEqualTo(small)
        assertThat(DriveManager.pickPlayable(listOf(File("/x/a.txt")))).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.DriveManagerTest"`
Expected: FAIL — `DriveManager` unresolved.

- [ ] **Step 3: Create DriveManager** — `app/src/main/kotlin/com/arflix/tv/megaflix/DriveManager.kt`

```kotlin
package com.arflix.tv.megaflix

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Root folder to converge with the feed. Order of preference:
     * 1. A test/manual override path stored under settings key "megaflix_drive_override"
     *    (used for emulator verification — adb-push files there).
     * 2. The first *removable* external volume (a plugged-in USB drive).
     * 3. Primary external storage (fallback).
     */
    fun driveRoot(): File? {
        val override = runBlocking {
            context.settingsDataStore.data.first()[stringPreferencesKey("megaflix_drive_override")]
        }
        if (!override.isNullOrBlank()) return File(override)

        // getExternalFilesDirs returns one dir per mounted volume; index > 0 is
        // typically removable (SD/USB). Walk up from the app-specific dir to the
        // volume root where the "Megaflix/..." tree lives.
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val removable = dirs.getOrNull(1)
        val volumeRoot = removable?.let { volumeRootOf(it) }
        if (volumeRoot != null && volumeRoot.canRead()) return volumeRoot

        return dirs.getOrNull(0)?.let { volumeRootOf(it) }
    }

    fun itemDir(relativePath: String): File? {
        val root = driveRoot() ?: return null
        return File(root, relativePath)
    }

    fun findPlayableFile(relativePath: String): File? {
        val dir = itemDir(relativePath) ?: return null
        if (!dir.isDirectory) return null
        val videos = dir.walkTopDown().filter { it.isFile && isVideoFile(it.name) }.toList()
            .sortedByDescending { it.length() }
        return pickPlayable(videos)
    }

    private fun volumeRootOf(appDir: File): File {
        // appDir looks like /storage/XXXX-XXXX/Android/data/<pkg>/files — climb to
        // /storage/XXXX-XXXX so we can read a top-level "Megaflix/" folder.
        var f: File = appDir
        repeat(4) { f = f.parentFile ?: return appDir }
        return f
    }

    companion object {
        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv")

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTS
        }

        /** Returns the first video file in the (caller-sorted) list, or null if none. */
        fun pickPlayable(files: List<File>): File? = files.firstOrNull { isVideoFile(it.name) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.DriveManagerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/DriveManager.kt \
        app/src/test/kotlin/com/arflix/tv/megaflix/DriveManagerTest.kt
git commit -m "feat(megaflix): DriveManager resolves feed paths to files on the drive"
```

---

### Task 5: Sync plan (pure diff logic)

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/SyncPlan.kt`
- Test: `app/src/test/kotlin/com/arflix/tv/megaflix/SyncPlanTest.kt`

**Interfaces:**
- Consumes: `FeedItemDto` (Task 1), `DownloadRecord` + `DownloadStatus` (Tasks 2–3).
- Produces: `data class SyncPlan(upserts: List<DownloadRecord>, removedIds: List<String>)`; `object SyncPlanner { fun plan(feed: List<FeedItemDto>, existing: Map<String, DownloadRecord>, presentFile: (FeedItemDto) -> String?): SyncPlan }`.
- The `presentFile` lambda returns the absolute playable file path if the item's files are on the drive, else null (injected so this stays pure/testable; the real caller passes `driveManager.findPlayableFile(it.path)?.absolutePath`).

- [ ] **Step 1: Write the failing test** — `app/src/test/kotlin/com/arflix/tv/megaflix/SyncPlanTest.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncPlanTest {
    private fun item(id: String) = FeedItemDto(id = id, tmdbId = 1, title = id, path = "P/$id")

    @Test
    fun newItemWithFileOnDriveBecomesReady() {
        val plan = SyncPlanner.plan(
            feed = listOf(item("a")),
            existing = emptyMap(),
            presentFile = { "/drive/P/a/a.mkv" }
        )
        assertThat(plan.upserts).hasSize(1)
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.READY)
        assertThat(plan.upserts[0].localFilePath).isEqualTo("/drive/P/a/a.mkv")
        assertThat(plan.removedIds).isEmpty()
    }

    @Test
    fun newItemWithoutFileBecomesComingSoon() {
        val plan = SyncPlanner.plan(listOf(item("a")), emptyMap(), presentFile = { null })
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.COMING_SOON)
        assertThat(plan.upserts[0].localFilePath).isNull()
    }

    @Test
    fun inProgressItemIsPreservedWhenStillNoFile() {
        val existing = mapOf("a" to DownloadRecord("a", DownloadStatus.DOWNLOADING, null, 0.5f, null))
        val plan = SyncPlanner.plan(listOf(item("a")), existing, presentFile = { null })
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.DOWNLOADING)
        assertThat(plan.upserts[0].progress).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun downloadingItemFlipsToReadyOnceFileAppears() {
        val existing = mapOf("a" to DownloadRecord("a", DownloadStatus.DOWNLOADING, null, 0.9f, null))
        val plan = SyncPlanner.plan(listOf(item("a")), existing, presentFile = { "/drive/P/a/a.mkv" })
        assertThat(plan.upserts[0].status).isEqualTo(DownloadStatus.READY)
        assertThat(plan.upserts[0].progress).isEqualTo(1f)
    }

    @Test
    fun vanishedItemsAreRemoved() {
        val existing = mapOf(
            "a" to DownloadRecord("a", DownloadStatus.READY, "/drive/P/a/a.mkv", 1f, null),
            "gone" to DownloadRecord("gone", DownloadStatus.READY, "/drive/P/gone/g.mkv", 1f, null)
        )
        val plan = SyncPlanner.plan(listOf(item("a")), existing, presentFile = { "/drive/P/a/a.mkv" })
        assertThat(plan.removedIds).containsExactly("gone")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.SyncPlanTest"`
Expected: FAIL — `SyncPlanner` unresolved.

- [ ] **Step 3: Create SyncPlan + SyncPlanner** — `app/src/main/kotlin/com/arflix/tv/megaflix/SyncPlan.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus

data class SyncPlan(
    val upserts: List<DownloadRecord>,
    val removedIds: List<String>
)

object SyncPlanner {
    /**
     * Pure convergence: given the feed, the existing records, and a way to tell
     * whether an item's file is already on the drive, decide the new record for
     * each feed item and which stale ids to drop.
     */
    fun plan(
        feed: List<FeedItemDto>,
        existing: Map<String, DownloadRecord>,
        presentFile: (FeedItemDto) -> String?
    ): SyncPlan {
        val feedIds = feed.map { it.id }.toSet()

        val upserts = feed.map { item ->
            val file = presentFile(item)
            val prev = existing[item.id]
            when {
                // File on the drive → always READY (covers pre-seeded drives and completed downloads).
                file != null -> DownloadRecord(item.id, DownloadStatus.READY, file, 1f, item.sizeBytes)
                // No file, but a download was already underway/failed → preserve that state.
                prev != null && (prev.status == DownloadStatus.DOWNLOADING || prev.status == DownloadStatus.FAILED) ->
                    prev.copy(sizeBytes = item.sizeBytes ?: prev.sizeBytes)
                // Otherwise it's queued.
                else -> DownloadRecord(item.id, DownloadStatus.COMING_SOON, null, 0f, item.sizeBytes)
            }
        }

        val removedIds = existing.keys.filter { it !in feedIds }
        return SyncPlan(upserts, removedIds)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.SyncPlanTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/SyncPlan.kt \
        app/src/test/kotlin/com/arflix/tv/megaflix/SyncPlanTest.kt
git commit -m "feat(megaflix): pure sync-plan convergence logic + tests"
```

---

### Task 6: MegaflixSyncManager (fetch, apply plan, delete, rev)

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixSyncManager.kt`
- Test: `app/src/test/kotlin/com/arflix/tv/megaflix/MegaflixSyncManagerTest.kt` (mockk)

**Interfaces:**
- Consumes: `MegaflixFeedApi` (T1), `DownloadStateStore` (T3), `DriveManager` (T4), `SyncPlanner` (T5), `Constants.MEGAFLIX_FEED_TOKEN`.
- Produces: `@Singleton class MegaflixSyncManager @Inject constructor(feedApi, store, driveManager)` with `suspend fun sync(): Result<SyncOutcome>`, `suspend fun revChanged(): Boolean`, and `val feedItems: StateFlow<List<FeedItemDto>>`. `data class SyncOutcome(total:Int, ready:Int, comingSoon:Int, removed:Int)`.
- The delete side-effect (removing files for vanished ids) happens here, guarded so it only deletes inside the drive's `Megaflix/` tree.

- [ ] **Step 1: Write the failing test** — `app/src/test/kotlin/com/arflix/tv/megaflix/MegaflixSyncManagerTest.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MegaflixSyncManagerTest {
    private val api = mockk<MegaflixFeedApi>()
    private val store = mockk<DownloadStateStore>(relaxed = true)
    private val drive = mockk<DriveManager>(relaxed = true)

    private fun feedItem(id: String) = FeedItemDto(id = id, tmdbId = 1, title = id, path = "Megaflix/Movies/$id")

    @Test
    fun syncPagesFeedAndWritesRecords() = runTest {
        coEvery { api.getFeed(any(), any(), 0) } returns
            FeedResponseDto(total = 1, offset = 0, limit = 100, items = listOf(feedItem("a")))
        coEvery { store.all() } returns emptyMap()
        coEvery { drive.findPlayableFile(any()) } returns null

        val mgr = MegaflixSyncManager(api, store, drive)
        val outcome = mgr.sync().getOrThrow()

        assertThat(outcome.total).isEqualTo(1)
        assertThat(outcome.comingSoon).isEqualTo(1)
        val captured = slot<List<DownloadRecord>>()
        coVerify { store.putAll(capture(captured)) }
        assertThat(captured.captured.single().status).isEqualTo(DownloadStatus.COMING_SOON)
    }

    @Test
    fun revChangedTrueWhenServerDiffers() = runTest {
        coEvery { api.getRev(any()) } returns RevResponseDto("2:t")
        coEvery { store.getRev() } returns "1:t"
        val mgr = MegaflixSyncManager(api, store, drive)
        assertThat(mgr.revChanged()).isTrue()
    }

    @Test
    fun revChangedFalseWhenSame() = runTest {
        coEvery { api.getRev(any()) } returns RevResponseDto("1:t")
        coEvery { store.getRev() } returns "1:t"
        val mgr = MegaflixSyncManager(api, store, drive)
        assertThat(mgr.revChanged()).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.MegaflixSyncManagerTest"`
Expected: FAIL — `MegaflixSyncManager` unresolved.

- [ ] **Step 3: Create MegaflixSyncManager** — `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixSyncManager.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SyncOutcome(val total: Int, val ready: Int, val comingSoon: Int, val removed: Int)

@Singleton
class MegaflixSyncManager @Inject constructor(
    private val feedApi: MegaflixFeedApi,
    private val store: DownloadStateStore,
    private val driveManager: DriveManager
) {
    private val _feedItems = MutableStateFlow<List<FeedItemDto>>(emptyList())
    val feedItems: StateFlow<List<FeedItemDto>> = _feedItems.asStateFlow()

    private val token = Constants.MEGAFLIX_FEED_TOKEN
    private val pageSize = 100

    /** Returns true if the server's rev differs from the last one we synced. */
    suspend fun revChanged(): Boolean = runCatching {
        feedApi.getRev(token).rev != store.getRev()
    }.getOrDefault(false)

    suspend fun sync(): Result<SyncOutcome> = runCatching {
        val feed = fetchAllPages()
        _feedItems.value = feed

        val existing = store.all()
        val plan = SyncPlanner.plan(
            feed = feed,
            existing = existing,
            presentFile = { driveManager.findPlayableFile(it.path)?.absolutePath }
        )

        store.putAll(plan.upserts)
        if (plan.removedIds.isNotEmpty()) {
            deleteFilesFor(plan.removedIds, existing)
            store.remove(plan.removedIds)
        }

        runCatching { feedApi.getRev(token).rev }.getOrNull()?.let { store.setRev(it) }

        val ready = plan.upserts.count { it.status == DownloadStatus.READY }
        val coming = plan.upserts.count { it.status == DownloadStatus.COMING_SOON }
        SyncOutcome(total = feed.size, ready = ready, comingSoon = coming, removed = plan.removedIds.size)
    }

    private suspend fun fetchAllPages(): List<FeedItemDto> {
        val out = mutableListOf<FeedItemDto>()
        var offset = 0
        while (true) {
            val page = feedApi.getFeed(token, pageSize, offset)
            out += page.items
            offset += page.items.size
            if (page.items.size < pageSize || offset >= page.total) break
        }
        return out
    }

    /** Deletes an item's folder, but only inside the drive's Megaflix/ tree (safety guard). */
    private fun deleteFilesFor(ids: Collection<String>, existing: Map<String, DownloadRecord>) {
        val root = driveManager.driveRoot() ?: return
        val megaflixTree = File(root, "Megaflix").absolutePath
        ids.forEach { id ->
            val filePath = existing[id]?.localFilePath ?: return@forEach
            val dir = File(filePath).parentFile ?: return@forEach
            if (dir.absolutePath.startsWith(megaflixTree)) dir.deleteRecursively()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.MegaflixSyncManagerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixSyncManager.kt \
        app/src/test/kotlin/com/arflix/tv/megaflix/MegaflixSyncManagerTest.kt
git commit -m "feat(megaflix): sync manager (paged fetch, converge, declarative delete, rev)"
```

---

### Task 7: MediaRepository integration (feed → library MediaItems)

**Files:**
- Create: `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixLibraryBuilder.kt`
- Modify: `app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt` (constructor ~line 104; `loadLocalCatalog` ~line 1941)
- Test: `app/src/test/kotlin/com/arflix/tv/megaflix/MegaflixLibraryBuilderTest.kt` (mockk)

**Interfaces:**
- Consumes: `MegaflixSyncManager` (T6), `DownloadStateStore` (T3), `TmdbApi.getMovieDetails(movieId:Int, apiKey:String): TmdbMovieDetails` / `getTvDetails(tvId:Int, apiKey:String): TmdbTvDetails`, `Constants.TMDB_API_KEY`, `Constants.IMAGE_BASE`/`BACKDROP_BASE`, `MediaItem`, `MediaType`, `DownloadStatus`.
- Produces: `@Singleton class MegaflixLibraryBuilder @Inject constructor(syncManager, store, tmdbApi)` with `suspend fun libraryItems(filter: MediaType?): List<MediaItem>`.
- MediaRepository's `loadLocalCatalog` calls `megaflixLibrary.libraryItems(...)` instead of `allSeedItems()`.

- [ ] **Step 1: Write the failing test** — `app/src/test/kotlin/com/arflix/tv/megaflix/MegaflixLibraryBuilderTest.kt`

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbMovieDetails
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.data.model.MediaType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MegaflixLibraryBuilderTest {
    private val sync = mockk<MegaflixSyncManager>()
    private val store = mockk<DownloadStateStore>()
    private val tmdb = mockk<TmdbApi>()

    @Test
    fun readyItemGetsLocalUriAndReadyStatus() = runTest {
        val feed = listOf(FeedItemDto(id = "a", type = "movie", tmdbId = 10378, title = "Big Buck Bunny",
            description = "d", link = "magnet:?x", path = "Megaflix/Movies/BBB"))
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf(
            "a" to DownloadRecord("a", DownloadStatus.READY, "/drive/Megaflix/Movies/BBB/bbb.mkv", 1f, null))
        coEvery { tmdb.getMovieDetails(10378, any(), any(), any()) } returns
            TmdbMovieDetails(id = 10378, title = "Big Buck Bunny")

        val builder = MegaflixLibraryBuilder(sync, store, tmdb)
        val items = builder.libraryItems(MediaType.MOVIE)

        assertThat(items).hasSize(1)
        assertThat(items[0].id).isEqualTo(10378)
        assertThat(items[0].downloadStatus).isEqualTo(DownloadStatus.READY)
        assertThat(items[0].localUri).isEqualTo("/drive/Megaflix/Movies/BBB/bbb.mkv")
    }

    @Test
    fun comingSoonItemHasNoLocalUriAndUsesFeedFallbackTitle() = runTest {
        val feed = listOf(FeedItemDto(id = "b", type = "movie", tmdbId = 999, title = "Feed Title",
            description = "feed desc", link = "magnet:?x", path = "Megaflix/Movies/B"))
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf("b" to DownloadRecord("b", DownloadStatus.COMING_SOON, null, 0f, null))
        coEvery { tmdb.getMovieDetails(999, any(), any(), any()) } throws RuntimeException("tmdb down")

        val builder = MegaflixLibraryBuilder(sync, store, tmdb)
        val items = builder.libraryItems(MediaType.MOVIE)

        assertThat(items).hasSize(1)
        assertThat(items[0].downloadStatus).isEqualTo(DownloadStatus.COMING_SOON)
        assertThat(items[0].localUri).isNull()
        assertThat(items[0].title).isEqualTo("Feed Title") // TMDB failed → feed fallback
    }

    @Test
    fun filtersByType() = runTest {
        val feed = listOf(
            FeedItemDto(id = "m", type = "movie", tmdbId = 1, title = "M", link = "x", path = "Megaflix/Movies/M"),
            FeedItemDto(id = "s", type = "series", tmdbId = 2, title = "S", link = "x", path = "Megaflix/TV/S")
        )
        every { sync.feedItems } returns MutableStateFlow(feed)
        coEvery { store.all() } returns mapOf(
            "m" to DownloadRecord("m", DownloadStatus.COMING_SOON, null, 0f, null),
            "s" to DownloadRecord("s", DownloadStatus.COMING_SOON, null, 0f, null))
        coEvery { tmdb.getMovieDetails(any(), any(), any(), any()) } throws RuntimeException("x")

        val builder = MegaflixLibraryBuilder(sync, store, tmdb)
        assertThat(builder.libraryItems(MediaType.MOVIE).map { it.title }).containsExactly("M")
    }
}
```

*Note: if `TmdbMovieDetails`/`TmdbTvDetails` have required (non-default) constructor params beyond `id`/`title`, adjust the test's constructor calls to match the actual data class (read `data/api/TmdbApi.kt`). Only `id` and `title` are asserted.*

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.MegaflixLibraryBuilderTest"`
Expected: FAIL — `MegaflixLibraryBuilder` unresolved.

- [ ] **Step 3: Create MegaflixLibraryBuilder** — `app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixLibraryBuilder.kt`

Build a `MediaItem` per feed item: fetch TMDB details by id (falling back to feed title/description on any error), attach `localUri` + `downloadStatus`/`downloadProgress` from the record. Image URLs use the same `Constants.IMAGE_BASE`/`BACKDROP_BASE` as the existing mappers.

```kotlin
package com.arflix.tv.megaflix

import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.DownloadStatus
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MegaflixLibraryBuilder @Inject constructor(
    private val syncManager: MegaflixSyncManager,
    private val store: DownloadStateStore,
    private val tmdbApi: TmdbApi
) {
    private val apiKey get() = Constants.TMDB_API_KEY

    suspend fun libraryItems(filter: MediaType?): List<MediaItem> = withContext(Dispatchers.IO) {
        val feed = syncManager.feedItems.value
        val records = store.all()
        val wanted = feed.filter { filter == null || typeOf(it) == filter }

        coroutineScope {
            val gate = Semaphore(6)
            wanted.map { item ->
                async { gate.withPermit { buildItem(item, records[item.id]) } }
            }.awaitAll()
        }
    }

    private fun typeOf(item: FeedItemDto): MediaType =
        if (item.type.equals("series", true) || item.type.equals("tv", true)) MediaType.TV else MediaType.MOVIE

    private suspend fun buildItem(item: FeedItemDto, record: DownloadRecord?): MediaItem {
        val type = typeOf(item)
        val status = record?.status ?: DownloadStatus.COMING_SOON
        val localUri = if (status == DownloadStatus.READY) record?.localFilePath else null

        val base = runCatching {
            if (type == MediaType.TV) {
                val d = tmdbApi.getTvDetails(item.tmdbId, apiKey)
                MediaItem(
                    id = item.tmdbId,
                    title = d.name ?: item.title,
                    overview = d.overview ?: item.description.orEmpty(),
                    posterUrl = d.posterPath?.let { Constants.IMAGE_BASE + it } ?: "",
                    backdropUrl = d.backdropPath?.let { Constants.BACKDROP_BASE + it } ?: "",
                    mediaType = MediaType.TV
                )
            } else {
                val d = tmdbApi.getMovieDetails(item.tmdbId, apiKey)
                MediaItem(
                    id = item.tmdbId,
                    title = d.title ?: item.title,
                    overview = d.overview ?: item.description.orEmpty(),
                    posterUrl = d.posterPath?.let { Constants.IMAGE_BASE + it } ?: "",
                    backdropUrl = d.backdropPath?.let { Constants.BACKDROP_BASE + it } ?: "",
                    mediaType = MediaType.MOVIE
                )
            }
        }.getOrElse {
            // TMDB unavailable → feed-only fallback so the card still appears.
            MediaItem(id = item.tmdbId, title = item.title, overview = item.description.orEmpty(), mediaType = type)
        }

        return base.copy(
            localUri = localUri,
            downloadStatus = status,
            downloadProgress = record?.progress ?: 0f
        )
    }
}
```

*Note: `MediaItem`'s field names for poster/overview must match `data/model/Models.kt`. Confirm `overview`, `posterUrl`, `backdropUrl` (or the actual names) while implementing; the mappers in `MediaRepository.kt:4093/4130/4161` show the exact property names to copy. `TmdbTvDetails.name`/`TmdbMovieDetails.title` likewise — mirror the existing `toMediaItem()` field access.*

- [ ] **Step 4: Wire into MediaRepository** — in `app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt`:

(a) Add the dependency to the constructor (line 104), appended to the existing param list:
```kotlin
    private val megaflixLibrary: com.arflix.tv.megaflix.MegaflixLibraryBuilder,
```

(b) In `loadLocalCatalog` (line 1941), replace the call that gets items from `allSeedItems()` with the feed-driven source. The current body filters seed items by `catalog.id`. Replace the item source so it becomes:
```kotlin
    suspend fun loadLocalCatalog(catalog: CatalogConfig, maxItems: Int = 60): Category? {
        val filter = when (catalog.id) {
            "local_movies" -> MediaType.MOVIE
            "local_tv" -> MediaType.TV
            else -> null
        }
        val items = megaflixLibrary.libraryItems(filter).take(maxItems)
        if (items.isEmpty()) return null
        return Category(catalog.id, catalog.title, items)
    }
```
Leave `demoSeed`/`allSeedItems()`/`cachedSeedItems` in place (unused now; removed in a later cleanup) to keep this diff minimal.

- [ ] **Step 5: Run the builder test to verify it passes**

Run: `./gradlew :app:testSideloadDebugUnitTest --tests "com.arflix.tv.megaflix.MegaflixLibraryBuilderTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Verify the app compiles (Hilt graph resolves the new constructor dep)**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/megaflix/MegaflixLibraryBuilder.kt \
        app/src/main/kotlin/com/arflix/tv/data/repository/MediaRepository.kt \
        app/src/test/kotlin/com/arflix/tv/megaflix/MegaflixLibraryBuilderTest.kt
git commit -m "feat(megaflix): library builder — feed items become MediaItems; wire into loadLocalCatalog"
```

---

### Task 8: UI — badges, Play-gating, Sync trigger + button

**Files:**
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/components/MediaCard.kt` (add a status badge overlay in `MediaCard`, ~line 73)
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsScreen.kt` (Play action idx 0 ~line 434; Play button label)
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/home/HomeViewModel.kt` (kick sync on load + a 60s rev poller)
- Modify: `app/src/main/kotlin/com/arflix/tv/ui/screens/settings/SettingsScreen.kt` (a "Sync library" row near the app-update section ~line 2153) and its ViewModel
- No new unit test (UI/integration); verified on the emulator against the live feed.

**Interfaces:**
- Consumes: `MediaItem.downloadStatus`/`downloadProgress`, `DownloadStatus`, `MegaflixSyncManager.sync()`/`revChanged()`.

- [ ] **Step 1: Add the badge to MediaCard** — in `MediaCard.kt`, inside the card's poster `Box` (the same box that holds the poster image), add an overlay driven by `item.downloadStatus`. Add imports for `androidx.compose.foundation.layout.*`, `androidx.compose.ui.Alignment`, `androidx.compose.ui.graphics.Color`, `androidx.tv.material3.Text` if not present:

```kotlin
// Megaflix: download-state badge (top-start of the poster)
when (item.downloadStatus) {
    com.arflix.tv.data.model.DownloadStatus.COMING_SOON ->
        StatusBadge(text = "COMING SOON", modifier = Modifier.align(Alignment.TopStart))
    com.arflix.tv.data.model.DownloadStatus.DOWNLOADING ->
        StatusBadge(text = "↓ ${(item.downloadProgress * 100).toInt()}%", modifier = Modifier.align(Alignment.TopStart))
    com.arflix.tv.data.model.DownloadStatus.FAILED ->
        StatusBadge(text = "RETRY", modifier = Modifier.align(Alignment.TopStart))
    com.arflix.tv.data.model.DownloadStatus.READY -> { /* no badge */ }
}
```

And define the small composable once at file scope:
```kotlin
@androidx.compose.runtime.Composable
private fun StatusBadge(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        androidx.tv.material3.Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}
```
(Ensure the poster `Box` uses `contentAlignment`/`align` — if the root of `MediaCard` isn't a `Box`, wrap the poster+badge in one. Match the file's existing card structure.)

- [ ] **Step 2: Gate Play on the detail page** — in `DetailsScreen.kt`, at the Play action (`onButtonClickRemembered` idx 0, ~line 434), short-circuit when the item isn't ready:

```kotlin
0 -> { // Play
    if (state.item?.downloadStatus != null &&
        state.item.downloadStatus != com.arflix.tv.data.model.DownloadStatus.READY) {
        return@remember // not downloaded yet — do nothing (button shows "Coming soon")
    }
    val localUriDirect = state.item?.localUri
    // ... existing Play logic unchanged ...
}
```
And change the Play button label so it reads "Coming soon" when not ready. Where `playButtonLabel` is computed (mobile ~line 1559, TV ~line 2306), wrap it:
```kotlin
val notReady = uiState.item?.downloadStatus != null &&
    uiState.item.downloadStatus != com.arflix.tv.data.model.DownloadStatus.READY
val playButtonLabel = when {
    notReady && uiState.item?.downloadStatus == com.arflix.tv.data.model.DownloadStatus.DOWNLOADING ->
        "Downloading ${((uiState.item.downloadProgress) * 100).toInt()}%"
    notReady -> "Coming soon"
    !playLabel.isNullOrBlank() -> playLabel
    else -> stringResource(R.string.play)
}
```

- [ ] **Step 3: Trigger sync from HomeViewModel** — in `HomeViewModel.kt`, inject `MegaflixSyncManager` (add to the `@Inject constructor`), and in the existing init/refresh path, before catalogs load, call sync; then start a 60s poller. Add:

```kotlin
// in the constructor params:
private val megaflixSyncManager: com.arflix.tv.megaflix.MegaflixSyncManager,

// called once from init {} (or the existing first-load coroutine):
private fun startMegaflixSync() {
    viewModelScope.launch {
        runCatching { megaflixSyncManager.sync() }
        reloadHome() // existing method that rebuilds catalog rows (use whatever the class calls)
        while (true) {
            kotlinx.coroutines.delay(60_000)
            if (runCatching { megaflixSyncManager.revChanged() }.getOrDefault(false)) {
                runCatching { megaflixSyncManager.sync() }
                reloadHome()
            }
        }
    }
}
```
(Call `startMegaflixSync()` from `init {}`. Replace `reloadHome()` with the actual method name the class uses to refresh rows — see how `loadCustomCatalogsIncrementally`/the main load is invoked.)

- [ ] **Step 4: Add a "Sync library" button in Settings** — in the Settings ViewModel, add:
```kotlin
fun syncMegaflixLibrary() {
    viewModelScope.launch { runCatching { megaflixSyncManager.sync() } }
}
```
(inject `MegaflixSyncManager` into the Settings ViewModel). In `SettingsScreen.kt`, near the app-update row (~line 2153, where `onCheckUpdates` is wired), add a sibling row/button labeled "Sync library" whose onClick calls `viewModel.syncMegaflixLibrary()`. Match the surrounding row composable style.

- [ ] **Step 5: Verify compile**

Run: `./gradlew :app:compileSideloadDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Emulator verification against the live feed**

```bash
# Build + install the debug variant
./gradlew :app:installSideloadDebug
# Point the drive at an on-device folder for testing (override), then push a fake "ready" movie:
adb shell mkdir -p /sdcard/MegaflixTest/Megaflix/Movies/'Big Buck Bunny (2008)'
adb push <a-small-h264.mp4> "/sdcard/MegaflixTest/Megaflix/Movies/Big Buck Bunny (2008)/bbb.mp4"
# Set the override via the app (temporary: write settings key), or hardcode driveRoot() fallback to /sdcard/MegaflixTest for this test.
adb shell monkey -p com.megaflix.tv2 1
```
Expected on screen:
- Home "Movies" row shows **Big Buck Bunny** (from the live feed) with full TMDB poster.
- With the file pushed → it shows **no badge** and Play works (plays the pushed file).
- Without the file → it shows a **COMING SOON** badge and the detail page's primary button reads "Coming soon".
- Deleting the row in Postgres (`DELETE FROM megaflix_items WHERE id='big-buck-bunny-2008'`) then pressing "Sync library" → the card disappears and the on-drive folder is removed.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/arflix/tv/ui/components/MediaCard.kt \
        app/src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsScreen.kt \
        app/src/main/kotlin/com/arflix/tv/ui/screens/home/HomeViewModel.kt \
        app/src/main/kotlin/com/arflix/tv/ui/screens/settings/SettingsScreen.kt
git commit -m "feat(megaflix): library status badges, Play-gating, sync trigger + Settings button"
```

---

## Milestone D (separate plan, after C is validated)

Not built here. Will cover: `libtorrent4j` (`sideloadImplementation`, ABI armeabi-v7a+arm64-v8a, 16KB-page check), a `TorrentEngine` interface (main) with a libtorrent impl in `src/sideload/kotlin` and a no-op in `src/play/kotlin`; a foreground `DownloadService` + notification channel (new `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC`/`POST_NOTIFICATIONS`/`WAKE_LOCK`/`MANAGE_EXTERNAL_STORAGE` permissions + `<service>` in the manifest); the "All files access" permission flow; and wiring `MegaflixSyncManager` to enqueue COMING_SOON items → the service → progress writes back to `DownloadStateStore` (flipping cards DOWNLOADING→READY). Ships as v0.1.4 together with C.

## Self-Review

**Spec coverage:**
- Feed schema (§1) → Task 1 DTOs. ✅
- `/feed.json` + `/rev` consumption (§2) → Tasks 1, 6. ✅
- Declarative desired-state + deletions (§ core principle, §4a) → Tasks 5, 6. ✅
- Pre-existing-files rule (§4a) → Tasks 4, 5 (`presentFile`→READY). ✅
- Catalog = feed, status overlay READY/DOWNLOADING/COMING_SOON (§4d) → Tasks 2, 7, 8. ✅
- 60s poller + Sync-now + launch sync (§4a) → Task 8. ✅
- Token gating (§2) → Task 1 (token as query param). ✅
- Torrent engine, foreground service, permissions (§4b, §4c) → **deferred to Milestone D** (explicitly). ✅
- Capacity/`size_bytes` (§1) → carried in DTO + record (Tasks 1, 3), enforced curator-side (not app). ✅

**Placeholder scan:** Code steps contain real code. Two `*Note*` callouts (Task 7) flag that exact `MediaItem`/`TmdbDetails` property names must mirror existing mappers — these are verification instructions, not missing logic, and point to the exact source lines (`MediaRepository.kt:4093/4130/4161`).

**Type consistency:** `DownloadStatus` (READY/DOWNLOADING/COMING_SOON/FAILED) used identically across Tasks 2–8. `DownloadRecord` signature stable across Tasks 3, 5, 6, 7. `FeedItemDto` fields stable across Tasks 1, 5, 6, 7. `MegaflixSyncManager.sync()/revChanged()/feedItems` consistent in Tasks 6, 7, 8. `SyncPlanner.plan(feed, existing, presentFile)` consistent Tasks 5, 6.
