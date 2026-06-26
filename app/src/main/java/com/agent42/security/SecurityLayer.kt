package com.agent42.security

import android.content.Context
import net.sqlcipher.database.SupportFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64

object SecurityLayer {
    private const val KEYSTORE_ALIAS = "agent42_db_key"
    private const val KEYSTORE = "AndroidKeyStore"

    fun getSupportFactory(context: Context): SupportFactory {
        val passphrase = getOrCreateDatabaseKey(context)
        return SupportFactory(passphrase)
    }

    private fun getOrCreateDatabaseKey(context: Context): ByteArray {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }

        val prefs = context.getSharedPreferences("agent42_security", Context.MODE_PRIVATE)

        if (prefs.contains("encrypted_db_key")) {
            return decryptWithKeystore(
                Base64.decode(prefs.getString("encrypted_db_key", ""), Base64.NO_WRAP),
                Base64.decode(prefs.getString("db_key_iv", ""), Base64.NO_WRAP)
            )
        }

        val dbKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val (encrypted, iv) = encryptWithKeystore(dbKey)
        prefs.edit()
            .putString("encrypted_db_key", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("db_key_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        return dbKey
    }

    private fun encryptWithKeystore(data: ByteArray): Pair<ByteArray, ByteArray> {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        val key = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data) to cipher.iv
    }

    private fun decryptWithKeystore(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        val key = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
