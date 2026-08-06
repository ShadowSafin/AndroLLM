package io.androllm.core.cloud.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts/decrypts API keys at rest. Production keys never leave the Android
 * Keystore (hardware-backed where available); only ciphertext is persisted.
 */
interface KeyCipher {
    /** Encrypts [plaintext], returning base64(IV + AES/GCM ciphertext). Empty input → empty output. */
    fun encrypt(plaintext: String): String

    /** Decrypts a value produced by [encrypt]. Empty input → empty output. */
    fun decrypt(ciphertext: String): String

    /** Removes the underlying key material. */
    fun delete()
}

/**
 * AES-256/GCM implementation backed by the Android Keystore. The key never
 * leaves secure hardware/software keystore storage; each encryption uses a
 * fresh random IV that is stored alongside the ciphertext.
 */
@Singleton
class AndroidKeyCipher @Inject constructor(
    @ApplicationContext context: Context
) : KeyCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        // Serialized so two concurrent first-use calls can never both try to
        // generate the same alias (the second would throw "key already exists").
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (combined.size < IV_LENGTH + 1) return ""
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    override fun delete() {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "androllm_cloud_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
