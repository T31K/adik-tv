package com.arflix.tv.stream

import java.security.MessageDigest

/**
 * Device id and pairing code, derived from the TV's Keystore public keys exactly like
 * main-server lib/adik-stream-crypto.js: id = "S_" + hex(sha256(rsaSpki))[0:16],
 * code = base32(sha256(rsaSpki || ecSpki))[0:8].
 */
object StreamIds {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun deviceId(rsaSpki: ByteArray): String =
        "S_" + sha256(rsaSpki).joinToString("") { "%02x".format(it) }.take(16)

    fun pairingCode(rsaSpki: ByteArray, ecSpki: ByteArray): String = base32(sha256(rsaSpki, ecSpki)).take(8)

    fun display(code: String): String = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var value = 0
        var bits = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(B32[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
            value = value and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(B32[(value shl (5 - bits)) and 31])
        return out.toString()
    }
}
