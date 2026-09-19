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
