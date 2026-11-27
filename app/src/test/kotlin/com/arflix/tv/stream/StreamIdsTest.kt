package com.arflix.tv.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Vectors produced by main-server lib/adik-stream-crypto.js — the TV must derive the same id and code. */
class StreamIdsTest {
    private val rsa = ByteArray(100) { it.toByte() }
    private val ec = ByteArray(50) { (it + 100).toByte() }

    @Test
    fun deviceIdMatchesServer() {
        assertThat(StreamIds.deviceId(rsa)).isEqualTo("S_bce0aff19cf5aa6a")
    }

    @Test
    fun pairingCodeMatchesServer() {
        assertThat(StreamIds.pairingCode(rsa, ec)).isEqualTo("6IVS4YKO")
    }

    @Test
    fun pairingCodeOfEmptyInputIsKnownBase32() {
        assertThat(StreamIds.pairingCode(ByteArray(0), ByteArray(0))).isEqualTo("4OYMIQUY")
    }

    @Test
    fun displayCodeHasDash() {
        assertThat(StreamIds.display("6IVS4YKO")).isEqualTo("6IVS-4YKO")
    }
}
