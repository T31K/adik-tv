package com.arflix.tv.megaflix

import androidx.test.core.app.ApplicationProvider
import com.arflix.tv.data.model.DownloadStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Robolectric 4.11 supports up to API 34; app targetSdk is 36
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
