package com.arflix.tv.stream

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This TV's two ADIK Stream keys, held in AndroidKeyStore and never exportable:
 * - RSA-3072 (decrypt): unwraps per-movie content keys (OAEP, SHA-256 digest, MGF1-SHA1 —
 *   the only MGF1 digest Keystore supports before Android 13; the server wraps the same way).
 * - EC P-256 (sign): proves to the server that requests come from this TV.
 * Keys are created on first use. A reinstall or data wipe creates new keys → a new device id
 * that has to be approved again.
 */
@Singleton
class StreamKeys @Inject constructor() {
    private val ks: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    @Synchronized
    fun ensureKeys() {
        if (!ks.containsAlias(RSA_ALIAS)) generateRsa()
        if (!ks.containsAlias(EC_ALIAS)) generateEc()
    }

    fun rsaSpki(): ByteArray = ks.getCertificate(RSA_ALIAS).publicKey.encoded
    fun ecSpki(): ByteArray = ks.getCertificate(EC_ALIAS).publicKey.encoded

    fun deviceId(): String = StreamIds.deviceId(rsaSpki())
    fun pairingCode(): String = StreamIds.pairingCode(rsaSpki(), ecSpki())

    /** Attestation chain of the RSA key (base64 DER certs), recorded by the server at pairing. */
    fun attestationChain(): List<String> =
        ks.getCertificateChain(RSA_ALIAS)?.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }.orEmpty()

    fun securityLevel(): String = runCatching {
        val key = ks.getKey(RSA_ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                else -> "software"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "tee" else "software"
        }
    }.getOrDefault("unknown")

    fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            ks.getKey(RSA_ALIAS, null) as PrivateKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        )
        return cipher.doFinal(wrapped)
    }

    fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(ks.getKey(EC_ALIAS, null) as PrivateKey)
            update(message)
            sign()
        }

    private fun generateRsa() {
        fun spec(strongBox: Boolean, attest: Boolean) =
            KeyGenParameterSpec.Builder(RSA_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(3072)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .apply {
                    if (attest && Build.VERSION.SDK_INT >= 24) setAttestationChallenge(ATTEST_CHALLENGE)
                    if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true)
                }
                .build()
        generateWithFallbacks(KeyProperties.KEY_ALGORITHM_RSA, ::spec)
    }

    private fun generateEc() {
        fun spec(strongBox: Boolean, attest: Boolean) =
            KeyGenParameterSpec.Builder(EC_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
        generateWithFallbacks(KeyProperties.KEY_ALGORITHM_EC, ::spec)
    }

    /** Best first: StrongBox + attestation → TEE + attestation → TEE without attestation. */
    private fun generateWithFallbacks(algorithm: String, spec: (Boolean, Boolean) -> KeyGenParameterSpec) {
        val attempts = listOf(true to true, false to true, false to false)
        var last: Exception? = null
        for ((strongBox, attest) in attempts) {
            if (strongBox && Build.VERSION.SDK_INT < 28) continue
            try {
                KeyPairGenerator.getInstance(algorithm, KEYSTORE).apply { initialize(spec(strongBox, attest)) }.generateKeyPair()
                Log.i(TAG, "$algorithm key created (strongBox=$strongBox, attest=$attest)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "$algorithm keygen failed (strongBox=$strongBox, attest=$attest): ${e.javaClass.simpleName}")
                last = e
            }
        }
        throw IllegalStateException("could not create $algorithm key", last)
    }

    companion object {
        private const val TAG = "AdikStream"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val RSA_ALIAS = "adik_stream_rsa"
        private const val EC_ALIAS = "adik_stream_ec"
        private val ATTEST_CHALLENGE = "adik-stream-pair-v1".toByteArray()
    }
}
