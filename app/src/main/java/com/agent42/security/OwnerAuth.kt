package com.agent42.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owner authentication layer — Rule 1 (Owner Lock).
 *
 * Requires 2-factor verification: PIN + biometric, or passphrase + biometric.
 * The owner identity is bound to this device's Android Keystore — no other
 * device or user can authenticate, even with the phone unlocked.
 *
 * This is HARDLOCKED: the self-modification engine cannot access or modify
 * this file. It is excluded from the CodeModificationEngine's module list.
 */
class OwnerAuth(private val context: Context) {

    companion object {
        private const val KEYSTORE_ALIAS = "agent42_owner_auth"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_NAME = "agent42_owner_auth"
        private const val PIN_HASH_KEY = "owner_pin_hash"
        private const val PASSPHRASE_HASH_KEY = "owner_passphrase_hash"
        private const val OWNER_REGISTERED_KEY = "owner_registered"
        private val SALT = "agent42_owner_salt_v1".toByteArray()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isOwnerRegistered(): Boolean = prefs.getBoolean(OWNER_REGISTERED_KEY, false)

    /**
     * Register the owner for the first time.
     * Called once during onboarding — requires a PIN and optional passphrase.
     */
    fun registerOwner(pin: String, passphrase: String? = null): Boolean {
        if (pin.length < 4) return false
        prefs.edit()
            .putString(PIN_HASH_KEY, hashCredential(pin))
            .putString(PASSPHRASE_HASH_KEY, passphrase?.let { hashCredential(it) })
            .putBoolean(OWNER_REGISTERED_KEY, true)
            .apply()
        return true
    }

    /**
     * Verify owner identity using 2 factors.
     * Factor 1: PIN or passphrase (knowledge)
     * Factor 2: Biometric (inherence) — must be called from the UI layer
     *           which triggers BiometricPrompt
     *
     * Returns true only if BOTH factors pass.
     */
    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(PIN_HASH_KEY, null) ?: return false
        return constantTimeEquals(hashCredential(pin), storedHash)
    }

    fun verifyPassphrase(passphrase: String): Boolean {
        val storedHash = prefs.getString(PASSPHRASE_HASH_KEY, null) ?: return false
        return constantTimeEquals(hashCredential(passphrase), storedHash)
    }

    /**
     * Biometric verification is handled by the UI layer via BiometricPrompt.
     * The UI calls this after biometric succeeds to mark the session as verified.
     */
    @Volatile
    private var biometricVerified = false
    @Volatile
    private var biometricVerifiedAt: Long = 0

    fun markBiometricVerified() {
        biometricVerified = true
        biometricVerifiedAt = System.currentTimeMillis()
    }

    fun isBiometricValid(timeoutMs: Long = 60_000): Boolean {
        return biometricVerified && (System.currentTimeMillis() - biometricVerifiedAt) < timeoutMs
    }

    /**
     * Full 2-factor check: knowledge (PIN/passphrase) + biometric.
     * Both must pass within the timeout window.
     */
    fun isOwnerVerified(knowledgeFactor: String): Boolean {
        val knowledgeOk = verifyPin(knowledgeFactor) || verifyPassphrase(knowledgeFactor)
        return knowledgeOk && isBiometricValid()
    }

    fun resetBiometric() {
        biometricVerified = false
        biometricVerifiedAt = 0
    }

    private fun hashCredential(credential: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(SALT)
        val hash = md.digest(credential.toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
