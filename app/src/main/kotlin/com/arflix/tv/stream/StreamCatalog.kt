package com.arflix.tv.stream

import android.util.Base64
import android.util.Log
import com.arflix.tv.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairs this TV with the stream server and keeps the list of streamable movies.
 * Called from the home screen's sync loop; stream movies are then merged into the
 * library by MegaflixLibraryBuilder and look like any other movie.
 */
@Singleton
class StreamCatalog @Inject constructor(
    private val api: StreamApi,
    private val keys: StreamKeys
) {
    private val _state = MutableStateFlow<PairState>(PairState.Unknown)
    val state: StateFlow<PairState> = _state.asStateFlow()

    private val _movies = MutableStateFlow<List<StreamMovieDto>>(emptyList())
    val movies: StateFlow<List<StreamMovieDto>> = _movies.asStateFlow()

    private val mutex = Mutex()

    /** Pair (idempotent), then fetch the movie list once approved. Returns true if the list changed. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = _movies.value
            runCatching {
                keys.ensureKeys()
                val p = api.pair(
                    Constants.MEGAFLIX_FEED_TOKEN,
                    PairRequestDto(b64(keys.rsaSpki()), b64(keys.ecSpki()), keys.securityLevel(), keys.attestationChain())
                )
                _state.value = when (p.status) {
                    "approved" -> PairState.Approved(p.deviceId)
                    "revoked" -> PairState.Revoked(p.deviceId)
                    else -> PairState.Pending(p.deviceId, p.pairingCode)
                }
                if (p.status == "approved") {
                    val nonce = api.challenge(Constants.MEGAFLIX_FEED_TOKEN, p.deviceId).nonce
                    val sig = b64(keys.sign("$nonce:movies".toByteArray()))
                    _movies.value = api.movies(Constants.MEGAFLIX_FEED_TOKEN, p.deviceId, nonce, sig).items
                } else {
                    _movies.value = emptyList()
                }
            }.onFailure {
                Log.w(TAG, "refresh failed: ${it.javaClass.simpleName}: ${it.message}")
                if (_state.value is PairState.Unknown) _state.value = PairState.Failed(it.javaClass.simpleName)
            }
            _movies.value != before
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private companion object { const val TAG = "AdikStream" }
}
