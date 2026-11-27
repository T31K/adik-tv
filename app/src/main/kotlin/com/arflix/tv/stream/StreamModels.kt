package com.arflix.tv.stream

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class PairRequestDto(
    @SerializedName("rsa_pub") val rsaPub: String,
    @SerializedName("ec_pub") val ecPub: String,
    @SerializedName("security_level") val securityLevel: String,
    @SerializedName("attestation_chain") val attestationChain: List<String>
)

@Keep
data class PairResponseDto(
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("pairing_code") val pairingCode: String = "",
    @SerializedName("status") val status: String = ""
)

@Keep
data class ChallengeDto(@SerializedName("nonce") val nonce: String = "")

@Keep
data class StreamMovieDto(
    @SerializedName("id") val id: String = "",
    @SerializedName("tmdb_id") val tmdbId: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("plaintext_size") val plaintextSize: Long = 0
)

@Keep
data class StreamMoviesDto(@SerializedName("items") val items: List<StreamMovieDto> = emptyList())

@Keep
data class PlayRequestDto(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("sig") val sig: String
)

@Keep
data class PlayResponseDto(
    @SerializedName("wrapped_ck") val wrappedCk: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("expires_at") val expiresAt: Long = 0,
    @SerializedName("movie_uuid") val movieUuid: String = "",
    @SerializedName("nonce_prefix") val noncePrefix: String = "",
    @SerializedName("chunk_size") val chunkSize: Int = 0,
    @SerializedName("chunk_count") val chunkCount: Int = 0,
    @SerializedName("plaintext_size") val plaintextSize: Long = 0
)

/** Where this TV stands with the stream server. */
sealed class PairState {
    data object Unknown : PairState()
    data class Pending(val deviceId: String, val code: String) : PairState()
    data class Approved(val deviceId: String) : PairState()
    data class Revoked(val deviceId: String) : PairState()
    data class Failed(val reason: String) : PairState()
}
