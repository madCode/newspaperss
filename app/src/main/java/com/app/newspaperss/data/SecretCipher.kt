package com.app.newspaperss.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets, such as a password, for storing on disk. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Throws [java.security.GeneralSecurityException] if [sealed] can't be decrypted with this key. */
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES/GCM with a fresh IV per message, stored as `[IV length][IV][ciphertext and tag]`.
 *
 * @param key called for each operation, so a Keystore key is only created once it's needed.
 */
class AesGcmCipher(private val key: () -> SecretKey) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        // The cipher picks the IV: Android Keystore keys refuse one supplied by the caller.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val ivLength = sealed.firstOrNull()?.toInt() ?: throw javax.crypto.AEADBadTagException("empty")
        if (ivLength <= 0 || sealed.size < 1 + ivLength) throw javax.crypto.AEADBadTagException("truncated")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 1, ivLength))
        return cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "newspaperss-secrets"

        /**
         * A cipher whose key lives in the Android Keystore and never leaves it. Keystore keys
         * aren't backed up or restored, so anything it sealed is unreadable on another device.
         */
        fun androidKeystore() = AesGcmCipher(::keystoreKey)

        @Synchronized
        private fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
    }
}
