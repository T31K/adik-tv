package com.arflix.tv.stream

import android.util.Base64
import com.arflix.tv.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens a stream movie for playback: proves this TV's identity (signed nonce), receives the
 * movie key wrapped for this TV plus a 6 h presigned R2 URL, unwraps the key in Keystore and
 * starts a [DecryptingProxy] over the URL. The caller plays the returned proxy's URL and
 * must call [DecryptingProxy.stop] when done.
 */
@Singleton
class StreamSession @Inject constructor(
    private val api: StreamApi,
    private val keys: StreamKeys,
    http: OkHttpClient
) {
    // R2 reads: no disk cache and no logging interceptor (debug builds log headers, incl. the presigned URL).
    private val r2Http: OkHttpClient = http.newBuilder().cache(null).apply { interceptors().clear() }.build()

    suspend fun open(id: String): DecryptingProxy = withContext(Dispatchers.IO) {
        keys.ensureKeys()
        val first = play(id)
        val ck = keys.unwrap(b64d(first.wrappedCk))
        val header = EncHeader(
            chunkSize = first.chunkSize,
            plaintextSize = first.plaintextSize,
            chunkCount = first.chunkCount,
            noncePrefix = b64d(first.noncePrefix),
            movieUuid = b64d(first.movieUuid)
        )
        val proxy = DecryptingProxy(ck, header, r2Source(first.url)) {
            // Presigned URL died (long pause > 6 h): ask for a fresh one, same key.
            r2Source(runBlocking { play(id) }.url)
        }
        ck.fill(0)
        proxy
    }

    private suspend fun play(id: String): PlayResponseDto {
        val deviceId = keys.deviceId()
        val nonce = api.challenge(Constants.MEGAFLIX_FEED_TOKEN, deviceId).nonce
        val sig = Base64.encodeToString(keys.sign("$nonce:$id".toByteArray()), Base64.NO_WRAP)
        return api.play(id, Constants.MEGAFLIX_FEED_TOKEN, PlayRequestDto(deviceId, nonce, sig))
    }

    private fun r2Source(url: String) = CipherSource { offset, length ->
        val req = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        r2Http.newCall(req).execute().use { res ->
            if (res.code == 403) throw UrlExpired()
            if (res.code != 206 && res.code != 200) throw IOException("R2 HTTP ${res.code}")
            val body = res.body?.bytes() ?: throw IOException("empty R2 body")
            if (body.size < length) throw IOException("short read ${body.size}/$length")
            if (res.code == 200) body.copyOfRange(offset.toInt(), offset.toInt() + length) else body
        }
    }

    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
