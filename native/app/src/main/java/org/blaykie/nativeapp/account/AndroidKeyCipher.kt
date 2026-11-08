package org.blaykie.nativeapp.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The key material never leaves Android Keystore. */
class AndroidKeyCipher : SecretCipher {
    private val alias = "blaykie-zotero-account-v1"
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        val store = store()
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw IllegalStateException("Keystore key unavailable")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(plaintext)
    }
    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }
    override fun removeKey() { store().deleteEntry(alias) }
}
