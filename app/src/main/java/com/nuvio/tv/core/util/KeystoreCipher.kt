package com.nuvio.tv.core.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystoreCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "nuvio_tokens_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val keystore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
        keystore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(ct, 0, this, iv.size, ct.size)
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(ciphertext: String): String? = try {
        val raw = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val iv = raw.copyOfRange(0, IV_BYTES)
        val ct = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }
}
