package com.example.sensevault.auth

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local single-user authorization. The PIN itself is never stored. */
class PinAuth(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sensevault_auth", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    fun createPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove(KEY_FAILURES)
            .remove(KEY_LOCK_UNTIL)
            .apply()
        return true
    }

    fun verifyPin(pin: String): Boolean {
        val now = System.currentTimeMillis()
        if (prefs.getLong(KEY_LOCK_UNTIL, 0L) > now) return false
        val saltText = prefs.getString(KEY_SALT, null) ?: return false
        val hashText = prefs.getString(KEY_HASH, null) ?: return false
        val valid = try {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            MessageDigest.isEqual(expected, derive(pin, salt))
        } catch (_: Exception) {
            false
        }
        if (valid) {
            prefs.edit().remove(KEY_FAILURES).remove(KEY_LOCK_UNTIL).apply()
        } else {
            val failures = prefs.getInt(KEY_FAILURES, 0) + 1
            if (failures >= 5) {
                prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCK_UNTIL, now + LOCK_DURATION_MS).apply()
            } else {
                prefs.edit().putInt(KEY_FAILURES, failures).apply()
            }
        }
        return valid
    }

    fun remainingLockMillis(): Long =
        (prefs.getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun isValidPin(pin: String): Boolean = pin.length == 6 && pin.all(Char::isDigit)

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 250_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILURES = "pin_failures"
        const val KEY_LOCK_UNTIL = "pin_lock_until"
        const val LOCK_DURATION_MS = 30_000L
    }
}
