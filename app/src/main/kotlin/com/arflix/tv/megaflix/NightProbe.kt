package com.arflix.tv.megaflix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dry run of the night window: proves the TV can download in standby without
 * downloading anything real.
 *
 * Enabled while the flag file [FLAG] exists (create/delete it over adb). Each night
 * firing (every 15 min, 3–7 am) then runs one round of checks instead of the
 * download queue and appends one line to [LOG] (`adb shell cat` it):
 * screen state, write+read of a small .txt on internal storage and on the drive,
 * network + internet, our feed server, a 10 MB download speed test (held in
 * memory, discarded) and BitTorrent DHT reachability. Every check has a time
 * limit so a hung drive or network can't stall the round.
 */
object NightProbe {
    private const val TAG = "NightProbe"
    private val FLAG = File("/sdcard/Download/adik-night-probe")
    private val LOG = File("/sdcard/Download/adik-night.log")
    private const val IO_TIMEOUT_MS = 20_000L

    fun requested(): Boolean = runCatching { FLAG.exists() }.getOrDefault(false)

    fun run(context: Context, engine: TorrentEngine, driveDir: File?) {
        val pm = context.getSystemService(PowerManager::class.java)
        val screenOn = runCatching { pm.isInteractive }.getOrNull()

        val internal = writeRead(File(context.filesDir, "night-probe.txt"))
        val drive = driveDir?.let { writeRead(File(it, ".night-probe.txt")) } ?: "no drive"

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val feed = timedGet("https://api.kaleidoscopical.com/adik/rev?k=${com.arflix.tv.util.Constants.MEGAFLIX_FEED_TOKEN}", 0)
        val speed = timedGet("https://speed.cloudflare.com/__down?bytes=10000000", 10_000_000)
        val speedText = if (speed.first == "ok") "%.1f MB/s".format(Locale.US, 10_000.0 / speed.second.coerceAtLeast(1)) else speed.first

        runCatching { engine.ensureStarted() }
        val dht = runCatching { engine.stats().dhtNodes }.getOrDefault(-1)

        write(
            "screen=${if (screenOn == true) "ON" else if (screenOn == false) "off" else "?"} " +
                "internal=$internal drive=$drive online=$online wifi=$wifi " +
                "feed=${feed.first} (${feed.second} ms) speed=$speedText dht=$dht"
        )
    }

    /** Write a small .txt, read it back, compare, delete — in a thread with a time limit. */
    private fun writeRead(file: File): String {
        var result = "timeout"
        val t = Thread {
            result = runCatching {
                val text = "adik night probe ${System.currentTimeMillis()}"
                val w0 = System.currentTimeMillis()
                file.writeText(text)
                val back = file.readText()
                file.delete()
                if (back == text) "ok(${System.currentTimeMillis() - w0}ms)" else "MISMATCH"
            }.getOrElse { "error:${it.javaClass.simpleName}" }
        }
        t.isDaemon = true
        t.start()
        t.join(IO_TIMEOUT_MS)
        return if (t.isAlive) "HUNG(>${IO_TIMEOUT_MS / 1000}s)" else result
    }

    /** ("ok" | "HTTP <code>" | error class, elapsed ms). Reads the body into memory and drops it. */
    private fun timedGet(url: String, expectBytes: Int): Pair<String, Long> {
        val start = System.currentTimeMillis()
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "ADIK-TV night probe")
            val code = c.responseCode
            if (code != 200) return@runCatching "HTTP $code" to (System.currentTimeMillis() - start)
            var read = 0L
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    read += n
                }
            }
            val status = if (expectBytes > 0 && read < expectBytes) "short ($read B)" else "ok"
            status to (System.currentTimeMillis() - start)
        }.getOrElse { it.javaClass.simpleName to (System.currentTimeMillis() - start) }
    }

    private fun write(line: String) {
        val stamped = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} $line"
        Log.w(TAG, stamped)
        runCatching { LOG.appendText(stamped + "\n") }
    }
}
