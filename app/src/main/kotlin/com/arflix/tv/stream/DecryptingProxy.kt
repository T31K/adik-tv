package com.arflix.tv.stream

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

/** Ranged read of ciphertext bytes from R2 (or a test blob). Throws [UrlExpired] when the presigned URL died. */
fun interface CipherSource {
    fun read(offset: Long, length: Int): ByteArray
}

class UrlExpired : IOException("presigned URL expired")

/**
 * A tiny HTTP server on 127.0.0.1 that libVLC reads like a normal seekable MKV.
 * It maps each byte range to ADIKENC1 chunks, fetches their ciphertext, verifies and
 * decrypts each one (AES-GCM), and serves plaintext. Plaintext lives only in a small
 * in-memory LRU (16 chunks); nothing is written to disk. Requests without the random
 * per-session secret in the path get 403. A chunk that fails verification twice cuts
 * the connection, so unverified bytes never reach the player.
 */
class DecryptingProxy(
    ck: ByteArray,
    private val header: EncHeader,
    source: CipherSource,
    private val onExpired: () -> CipherSource
) {
    private val ck = ck.copyOf()
    @Volatile private var source = source
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val cache = object : LinkedHashMap<Int, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > CACHE_CHUNKS
    }
    private val prefetcher: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "adik-prefetch").apply { isDaemon = true } }
    private var server: ServerSocket? = null
    @Volatile private var stopped = false

    /**
     * True once a chunk could not be delivered (failed verification, network down, URL refresh failed).
     * The player checks this so a cut stream shows an error instead of looking like the end of the movie.
     */
    @Volatile var failed = false
        private set

    fun start(): String {
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!stopped) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ handle(sock) }, "adik-proxy-conn").apply { isDaemon = true }.start()
            }
        }, "adik-proxy").apply { isDaemon = true }.start()
        return "http://127.0.0.1:${s.localPort}/$secret/movie"
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }
        prefetcher.shutdownNow()
        synchronized(cache) { cache.values.forEach { it.fill(0) }; cache.clear() }
        ck.fill(0)
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                var range: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val out = s.getOutputStream()
                if (parts.getOrNull(1) != "/$secret/movie") return writeHead(out, "403 Forbidden", emptyMap())
                if (method != "GET" && method != "HEAD") return writeHead(out, "405 Method Not Allowed", emptyMap())
                serve(out, method == "HEAD", range)
            } catch (e: StreamBroken) {
                failed = true
                warn("stream broken: ${e.message}")
            } catch (e: IOException) {
                // client closed (VLC seeks by dropping connections) — nothing to do
            } catch (e: Exception) {
                // never let a proxy thread take the app down
                failed = true
                warn("proxy error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun serve(out: OutputStream, headOnly: Boolean, range: String?) {
        val total = header.plaintextSize
        var start = 0L
        var end = total - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-')
            val b = spec.substringAfter('-', "")
            if (a.isEmpty()) {
                val suffix = b.toLongOrNull() ?: 0L
                start = (total - suffix).coerceAtLeast(0)
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, total - 1)
            }
            partial = true
        }
        if (start >= total || start > end) {
            return writeHead(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total"))
        }
        val headers = linkedMapOf(
            "Content-Type" to "video/x-matroska",
            "Accept-Ranges" to "bytes",
            "Content-Length" to (end - start + 1).toString()
        )
        if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
        writeHead(out, if (partial) "206 Partial Content" else "200 OK", headers)
        if (headOnly) return

        for (i in AdikEncFormat.chunksFor(header, start, end)) {
            val plain = plainChunk(i) ?: throw StreamBroken("chunk $i failed verification twice")
            val chunkStart = i.toLong() * header.chunkSize
            val from = (maxOf(start, chunkStart) - chunkStart).toInt()
            val to = (minOf(end, chunkStart + plain.size - 1) - chunkStart).toInt()
            out.write(plain, from, to - from + 1)
            prefetch(i + 1)
        }
        out.flush()
    }

    /** Decrypted chunk i, from cache or fetched (retries a bad chunk once; refreshes an expired URL once). */
    private fun plainChunk(i: Int): ByteArray? {
        synchronized(cache) { cache[i] }?.let { return it }
        repeat(2) { attempt ->
            try {
                val p = AdikEncFormat.decryptChunk(ck, header, i, fetch(i))
                synchronized(cache) { cache[i] = p }
                return p
            } catch (e: AEADBadTagException) {
                warn("chunk $i failed verification (attempt ${attempt + 1})")
            }
        }
        return null
    }

    /**
     * Ciphertext of chunk i. Transient network errors are retried (3 tries, short backoff); an expired
     * URL is refreshed once. Anything that still fails becomes [StreamBroken].
     */
    private fun fetch(i: Int): ByteArray {
        val off = AdikEncFormat.chunkOffset(header, i)
        val len = AdikEncFormat.chunkCipherLen(header, i)
        var refreshed = false
        var lastError: Exception? = null
        for (attempt in 0 until FETCH_TRIES) {
            try {
                return source.read(off, len)
            } catch (e: UrlExpired) {
                if (refreshed) throw StreamBroken("URL still expired after refresh")
                refreshed = true
                try {
                    synchronized(this) { source = onExpired() }
                } catch (r: Exception) {
                    throw StreamBroken("URL refresh failed: ${r.javaClass.simpleName}")
                }
            } catch (e: IOException) {
                lastError = e
                if (stopped) break
                Thread.sleep(BACKOFF_MS * (attempt + 1))
            }
        }
        throw StreamBroken("chunk $i unreachable: ${lastError?.javaClass?.simpleName}")
    }

    private fun prefetch(from: Int) {
        if (stopped) return
        for (i in from until minOf(from + READ_AHEAD, header.chunkCount)) {
            if (synchronized(cache) { cache.containsKey(i) }) continue
            runCatching { prefetcher.execute { if (!stopped) runCatching { plainChunk(i) } } }
        }
    }

    /** Logging must never throw from a proxy thread (e.g. android.util.Log is absent on the JVM). */
    private fun warn(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    private fun writeHead(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (!headers.containsKey("Content-Length")) sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("header too long")
            sb.append(c.toChar())
        }
    }

    private companion object {
        const val TAG = "AdikStream"
        const val CACHE_CHUNKS = 16
        const val READ_AHEAD = 6
        const val FETCH_TRIES = 3
        const val BACKOFF_MS = 300L
    }
}

/** A chunk could not be delivered; the connection is cut and [DecryptingProxy.failed] is set. */
class StreamBroken(message: String) : IOException(message)
