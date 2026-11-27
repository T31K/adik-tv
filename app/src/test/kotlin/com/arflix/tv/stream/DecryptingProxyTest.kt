package com.arflix.tv.stream

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Real sockets against the localhost proxy, fed from the Mac encoder's fixture (10,000 bytes, 4 KiB chunks). */
class DecryptingProxyTest {
    private val blob: ByteArray = javaClass.getResourceAsStream("/adikenc/fixture.bin")!!.readBytes()
    private val meta = JsonParser.parseString(
        javaClass.getResourceAsStream("/adikenc/fixture.json")!!.readBytes().decodeToString()
    ).asJsonObject
    private fun ck() = meta["ck_hex"].asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val header = AdikEncFormat.parseHeader(blob.copyOfRange(0, 64))
    private val plain: ByteArray = (0 until 10000).map { (it % 251).toByte() }.toByteArray()
    private var proxy: DecryptingProxy? = null

    private val goodSource = CipherSource { off, len -> blob.copyOfRange(off.toInt(), off.toInt() + len) }

    @After
    fun tearDown() { proxy?.stop() }

    private fun start(source: CipherSource, onExpired: () -> CipherSource = { goodSource }): String =
        DecryptingProxy(ck(), header, source, onExpired).also { proxy = it }.start()

    private fun get(url: String, range: String? = null): Pair<HttpURLConnection, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        val out = ByteArrayOutputStream()
        try {
            c.inputStream.use { input ->
                val buf = ByteArray(1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        } catch (_: IOException) {
            // connection cut mid-body: return what arrived
        }
        return c to out.toByteArray()
    }

    @Test
    fun fullGetReturnsThePlaintext() {
        val (c, body) = get(start(goodSource))
        assertThat(c.responseCode).isEqualTo(200)
        assertThat(c.getHeaderField("Accept-Ranges")).isEqualTo("bytes")
        assertThat(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) })
            .isEqualTo(meta["plaintext_sha256"].asString)
    }

    @Test
    fun rangeAcrossAChunkBoundary() {
        val (c, body) = get(start(goodSource), "bytes=4090-4100")
        assertThat(c.responseCode).isEqualTo(206)
        assertThat(c.getHeaderField("Content-Range")).isEqualTo("bytes 4090-4100/10000")
        assertThat(body).isEqualTo(plain.copyOfRange(4090, 4101))
    }

    @Test
    fun openEndedRangeAtTheEnd() {
        val (c, body) = get(start(goodSource), "bytes=9990-")
        assertThat(c.responseCode).isEqualTo(206)
        assertThat(c.getHeaderField("Content-Range")).isEqualTo("bytes 9990-9999/10000")
        assertThat(body).isEqualTo(plain.copyOfRange(9990, 10000))
    }

    @Test
    fun rangePastTheEndIs416() {
        val (c, _) = get(start(goodSource), "bytes=10000-")
        assertThat(c.responseCode).isEqualTo(416)
    }

    @Test
    fun wrongSecretIs403() {
        val url = start(goodSource)
        val bad = url.substringBeforeLast("/").substringBeforeLast("/") + "/" + "0".repeat(32) + "/movie"
        assertThat(bad).isNotEqualTo(url)
        val (c, _) = get(bad)
        assertThat(c.responseCode).isEqualTo(403)
    }

    @Test
    fun corruptedChunkCutsTheStreamBeforeItsBytes() {
        val corrupt = CipherSource { off, len ->
            blob.copyOfRange(off.toInt(), off.toInt() + len).also {
                val c1 = AdikEncFormat.chunkOffset(header, 1)
                if (off <= c1 && c1 < off + len) it[(c1 - off).toInt() + 5] = (it[(c1 - off).toInt() + 5].toInt() xor 1).toByte()
            }
        }
        val (_, body) = get(start(corrupt))
        assertThat(body.size).isAtMost(4096)
        assertThat(body).isEqualTo(plain.copyOfRange(0, body.size))
    }

    @Test
    fun failedUrlRefreshClosesTheConnectionWithoutCrashing() {
        val crashes = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(crashes) { crashes += e } }
        try {
            val expired = CipherSource { _, _ -> throw UrlExpired() }
            val url = start(expired) { throw RuntimeException("HTTP 502 from /play") }
            val (_, body) = get(url, "bytes=0-99")
            Thread.sleep(300)
            assertThat(body).isEmpty()
            assertThat(synchronized(crashes) { crashes.toList() }).isEmpty()
            assertThat(proxy!!.failed).isTrue()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun transientNetworkErrorsAreRetried() {
        var calls = 0
        val flaky = CipherSource { off, len ->
            if (calls++ < 2) throw IOException("wifi blip")
            blob.copyOfRange(off.toInt(), off.toInt() + len)
        }
        val (c, body) = get(start(flaky), "bytes=0-99")
        assertThat(c.responseCode).isEqualTo(206)
        assertThat(body).isEqualTo(plain.copyOfRange(0, 100))
        assertThat(proxy!!.failed).isFalse()
    }

    @Test
    fun corruptedChunkMarksTheProxyFailed() {
        val corrupt = CipherSource { off, len ->
            blob.copyOfRange(off.toInt(), off.toInt() + len).also { it[20] = (it[20].toInt() xor 1).toByte() }
        }
        get(start(corrupt), "bytes=0-99")
        assertThat(proxy!!.failed).isTrue()
    }

    @Test
    fun expiredUrlIsRefreshedOnce() {
        var calls = 0
        var refreshed = 0
        val expiring = CipherSource { off, len ->
            if (calls++ == 0) throw UrlExpired()
            blob.copyOfRange(off.toInt(), off.toInt() + len)
        }
        val (c, body) = get(start(expiring) { refreshed++; goodSource }, "bytes=0-99")
        assertThat(c.responseCode).isEqualTo(206)
        assertThat(refreshed).isEqualTo(1)
        assertThat(body).isEqualTo(plain.copyOfRange(0, 100))
    }
}
