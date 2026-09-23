package com.operations.securestore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Where a store's key lives. The phone's is [AndroidKeystoreKey]; the tests hand in a software one. */
interface KeySource {
    /** The key, if it exists. */
    fun existing(): SecretKey?

    /** Make a new key, replacing any there was. */
    fun create(): SecretKey

    fun delete()
}

/**
 * An AES-256 key in the Android Keystore under [alias], usable only for AES-GCM with a nonce the
 * Keystore chooses. It never leaves the secure hardware and is never in any backup, which is the
 * point: a store sealed with it is readable on this phone and nowhere else.
 *
 * No user authentication is attached. These stores are read by background work — the scheduled
 * archive upload, a bank sync — while the phone is locked, and a key that needed the lock screen
 * would stop that work rather than protect anything it doesn't already.
 */
class AndroidKeystoreKey(private val alias: String) : KeySource {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun existing(): SecretKey? = runCatching {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    override fun create(): SecretKey {
        delete()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun delete() {
        runCatching { keyStore().takeIf { it.containsAlias(alias) }?.deleteEntry(alias) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
