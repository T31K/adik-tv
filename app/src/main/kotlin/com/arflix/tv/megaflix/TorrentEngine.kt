package com.arflix.tv.megaflix

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.BlockFinishedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live stats for the torrent currently downloading. Reported on each block/piece
 * alert; never persisted (speed is transient — only the fractional [progress]
 * gets written to DataStore, throttled, by the download manager).
 */
data class TorrentProgress(
    val progress: Float,        // 0f..1f
    val downloadRateBps: Long,  // bytes/second, live
    val downloadedBytes: Long,  // bytes done so far
    val peers: Int
)

/** How the user interrupted the active download (see Settings → Downloads). */
enum class Interruption { PAUSE, STOP }

/** Result of a [TorrentEngine.download] call. */
sealed class DownloadOutcome {
    data class Completed(val file: File) : DownloadOutcome()
    data class Interrupted(val mode: Interruption) : DownloadOutcome()
}

/**
 * Thin wrapper over libtorrent4j's SessionManager. One session for the process;
 * downloads run one at a time (the manager serialises them). Seeding is stopped
 * on completion (we never upload from the TV).
 */
@Singleton
class TorrentEngine @Inject constructor() {
    private val session = SessionManager()

    // Set while a download is in flight so the user can pause/stop the active
    // torrent from Settings → Downloads. Completing [currentInterrupt] unblocks
    // download() with the requested mode.
    @Volatile private var currentInterrupt: CompletableDeferred<Interruption>? = null
    @Volatile private var currentHandle: TorrentHandle? = null

    @Synchronized
    fun ensureStarted() {
        if (!session.isRunning) session.start()
    }

    @Synchronized
    fun shutdown() {
        if (session.isRunning) session.stop()
    }

    /** Pause the item torrenting right now (keeps partial data on disk). No-op if idle. */
    fun requestPause() { currentInterrupt?.complete(Interruption.PAUSE) }

    /** Stop the item torrenting right now (the manager then discards partial data). No-op if idle. */
    fun requestStop() { currentInterrupt?.complete(Interruption.STOP) }

    /**
     * Downloads [magnetUri] into [saveDir], reporting live [TorrentProgress] on
     * each block/piece alert. Suspends until the torrent either finishes (→
     * [DownloadOutcome.Completed] with the largest video file) or the user
     * pauses/stops it (→ [DownloadOutcome.Interrupted]).
     */
    suspend fun download(magnetUri: String, saveDir: File, onProgress: (TorrentProgress) -> Unit): DownloadOutcome {
        ensureStarted()
        saveDir.mkdirs()
        val done = CompletableDeferred<Unit>()
        val interrupt = CompletableDeferred<Interruption>()
        currentInterrupt = interrupt

        val listener = object : AlertListener {
            override fun types(): IntArray? = null // all alerts
            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> (alert as AddTorrentAlert).handle().let {
                        currentHandle = it
                        it.resume()
                    }
                    AlertType.BLOCK_FINISHED ->
                        onProgress((alert as BlockFinishedAlert).handle().status().toProgress())
                    AlertType.PIECE_FINISHED ->
                        onProgress((alert as PieceFinishedAlert).handle().status().toProgress())
                    AlertType.TORRENT_FINISHED -> {
                        onProgress(TorrentProgress(1f, 0L, 0L, 0))
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    else -> {}
                }
            }
        }

        session.addListener(listener)
        try {
            session.download(magnetUri, saveDir, torrent_flags_t())
            // Whichever fires first wins: natural finish, or a user pause/stop.
            val interrupted: Interruption? = select {
                done.onAwait { null }
                interrupt.onAwait { it }
            }
            if (interrupted != null) {
                // Remove from the session so it stops using bandwidth. remove()
                // keeps the partial files on disk; the manager decides whether to
                // wipe them (STOP) or keep them for a later resume (PAUSE).
                currentHandle?.let { runCatching { session.remove(it) } }
                return DownloadOutcome.Interrupted(interrupted)
            }
        } finally {
            session.removeListener(listener)
            currentInterrupt = null
            currentHandle = null
        }

        val file = saveDir.walkTopDown()
            .filter { it.isFile && DriveManager.isVideoFile(it.name) }
            .maxByOrNull { it.length() }
            ?: throw IllegalStateException("Torrent finished but no video file in $saveDir")
        return DownloadOutcome.Completed(file)
    }

    private fun TorrentStatus.toProgress(): TorrentProgress = TorrentProgress(
        progress = progress(),
        downloadRateBps = downloadRate().toLong().coerceAtLeast(0L),
        downloadedBytes = totalDone().coerceAtLeast(0L),
        peers = numPeers().coerceAtLeast(0)
    )
}
